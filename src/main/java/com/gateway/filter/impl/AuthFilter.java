package com.gateway.filter.impl;

import com.gateway.config.GatewayProperties;
import com.gateway.filter.FilterChain;
import com.gateway.filter.GatewayFilter;
import com.gateway.model.GatewayRequest;
import com.gateway.model.GatewayResponse;
import com.gateway.model.RouteDefinition;
import com.gateway.router.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 鉴权过滤器
 * 调用认证服务验证 Token
 *
 * @author Claude Gateway Team
 * @version 1.0.0
 */
@Component
public class AuthFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final GatewayProperties properties;
    private final Router router;
    private final HttpClient httpClient;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthFilter(GatewayProperties properties, Router router) {
        this.properties = properties;
        this.router = router;
        this.httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(
                        properties.getSecurity() != null
                                && properties.getSecurity().getAuth() != null
                                ? properties.getSecurity().getAuth().getTimeoutMs()
                                : 500
                ));
    }

    @Override
    public String getName() {
        return "AuthFilter";
    }

    @Override
    public int getOrder() {
        return 300; // 限流之后
    }

    @Override
    public boolean isEnabled() {
        return properties.getSecurity() != null
                && properties.getSecurity().getAuth() != null
                && properties.getSecurity().getAuth().isEnabled();
    }

    @Override
    public Mono<GatewayResponse> filter(GatewayRequest request, FilterChain chain) {
        String path = request.getPath();

        // 检查是否跳过鉴权
        if (shouldSkipAuth(path)) {
            log.debug("[{}] 路径 {} 在跳过鉴权列表中", request.getTraceId(), path);
            return chain.filter(request);
        }

        // 检查路由是否需要鉴权
        RouteDefinition route = router.match(request);
        if (route != null && !route.isRequireAuth()) {
            log.debug("[{}] 路由 {} 不需要鉴权", request.getTraceId(), route.getId());
            return chain.filter(request);
        }

        // 获取 Token
        String token = request.getToken();
        if (token == null || token.isEmpty()) {
            log.warn("[{}] 缺少认证 Token", request.getTraceId());
            return Mono.just(GatewayResponse.unauthorized("Missing authentication token"));
        }

        // 调用认证服务验证 Token
        return verifyToken(token, request.getTraceId())
                .flatMap(valid -> {
                    if (valid) {
                        log.debug("[{}] Token 验证通过", request.getTraceId());
                        return chain.filter(request);
                    } else {
                        log.warn("[{}] Token 验证失败", request.getTraceId());
                        return Mono.just(GatewayResponse.unauthorized("Invalid authentication token"));
                    }
                })
                .onErrorResume(e -> {
                    log.error("[{}] 调用认证服务失败: {}", request.getTraceId(), e.getMessage());
                    // 认证服务故障时的策略：拒绝请求
                    return Mono.just(GatewayResponse.internalServerError("Authentication service unavailable"));
                });
    }

    /**
     * 检查路径是否应该跳过鉴权
     *
     * @param path 请求路径
     * @return 是否跳过
     */
    private boolean shouldSkipAuth(String path) {
        if (properties.getSecurity() == null || properties.getSecurity().getAuth() == null) {
            return false;
        }

        List<String> skipPaths = properties.getSecurity().getAuth().getSkipPaths();
        if (skipPaths == null || skipPaths.isEmpty()) {
            return false;
        }

        for (String skipPath : skipPaths) {
            if (pathMatcher.match(skipPath, path)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 验证 Token
     *
     * @param token   Token
     * @param traceId 追踪 ID
     * @return 验证结果
     */
    private Mono<Boolean> verifyToken(String token, String traceId) {
        String authServerUrl = properties.getSecurity().getAuth().getAuthServerUrl();

        if (authServerUrl == null || authServerUrl.isEmpty()) {
            log.warn("[{}] 未配置认证服务地址，默认允许通过", traceId);
            return Mono.just(true);
        }

        return httpClient
                .headers(headers -> {
                    headers.add("Authorization", "Bearer " + token);
                    headers.add("X-Trace-Id", traceId);
                })
                .get()
                .uri(authServerUrl + "/verify")
                .responseSingle((response, body) -> {
                    if (response.status().code() == 200) {
                        return Mono.just(true);
                    } else if (response.status().code() == 401) {
                        return Mono.just(false);
                    } else {
                        return body.asString(StandardCharsets.UTF_8)
                                .flatMap(msg -> {
                                    log.warn("[{}] 认证服务返回异常状态: {}, body: {}",
                                            traceId, response.status().code(), msg);
                                    return Mono.just(false);
                                });
                    }
                })
                .timeout(Duration.ofMillis(properties.getSecurity().getAuth().getTimeoutMs()))
                .onErrorResume(e -> {
                    log.error("[{}] Token 验证请求失败: {}", traceId, e.getMessage());
                    return Mono.error(e);
                });
    }
}
