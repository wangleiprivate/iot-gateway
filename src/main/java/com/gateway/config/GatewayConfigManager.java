package com.gateway.config;

import org.springframework.stereotype.Component;

/**
 * 网关配置管理器
 * 统一管理 GatewayProperties 的访问，确保所有组件获取到最新配置
 *
 * 重构说明：
 * - 移除 @RefreshScope，避免 Spring Cloud Alibaba 2025.0.0.0 的 BUG
 * - 委托给 GatewayConfigHolder 获取配置
 * - GatewayConfigHolder 使用 Nacos 原生 API 实现热更新
 *
 * @author Gateway Team
 * @version 2.0.0
 */
@Component
public class GatewayConfigManager {

    private final GatewayConfigHolder configHolder;

    public GatewayConfigManager(GatewayConfigHolder configHolder) {
        this.configHolder = configHolder;
    }

    /**
     * 获取当前的 GatewayProperties 配置
     * 每次调用都会获取最新的配置（支持 Nacos 热更新）
     */
    public GatewayProperties getProperties() {
        GatewayProperties properties = configHolder.getProperties();
        if (properties == null) {
            throw new IllegalStateException("GatewayProperties 不可用，请检查配置是否正确加载");
        }
        return properties;
    }

    /**
     * 获取指定路由的最新 requireAuth 状态
     */
    public boolean getRouteRequireAuth(String routeId) {
        return configHolder.getRouteRequireAuth(routeId);
    }

    /**
     * 获取 IP 白名单配置
     */
    public GatewayProperties.IpWhitelistProperties getIpWhitelistProperties() {
        return getProperties().getSecurity().getIpWhitelist();
    }

    /**
     * 获取鉴权配置
     */
    public GatewayProperties.AuthProperties getAuthProperties() {
        return getProperties().getSecurity().getAuth();
    }

    /**
     * 获取限流配置
     */
    public GatewayProperties.RateLimitProperties getRateLimitProperties() {
        return getProperties().getRateLimit();
    }

    /**
     * 获取熔断配置
     */
    public GatewayProperties.CircuitBreakerProperties getCircuitBreakerProperties() {
        return getProperties().getCircuitBreaker();
    }

    /**
     * 手动刷新配置
     */
    public void refresh() {
        configHolder.refresh();
    }
}
