package com.gateway.server;

import com.gateway.filter.FilterChain;
import com.gateway.filter.GatewayFilter;
import com.gateway.model.GatewayRequest;
import com.gateway.model.GatewayResponse;
import com.gateway.router.HttpForwarder;
import com.gateway.util.IpUtils;
import com.gateway.util.TraceIdGenerator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * HTTP 请求处理器
 * 处理所有进入网关的 HTTP 请求
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
public class HttpRequestHandler implements BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    private final List<GatewayFilter> filters;
    private final HttpForwarder httpForwarder;

    public HttpRequestHandler(List<GatewayFilter> filters, HttpForwarder httpForwarder) {
        // 按 order 排序过滤器
        this.filters = filters.stream()
                .sorted(Comparator.comparingInt(GatewayFilter::getOrder))
                .collect(Collectors.toList());
        this.httpForwarder = httpForwarder;

        log.info("HTTP 请求处理器初始化完成，已加载 {} 个过滤器", this.filters.size());
        for (GatewayFilter filter : this.filters) {
            log.info("  - {} (order={}, enabled={})", filter.getName(), filter.getOrder(), filter.isEnabled());
        }
    }

    @Override
    public Publisher<Void> apply(HttpServerRequest request, HttpServerResponse response) {
        String traceId = extractOrGenerateTraceId(request);
        Instant receiveTime = Instant.now();

        log.debug("[{}] 收到请求: {} {}", traceId, request.method(), request.uri());

        // 读取请求体并处理
        return request.receive()
                .aggregate()
                .asByteArray()
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> processRequest(request, response, traceId, receiveTime, body))
                .onErrorResume(e -> handleError(response, traceId, e));
    }

    /**
     * 处理请求
     */
    private Mono<Void> processRequest(HttpServerRequest request, HttpServerResponse response,
                                       String traceId, Instant receiveTime, byte[] body) {
        // 构建 GatewayRequest
        GatewayRequest gatewayRequest = buildGatewayRequest(request, traceId, receiveTime, body);

        // 创建过滤器链并执行
        FilterChain filterChain = new FilterChain(filters);

        return filterChain.filter(gatewayRequest)
                .switchIfEmpty(Mono.defer(() -> {
                    // 过滤器链执行完毕，执行转发
                    return httpForwarder.forward(gatewayRequest);
                }))
                .flatMap(gatewayResponse -> sendResponse(response, gatewayResponse, traceId))
                .doOnTerminate(() -> {
                    long duration = Instant.now().toEpochMilli() - receiveTime.toEpochMilli();
                    log.info("[{}] 请求处理完成: {} {} 耗时 {}ms",
                            traceId, request.method(), request.uri(), duration);
                });
    }

    /**
     * 构建网关请求对象
     */
    private GatewayRequest buildGatewayRequest(HttpServerRequest request, String traceId,
                                                Instant receiveTime, byte[] body) {
        // 提取请求头
        Map<String, String> headers = new HashMap<>();
        request.requestHeaders().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));

        // 获取真实 IP
        String remoteIp = IpUtils.getRealIp(
                headers.get("X-Forwarded-For"),
                headers.get("X-Real-IP"),
                request.remoteAddress() != null ? request.remoteAddress().getAddress().getHostAddress() : "unknown"
        );

        // 解析 URI
        String uri = request.uri();
        String path = request.path();
        String queryString = null;

        int queryIndex = uri.indexOf('?');
        if (queryIndex > 0) {
            queryString = uri.substring(queryIndex + 1);
        }

        return GatewayRequest.builder()
                .method(request.method().name())
                .path(path)
                .queryString(queryString)
                .uri(uri)
                .headers(headers)
                .body(body)
                .remoteIp(remoteIp)
                .traceId(traceId)
                .receiveTime(receiveTime)
                .build();
    }

    /**
     * 发送响应
     */
    private Mono<Void> sendResponse(HttpServerResponse response, GatewayResponse gatewayResponse, String traceId) {
        // 设置状态码
        response.status(HttpResponseStatus.valueOf(gatewayResponse.getStatusCode()));

        // 设置响应头
        if (gatewayResponse.getHeaders() != null) {
            gatewayResponse.getHeaders().forEach((key, value) -> {
                if (value != null) {
                    response.header(key, value);
                }
            });
        }

        // 添加追踪头
        response.header("X-Trace-Id", traceId);

        // 发送响应体
        byte[] body = gatewayResponse.getBody();
        if (body != null && body.length > 0) {
            response.header(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.length));
            return response.sendObject(Unpooled.wrappedBuffer(body)).then();
        } else {
            response.header(HttpHeaderNames.CONTENT_LENGTH, "0");
            return response.send().then();
        }
    }

    /**
     * 处理错误
     */
    private Mono<Void> handleError(HttpServerResponse response, String traceId, Throwable e) {
        log.error("[{}] 请求处理异常: {}", traceId, e.getMessage(), e);

        response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        response.header(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.header("X-Trace-Id", traceId);

        String errorJson = String.format("{\"error\":\"%s\",\"traceId\":\"%s\"}",
                escapeJson(e.getMessage()), traceId);
        byte[] body = errorJson.getBytes(StandardCharsets.UTF_8);

        response.header(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.length));
        return response.sendObject(Unpooled.wrappedBuffer(body)).then();
    }

    /**
     * 提取或生成追踪 ID
     */
    private String extractOrGenerateTraceId(HttpServerRequest request) {
        String traceId = request.requestHeaders().get("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = request.requestHeaders().get("X-Request-Id");
        }
        if (traceId == null || traceId.isEmpty() || !TraceIdGenerator.isValid(traceId)) {
            traceId = TraceIdGenerator.generate();
        }
        return traceId;
    }

    /**
     * 转义 JSON 字符串
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "null";
        }
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
