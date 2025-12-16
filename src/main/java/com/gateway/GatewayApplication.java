package com.gateway;

import com.gateway.config.GatewayProperties;
import com.gateway.server.NettyHttpServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
     * 启动 Netty HTTP 服务器
     */
    @Bean
    public CommandLineRunner startNettyServer(NettyHttpServer server) {
        return args -> {
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
