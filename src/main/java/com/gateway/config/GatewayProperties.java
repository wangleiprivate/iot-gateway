package com.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关配置属性类
 * 从 application.yml 绑定配置到 Java 对象
 *
 * @author Claude Gateway Team
 * @version 1.0.0
 */
@Data
@Validated
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /**
     * 服务器配置
     */
    private ServerProperties server = new ServerProperties();

    /**
     * 安全配置
     */
    private SecurityProperties security = new SecurityProperties();

    /**
     * 限流配置
     */
    private RateLimitProperties rateLimit = new RateLimitProperties();

    /**
     * 熔断配置
     */
    private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

    /**
     * 路由配置列表
     */
    private List<RouteProperties> routes = new ArrayList<>();

    /**
     * 服务器配置
     */
    @Data
    public static class ServerProperties {
        /**
         * 服务端口
         */
        private int port = 8080;

        /**
         * Netty 配置
         */
        private NettyProperties netty = new NettyProperties();
    }

    /**
     * Netty 配置
     */
    @Data
    public static class NettyProperties {
        /**
         * Boss 线程数（处理连接）
         */
        private int bossThreads = 1;

        /**
         * Worker 线程数（处理 IO），0 表示自动计算
         */
        private int workerThreads = 0;
    }

    /**
     * 安全配置
     */
    @Data
    public static class SecurityProperties {
        /**
         * IP 白名单配置
         */
        private IpWhitelistProperties ipWhitelist = new IpWhitelistProperties();

        /**
         * 鉴权配置
         */
        private AuthProperties auth = new AuthProperties();
    }

    /**
     * IP 白名单配置
     */
    @Data
    public static class IpWhitelistProperties {
        /**
         * 是否启用 IP 白名单
         */
        private boolean enabled = true;

        /**
         * IP 白名单列表（支持 CIDR 格式，如 10.0.0.0/8）
         */
        private List<String> list = new ArrayList<>();
    }

    /**
     * 鉴权配置
     */
    @Data
    public static class AuthProperties {
        /**
         * 是否启用鉴权
         */
        private boolean enabled = true;

        /**
         * 跳过鉴权的路径列表（支持 AntPath 模式）
         */
        private List<String> skipPaths = new ArrayList<>();

        /**
         * 认证服务器 URL
         */
        private String authServerUrl;

        /**
         * 认证超时时间（毫秒）
         */
        private int timeoutMs = 500;
    }

    /**
     * 限流配置
     */
    @Data
    public static class RateLimitProperties {
        /**
         * 是否启用限流
         */
        private boolean enabled = true;

        /**
         * 限流算法：token-bucket（令牌桶）或 leaky-bucket（漏桶）
         */
        private String algorithm = "token-bucket";

        /**
         * 令牌桶容量
         */
        private int capacity = 100;

        /**
         * 每次填充的令牌数
         */
        private int refillTokens = 100;

        /**
         * 填充周期（秒）
         */
        private int refillPeriodSeconds = 60;

        /**
         * 按客户端限流配置
         */
        private PerClientProperties perClient = new PerClientProperties();
    }

    /**
     * 按客户端限流配置
     */
    @Data
    public static class PerClientProperties {
        /**
         * 是否启用按客户端限流
         */
        private boolean enabled = true;
    }

    /**
     * 熔断配置
     */
    @Data
    public static class CircuitBreakerProperties {
        /**
         * 是否启用熔断
         */
        private boolean enabled = true;

        /**
         * 失败率阈值（百分比）
         */
        private int failureRateThreshold = 50;

        /**
         * 熔断打开状态等待时间（秒）
         */
        private int waitDurationOpenStateSeconds = 30;

        /**
         * 滑动窗口大小
         */
        private int slidingWindowSize = 20;

        /**
         * 慢调用持续时间阈值（毫秒）
         */
        private int slowCallDurationThresholdMs = 2000;

        /**
         * 慢调用率阈值（百分比）
         */
        private int slowCallRateThreshold = 80;

        /**
         * 最小调用次数
         */
        private int minimumNumberOfCalls = 10;
    }

    /**
     * 路由配置
     */
    @Data
    public static class RouteProperties {
        /**
         * 路由 ID
         */
        private String id;

        /**
         * 路径模式（支持 AntPath 模式）
         */
        private String pathPattern;

        /**
         * 目标服务地址列表
         */
        private List<String> targets = new ArrayList<>();

        /**
         * 是否剥离前缀
         */
        private boolean stripPrefix = false;

        /**
         * 是否需要鉴权
         */
        private boolean requireAuth = true;

        /**
         * 请求头转换
         */
        private Map<String, String> headerTransforms = new HashMap<>();
    }
}
