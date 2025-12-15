package com.gateway.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务发现客户端
 * 封装 Spring Cloud DiscoveryClient，提供服务实例获取和负载均衡功能
 *
 * @author Claude Gateway Team
 * @version 1.0.0
 */
@Component
public class ServiceDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceDiscoveryClient.class);

    /**
     * lb:// 协议前缀
     */
    public static final String LB_SCHEME = "lb://";

    private final DiscoveryClient discoveryClient;

    /**
     * 服务轮询索引缓存（用于负载均衡）
     */
    private final Map<String, AtomicInteger> serviceIndexMap = new ConcurrentHashMap<>();

    public ServiceDiscoveryClient(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
        log.info("服务发现客户端初始化完成");
    }

    /**
     * 判断目标地址是否为服务发现地址
     *
     * @param target 目标地址
     * @return 是否为 lb:// 协议
     */
    public boolean isServiceDiscoveryTarget(String target) {
        return target != null && target.toLowerCase().startsWith(LB_SCHEME);
    }

    /**
     * 从 lb:// 地址中提取服务名
     *
     * @param target lb:// 格式的目标地址
     * @return 服务名
     */
    public String extractServiceName(String target) {
        if (!isServiceDiscoveryTarget(target)) {
            return null;
        }
        // lb://service-name 或 lb://service-name/path
        String remaining = target.substring(LB_SCHEME.length());
        int pathIndex = remaining.indexOf('/');
        if (pathIndex > 0) {
            return remaining.substring(0, pathIndex);
        }
        return remaining;
    }

    /**
     * 从 lb:// 地址中提取路径部分
     *
     * @param target lb:// 格式的目标地址
     * @return 路径部分，如果没有则返回空字符串
     */
    public String extractPath(String target) {
        if (!isServiceDiscoveryTarget(target)) {
            return "";
        }
        String remaining = target.substring(LB_SCHEME.length());
        int pathIndex = remaining.indexOf('/');
        if (pathIndex > 0) {
            return remaining.substring(pathIndex);
        }
        return "";
    }

    /**
     * 获取服务的所有实例
     *
     * @param serviceName 服务名
     * @return 服务实例列表
     */
    public List<ServiceInstance> getInstances(String serviceName) {
        if (serviceName == null || serviceName.isEmpty()) {
            log.warn("服务名为空，无法获取服务实例");
            return List.of();
        }

        List<ServiceInstance> instances = discoveryClient.getInstances(serviceName);
        if (instances == null || instances.isEmpty()) {
            log.warn("服务 {} 没有可用实例", serviceName);
            return List.of();
        }

        log.debug("服务 {} 发现 {} 个实例", serviceName, instances.size());
        return instances;
    }

    /**
     * 获取下一个服务实例（轮询负载均衡）
     *
     * @param serviceName 服务名
     * @return 服务实例，如果没有可用实例返回 null
     */
    public ServiceInstance getNextInstance(String serviceName) {
        List<ServiceInstance> instances = getInstances(serviceName);
        if (instances.isEmpty()) {
            return null;
        }

        AtomicInteger index = serviceIndexMap.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
        int currentIndex = index.getAndUpdate(i -> (i + 1) % instances.size());

        // 确保索引在有效范围内（实例数量可能变化）
        int actualIndex = currentIndex % instances.size();
        ServiceInstance instance = instances.get(actualIndex);

        log.debug("服务 {} 选择实例: {}:{}", serviceName, instance.getHost(), instance.getPort());
        return instance;
    }

    /**
     * 解析 lb:// 地址为实际的 HTTP URL
     *
     * @param target lb:// 格式的目标地址
     * @return 实际的 HTTP URL，如果解析失败返回 null
     */
    public String resolveServiceUrl(String target) {
        if (!isServiceDiscoveryTarget(target)) {
            return target;
        }

        String serviceName = extractServiceName(target);
        String path = extractPath(target);

        ServiceInstance instance = getNextInstance(serviceName);
        if (instance == null) {
            log.error("无法解析服务地址: {}，服务 {} 没有可用实例", target, serviceName);
            return null;
        }

        // 构建实际 URL
        URI uri = instance.getUri();
        String resolvedUrl = uri.toString() + path;

        log.debug("服务地址解析: {} -> {}", target, resolvedUrl);
        return resolvedUrl;
    }

    /**
     * 获取所有已注册的服务名
     *
     * @return 服务名列表
     */
    public List<String> getServices() {
        return discoveryClient.getServices();
    }

    /**
     * 清除服务索引缓存
     *
     * @param serviceName 服务名，如果为 null 则清除所有
     */
    public void clearIndexCache(String serviceName) {
        if (serviceName == null) {
            serviceIndexMap.clear();
            log.info("已清除所有服务索引缓存");
        } else {
            serviceIndexMap.remove(serviceName);
            log.info("已清除服务 {} 的索引缓存", serviceName);
        }
    }
}
