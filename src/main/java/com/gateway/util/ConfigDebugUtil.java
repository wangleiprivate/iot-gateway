package com.gateway.util;

import com.gateway.config.GatewayConfigHolder;
import com.gateway.config.GatewayProperties;
import com.gateway.router.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 配置调试工具类
 * 用于查看当前配置状态，辅助调试热加载功能
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
public class ConfigDebugUtil {

    private static final Logger log = LoggerFactory.getLogger(ConfigDebugUtil.class);

    private final GatewayConfigHolder configHolder;
    private final Router router;

    public ConfigDebugUtil(GatewayConfigHolder configHolder, Router router) {
        this.configHolder = configHolder;
        this.router = router;
    }

    /**
     * 打印当前配置状态
     */
    public void printCurrentConfig() {
        GatewayProperties properties = configHolder.getProperties();
        if (properties == null) {
            log.warn("GatewayProperties 不可用");
            return;
        }

        log.info("=== 当前网关配置状态 ===");

        // 打印安全配置
        if (properties.getSecurity() != null) {
            log.info("安全配置:");
            if (properties.getSecurity().getIpWhitelist() != null) {
                log.info("  IP白名单启用: {}", properties.getSecurity().getIpWhitelist().isEnabled());
                log.info("  IP白名单列表: {}", properties.getSecurity().getIpWhitelist().getList());
            }
            if (properties.getSecurity().getAuth() != null) {
                log.info("  鉴权启用: {}", properties.getSecurity().getAuth().isEnabled());
                log.info("  认证服务器URL: {}", properties.getSecurity().getAuth().getAuthServerUrl());
                log.info("  跳过路径: {}", properties.getSecurity().getAuth().getSkipPaths());
            }
        }

        // 打印限流配置
        log.info("限流配置:");
        log.info("  限流启用: {}", properties.getRateLimit().isEnabled());
        log.info("  容量: {}", properties.getRateLimit().getCapacity());
        log.info("  填充令牌: {}", properties.getRateLimit().getRefillTokens());
        log.info("  填充周期(秒): {}", properties.getRateLimit().getRefillPeriodSeconds());

        // 打印熔断配置
        log.info("熔断配置:");
        log.info("  熔断启用: {}", properties.getCircuitBreaker().isEnabled());
        log.info("  失败率阈值: {}", properties.getCircuitBreaker().getFailureRateThreshold());
        log.info("  等待时间(秒): {}", properties.getCircuitBreaker().getWaitDurationOpenStateSeconds());

        // 打印路由配置
        if (properties.getRoutes() != null) {
            log.info("路由配置:");
            for (GatewayProperties.RouteProperties route : properties.getRoutes()) {
                log.info("  路由[{}]:", route.getId());
                log.info("    路径模式: {}", route.getPathPattern());
                log.info("    目标地址: {}", route.getTargets());
                log.info("    剥离前缀: {}", route.isStripPrefix());
                log.info("    需要鉴权: {}", route.isRequireAuth()); // 重点关注的 requireAuth 配置
                log.info("    请求头转换: {}", route.getHeaderTransforms());
            }
        }

        // 打印路由器中的路由状态
        log.info("路由器中当前路由状态:");
        for (com.gateway.model.RouteDefinition route : router.getRoutes()) {
            log.info("  路由[{}]: requireAuth={}", route.getId(), route.isRequireAuth());
        }

        log.info("===================");
    }

    /**
     * 获取特定路由的 requireAuth 状态
     *
     * @param routeId 路由ID
     * @return requireAuth 状态
     */
    public boolean getRouteRequireAuthStatus(String routeId) {
        GatewayProperties properties = configHolder.getProperties();
        if (properties != null && properties.getRoutes() != null) {
            return properties.getRoutes().stream()
                .filter(r -> routeId.equals(r.getId()))
                .findFirst()
                .map(GatewayProperties.RouteProperties::isRequireAuth)
                .orElse(true);
        }
        return true; // 默认需要鉴权
    }
}