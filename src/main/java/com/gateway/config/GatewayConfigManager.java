package com.gateway.config;

import com.gateway.filter.impl.AuthFilter;
import com.gateway.filter.impl.CircuitBreakerFilter;
import com.gateway.filter.impl.IpWhitelistFilter;
import com.gateway.filter.impl.RateLimitFilter;
import com.gateway.router.Router;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 网关配置管理器
 * 统一管理GatewayProperties的访问，确保所有组件获取到的是同一个实例
 * 解决多个组件中重复定义getProperties()方法导致的热更新问题
 * 
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
@RefreshScope
public class GatewayConfigManager {

    private final ObjectProvider<GatewayProperties> propertiesProvider;
    
    public GatewayConfigManager(ObjectProvider<GatewayProperties> propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    /**
     * 获取当前的GatewayProperties配置
     * 每次调用都会获取最新的配置（支持Nacos热更新）
     */
    public GatewayProperties getProperties() {
        GatewayProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null) {
            throw new IllegalStateException("GatewayProperties 不可用，请检查配置是否正确加载");
        }
        return properties;
    }
    
    /**
     * 获取指定路由的最新requireAuth状态
     */
    public boolean getRouteRequireAuth(String routeId) {
        GatewayProperties properties = getProperties();
        if (properties.getRoutes() != null) {
            return properties.getRoutes().stream()
                .filter(r -> routeId.equals(r.getId()))
                .findFirst()
                .map(GatewayProperties.RouteProperties::isRequireAuth)
                .orElse(true);
        }
        return true;
    }
    
    /**
     * 获取IP白名单配置
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
}