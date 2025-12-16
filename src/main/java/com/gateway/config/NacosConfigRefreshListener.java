package com.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.gateway.router.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
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
 * Nacos 配置刷新监听器
 * 监听 Nacos 配置中心的配置变化，自动刷新网关路由配置
 *
 * 实现两种监听机制：
 * 1. Spring Cloud 环境变更事件监听（推荐，与 Spring Cloud 生态集成）
 * 2. Nacos 原生配置监听器（备选，更精确控制）
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
public class NacosConfigRefreshListener {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigRefreshListener.class);

    private final NacosConfigManager nacosConfigManager;
    private final Router router;

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

    public NacosConfigRefreshListener(NacosConfigManager nacosConfigManager, Router router) {
        this.nacosConfigManager = nacosConfigManager;
        this.router = router;
    }

    /**
     * 初始化 Nacos 原生配置监听器
     * 同时监听基础配置和 profile 配置
     */
    @PostConstruct
    public void init() {
        // 监听基础配置文件: application.yml
        String baseDataId = prefix + "." + fileExtension;
        registerListener(baseDataId);

        // 如果有激活的 profile，也监听 profile 配置文件: application-dev.yml
        if (activeProfile != null && !activeProfile.isEmpty()) {
            String profileDataId = prefix + "-" + activeProfile + "." + fileExtension;
            registerListener(profileDataId);
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
     * 使用 @Order 确保在 ConfigurationPropertiesRebinder 之后执行
     * ConfigurationPropertiesRebinder 使用 Ordered.LOWEST_PRECEDENCE - 100
     * 我们使用 Ordered.LOWEST_PRECEDENCE 确保 GatewayProperties 已被重新绑定
     *
     * @param event 环境变更事件
     */
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        Set<String> changedKeys = event.getKeys();

        // 检查是否有网关相关配置变更
        boolean gatewayConfigChanged = changedKeys.stream()
                .anyMatch(key -> key.startsWith("gateway."));

        if (gatewayConfigChanged) {
            log.info("检测到网关配置变更，变更的配置项: {}", changedKeys);
            doRefresh();
        }
    }

    /**
     * 执行配置刷新
     * 使用 CAS 操作防止并发刷新
     */
    private void doRefresh() {
        if (refreshing.compareAndSet(false, true)) {
            try {
                log.info("开始刷新路由配置...");
                router.refresh();
                log.info("路由配置刷新完成");
            } catch (Exception e) {
                log.error("刷新路由配置失败", e);
            } finally {
                refreshing.set(false);
            }
        } else {
            log.debug("配置刷新正在进行中，跳过本次刷新请求");
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
