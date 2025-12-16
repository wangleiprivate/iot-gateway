package com.gateway.filter.impl;

import com.gateway.config.GatewayProperties;
import com.gateway.filter.FilterChain;
import com.gateway.filter.GatewayFilter;
import com.gateway.model.GatewayRequest;
import com.gateway.model.GatewayResponse;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流过滤器
 * 基于令牌桶算法实现限流
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
public class RateLimitFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /**
     * 使用 ObjectProvider 延迟获取 GatewayProperties
     * 这样每次调用时都能获取到 ConfigurationPropertiesRebinder 刷新后的最新配置（支持 Nacos 热更新）
     */
    private final ObjectProvider<GatewayProperties> propertiesProvider;

    /**
     * 全局限流桶
     */
    private volatile Bucket globalBucket;

    /**
     * 按客户端 IP 的限流桶
     */
    private final ConcurrentHashMap<String, Bucket> clientBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter(ObjectProvider<GatewayProperties> propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
        initGlobalBucket();
    }

    /**
     * 获取当前的 GatewayProperties 配置
     * 每次调用都会获取最新的配置（支持 Nacos 热更新）
     */
    private GatewayProperties getProperties() {
        return propertiesProvider.getIfAvailable();
    }

    /**
     * 初始化全局限流桶
     */
    private void initGlobalBucket() {
        GatewayProperties properties = getProperties();
        GatewayProperties.RateLimitProperties rateLimitConfig = (properties != null)
                ? properties.getRateLimit() : null;
        if (rateLimitConfig == null) {
            rateLimitConfig = new GatewayProperties.RateLimitProperties();
        }

        Bandwidth bandwidth = Bandwidth.classic(
                rateLimitConfig.getCapacity(),
                Refill.greedy(
                        rateLimitConfig.getRefillTokens(),
                        Duration.ofSeconds(rateLimitConfig.getRefillPeriodSeconds())
                )
        );

        this.globalBucket = Bucket.builder()
                .addLimit(bandwidth)
                .build();

        log.info("初始化全局限流桶: capacity={}, refillTokens={}, refillPeriod={}s",
                rateLimitConfig.getCapacity(),
                rateLimitConfig.getRefillTokens(),
                rateLimitConfig.getRefillPeriodSeconds());
    }

    @Override
    public String getName() {
        return "RateLimitFilter";
    }

    @Override
    public int getOrder() {
        return 200; // IP 白名单之后
    }

    @Override
    public boolean isEnabled() {
        GatewayProperties properties = getProperties();
        return properties != null && properties.getRateLimit() != null && properties.getRateLimit().isEnabled();
    }

    @Override
    public Mono<GatewayResponse> filter(GatewayRequest request, FilterChain chain) {
        String clientIp = request.getRemoteIp();

        // 检查全局限流
        if (!globalBucket.tryConsume(1)) {
            log.warn("[{}] 全局限流触发，拒绝请求: clientIp={}", request.getTraceId(), clientIp);
            return Mono.just(GatewayResponse.tooManyRequests("Global rate limit exceeded"));
        }

        // 检查客户端限流
        GatewayProperties properties = getProperties();
        if (properties != null && properties.getRateLimit() != null
                && properties.getRateLimit().getPerClient() != null
                && properties.getRateLimit().getPerClient().isEnabled()) {

            Bucket clientBucket = getClientBucket(clientIp);
            if (!clientBucket.tryConsume(1)) {
                log.warn("[{}] 客户端限流触发，拒绝请求: clientIp={}", request.getTraceId(), clientIp);
                return Mono.just(GatewayResponse.tooManyRequests("Client rate limit exceeded for IP: " + clientIp));
            }
        }

        log.debug("[{}] 限流检查通过: clientIp={}", request.getTraceId(), clientIp);
        return chain.filter(request);
    }

    /**
     * 获取客户端限流桶
     *
     * @param clientIp 客户端 IP
     * @return 限流桶
     */
    private Bucket getClientBucket(String clientIp) {
        return clientBuckets.computeIfAbsent(clientIp, ip -> {
            GatewayProperties properties = getProperties();
            GatewayProperties.RateLimitProperties rateLimitConfig = (properties != null)
                    ? properties.getRateLimit() : new GatewayProperties.RateLimitProperties();
            if (rateLimitConfig == null) {
                rateLimitConfig = new GatewayProperties.RateLimitProperties();
            }

            // 客户端限流使用更小的容量（全局容量的 1/10）
            int clientCapacity = Math.max(1, rateLimitConfig.getCapacity() / 10);
            int clientRefillTokens = Math.max(1, rateLimitConfig.getRefillTokens() / 10);

            Bandwidth bandwidth = Bandwidth.classic(
                    clientCapacity,
                    Refill.greedy(
                            clientRefillTokens,
                            Duration.ofSeconds(rateLimitConfig.getRefillPeriodSeconds())
                    )
            );

            log.debug("创建客户端限流桶: ip={}, capacity={}", ip, clientCapacity);
            return Bucket.builder()
                    .addLimit(bandwidth)
                    .build();
        });
    }

    /**
     * 清理过期的客户端限流桶（可由定时任务调用）
     */
    public void cleanupExpiredBuckets() {
        // 简单实现：清理超过一定数量的桶
        if (clientBuckets.size() > 10000) {
            log.info("清理客户端限流桶，当前数量: {}", clientBuckets.size());
            clientBuckets.clear();
        }
    }
}
