package com.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由定义模型
 * 定义请求如何路由到后端服务
 *
 * @author Claude Gateway Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteDefinition {

    /**
     * 路由 ID
     */
    private String id;

    /**
     * 路径模式（支持 AntPath 模式）
     */
    private String pathPattern;

    /**
     * 目标服务地址列表（支持负载均衡）
     */
    @Builder.Default
    private List<String> targets = new ArrayList<>();

    /**
     * 是否剥离前缀
     */
    private boolean stripPrefix;

    /**
     * 是否需要鉴权
     */
    private boolean requireAuth;

    /**
     * 请求头转换规则
     */
    @Builder.Default
    private Map<String, String> headerTransforms = new HashMap<>();

    /**
     * 限流策略（可覆盖全局配置）
     */
    private RateLimitPolicy rateLimitPolicy;

    /**
     * 熔断策略（可覆盖全局配置）
     */
    private CircuitBreakerPolicy circuitBreakerPolicy;

    /**
     * 当前目标索引（用于轮询负载均衡）
     */
    private transient int currentTargetIndex = 0;

    /**
     * 获取下一个目标地址（轮询方式）
     *
     * @return 目标地址
     */
    public synchronized String getNextTarget() {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        String target = targets.get(currentTargetIndex);
        currentTargetIndex = (currentTargetIndex + 1) % targets.size();
        return target;
    }

    /**
     * 限流策略
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitPolicy {
        /**
         * 是否启用
         */
        private boolean enabled;

        /**
         * 容量
         */
        private int capacity;

        /**
         * 填充令牌数
         */
        private int refillTokens;

        /**
         * 填充周期（秒）
         */
        private int refillPeriodSeconds;
    }

    /**
     * 熔断策略
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CircuitBreakerPolicy {
        /**
         * 是否启用
         */
        private boolean enabled;

        /**
         * 失败率阈值
         */
        private int failureRateThreshold;

        /**
         * 熔断等待时间（秒）
         */
        private int waitDurationSeconds;

        /**
         * 滑动窗口大小
         */
        private int slidingWindowSize;
    }
}
