package com.gateway.router;

import com.gateway.config.GatewayProperties;
import com.gateway.discovery.ServiceDiscoveryClient;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 路由器
 * 负责将请求匹配到对应的后端服务
 *
 * @author Claude Gateway Team
 * @version 1.0.0
 */
@Component
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    private final GatewayProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 路由定义列表（线程安全，支持动态更新）
     */
    private final CopyOnWriteArrayList<RouteDefinition> routes = new CopyOnWriteArrayList<>();

    public Router(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * 初始化路由配置
     */
    @PostConstruct
    public void init() {
        loadRoutesFromConfig();
        log.info("路由器初始化完成，共加载 {} 条路由", routes.size());
    }

    /**
     * 从配置加载路由
     */
    private void loadRoutesFromConfig() {
        List<GatewayProperties.RouteProperties> routeConfigs = properties.getRoutes();
        if (routeConfigs == null || routeConfigs.isEmpty()) {
            log.warn("未配置任何路由");
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
            log.info("加载路由: id={}, pattern={}, targets={}",
                    route.getId(), route.getPathPattern(), route.getTargets());
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
        // 确保路径以 "/" 开头，兼容不同的请求解析结果
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        for (RouteDefinition route : routes) {
            if (pathMatcher.match(route.getPathPattern(), path)) {
                log.debug("请求 {} 匹配路由 {}", path, route.getId());
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

        // 获取下一个目标地址（负载均衡）
        String targetBase = route.getNextTarget();
        if (targetBase == null) {
            log.error("路由 {} 没有可用的目标地址", route.getId());
            return null;
        }

        // 处理路径 - 先确保路径以 "/" 开头，再进行前缀剥离
        String path = request.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (route.isStripPrefix()) {
            path = stripPrefix(path, route.getPathPattern());
        }

        // 移除目标地址末尾的斜杠
        if (targetBase.endsWith("/")) {
            targetBase = targetBase.substring(0, targetBase.length() - 1);
        }

        // 确保路径以斜杠开头
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        // 构建完整 URL
        StringBuilder url = new StringBuilder(targetBase);
        url.append(path);

        // 添加查询字符串
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            url.append("?").append(request.getQueryString());
        }

        return url.toString();
    }

    /**
     * 剥离前缀
     *
     * @param path    原始路径
     * @param pattern 路径模式
     * @return 剥离前缀后的路径
     */
    private String stripPrefix(String path, String pattern) {
        // 找到模式中第一个通配符的位置
        int wildcardIndex = pattern.indexOf("*");
        if (wildcardIndex == -1) {
            wildcardIndex = pattern.indexOf("{");
        }

        if (wildcardIndex > 0) {
            String prefix = pattern.substring(0, wildcardIndex);
            // 移除末尾的斜杠
            if (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }

            if (path.startsWith(prefix)) {
                String stripped = path.substring(prefix.length());
                // 确保以斜杠开头
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
     * 用于动态更新路由（从 Nacos 等配置中心）
     */
    public void refresh() {
        log.info("刷新路由配置...");
        loadRoutesFromConfig();
    }

    /**
     * 添加路由
     *
     * @param route 路由定义
     */
    public void addRoute(RouteDefinition route) {
        if (route != null && route.getId() != null) {
            routes.add(route);
            log.info("添加路由: {}", route.getId());
        }
    }

    /**
     * 移除路由
     *
     * @param routeId 路由 ID
     */
    public void removeRoute(String routeId) {
        routes.removeIf(r -> r.getId().equals(routeId));
        log.info("移除路由: {}", routeId);
    }

    /**
     * 获取所有路由
     *
     * @return 路由列表
     */
    public List<RouteDefinition> getRoutes() {
        return new ArrayList<>(routes);
    }
}
