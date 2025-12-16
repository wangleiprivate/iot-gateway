package com.gateway.server;

import com.gateway.config.GatewayProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

/**
 * Netty HTTP 服务器
 * 网关的核心入口点
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
public class NettyHttpServer {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final GatewayProperties properties;
    private final HttpRequestHandler requestHandler;

    private volatile DisposableServer server;

    public NettyHttpServer(GatewayProperties properties, HttpRequestHandler requestHandler) {
        this.properties = properties;
        this.requestHandler = requestHandler;
    }

    /**
     * 启动服务器
     */
    public void start() {
        int port = properties.getServer().getPort();

        log.info("正在启动 Netty HTTP 服务器...");
        log.info("配置: port={}, bossThreads={}, workerThreads={}",
                port,
                properties.getServer().getNetty().getBossThreads(),
                properties.getServer().getNetty().getWorkerThreads());

        try {
            this.server = HttpServer.create()
                    .port(port)
                    // 配置 TCP 选项
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // 配置请求处理
                    .handle(requestHandler)
                    // 配置压缩
                    .compress(true)
                    // 配置访问日志
                    .accessLog(true)
                    // 绑定并启动
                    .bindNow(Duration.ofSeconds(30));

            log.info("========================================");
            log.info("  Gateway 启动成功!");
            log.info("  监听端口: {}", port);
            log.info("  服务地址: http://localhost:{}", port);
            log.info("========================================");

            // 阻塞主线程，保持服务器运行直到被关闭
            server.onDispose().block();

        } catch (Exception e) {
            log.error("Netty HTTP 服务器启动失败: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to start Netty HTTP Server", e);
        }
    }

    /**
     * 停止服务器
     */
    public void stop() {
        if (server != null && !server.isDisposed()) {
            log.info("正在停止 Netty HTTP 服务器...");
            try {
                server.disposeNow(Duration.ofSeconds(30));
                log.info("Netty HTTP 服务器已停止");
            } catch (Exception e) {
                log.error("停止 Netty HTTP 服务器时发生错误: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 检查服务器是否运行中
     *
     * @return 是否运行中
     */
    public boolean isRunning() {
        return server != null && !server.isDisposed();
    }

    /**
     * 获取服务器端口
     *
     * @return 端口号
     */
    public int getPort() {
        return server != null ? server.port() : -1;
    }
}
