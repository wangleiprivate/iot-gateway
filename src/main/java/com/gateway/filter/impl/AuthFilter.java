package com.gateway.filter.impl;

import com.gateway.config.GatewayConfigHolder;
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
 * TODO: 第三方鉴权服务地址待配置
 * 目前第三方鉴权接口地址还未确定，需要后续配置 gateway.security.auth.auth-server-url
 * 鉴权失败时需要提示用户去指定地址进行鉴权
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
public class AuthFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    /**
     * 使用 GatewayConfigHolder 获取最新配置
     * GatewayConfigHolder 通过 Nacos 原生 API 实现热更新，绕过 Spring 的配置绑定问题
     */
    private final GatewayConfigHolder configHolder;
    private final Router router;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthFilter(GatewayConfigHolder configHolder, Router router) {
        this.configHolder = configHolder;
        this.router = router;
    }

    /**
     * 获取当前的 GatewayProperties 配置
     * 每次调用都会获取最新的配置（支持 Nacos 热更新）
     */
    private GatewayProperties getProperties() {
        return configHolder.getProperties();
    }

    /**
     * 创建 HTTP 客户端（用于 Token 验证）
     * 每次调用时获取最新的超时配置
     */
    private HttpClient createHttpClient() {
        GatewayProperties properties = getProperties();
        int timeoutMs = (properties != null
                && properties.getSecurity() != null
                && properties.getSecurity().getAuth() != null)
                ? properties.getSecurity().getAuth().getTimeoutMs()
                : 500;
        return HttpClient.create().responseTimeout(Duration.ofMillis(timeoutMs));
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
        // 始终启用 AuthFilter，具体鉴权逻辑在 filter 方法中根据路由配置判断
        // 这样可以支持：全局关闭鉴权但路由级别开启鉴权的场景
        return true;
    }

    @Override
    public Mono<GatewayResponse> filter(GatewayRequest request, FilterChain chain) {
        String path = request.getPath();

        // 检查是否跳过鉴权
        if (shouldSkipAuth(path)) {
            log.debug("[{}] 路径 {} 在跳过鉴权列表中", request.getTraceId(), path);
            return chain.filter(request);
        }

        // 检查路由是否需要鉴权 - 确保获取最新的路由配置
        RouteDefinition route = router.match(request);
        if (route != null) {
            // 获取最新的 requireAuth 状态
            // 这里使用 router 的辅助方法来获取最新的配置状态，确保使用最新值
            boolean latestRequireAuth = router.getLatestRequireAuth(route.getId());
            
            // 更新路由对象的 requireAuth 值以确保一致性
            route.setRequireAuth(latestRequireAuth);
            
            // 记录当前路由的鉴权配置状态，便于调试热加载
            log.info("[{}] 路由 {} requireAuth={}", request.getTraceId(), route.getId(), route.isRequireAuth());
            
            // 额外调试：直接从配置获取 requireAuth 值进行对比
            GatewayProperties properties = getProperties();
            if (properties != null && properties.getRoutes() != null) {
                properties.getRoutes().stream()
                    .filter(r -> route.getId().equals(r.getId()))
                    .findFirst()
                    .ifPresent(r -> {
                        log.info("[{}] 配置中的路由 {} requireAuth={}", 
                                request.getTraceId(), r.getId(), r.isRequireAuth());
                        if (r.isRequireAuth() != route.isRequireAuth()) {
                            log.error("[{}] 配置不一致！配置值: {}, 路由对象值: {}", 
                                    request.getTraceId(), r.isRequireAuth(), route.isRequireAuth());
                        }
                    });
            }
            
            if (!route.isRequireAuth()) {
                log.info("[{}] 路由 {} 配置为无需鉴权，直接放行", request.getTraceId(), route.getId());
                return chain.filter(request);
            }
        }

        // 获取 Token
        String token = request.getToken();
        if (token == null || token.isEmpty()) {
            log.warn("[{}] 缺少认证 Token", request.getTraceId());
            // TODO: 第三方鉴权服务地址待配置，需要在提示信息中告知用户去哪里鉴权
            return Mono.just(GatewayResponse.unauthorized("Missing authentication token, please authenticate at the authentication service"));
        }

        // 调用认证服务验证 Token
        // TODO: 第三方鉴权服务地址待配置，配置项：gateway.security.auth.auth-server-url
        return verifyToken(token, request.getTraceId())
                .flatMap(valid -> {
                    if (valid) {
                        log.debug("[{}] Token 验证通过", request.getTraceId());
                        return chain.filter(request);
                    } else {
                        log.warn("[{}] Token 验证失败", request.getTraceId());
                        // TODO: 需要在提示信息中告知用户去哪里鉴权，待第三方鉴权服务地址确定后更新
                        return Mono.just(GatewayResponse.unauthorized("Invalid authentication token, please re-authenticate at the authentication service"));
                    }
                })
                .onErrorResume(e -> {
                    log.error("[{}] 调用认证服务失败: {}", request.getTraceId(), e.getMessage());
                    // 认证服务故障时的策略：拒绝请求
                    // TODO: 需要在提示信息中告知用户去哪里鉴权
                    return Mono.just(GatewayResponse.internalServerError("Authentication service unavailable, please try again later"));
                });
    }

    /**
     * 检查路径是否应该跳过鉴权
     *
     * @param path 请求路径
     * @return 是否跳过
     */
    private boolean shouldSkipAuth(String path) {
        GatewayProperties properties = getProperties();
        if (properties == null || properties.getSecurity() == null || properties.getSecurity().getAuth() == null) {
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
     * 调用第三方鉴权服务验证 Token 有效性
     *
     * TODO: 第三方鉴权服务地址待配置
     * 配置项：gateway.security.auth.auth-server-url
     * 验证接口：{auth-server-url}/verify
     * 请求头：Authorization: Bearer {token}
     *
     * @param token   Token
     * @param traceId 追踪 ID
     * @return 验证结果
     */
    private Mono<Boolean> verifyToken(String token, String traceId) {
        GatewayProperties properties = getProperties();
        if (properties == null || properties.getSecurity() == null || properties.getSecurity().getAuth() == null) {
            log.warn("[{}] GatewayProperties 未初始化或安全配置缺失，默认允许通过", traceId);
            return Mono.just(true);
        }

        String authServerUrl = properties.getSecurity().getAuth().getAuthServerUrl();

        if (authServerUrl == null || authServerUrl.isEmpty()) {
            // 未配置认证服务地址时，拒绝所有需要鉴权的请求
            // 这样可以确保安全性：宁可误拒也不误放
            log.error("[{}] 未配置认证服务地址 (gateway.security.auth.auth-server-url)，拒绝请求", traceId);
            return Mono.just(false);
        }

        int timeoutMs = properties.getSecurity().getAuth().getTimeoutMs();

        return createHttpClient()
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
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(e -> {
                    log.error("[{}] Token 验证请求失败: {}", traceId, e.getMessage());
                    return Mono.error(e);
                });
    }
}
