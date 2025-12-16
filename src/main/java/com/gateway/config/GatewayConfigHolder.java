package com.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 网关配置持有器
 * 解决 Spring Cloud Alibaba 2025.0.0.0 中 @RefreshScope + @ConfigurationProperties 的 BUG
 * 核心机制：
 * 1. 使用 Nacos ConfigService 原生 API 监听配置变化
 * 2. 使用 AtomicReference 保证配置读取的线程安全
 * 3. 直接解析 YAML 配置，绕过 Spring 的配置绑定机制
 *
 * @author Gateway Team
 * @version 2.0.0
 */
@Component
public class GatewayConfigHolder {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigHolder.class);

    private final NacosConfigManager nacosConfigManager;

    /**
     * 使用 AtomicReference 保证配置读取的线程安全性
     */
    private final AtomicReference<GatewayProperties> propertiesRef = new AtomicReference<>();

    /**
     * 配置变更监听器列表
     */
    private final CopyOnWriteArrayList<Consumer<GatewayProperties>> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * Nacos 监听器列表，用于销毁时移除
     */
    private final List<ListenerInfo> nacosListeners = new ArrayList<>();

    @Value("${spring.cloud.nacos.config.prefix:application}")
    private String prefix;

    @Value("${spring.cloud.nacos.config.group:IOT_GROUP}")
    private String group;

    @Value("${spring.cloud.nacos.config.file-extension:yml}")
    private String fileExtension;

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    /**
     * 配置刷新执行器
     */
    private final Executor refreshExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "gateway-config-refresh");
        thread.setDaemon(true);
        return thread;
    });

    public GatewayConfigHolder(NacosConfigManager nacosConfigManager, GatewayProperties initialProperties) {
        this.nacosConfigManager = nacosConfigManager;
        // 初始化时使用 Spring 绑定的配置
        this.propertiesRef.set(initialProperties);
    }

    @PostConstruct
    public void init() {
        log.info("初始化 GatewayConfigHolder...");

        // 首次加载配置
        loadConfigFromNacos();

        // 注册 Nacos 配置监听器
        if (activeProfile != null && !activeProfile.isEmpty()) {
            String profileDataId = prefix + "-" + activeProfile + "." + fileExtension;
            registerNacosListener(profileDataId);
        }

        // 也监听基础配置
        String baseDataId = prefix + "." + fileExtension;
        registerNacosListener(baseDataId);

        log.info("GatewayConfigHolder 初始化完成");
    }

    /**
     * 从 Nacos 加载配置
     */
    private void loadConfigFromNacos() {
        try {
            String dataId = activeProfile != null && !activeProfile.isEmpty()
                    ? prefix + "-" + activeProfile + "." + fileExtension
                    : prefix + "." + fileExtension;

            String configContent = nacosConfigManager.getConfigService().getConfig(dataId, group, 5000);
            if (configContent != null && !configContent.isEmpty()) {
                GatewayProperties newProperties = parseYamlConfig(configContent);
                if (newProperties != null) {
                    propertiesRef.set(newProperties);
                    log.info("从 Nacos 加载配置成功: dataId={}", dataId);
                    logCurrentConfig();
                }
            }
        } catch (NacosException e) {
            log.warn("从 Nacos 加载配置失败，使用初始配置: {}", e.getMessage());
        }
    }

    /**
     * 注册 Nacos 配置监听器
     */
    private void registerNacosListener(String dataId) {
        Listener listener = new Listener() {
            @Override
            public Executor getExecutor() {
                return refreshExecutor;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                log.info("收到 Nacos 配置变更通知: dataId={}", dataId);
                handleConfigChange(configInfo, dataId);
            }
        };

        try {
            nacosConfigManager.getConfigService().addListener(dataId, group, listener);
            nacosListeners.add(new ListenerInfo(dataId, listener));
            log.info("注册 Nacos 配置监听器成功: dataId={}, group={}", dataId, group);
        } catch (NacosException e) {
            log.error("注册 Nacos 配置监听器失败: dataId={}", dataId, e);
        }
    }

    /**
     * 处理配置变更
     */
    private void handleConfigChange(String configContent, String dataId) {
        if (configContent == null || configContent.isEmpty()) {
            log.warn("收到空配置内容: dataId={}", dataId);
            return;
        }

        try {
            log.info("开始解析配置变更...");
            log.debug("配置内容: {}", configContent.substring(0, Math.min(500, configContent.length())));

            GatewayProperties newProperties = parseYamlConfig(configContent);
            if (newProperties != null) {
                GatewayProperties oldProperties = propertiesRef.get();
                propertiesRef.set(newProperties);

                log.info("配置热更新成功!");
                logConfigDiff(oldProperties, newProperties);
                logCurrentConfig();

                // 通知所有监听器
                notifyListeners(newProperties);
            }
        } catch (Exception e) {
            log.error("解析配置变更失败: dataId={}", dataId, e);
        }
    }

    /**
     * 解析 YAML 配置为 GatewayProperties
     */
    @SuppressWarnings("unchecked")
    private GatewayProperties parseYamlConfig(String yamlContent) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> rootMap = yaml.load(yamlContent);
            if (rootMap == null) {
                return null;
            }

            Map<String, Object> gatewayMap = (Map<String, Object>) rootMap.get("gateway");
            if (gatewayMap == null) {
                log.warn("配置中未找到 'gateway' 节点");
                return null;
            }

            GatewayProperties properties = new GatewayProperties();

            // 解析服务器配置
            parseServerConfig(gatewayMap, properties);

            // 解析安全配置
            parseSecurityConfig(gatewayMap, properties);

            // 解析限流配置
            parseRateLimitConfig(gatewayMap, properties);

            // 解析熔断配置
            parseCircuitBreakerConfig(gatewayMap, properties);

            // 解析路由配置
            parseRoutesConfig(gatewayMap, properties);

            return properties;
        } catch (Exception e) {
            log.error("解析 YAML 配置失败", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void parseServerConfig(Map<String, Object> gatewayMap, GatewayProperties properties) {
        Map<String, Object> serverMap = (Map<String, Object>) gatewayMap.get("server");
        if (serverMap != null) {
            GatewayProperties.ServerProperties server = properties.getServer();
            if (serverMap.get("port") != null) {
                server.setPort(((Number) serverMap.get("port")).intValue());
            }

            Map<String, Object> nettyMap = (Map<String, Object>) serverMap.get("netty");
            if (nettyMap != null) {
                GatewayProperties.NettyProperties netty = server.getNetty();
                if (nettyMap.get("boss-threads") != null) {
                    netty.setBossThreads(((Number) nettyMap.get("boss-threads")).intValue());
                }
                if (nettyMap.get("worker-threads") != null) {
                    netty.setWorkerThreads(((Number) nettyMap.get("worker-threads")).intValue());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parseSecurityConfig(Map<String, Object> gatewayMap, GatewayProperties properties) {
        Map<String, Object> securityMap = (Map<String, Object>) gatewayMap.get("security");
        if (securityMap != null) {
            GatewayProperties.SecurityProperties security = properties.getSecurity();

            // IP 白名单
            Map<String, Object> ipWhitelistMap = (Map<String, Object>) securityMap.get("ip-whitelist");
            if (ipWhitelistMap != null) {
                GatewayProperties.IpWhitelistProperties ipWhitelist = security.getIpWhitelist();
                if (ipWhitelistMap.get("enabled") != null) {
                    ipWhitelist.setEnabled((Boolean) ipWhitelistMap.get("enabled"));
                }
                if (ipWhitelistMap.get("list") != null) {
                    ipWhitelist.setList((List<String>) ipWhitelistMap.get("list"));
                }
            }

            // 鉴权配置
            Map<String, Object> authMap = (Map<String, Object>) securityMap.get("auth");
            if (authMap != null) {
                GatewayProperties.AuthProperties auth = security.getAuth();
                if (authMap.get("enabled") != null) {
                    auth.setEnabled((Boolean) authMap.get("enabled"));
                }
                if (authMap.get("skip-paths") != null) {
                    auth.setSkipPaths((List<String>) authMap.get("skip-paths"));
                }
                if (authMap.get("auth-server-url") != null) {
                    auth.setAuthServerUrl((String) authMap.get("auth-server-url"));
                }
                if (authMap.get("timeout-ms") != null) {
                    auth.setTimeoutMs(((Number) authMap.get("timeout-ms")).intValue());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parseRateLimitConfig(Map<String, Object> gatewayMap, GatewayProperties properties) {
        Map<String, Object> rateLimitMap = (Map<String, Object>) gatewayMap.get("rate-limit");
        if (rateLimitMap != null) {
            GatewayProperties.RateLimitProperties rateLimit = properties.getRateLimit();
            if (rateLimitMap.get("enabled") != null) {
                rateLimit.setEnabled((Boolean) rateLimitMap.get("enabled"));
            }
            if (rateLimitMap.get("algorithm") != null) {
                rateLimit.setAlgorithm((String) rateLimitMap.get("algorithm"));
            }
            if (rateLimitMap.get("capacity") != null) {
                rateLimit.setCapacity(((Number) rateLimitMap.get("capacity")).intValue());
            }
            if (rateLimitMap.get("refill-tokens") != null) {
                rateLimit.setRefillTokens(((Number) rateLimitMap.get("refill-tokens")).intValue());
            }
            if (rateLimitMap.get("refill-period-seconds") != null) {
                rateLimit.setRefillPeriodSeconds(((Number) rateLimitMap.get("refill-period-seconds")).intValue());
            }

            Map<String, Object> perClientMap = (Map<String, Object>) rateLimitMap.get("per-client");
            if (perClientMap != null && perClientMap.get("enabled") != null) {
                rateLimit.getPerClient().setEnabled((Boolean) perClientMap.get("enabled"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parseCircuitBreakerConfig(Map<String, Object> gatewayMap, GatewayProperties properties) {
        Map<String, Object> cbMap = (Map<String, Object>) gatewayMap.get("circuit-breaker");
        if (cbMap != null) {
            GatewayProperties.CircuitBreakerProperties cb = properties.getCircuitBreaker();
            if (cbMap.get("enabled") != null) {
                cb.setEnabled((Boolean) cbMap.get("enabled"));
            }
            if (cbMap.get("failure-rate-threshold") != null) {
                cb.setFailureRateThreshold(((Number) cbMap.get("failure-rate-threshold")).intValue());
            }
            if (cbMap.get("wait-duration-open-state-seconds") != null) {
                cb.setWaitDurationOpenStateSeconds(((Number) cbMap.get("wait-duration-open-state-seconds")).intValue());
            }
            if (cbMap.get("sliding-window-size") != null) {
                cb.setSlidingWindowSize(((Number) cbMap.get("sliding-window-size")).intValue());
            }
            if (cbMap.get("slow-call-duration-threshold-ms") != null) {
                cb.setSlowCallDurationThresholdMs(((Number) cbMap.get("slow-call-duration-threshold-ms")).intValue());
            }
            if (cbMap.get("slow-call-rate-threshold") != null) {
                cb.setSlowCallRateThreshold(((Number) cbMap.get("slow-call-rate-threshold")).intValue());
            }
            if (cbMap.get("minimum-number-of-calls") != null) {
                cb.setMinimumNumberOfCalls(((Number) cbMap.get("minimum-number-of-calls")).intValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parseRoutesConfig(Map<String, Object> gatewayMap, GatewayProperties properties) {
        List<Map<String, Object>> routesList = (List<Map<String, Object>>) gatewayMap.get("routes");
        if (routesList != null && !routesList.isEmpty()) {
            List<GatewayProperties.RouteProperties> routes = new ArrayList<>();
            for (Map<String, Object> routeMap : routesList) {
                GatewayProperties.RouteProperties route = new GatewayProperties.RouteProperties();
                route.setId((String) routeMap.get("id"));
                route.setPathPattern((String) routeMap.get("path-pattern"));

                if (routeMap.get("targets") != null) {
                    route.setTargets((List<String>) routeMap.get("targets"));
                }
                if (routeMap.get("strip-prefix") != null) {
                    route.setStripPrefix((Boolean) routeMap.get("strip-prefix"));
                }
                if (routeMap.get("require-auth") != null) {
                    route.setRequireAuth((Boolean) routeMap.get("require-auth"));
                }
                if (routeMap.get("header-transforms") != null) {
                    route.setHeaderTransforms((Map<String, String>) routeMap.get("header-transforms"));
                }
                routes.add(route);
            }
            properties.setRoutes(routes);
        }
    }

    /**
     * 记录配置差异
     */
    private void logConfigDiff(GatewayProperties oldProps, GatewayProperties newProps) {
        if (oldProps == null || newProps == null) return;

        // 对比路由的 requireAuth 变化
        if (oldProps.getRoutes() != null && newProps.getRoutes() != null) {
            for (GatewayProperties.RouteProperties newRoute : newProps.getRoutes()) {
                oldProps.getRoutes().stream()
                        .filter(r -> r.getId().equals(newRoute.getId()))
                        .findFirst()
                        .ifPresent(oldRoute -> {
                            if (oldRoute.isRequireAuth() != newRoute.isRequireAuth()) {
                                log.info("路由 {} 的 requireAuth 变更: {} -> {}",
                                        newRoute.getId(), oldRoute.isRequireAuth(), newRoute.isRequireAuth());
                            }
                        });
            }
        }
    }

    /**
     * 记录当前配置状态
     */
    private void logCurrentConfig() {
        GatewayProperties props = propertiesRef.get();
        if (props == null) return;

        log.info("当前配置状态:");
        log.info("  服务端口: {}", props.getServer().getPort());
        if (props.getRoutes() != null) {
            for (GatewayProperties.RouteProperties route : props.getRoutes()) {
                log.info("  路由 {}: pattern={}, requireAuth={}",
                        route.getId(), route.getPathPattern(), route.isRequireAuth());
            }
        }
    }

    /**
     * 通知所有配置变更监听器
     */
    private void notifyListeners(GatewayProperties newProperties) {
        for (Consumer<GatewayProperties> listener : changeListeners) {
            try {
                listener.accept(newProperties);
            } catch (Exception e) {
                log.error("通知配置变更监听器失败", e);
            }
        }
    }

    // ==================== 公共 API ====================

    /**
     * 获取当前配置（线程安全）
     */
    public GatewayProperties getProperties() {
        return propertiesRef.get();
    }

    /**
     * 获取指定路由的 requireAuth 状态
     */
    public boolean getRouteRequireAuth(String routeId) {
        GatewayProperties props = propertiesRef.get();
        if (props != null && props.getRoutes() != null) {
            return props.getRoutes().stream()
                    .filter(r -> routeId.equals(r.getId()))
                    .findFirst()
                    .map(GatewayProperties.RouteProperties::isRequireAuth)
                    .orElse(true);
        }
        return true;
    }

    /**
     * 注册配置变更监听器
     */
    public void addChangeListener(Consumer<GatewayProperties> listener) {
        changeListeners.add(listener);
    }

    /**
     * 手动刷新配置
     */
    public void refresh() {
        loadConfigFromNacos();
    }

    @PreDestroy
    public void destroy() {
        for (ListenerInfo info : nacosListeners) {
            try {
                nacosConfigManager.getConfigService().removeListener(info.dataId, group, info.listener);
                log.info("移除 Nacos 配置监听器: dataId={}", info.dataId);
            } catch (Exception e) {
                log.error("移除 Nacos 配置监听器失败: dataId={}", info.dataId, e);
            }
        }
        nacosListeners.clear();
    }

    private static class ListenerInfo {
        final String dataId;
        final Listener listener;

        ListenerInfo(String dataId, Listener listener) {
            this.dataId = dataId;
            this.listener = listener;
        }
    }
}
