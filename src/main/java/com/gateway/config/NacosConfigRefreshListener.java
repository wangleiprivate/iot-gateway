package com.gateway.config;

import com.gateway.router.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Nacos 配置刷新监听器（简化版）
 *
 * 重构说明：
 * - 移除复杂的 Nacos 原生监听器注册逻辑（已迁移到 GatewayConfigHolder）
 * - 移除 @RefreshScope 相关的刷新逻辑（避免 Spring Cloud Alibaba 2025.0.0.0 BUG）
 * - 仅负责监听 GatewayConfigHolder 的配置变更，并触发 Router 刷新
 *
 * @author Gateway Team
 * @version 2.0.0
 */
@Component
public class NacosConfigRefreshListener {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigRefreshListener.class);

    private final GatewayConfigHolder configHolder;
    private final Router router;

    public NacosConfigRefreshListener(GatewayConfigHolder configHolder, Router router) {
        this.configHolder = configHolder;
        this.router = router;
    }

    /**
     * 初始化：注册配置变更监听器
     */
    @PostConstruct
    public void init() {
        log.info("初始化 NacosConfigRefreshListener...");

        // 注册配置变更监听器，当配置变更时刷新路由
        configHolder.addChangeListener(this::onConfigChange);

        log.info("NacosConfigRefreshListener 初始化完成");
    }

    /**
     * 配置变更回调
     */
    private void onConfigChange(GatewayProperties newProperties) {
        log.info("检测到配置变更，开始刷新路由...");

        try {
            // 刷新路由配置
            router.refresh();
            log.info("路由刷新完成");

            // 输出当前路由状态
            logRouteStatus(newProperties);

        } catch (Exception e) {
            log.error("配置变更处理失败", e);
        }
    }

    /**
     * 输出路由状态
     */
    private void logRouteStatus(GatewayProperties properties) {
        if (properties == null || properties.getRoutes() == null) {
            return;
        }

        log.info("当前路由状态:");
        for (GatewayProperties.RouteProperties route : properties.getRoutes()) {
            log.info("  路由 {}: pattern={}, requireAuth={}",
                    route.getId(), route.getPathPattern(), route.isRequireAuth());
        }
    }
}
