package com.couponrush.mcpserver.couponapi;

import com.couponrush.mcpserver.auth.Caller;
import com.couponrush.mcpserver.config.McpProperties;
import com.couponrush.mcpserver.couponapi.CouponApiDtos.CouponEvent;
import com.couponrush.mcpserver.couponapi.CouponApiDtos.MyCoupon;
import com.couponrush.mcpserver.couponapi.CouponApiDtos.SearchResult;
import com.couponrush.mcpserver.couponapi.CouponApiException.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * coupon-api 조회 API 호출. 도메인 판단은 하지 않는다 — 응답을 그대로 받아 도구에 넘긴다.
 * 짧은 타임아웃(연결 2초·응답 5초): AI 클라이언트의 도구 호출이 coupon-api 장애에 오래 묶이지 않게.
 * <p>
 * 인증은 Caller 별로 다르게 싣는다 (mcp-server 는 토큰을 검증하지 않는다 — 판정은 coupon-api 한 곳):
 * 사람(User)은 받은 access token 을 그대로 Authorization 에, 서버(Service)는 내부 API + X-Internal-Token.
 */
@Component
public class CouponApiClient {

    private static final Logger log = LoggerFactory.getLogger(CouponApiClient.class);

    private final RestClient restClient;
    private final McpProperties.Search search;
    private final String internalToken;

