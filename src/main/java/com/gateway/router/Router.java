package com.gateway.router;

import com.gateway.config.GatewayProperties;
import com.gateway.discovery.ServiceDiscoveryClient;
import com.gateway.model.GatewayRequest;
import com.gateway.model.RouteDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    /**
     * 热更新状态标志，防止在热更新过程中路由匹配读取到不一致的配置
     */
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);
    
    /**
     * 使用 ObjectProvider 延迟获取 GatewayProperties
     * 这样每次调用时都能获取到 ConfigurationPropertiesRebinder 刷新后的最新配置
     */
    private final ObjectProvider<GatewayProperties> propertiesProvider;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 路由定义列表（线程安全，支持动态更新）
     */
    private final CopyOnWriteArrayList<RouteDefinition> routes = new CopyOnWriteArrayList<>();

    public Router(ObjectProvider<GatewayProperties> propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    /**
     * 获取当前的 GatewayProperties 配置
     * 每次调用都会获取最新的配置（支持 Nacos 热更新）
     */
    private GatewayProperties getProperties() {
        GatewayProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null) {
            log.warn("GatewayProperties 不可用");
        }
        return properties;
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
                Thread.sleep(1000); // 等待1秒后重试
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
        // 多次尝试获取配置，确保获取到最新值
        GatewayProperties properties = null;
        int attempts = 0;
        final int maxAttempts = 3;
        
        while (properties == null && attempts < maxAttempts) {
            properties = getProperties();
            if (properties == null) {
                attempts++;
                log.warn("第 {} 次尝试获取 GatewayProperties 失败", attempts);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        if (properties == null) {
            log.error("多次尝试后仍无法获取 GatewayProperties");
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
                    .requireAuth(config.isRequireAuth())  // 确保获取最新的 requireAuth 配置
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
        // 确保路径以 "/" 开头，兼容不同的请求解析结果
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        for (RouteDefinition route : routes) {
            if (pathMatcher.match(route.getPathPattern(), path)) {
                // 优化：只有在非热更新状态下才实时读取最新配置
                // 避免在热更新过程中因配置不同步导致的状态不一致
                if (!isRefreshing.get()) {
                    GatewayProperties properties = getProperties();
                    if (properties != null && properties.getRoutes() != null) {
                        Optional<GatewayProperties.RouteProperties> config = properties.getRoutes().stream()
                            .filter(r -> r.getId().equals(route.getId()))
                            .findFirst();
                        
                        if (config.isPresent()) {
                            // 更新路由对象的 requireAuth 值，确保使用最新的配置
                            route.setRequireAuth(config.get().isRequireAuth());
                        }
                    }
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
        // 设置热更新状态，防止 match() 方法在刷新过程中读取不一致的配置
        isRefreshing.set(true);
        try {
            log.info("刷新路由配置...");
            int oldSize = routes.size();
            
            // 先输出当前路由状态
            log.info("刷新前路由状态:");
            for (RouteDefinition route : routes) {
                log.info("  路由: id={}, requireAuth={}", route.getId(), route.isRequireAuth());
            }
            
            // 获取最新的配置信息
            GatewayProperties properties = getProperties();
            if (properties != null && properties.getRoutes() != null) {
                log.info("配置中的路由状态:");
                for (GatewayProperties.RouteProperties config : properties.getRoutes()) {
                    log.info("  配置: id={}, requireAuth={}", config.getId(), config.isRequireAuth());
                }
            }
            
            loadRoutesFromConfig();
            int newSize = routes.size();
            
            // 输出路由变更信息，特别是 require-auth 的变更
            log.info("路由配置刷新完成，路由数量: {} -> {}", oldSize, newSize);
            log.info("刷新后路由状态:");
            for (RouteDefinition route : routes) {
                log.info("  路由: id={}, pattern={}, requireAuth={}", 
                        route.getId(), route.getPathPattern(), route.isRequireAuth());
            }
        } finally {
            // 确保热更新状态被重置，避免影响后续请求
            isRefreshing.set(false);
        }
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
    
    /**
     * 获取路由的最新 requireAuth 状态
     * 用于在运行时获取最新的配置状态
     * 
     * @param routeId 路由ID
     * @return requireAuth 状态，如果路由不存在返回true（默认需要鉴权）
     */
    public boolean getLatestRequireAuth(String routeId) {
        GatewayProperties properties = getProperties();
        if (properties != null && properties.getRoutes() != null) {
            return properties.getRoutes().stream()
                .filter(r -> routeId.equals(r.getId()))
                .findFirst()
                .map(GatewayProperties.RouteProperties::isRequireAuth)
                .orElse(true); // 默认返回true，即需要鉴权
        }
        return true; // 默认需要鉴权
    }
}
