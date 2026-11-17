package com.couponrush.mcpserver.auth;

import com.couponrush.mcpserver.config.McpProperties;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * HTTP 헤더 → Caller. 두 가지로 SDK 전송 계층에 붙는다.
 * <ol>
 *   <li>ServerHttpHeaderValidator: 잘못된 인증은 MCP 처리 전에 HTTP 오류로 끊는다 (연결(initialize)부터 실패 — 설정 실수를 바로 알 수 있게)</li>
 *   <li>McpTransportContextExtractor: HTTP 요청마다 Caller 를 만들어 도구 핸들러에 넘긴다 (exchange.transportContext())</li>
 * </ol>
 * 헤더 규칙:
 * <ul>
 *   <li>Authorization: Bearer {access token} → User (사람 — Claude Code 등)</li>
 *   <li>X-Mcp-Service-Token + (선택) X-Mcp-User-Id → Service (서버 — llm-service 챗봇). 토큰이 틀리면 401</li>
 *   <li>X-Mcp-User-Id 만 있으면 401 — 서비스 토큰 없이 사용자를 지정할 수 없다</li>
 *   <li>서비스 토큰과 Bearer 를 함께 보내면 400 — 누구로 처리할지 모호하다</li>
 *   <li>아무것도 없으면 Anonymous (공개 도구만)</li>
 * </ul>
 */
@Component
public class CallerAuthentication implements ServerHttpHeaderValidator, McpTransportContextExtractor<HttpServletRequest> {

    private static final Logger log = LoggerFactory.getLogger(CallerAuthentication.class);

    public static final String SERVICE_TOKEN_HEADER = "X-Mcp-Service-Token";
    public static final String USER_ID_HEADER = "X-Mcp-User-Id";
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";

    private final byte[] serviceToken;

    public CallerAuthentication(McpProperties properties) {
        String configured = properties.auth().serviceToken();
        this.serviceToken = configured == null || configured.isBlank() ? null : configured.getBytes(StandardCharsets.UTF_8);
        if (serviceToken == null) {
            log.info("app.mcp.auth.service-token(MCP_SERVICE_TOKEN)이 비어 있어 서비스 호출(X-Mcp-Service-Token)을 받지 않습니다");
        }
    }

    @Override
    public void validate(HeaderAccessor headers) throws ServerTransportSecurityException {
        resolve(name -> nullToEmpty(headers.getHeader(name)));
    }

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        try {
            Caller caller = resolve(name -> Collections.list(request.getHeaders(name)));
            return McpTransportContext.create(Map.of(Caller.CONTEXT_KEY, caller));
        } catch (ServerTransportSecurityException e) {
            // validate 가 먼저 거절하므로 오지 않는다. 혹시 와도 권한이 가장 적은 쪽으로
            return McpTransportContext.create(Map.of(Caller.CONTEXT_KEY, Caller.ANONYMOUS));
        }
    }

    private Caller resolve(Function<String, List<String>> headers) throws ServerTransportSecurityException {
        String service = single(headers, SERVICE_TOKEN_HEADER);
        String userId = single(headers, USER_ID_HEADER);
        String authorization = single(headers, AUTHORIZATION);

        if (service != null) {
            if (!matches(service)) {
                throw new ServerTransportSecurityException(401, "Invalid service token");
            }
            if (authorization != null) {
                throw new ServerTransportSecurityException(400, "Send either a service token or a Bearer token, not both");
            }
            return new Caller.Service(userId == null ? null : parseUserId(userId));
        }
        if (userId != null) {
            throw new ServerTransportSecurityException(401, USER_ID_HEADER + " requires a service token");
        }
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String token = authorization.substring(BEARER.length()).trim();
            if (!token.isEmpty()) {
                return new Caller.User(token);
            }
        }
        return Caller.ANONYMOUS;
    }

    private boolean matches(String sent) {
        return serviceToken != null && MessageDigest.isEqual(serviceToken, sent.getBytes(StandardCharsets.UTF_8));
    }

    private static Long parseUserId(String raw) throws ServerTransportSecurityException {
        try {
            long id = Long.parseLong(raw.strip());
            if (id >= 1) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 거절
        }
        throw new ServerTransportSecurityException(400, USER_ID_HEADER + " must be a positive integer");
    }

    /** 같은 헤더가 여러 번 오면 거절 (어느 값을 믿을지 모호하다) */
    private static String single(Function<String, List<String>> headers, String name) throws ServerTransportSecurityException {
        List<String> values = headers.apply(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new ServerTransportSecurityException(400, "Duplicate header: " + name);
        }
        return values.get(0);
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
