package com.gateway.router;

import com.gateway.config.GatewayConfigHolder;
import com.gateway.config.GatewayProperties;
import com.gateway.model.GatewayRequest;
import com.gateway.model.RouteDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 路由器
 * 负责将请求匹配到对应的后端服务
 *
 * 重构说明：
 * - 使用 GatewayConfigHolder 替代 ObjectProvider<GatewayProperties>
 * - GatewayConfigHolder 通过 Nacos 原生 API 实现热更新，绕过 Spring 的配置绑定问题
 *
 * @author Gateway Team
 * @version 2.0.0
 */
@Component
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    /**
     * 热更新状态标志，防止在热更新过程中路由匹配读取到不一致的配置
     */
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);

    /**
     * 使用 GatewayConfigHolder 获取最新配置
     */
    private final GatewayConfigHolder configHolder;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 路由定义列表（线程安全，支持动态更新）
     */
    private final CopyOnWriteArrayList<RouteDefinition> routes = new CopyOnWriteArrayList<>();

    public Router(GatewayConfigHolder configHolder) {
        this.configHolder = configHolder;
    }

    /**
     * 获取当前的 GatewayProperties 配置
     */
    private GatewayProperties getProperties() {
        return configHolder.getProperties();
    }

    /**
     * 初始化路由配置
     */
    @PostConstruct
    public void init() {
        // 尝试多次加载路由配置，确保 Nacos 配置已加载
        int attempts = 0;
        final int maxAttempts = 10;
        while (attempts < maxAttempts) {
            loadRoutesFromConfig();
            if (routes.size() > 0) {
                log.info("路由器初始化完成，共加载 {} 条路由", routes.size());
                return;
            }

            attempts++;
            log.warn("第 {} 次尝试加载路由配置，未找到路由，等待后重试...", attempts);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("路由器初始化完成，共加载 {} 条路由", routes.size());
    }

    /**
     * 从配置加载路由
     */
    private void loadRoutesFromConfig() {
        GatewayProperties properties = getProperties();
        if (properties == null) {
            log.error("无法获取 GatewayProperties");
            return;
        }

        List<GatewayProperties.RouteProperties> routeConfigs = properties.getRoutes();
        if (routeConfigs == null || routeConfigs.isEmpty()) {
            log.warn("未配置任何路由");
            routes.clear();
            return;
        }

        List<RouteDefinition> newRoutes = new ArrayList<>();
        for (GatewayProperties.RouteProperties config : routeConfigs) {
            RouteDefinition route = RouteDefinition.builder()
                    .id(config.getId())
                    .pathPattern(config.getPathPattern())
                    .targets(config.getTargets())
                    .stripPrefix(config.isStripPrefix())
                    .requireAuth(config.isRequireAuth())
                    .headerTransforms(config.getHeaderTransforms())
                    .build();

            newRoutes.add(route);
            log.info("加载路由: id={}, pattern={}, targets={}, requireAuth={}",
                    route.getId(), route.getPathPattern(), route.getTargets(), config.isRequireAuth());
        }

        // 按路径模式长度排序（更精确的匹配优先）
        newRoutes.sort(Comparator.comparingInt(r -> -r.getPathPattern().length()));

        routes.clear();
        routes.addAll(newRoutes);
    }

    /**
     * 匹配路由
     *
     * @param request 网关请求
     * @return 匹配的路由定义，未找到返回 null
     */
    public RouteDefinition match(GatewayRequest request) {
        if (request == null || request.getPath() == null) {
            return null;
        }

        String path = request.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        for (RouteDefinition route : routes) {
            if (pathMatcher.match(route.getPathPattern(), path)) {
                // 实时读取最新的 requireAuth 配置
                if (!isRefreshing.get()) {
                    boolean latestRequireAuth = configHolder.getRouteRequireAuth(route.getId());
                    route.setRequireAuth(latestRequireAuth);
                }

                log.debug("请求 {} 匹配路由 {} (requireAuth={})", path, route.getId(), route.isRequireAuth());
                return route;
            }
        }

        log.debug("请求 {} 未匹配任何路由", path);
        return null;
    }

    /**
     * 构建目标 URL
     *
     * @param request 网关请求
     * @param route   路由定义
     * @return 目标 URL
     */
    public String buildTargetUrl(GatewayRequest request, RouteDefinition route) {
        if (route == null) {
            return null;
        }

        String targetBase = route.getNextTarget();
        if (targetBase == null) {
            log.error("路由 {} 没有可用的目标地址", route.getId());
            return null;
        }

        String path = request.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (route.isStripPrefix()) {
            path = stripPrefix(path, route.getPathPattern());
        }

        if (targetBase.endsWith("/")) {
            targetBase = targetBase.substring(0, targetBase.length() - 1);
        }

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        StringBuilder url = new StringBuilder(targetBase);
        url.append(path);

        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            url.append("?").append(request.getQueryString());
        }

        return url.toString();
    }

    /**
     * 剥离前缀
     */
    private String stripPrefix(String path, String pattern) {
        int wildcardIndex = pattern.indexOf("*");
        if (wildcardIndex == -1) {
            wildcardIndex = pattern.indexOf("{");
        }

        if (wildcardIndex > 0) {
            String prefix = pattern.substring(0, wildcardIndex);
            if (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }

            if (path.startsWith(prefix)) {
                String stripped = path.substring(prefix.length());
                if (!stripped.startsWith("/")) {
                    stripped = "/" + stripped;
                }
                return stripped;
            }
        }

        return path;
    }

    /**
     * 刷新路由配置
     */
    public void refresh() {
        isRefreshing.set(true);
        try {
            log.info("刷新路由配置...");
            int oldSize = routes.size();

            log.info("刷新前路由状态:");
            for (RouteDefinition route : routes) {
                log.info("  路由: id={}, requireAuth={}", route.getId(), route.isRequireAuth());
            }

            loadRoutesFromConfig();
            int newSize = routes.size();

            log.info("路由配置刷新完成，路由数量: {} -> {}", oldSize, newSize);
            log.info("刷新后路由状态:");
            for (RouteDefinition route : routes) {
                log.info("  路由: id={}, pattern={}, requireAuth={}",
                        route.getId(), route.getPathPattern(), route.isRequireAuth());
            }
        } finally {
            isRefreshing.set(false);
        }
    }

    /**
     * 添加路由
     */
    public void addRoute(RouteDefinition route) {
        if (route != null && route.getId() != null) {
            routes.add(route);
            log.info("添加路由: {}", route.getId());
        }
    }

    /**
     * 移除路由
     */
    public void removeRoute(String routeId) {
        routes.removeIf(r -> r.getId().equals(routeId));
        log.info("移除路由: {}", routeId);
    }

    /**
     * 获取所有路由
     */
    public List<RouteDefinition> getRoutes() {
        return new ArrayList<>(routes);
    }

    /**
     * 获取路由的最新 requireAuth 状态
     */
    public boolean getLatestRequireAuth(String routeId) {
        return configHolder.getRouteRequireAuth(routeId);
    }
}
