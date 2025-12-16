package com.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.gateway.router.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nacos 配置刷新监听器（重构版）
 * 基于 Spring Cloud Alibaba 2025.0.0.0 实现全量配置热加载
 * 确保 require-auth 属性能够实时热加载
 *
 * @author Gateway Team
 * @version 2.0.0
 */
@Component
public class NacosConfigRefreshListener {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigRefreshListener.class);

    private final NacosConfigManager nacosConfigManager;
    private final Router router;
    private final ConfigurationPropertiesRebinder configRebinder;
    private final RefreshScope refreshScope;

    /**
     * Nacos 配置数据 ID 前缀
     */
    @Value("${spring.cloud.nacos.config.prefix:application}")
    private String prefix;

    /**
     * Nacos 配置组
     */
    @Value("${spring.cloud.nacos.config.group:DEFAULT_GROUP}")
    private String group;

    /**
     * Nacos 配置文件扩展名
     */
    @Value("${spring.cloud.nacos.config.file-extension:yml}")
    private String fileExtension;

    /**
     * 当前激活的 profile
     */
    @Value("${spring.profiles.active:}")
    private String activeProfile;

    /**
     * 配置刷新执行器
     */
    private final Executor refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "nacos-config-refresh");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Nacos 配置监听器实例列表
     */
    private final List<ListenerInfo> configListeners = new ArrayList<>();

    /**
     * 防止重复刷新的标志
     */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public NacosConfigRefreshListener(NacosConfigManager nacosConfigManager, Router router, 
                                     ConfigurationPropertiesRebinder configRebinder, RefreshScope refreshScope) {
        this.nacosConfigManager = nacosConfigManager;
        this.router = router;
        this.configRebinder = configRebinder;
        this.refreshScope = refreshScope;
    }

    /**
     * 初始化 Nacos 原生配置监听器
     * 同时监听基础配置和 profile 配置
     */
    @PostConstruct
    public void init() {
        try {
            // 监听基础配置文件: application.yml
            String baseDataId = prefix + "." + fileExtension;
            registerListener(baseDataId);

            // 如果有激活的 profile，也监听 profile 配置文件: application-dev.yml
            if (activeProfile != null && !activeProfile.isEmpty()) {
                String profileDataId = prefix + "-" + activeProfile + "." + fileExtension;
                registerListener(profileDataId);
            }
            
            log.info("Nacos 配置监听器初始化完成，监听配置: {}, {}", baseDataId, 
                    activeProfile != null && !activeProfile.isEmpty() ? prefix + "-" + activeProfile + "." + fileExtension : "无profile配置");
        } catch (Exception e) {
            log.error("Nacos 配置监听器初始化失败", e);
        }
    }

    /**
     * 注册配置监听器
     *
     * @param dataId 配置 ID
     */
    private void registerListener(String dataId) {
        log.info("初始化 Nacos 配置监听器: dataId={}, group={}", dataId, group);

        Listener listener = new Listener() {
            @Override
            public Executor getExecutor() {
                return refreshExecutor;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                log.info("收到 Nacos 配置变更通知 (原生监听器): dataId={}", dataId);
                log.debug("配置变更内容: {}", configInfo.substring(0, Math.min(200, configInfo.length())) + "...");
                doRefresh();
            }
        };

        try {
            nacosConfigManager.getConfigService().addListener(dataId, group, listener);
            configListeners.add(new ListenerInfo(dataId, listener));
            log.info("Nacos 配置监听器注册成功: dataId={}", dataId);
        } catch (NacosException e) {
            log.error("注册 Nacos 配置监听器失败: dataId={}", dataId, e);
        }
    }

    /**
     * 监听 Spring Cloud 环境变更事件
     * 当 Nacos 配置变更时，Spring Cloud Nacos Config 会自动更新 Environment
     * 并发布 EnvironmentChangeEvent 事件
     *
     * @param event 环境变更事件
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        Set<String> changedKeys = event.getKeys();
        
        // 检查是否有网关相关配置变更
        boolean gatewayConfigChanged = changedKeys.stream()
                .anyMatch(key -> key.startsWith("gateway."));

        if (gatewayConfigChanged) {
            log.info("检测到网关配置变更，变更的配置项: {}", changedKeys);
            // 打印具体变更的键值对
            for (String key : changedKeys) {
                if (key.startsWith("gateway.")) {
                    log.info("配置变更: {} -> {}", key, System.getProperty(key));
                }
            }
            doRefresh();
        }
    }

    /**
     * 执行配置刷新
     * 使用 CAS 操作防止并发刷新
     * 重构：使用更可靠的方式确保配置更新
     */
    private void doRefresh() {
        if (refreshing.compareAndSet(false, true)) {
            try {
                log.info("开始执行全量配置刷新，包含路由级别的 require-auth 更新...");
                
                // 1. 记录刷新前的路由状态，便于调试
                log.info("刷新前路由 requireAuth 状态:");
                GatewayProperties currentProps = getCurrentGatewayProperties();
                if (currentProps != null && currentProps.getRoutes() != null) {
                    for (GatewayProperties.RouteProperties route : currentProps.getRoutes()) {
                        log.info("  配置中路由 {} requireAuth={}", route.getId(), route.isRequireAuth());
                    }
                }
                
                // 2. 强制刷新所有 @RefreshScope Bean
                refreshScope.refreshAll();
                log.info("@RefreshScope Bean 已刷新");
                
                // 3. 强制重新绑定所有配置
                configRebinder.rebind("gatewayProperties");
                log.info("GatewayProperties 配置已重新绑定");
                
                // 4. 等待配置完全加载
                Thread.sleep(500);
                
                // 5. 刷新路由配置（这将重新加载 GatewayProperties 中的路由配置）
                router.refresh();
                log.info("路由配置刷新完成");
                
                // 6. 再次刷新以确保所有 Bean 都使用最新配置
                refreshScope.refreshAll();
                
                // 7. 额外确保 GatewayProperties 已更新
                configRebinder.rebind("gatewayProperties");
                log.info("GatewayProperties 再次重新绑定");
                
                // 8. 再次等待确保配置完全加载
                Thread.sleep(300);
                
                // 9. 再次刷新路由以确保使用最新配置
                router.refresh();
                
                log.info("全量配置刷新完成，所有 Bean 已更新，路由 requireAuth 状态已同步");
                
                // 10. 输出刷新后的状态
                log.info("刷新后路由 requireAuth 状态:");
                GatewayProperties updatedProps = getCurrentGatewayProperties();
                if (updatedProps != null && updatedProps.getRoutes() != null) {
                    for (GatewayProperties.RouteProperties route : updatedProps.getRoutes()) {
                        log.info("  更新后路由 {} requireAuth={}", route.getId(), route.isRequireAuth());
                    }
                }
                
            } catch (Exception e) {
                log.error("全量配置刷新失败", e);
                
                // 备用刷新方案
                try {
                    log.warn("执行备用刷新方案...");
                    refreshScope.refreshAll();
                    Thread.sleep(300);
                    configRebinder.rebind("gatewayProperties");
                    Thread.sleep(200);
                    router.refresh();
                    log.info("备用刷新方案执行完成");
                } catch (Exception ex) {
                    log.error("备用刷新方案也失败", ex);
                }
            } finally {
                // 确保刷新标志被重置
                try {
                    Thread.sleep(100); // 短暂延迟确保刷新完成
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                refreshing.set(false);
            }
        } else {
            log.warn("配置刷新正在进行中，跳过本次刷新请求");
        }
    }
    
    /**
     * 获取当前 GatewayProperties 实例，用于调试
     */
    private GatewayProperties getCurrentGatewayProperties() {
        return GatewayProperties.class.cast(configRebinder.getBeans().get("gatewayProperties"));
    }

    /**
     * 销毁时移除所有监听器
     */
    @PreDestroy
    public void destroy() {
        for (ListenerInfo info : configListeners) {
            try {
                nacosConfigManager.getConfigService().removeListener(info.dataId, group, info.listener);
                log.info("Nacos 配置监听器已移除: dataId={}", info.dataId);
            } catch (Exception e) {
                log.error("移除 Nacos 配置监听器失败: dataId={}", info.dataId, e);
            }
        }
        configListeners.clear();
    }

    /**
     * 监听器信息
     */
    private static class ListenerInfo {
        final String dataId;
        final Listener listener;

        ListenerInfo(String dataId, Listener listener) {
            this.dataId = dataId;
            this.listener = listener;
        }
    }
}