    public CouponApiClient(RestClient.Builder builder, McpProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.couponApi().connectTimeout());
        requestFactory.setReadTimeout(properties.couponApi().readTimeout());
        this.restClient = builder.baseUrl(properties.couponApi().baseUrl()).requestFactory(requestFactory).build();
        this.search = properties.search();
        this.internalToken = properties.couponApi().internalToken();
    }

    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final ParameterizedTypeReference<List<MyCoupon>> MY_COUPONS = new ParameterizedTypeReference<>() {
    };

    /**
     * 호출한 사용자 본인의 쿠폰. 누구인지는 Caller(HTTP 헤더)로만 정한다.
     * <ul>
     *   <li>User → GET /api/users/me/coupons (Bearer 그대로 — 만료·로그아웃이면 coupon-api 가 401)</li>
     *   <li>Service + userId → GET /internal/users/{userId}/coupons (X-Internal-Token)</li>
     *   <li>그 외 → UNAUTHENTICATED (coupon-api 를 부르지 않는다)</li>
     * </ul>
     */
    public List<MyCoupon> getMyCoupons(Caller caller) {
        if (caller instanceof Caller.User user) {
            return call("내 쿠폰 조회", () -> restClient.get().uri("/api/users/me/coupons")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()).retrieve().body(MY_COUPONS));
        }
        if (caller instanceof Caller.Service service && service.userId() != null) {
            if (internalToken == null || internalToken.isBlank()) {
                log.error("서비스 호출의 사용자 쿠폰 조회 실패: app.mcp.coupon-api.internal-token(INTERNAL_API_TOKEN)이 비어 있음");
                throw new CouponApiException(Kind.UNAVAILABLE, "쿠폰 서비스 내부 연결이 설정되지 않았습니다.");
            }
            try {
                return call("사용자 쿠폰 조회(내부)", () -> restClient.get().uri("/internal/users/{id}/coupons", service.userId())
                        .header(INTERNAL_TOKEN_HEADER, internalToken).retrieve().body(MY_COUPONS));
            } catch (CouponApiException e) {
                if (e.kind() == Kind.UNAUTHENTICATED) {
                    // 사용자 탓이 아니라 서버 설정(두 서비스의 INTERNAL_API_TOKEN 불일치) 문제
                    log.error("coupon-api 내부 API 가 X-Internal-Token 을 거절함 — 두 서비스의 INTERNAL_API_TOKEN 이 같은지 확인");
                    throw new CouponApiException(Kind.UNAVAILABLE, "쿠폰 서비스 내부 연결에 실패했습니다.", e);
                }
                throw e;
            }
        }
        throw new CouponApiException(Kind.UNAUTHENTICATED, "로그인한 사용자만 쓸 수 있습니다.");
    }

    public CouponEvent getEvent(long eventId) {
        return call("이벤트 조회", () -> restClient.get().uri("/api/coupons/{id}", eventId).retrieve().body(CouponEvent.class));
    }

    /**
     * 자연어 검색. coupon-api 가 202 PENDING(AI 조건 생성 중)을 주면 끝날 때까지 기다렸다가 결과 하나로 돌려준다
     * — 도구 호출은 결과 하나를 기대하므로 폴링을 모델에게 떠넘기지 않는다.
     * coupon-api 는 마감(15초)이 지나면 키워드 검색으로 끝내므로, 보통 대기 한도(20초) 전에 COMPLETED / FALLBACK 이 온다.
     */
    public SearchResult searchEvents(String query, Caller caller) {
        // 로그인한 사람이면 토큰을 함께 보내 검색 한도를 회원별로 센다 (없으면 coupon-api 는 IP 별 — mcp-server 하나가 모두 127.0.0.1).
        // 공개 경로라 토큰이 만료돼도 401 이 아니라 비로그인으로 처리된다
        String authorization = caller instanceof Caller.User user ? "Bearer " + user.accessToken() : null;
        SearchResult result = call("이벤트 검색", () -> restClient.post().uri("/api/coupons/search")
                .headers(headers -> {
                    if (authorization != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("query", query)).retrieve().body(SearchResult.class));
        long deadline = System.nanoTime() + search.waitTimeout().toNanos();
        while (result != null && result.isPending()) {
            if (System.nanoTime() >= deadline) {
                throw new CouponApiException(Kind.UNAVAILABLE, "검색 결과가 늦어지고 있습니다. 잠시 후 다시 시도해 주세요.");
            }
            pause();
            String requestId = result.requestId();
            result = call("검색 결과 조회",
                    () -> restClient.get().uri("/api/coupons/search/{id}", requestId).retrieve().body(SearchResult.class));
        }
        if (result == null) {
            throw new CouponApiException(Kind.UNAVAILABLE, "coupon-api 가 빈 응답을 보냈습니다.");
        }
        return result;
    }

    private void pause() {
        try {
            Thread.sleep(search.pollInterval());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CouponApiException(Kind.UNAVAILABLE, "검색 대기가 중단되었습니다.", e);
        }
    }

    /** HTTP 결과 → 도구가 설명할 수 있는 실패 종류. 4xx 메시지는 coupon-api 의 {"code","message"} 를 그대로 전한다 */
    private <T> T call(String action, Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            HttpStatusCode status = e.getStatusCode();
            String message = errorMessage(e);
            if (status.value() == 404) {
                throw new CouponApiException(Kind.NOT_FOUND, message, e);
            }
            if (status.value() == 400) {
                throw new CouponApiException(Kind.INVALID_INPUT, message, e);
            }
            if (status.value() == 401) {
                throw new CouponApiException(Kind.UNAUTHENTICATED, message, e);
            }
            log.warn("{} 실패: coupon-api {} {}", action, status.value(), message);
            throw new CouponApiException(Kind.UNAVAILABLE, message, e);
        } catch (RestClientResponseException e) {   // 5xx
            log.warn("{} 실패: coupon-api {}", action, e.getStatusCode().value());
            throw new CouponApiException(Kind.UNAVAILABLE, "쿠폰 서비스가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.", e);
        } catch (ResourceAccessException e) {        // 연결 실패·타임아웃
            log.warn("{} 실패: coupon-api 연결 불가 ({})", action, e.getMessage());
            throw new CouponApiException(Kind.UNAVAILABLE, "쿠폰 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", e);
        } catch (RestClientException e) {            // 응답 형식이 예상과 다름
            log.warn("{} 실패: coupon-api 응답을 읽을 수 없음", action, e);
            throw new CouponApiException(Kind.UNAVAILABLE, "쿠폰 서비스 응답을 읽을 수 없습니다.", e);
        }
    }

    private static String errorMessage(RestClientResponseException e) {
        try {
            CouponApiDtos.ApiError error = e.getResponseBodyAs(CouponApiDtos.ApiError.class);
            if (error != null && error.message() != null) {
                return error.message();
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON 이 아니면 상태 코드로만 설명
        }
        return "쿠폰 서비스 요청이 거절되었습니다 (HTTP " + e.getStatusCode().value() + ").";
    }
}
