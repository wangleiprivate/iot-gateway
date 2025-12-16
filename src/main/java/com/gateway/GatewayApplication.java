package com.gateway;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.gateway.config.GatewayProperties;
import com.gateway.router.Router;
import com.gateway.server.NettyHttpServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 统一网关启动类
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(GatewayApplication.class);

    private NettyHttpServer nettyHttpServer;

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * 等待 Nacos 配置加载完成
     */
    @Bean
    public CommandLineRunner waitForNacosConfig(NacosConfigManager nacosConfigManager, 
                                               Router router, GatewayProperties properties, Environment env) {
        return args -> {
            log.info("========================================");
            log.info("  Gateway 初始化中...");
            log.info("  等待 Nacos 配置加载...");
            log.info("  激活的 profile: {}", env.getActiveProfiles().length > 0 ? 
                     String.join(",", env.getActiveProfiles()) : "default");
            log.info("========================================");
            
            // 等待 Nacos 配置加载完成
            int maxWaitTimeSeconds = 10; // 最大等待10秒
            int checkIntervalMs = 500; // 每500毫秒检查一次
            int checks = maxWaitTimeSeconds * 1000 / checkIntervalMs;
            
            for (int i = 0; i < checks; i++) {
                try {
                    Thread.sleep(checkIntervalMs);
                    
                    // 检查配置是否已加载
                    if (properties.getRoutes() != null && !properties.getRoutes().isEmpty()) {
                        log.info("Nacos 配置已加载，路由数量: {}", properties.getRoutes().size());
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待配置加载被中断");
                    break;
                }
            }
            
            // 确保路由刷新
            router.refresh();
            
            log.info("当前加载的路由数量: {}", router.getRoutes().size());
            for (com.gateway.model.RouteDefinition route : router.getRoutes()) {
                log.info("路由: id={}, pattern={}, requireAuth={}", 
                        route.getId(), route.getPathPattern(), route.isRequireAuth());
            }
            
            log.info("Nacos 配置加载完成，准备启动网关");
        };
    }
    
    /**
     * 启动 Netty HTTP 服务器
     */
    @Bean
    public CommandLineRunner startNettyServer(NettyHttpServer server) {
        return args -> {
            // 启动服务器
            this.nettyHttpServer = server;
            log.info("========================================");
            log.info("  Gateway Starting...");
            log.info("========================================");
            server.start();
        };
    }

    /**
     * 优雅关闭 Netty 服务器
     */
    @PreDestroy
    public void shutdown() {
        if (nettyHttpServer != null) {
            log.info("Shutting down Netty HTTP Server...");
            nettyHttpServer.stop();
            log.info("Netty HTTP Server stopped.");
        }
    }
}
