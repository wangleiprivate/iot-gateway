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
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
 * @author Claude Gateway Team
 * @version 1.0.0
 */
@Component
public class NacosConfigRefreshListener {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigRefreshListener.class);

    private final NacosConfigManager nacosConfigManager;
    private final Router router;

    /**
     * Nacos 配置数据 ID
     */
    @Value("${spring.cloud.nacos.config.prefix:gateway-config}")
    private String dataId;

    /**
     * Nacos 配置组
     */
    @Value("${spring.cloud.nacos.config.group:DEFAULT_GROUP}")
    private String group;

    /**
     * Nacos 配置文件扩展名
     */
    @Value("${spring.cloud.nacos.config.file-extension:yaml}")
    private String fileExtension;

    /**
     * 配置刷新执行器
     */
    private final Executor refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "nacos-config-refresh");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Nacos 配置监听器实例
     */
    private Listener configListener;

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
     */
    @PostConstruct
    public void init() {
        String fullDataId = dataId + "." + fileExtension;

        log.info("初始化 Nacos 配置监听器: dataId={}, group={}", fullDataId, group);

        configListener = new Listener() {
            @Override
            public Executor getExecutor() {
                return refreshExecutor;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                log.info("收到 Nacos 配置变更通知 (原生监听器)");
                doRefresh();
            }
        };

        try {
            nacosConfigManager.getConfigService().addListener(fullDataId, group, configListener);
            log.info("Nacos 配置监听器注册成功");
        } catch (NacosException e) {
            log.error("注册 Nacos 配置监听器失败", e);
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
     * 销毁时移除监听器
     */
    @PreDestroy
    public void destroy() {
        if (configListener != null) {
            String fullDataId = dataId + "." + fileExtension;
            try {
                nacosConfigManager.getConfigService().removeListener(fullDataId, group, configListener);
                log.info("Nacos 配置监听器已移除");
            } catch (Exception e) {
                log.error("移除 Nacos 配置监听器失败", e);
            }
        }
    }
}
