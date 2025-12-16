package com.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.gateway.router.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.properties.ConfigurationPropertiesRebinder;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ApplicationContext;
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
    private final ObjectProvider<GatewayProperties> propertiesProvider;
    private final ApplicationContext applicationContext;

    /**
     * Nacos 配置数据 ID 前缀
     */
    @Value("${spring.cloud.nacos.config.prefix:application}")
    private String prefix;

    /**
     * Nacos 配置组
     */
    @Value("${spring.cloud.nacos.config.group:IOT_GROUP}")
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

    @Autowired
    public NacosConfigRefreshListener(NacosConfigManager nacosConfigManager, Router router, 
                                     ConfigurationPropertiesRebinder configRebinder, RefreshScope refreshScope,
                                     ObjectProvider<GatewayProperties> propertiesProvider,
                                     ApplicationContext applicationContext) {
        this.nacosConfigManager = nacosConfigManager;
        this.router = router;
        this.configRebinder = configRebinder;
        this.refreshScope = refreshScope;
        this.propertiesProvider = propertiesProvider;
        this.applicationContext = applicationContext;
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
                
                // 调试：启动时立即从 Nacos 拉取配置，确认客户端缓存内容
                try {
                    String config = nacosConfigManager.getConfigService().getConfig(profileDataId, group, 5000);
                    log.info("启动时拉取 profile 配置内容 ({}): {}", profileDataId, config.substring(0, Math.min(500, config.length())) + (config.length() > 500 ? "..." : ""));
                } catch (NacosException e) {
                    log.error("启动时拉取 profile 配置失败: {}", profileDataId, e);
                }
            }
            
            log.info("Nacos 配置监听器初始化完成，监听配置: {}, {}", baseDataId, 
                    activeProfile != null && !activeProfile.isEmpty() ? prefix + "-" + activeProfile + "." + fileExtension : "无profile配置");
            
            // 启动后立即尝试刷新配置以确保加载
            try {
                Thread.sleep(3000); // 等待 Nacos 客户端初始化
                log.info("开始首次配置加载...");
                doRefresh();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("配置刷新等待被中断");
            }
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
                // 调试：立即从 Nacos 拉取最新配置，确认是否真的更新
                try {
                    String latestConfig = nacosConfigManager.getConfigService().getConfig(dataId, group, 5000);
                    log.info("立即拉取的最新配置内容 ({}): {}", dataId, latestConfig.substring(0, Math.min(500, latestConfig.length())) + (latestConfig.length() > 500 ? "..." : ""));
                } catch (NacosException e) {
                    log.error("立即拉取配置失败: {}", dataId, e);
                }
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
            // 打印具体变更的键值对 - 修复：使用Environment而不是System.getProperty
            for (String key : changedKeys) {
                if (key.startsWith("gateway.")) {
                    String newValue = applicationContext.getEnvironment().getProperty(key);
                    log.info("配置变更: {} -> {}", key, newValue);
                }
            }
            doRefresh();
        }
    }

    /**
     * 执行配置刷新
     * 使用 CAS 操作防止并发刷新
     * 优化：减少重复刷新，确保配置完全生效后再读取
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
                
                // 4. 等待配置完全加载（增加等待时间确保异步绑定完成）
                Thread.sleep(800);
                
                // 5. 刷新路由配置（这将重新加载 GatewayProperties 中的路由配置）
                router.refresh();
                log.info("路由配置刷新完成");
                
                // 6. 输出刷新后的状态（只输出一次，避免混淆）
                log.info("全量配置刷新完成，所有 Bean 已更新，路由 requireAuth 状态已同步");
                log.info("刷新后路由 requireAuth 状态:");
                GatewayProperties updatedProps = getCurrentGatewayProperties();
                if (updatedProps != null && updatedProps.getRoutes() != null) {
                    for (GatewayProperties.RouteProperties route : updatedProps.getRoutes()) {
                        log.info("  更新后路由 {} requireAuth={}", route.getId(), route.isRequireAuth());
                    }
                }
                
            } catch (Exception e) {
                log.error("全量配置刷新失败", e);
                
                // 备用刷新方案：简化版本，避免重复刷新
                try {
                    log.warn("执行备用刷新方案...");
                    refreshScope.refreshAll();
                    Thread.sleep(500);
                    configRebinder.rebind("gatewayProperties");
                    Thread.sleep(300);
                    router.refresh();
                    log.info("备用刷新方案执行完成");
                } catch (Exception ex) {
                    log.error("备用刷新方案也失败", ex);
                }
            } finally {
                // 确保刷新标志被重置
                try {
                    Thread.sleep(200); // 短暂延迟确保刷新完成
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
        // 由于 ConfigurationPropertiesRebinder 没有直接的 API 获取 Bean，
        // 我们使用 ObjectProvider 来获取最新的 GatewayProperties
        try {
            return propertiesProvider.getIfAvailable();
        } catch (Exception e) {
            log.warn("无法获取 GatewayProperties 实例: {}", e.getMessage());
            return null;
        }
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
