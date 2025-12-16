package com.gateway.router;

import com.gateway.model.GatewayRequest;
import com.gateway.model.GatewayResponse;
import com.gateway.model.RouteDefinition;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 转发器
 * 负责将请求转发到后端服务
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Component
public class HttpForwarder {

    private static final Logger log = LoggerFactory.getLogger(HttpForwarder.class);

    /**
     * 需要过滤的请求头（不应转发到后端）
     */
    private static final String[] HOP_BY_HOP_HEADERS = {
            "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
            "TE", "Trailer", "Transfer-Encoding", "Upgrade", "Host"
    };

    private final Router router;
    private final HttpClient httpClient;

    public HttpForwarder(Router router) {
        this.router = router;
        this.httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(30))
                .compress(true);
    }

    /**
     * 转发请求
     *
     * @param request 网关请求
     * @return 网关响应
     */
    public Mono<GatewayResponse> forward(GatewayRequest request) {
        // 匹配路由
        RouteDefinition route = router.match(request);
        if (route == null) {
            log.warn("[{}] 未找到匹配的路由: path={}", request.getTraceId(), request.getPath());
            return Mono.just(GatewayResponse.notFound("No route found for path: " + request.getPath()));
        }

        // 构建目标 URL
        String targetUrl = router.buildTargetUrl(request, route);
        if (targetUrl == null) {
            log.error("[{}] 无法构建目标 URL: route={}", request.getTraceId(), route.getId());
            return Mono.just(GatewayResponse.internalServerError("Failed to build target URL"));
        }

        request.setMatchedRouteId(route.getId());
        request.setTargetUrl(targetUrl);

        log.info("[{}] 转发请求: {} {} -> {}", request.getTraceId(), request.getMethod(), request.getPath(), targetUrl);

        // 构建转发请求头
        Map<String, String> forwardHeaders = buildForwardHeaders(request, route);

        // 执行转发
        return executeForward(request, targetUrl, forwardHeaders)
                .doOnSuccess(response -> {
                    log.info("[{}] 转发完成: status={}, route={}",
                            request.getTraceId(), response.getStatusCode(), route.getId());
                })
                .doOnError(e -> {
                    log.error("[{}] 转发失败: route={}, error={}",
                            request.getTraceId(), route.getId(), e.getMessage());
                })
                .onErrorResume(e -> {
                    log.error("[{}] 转发异常: {}", request.getTraceId(), e.getMessage(), e);
                    return Mono.just(GatewayResponse.internalServerError("Forward failed: " + e.getMessage()));
                });
    }

    /**
     * 构建转发请求头
     *
     * @param request 原始请求
     * @param route   路由定义
     * @return 转发请求头
     */
    private Map<String, String> buildForwardHeaders(GatewayRequest request, RouteDefinition route) {
        Map<String, String> headers = new HashMap<>();

        // 复制原始请求头（排除 hop-by-hop 头）
        if (request.getHeaders() != null) {
            for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
                if (!isHopByHopHeader(entry.getKey())) {
                    headers.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // 添加追踪头
        headers.put("X-Trace-Id", request.getTraceId());
        headers.put("X-Forwarded-For", request.getRemoteIp());
        headers.put("X-Real-IP", request.getRemoteIp());

        // 应用路由级别的头部转换
        if (route.getHeaderTransforms() != null) {
            for (Map.Entry<String, String> transform : route.getHeaderTransforms().entrySet()) {
                String key = transform.getKey();
                String value = transform.getValue();

                if (value == null || value.isEmpty() || "null".equalsIgnoreCase(value)) {
                    // 移除头部
                    headers.remove(key);
                } else {
                    // 设置或替换头部
                    headers.put(key, value);
                }
            }
        }

        return headers;
    }

    /**
     * 检查是否是 hop-by-hop 头
     */
    private boolean isHopByHopHeader(String headerName) {
        for (String header : HOP_BY_HOP_HEADERS) {
            if (header.equalsIgnoreCase(headerName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行 HTTP 转发
     *
     * @param request       网关请求
     * @param targetUrl     目标 URL
     * @param forwardHeaders 转发请求头
     * @return 网关响应
     */
    private Mono<GatewayResponse> executeForward(GatewayRequest request, String targetUrl, Map<String, String> forwardHeaders) {
        HttpClient.RequestSender requestSender = httpClient
                .headers(headers -> {
                    for (Map.Entry<String, String> entry : forwardHeaders.entrySet()) {
                        headers.add(entry.getKey(), entry.getValue());
                    }
                })
                .request(io.netty.handler.codec.http.HttpMethod.valueOf(request.getMethod()));

        return requestSender
                .uri(targetUrl)
                .send((req, outbound) -> {
                    if (request.getBody() != null && request.getBody().length > 0) {
                        return outbound.sendByteArray(Mono.just(request.getBody()));
                    }
                    return outbound;
                })
                .responseSingle((response, body) -> {
                    int statusCode = response.status().code();

                    // 构建响应头
                    Map<String, String> responseHeaders = new HashMap<>();
                    response.responseHeaders().forEach(entry ->
                            responseHeaders.put(entry.getKey(), entry.getValue())
                    );

                    // 移除不需要的响应头
                    responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING.toString());

                    return body.asByteArray()
                            .defaultIfEmpty(new byte[0])
                            .map(bodyBytes -> GatewayResponse.builder()
                                    .statusCode(statusCode)
                                    .headers(responseHeaders)
                                    .body(bodyBytes)
                                    .build());
                });
    }
}
