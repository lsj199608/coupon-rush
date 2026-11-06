package com.couponrush.auth;

import com.couponrush.auth.jwt.AuthUser;
import com.couponrush.auth.jwt.JwtTokenProvider;
import com.couponrush.auth.jwt.RefreshTokenClaims;
import com.couponrush.auth.token.AccessTokenBlacklist;
import com.couponrush.auth.token.RefreshTokenStore;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.user.Role;
import com.couponrush.user.User;
import com.couponrush.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

@Service
public class AuthService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final AccessTokenBlacklist blacklist;
    private final UserRepository userRepository;
    private final Clock clock;

    public AuthService(JwtTokenProvider jwtTokenProvider, RefreshTokenStore refreshTokenStore,
                       AccessTokenBlacklist blacklist, UserRepository userRepository, Clock clock) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.blacklist = blacklist;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** 소셜 로그인 성공 시: access + refresh 발급, refresh 는 Redis 에 저장 (이전 refresh 는 덮어써져 무효) */
    public LoginTokens login(Long userId, Role role) {
        String accessToken = jwtTokenProvider.createAccessToken(userId, role);
        String refreshToken = jwtTokenProvider.createRefreshToken(userId);
        refreshTokenStore.save(userId, refreshToken, jwtTokenProvider.getRefreshTokenValidity());
        return new LoginTokens(accessToken, refreshToken);
    }

    /**
     * refresh token 으로 새 access token 발급.
     * 1) 서명/만료/용도 검증  2) Redis 에 저장된 값과 일치하는지 (로그아웃·재로그인된 토큰 거부)
     * 3) role 은 토큰이 아니라 DB 에서 다시 읽는다 (권한이 바뀌었으면 새 토큰에 반영)
     */
    @Transactional(readOnly = true)
    public AccessTokenResponse refresh(String refreshToken) {
        RefreshTokenClaims claims = jwtTokenProvider.parseRefreshToken(refreshToken);

        if (!refreshTokenStore.matches(claims.userId(), refreshToken)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_MISMATCH);
        }

        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> {
                    refreshTokenStore.delete(claims.userId());
                    return new BusinessException(ErrorCode.TOKEN_INVALID);
                });

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole());
        return new AccessTokenResponse(accessToken, jwtTokenProvider.getAccessTokenValidity().toSeconds());
    }

    /** refresh token 삭제(재발급 차단) + 지금 쓰던 access token 을 남은 유효시간 동안 블랙리스트에 등록 */
    public void logout(AuthUser authUser) {
        refreshTokenStore.delete(authUser.userId());
        blacklist.add(authUser.tokenId(), Duration.between(clock.instant(), authUser.tokenExpiresAt()));
    }
}
