package com.gateway.filter.impl;

import com.gateway.config.GatewayProperties;
import com.gateway.filter.FilterChain;
import com.gateway.filter.GatewayFilter;
import com.gateway.model.GatewayRequest;
import com.gateway.model.GatewayResponse;
import com.gateway.model.RouteDefinition;
import com.gateway.router.Router;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断过滤器
 * 基于 Resilience4j 实现熔断保护
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
public class CircuitBreakerFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerFilter.class);

    /**
     * 使用 ObjectProvider 延迟获取 GatewayProperties
     * 这样每次调用时都能获取到 ConfigurationPropertiesRebinder 刷新后的最新配置（支持 Nacos 热更新）
     */
    private final ObjectProvider<GatewayProperties> propertiesProvider;
    private final Router router;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ConcurrentHashMap<String, CircuitBreaker> routeCircuitBreakers = new ConcurrentHashMap<>();

    public CircuitBreakerFilter(ObjectProvider<GatewayProperties> propertiesProvider, Router router) {
        this.propertiesProvider = propertiesProvider;
        this.router = router;
        this.circuitBreakerRegistry = createCircuitBreakerRegistry();
    }

    /**
     * 获取当前的 GatewayProperties 配置
     * 每次调用都会获取最新的配置（支持 Nacos 热更新）
     */
    private GatewayProperties getProperties() {
        return propertiesProvider.getIfAvailable();
    }

    /**
     * 创建熔断器注册表
     */
    private CircuitBreakerRegistry createCircuitBreakerRegistry() {
        GatewayProperties properties = getProperties();
        GatewayProperties.CircuitBreakerProperties cbConfig = (properties != null)
                ? properties.getCircuitBreaker() : null;
        if (cbConfig == null) {
            cbConfig = new GatewayProperties.CircuitBreakerProperties();
        }

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbConfig.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(cbConfig.getWaitDurationOpenStateSeconds()))
                .slidingWindowSize(cbConfig.getSlidingWindowSize())
                .minimumNumberOfCalls(cbConfig.getMinimumNumberOfCalls())
                .slowCallDurationThreshold(Duration.ofMillis(cbConfig.getSlowCallDurationThresholdMs()))
                .slowCallRateThreshold(cbConfig.getSlowCallRateThreshold())
                .build();

        log.info("初始化熔断器配置: failureRateThreshold={}%, waitDuration={}s, slidingWindowSize={}",
                cbConfig.getFailureRateThreshold(),
                cbConfig.getWaitDurationOpenStateSeconds(),
                cbConfig.getSlidingWindowSize());

        return CircuitBreakerRegistry.of(config);
    }

    @Override
    public String getName() {
        return "CircuitBreakerFilter";
    }

    @Override
    public int getOrder() {
        return 400; // 鉴权之后
    }

    @Override
    public boolean isEnabled() {
        GatewayProperties properties = getProperties();
        return properties != null && properties.getCircuitBreaker() != null && properties.getCircuitBreaker().isEnabled();
    }

    @Override
    public Mono<GatewayResponse> filter(GatewayRequest request, FilterChain chain) {
        // 获取匹配的路由
        RouteDefinition route = router.match(request);
        if (route == null) {
            return chain.filter(request);
        }

        // 获取或创建路由对应的熔断器
        CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(route);

        // 检查熔断器状态
        CircuitBreaker.State state = circuitBreaker.getState();
        if (state == CircuitBreaker.State.OPEN) {
            log.warn("[{}] 熔断器打开，拒绝请求: route={}", request.getTraceId(), route.getId());
            return Mono.just(GatewayResponse.serviceUnavailable(
                    "Circuit breaker is open for route: " + route.getId()));
        }

        log.debug("[{}] 熔断器状态: route={}, state={}", request.getTraceId(), route.getId(), state);

        // 通过熔断器执行后续过滤器
        return chain.filter(request)
                .transform(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class, e -> {
                    log.warn("[{}] 熔断器拒绝调用: route={}", request.getTraceId(), route.getId());
                    return Mono.just(GatewayResponse.serviceUnavailable(
                            "Circuit breaker is open for route: " + route.getId()));
                });
    }

    /**
     * 获取或创建路由对应的熔断器
     *
     * @param route 路由定义
     * @return 熔断器
     */
    private CircuitBreaker getOrCreateCircuitBreaker(RouteDefinition route) {
        return routeCircuitBreakers.computeIfAbsent(route.getId(), id -> {
            // 检查路由是否有自定义熔断策略
            RouteDefinition.CircuitBreakerPolicy policy = route.getCircuitBreakerPolicy();
            if (policy != null && policy.isEnabled()) {
                CircuitBreakerConfig customConfig = CircuitBreakerConfig.custom()
                        .failureRateThreshold(policy.getFailureRateThreshold())
                        .waitDurationInOpenState(Duration.ofSeconds(policy.getWaitDurationSeconds()))
                        .slidingWindowSize(policy.getSlidingWindowSize())
                        .build();

                log.info("为路由 {} 创建自定义熔断器: failureRate={}%, waitDuration={}s",
                        id, policy.getFailureRateThreshold(), policy.getWaitDurationSeconds());

                return CircuitBreaker.of(id, customConfig);
            }

            // 使用全局配置
            log.info("为路由 {} 创建默认熔断器", id);
            return circuitBreakerRegistry.circuitBreaker(id);
        });
    }

    /**
     * 获取熔断器状态（用于监控）
     *
     * @param routeId 路由 ID
     * @return 熔断器状态
     */
    public CircuitBreaker.State getCircuitBreakerState(String routeId) {
        CircuitBreaker cb = routeCircuitBreakers.get(routeId);
        return cb != null ? cb.getState() : null;
    }

    /**
     * 重置熔断器（用于运维操作）
     *
     * @param routeId 路由 ID
     */
    public void resetCircuitBreaker(String routeId) {
        CircuitBreaker cb = routeCircuitBreakers.get(routeId);
        if (cb != null) {
            cb.reset();
            log.info("已重置路由 {} 的熔断器", routeId);
        }
    }
}
