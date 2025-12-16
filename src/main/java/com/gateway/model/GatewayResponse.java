package com.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 网关响应模型
 * 封装 HTTP 响应的所有信息
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayResponse {

    /**
     * HTTP 状态码
     */
    private int statusCode;

    /**
     * 响应头
     */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    /**
     * 响应体
     */
    private byte[] body;

    /**
     * 错误消息（用于错误响应）
     */
    private String errorMessage;

    /**
     * 创建成功响应
     *
     * @param statusCode 状态码
     * @param body       响应体
     * @return GatewayResponse
     */
    public static GatewayResponse ok(int statusCode, byte[] body) {
        return GatewayResponse.builder()
                .statusCode(statusCode)
                .body(body)
                .build();
    }

    /**
     * 创建成功响应（200）
     *
     * @param body 响应体
     * @return GatewayResponse
     */
    public static GatewayResponse ok(byte[] body) {
        return ok(200, body);
    }

    /**
     * 创建成功响应（200，字符串）
     *
     * @param body 响应体字符串
     * @return GatewayResponse
     */
    public static GatewayResponse ok(String body) {
        return ok(body != null ? body.getBytes() : new byte[0]);
    }

    /**
     * 创建错误响应
     *
     * @param statusCode   状态码
     * @param errorMessage 错误消息
     * @return GatewayResponse
     */
    public static GatewayResponse error(int statusCode, String errorMessage) {
        String jsonBody = String.format("{\"error\":\"%s\",\"status\":%d}", errorMessage, statusCode);
        return GatewayResponse.builder()
                .statusCode(statusCode)
                .body(jsonBody.getBytes())
                .errorMessage(errorMessage)
                .headers(Map.of("Content-Type", "application/json"))
                .build();
    }

    /**
     * 创建 403 Forbidden 响应
     *
     * @param message 错误消息
     * @return GatewayResponse
     */
    public static GatewayResponse forbidden(String message) {
        return error(403, message != null ? message : "Forbidden: Access denied");
    }

    /**
     * 创建 401 Unauthorized 响应
     *
     * @param message 错误消息
     * @return GatewayResponse
     */
    public static GatewayResponse unauthorized(String message) {
        return error(401, message != null ? message : "Unauthorized: Authentication required");
    }

    /**
     * 创建 429 Too Many Requests 响应
     *
     * @param message 错误消息
     * @return GatewayResponse
     */
    public static GatewayResponse tooManyRequests(String message) {
        return error(429, message != null ? message : "Too Many Requests: Rate limit exceeded");
    }

    /**
     * 创建 503 Service Unavailable 响应
     *
     * @param message 错误消息
     * @return GatewayResponse
     */
    public static GatewayResponse serviceUnavailable(String message) {
        return error(503, message != null ? message : "Service Unavailable: Circuit breaker is open");
    }

    /**
     * 创建 500 Internal Server Error 响应
     *
     * @param message 错误消息
     * @return GatewayResponse
     */
    public static GatewayResponse internalServerError(String message) {
        return error(500, message != null ? message : "Internal Server Error");
    }

    /**
     * 创建 404 Not Found 响应
     *
     * @param message 错误消息
     * @return GatewayResponse
     */
    public static GatewayResponse notFound(String message) {
        return error(404, message != null ? message : "Not Found");
    }

    /**
     * 添加响应头
     *
     * @param name  响应头名称
     * @param value 响应头值
     */
    public void addHeader(String name, String value) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put(name, value);
    }
}
