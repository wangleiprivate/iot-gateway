package com.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 网关请求模型
 * 封装 HTTP 请求的所有信息
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayRequest {

    /**
     * HTTP 方法（GET、POST、PUT、DELETE 等）
     */
    private String method;

    /**
     * 请求路径
     */
    private String path;

    /**
     * 查询字符串
     */
    private String queryString;

    /**
     * 完整 URI（包含查询参数）
     */
    private String uri;

    /**
     * 请求头
     */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    /**
     * 请求体
     */
    private byte[] body;

    /**
     * 远程 IP 地址
     */
    private String remoteIp;

    /**
     * 链路追踪 ID
     */
    private String traceId;

    /**
     * 请求接收时间
     */
    private Instant receiveTime;

    /**
     * 匹配的路由 ID
     */
    private String matchedRouteId;

    /**
     * 目标服务 URL
     */
    private String targetUrl;

    /**
     * 获取指定请求头的值
     *
     * @param name 请求头名称
     * @return 请求头值，如果不存在返回 null
     */
    public String getHeader(String name) {
        if (headers == null || name == null) {
            return null;
        }
        // 不区分大小写查找
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 添加请求头
     *
     * @param name  请求头名称
     * @param value 请求头值
     */
    public void addHeader(String name, String value) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put(name, value);
    }

    /**
     * 获取 Authorization 头
     *
     * @return Authorization 值
     */
    public String getAuthorization() {
        return getHeader("Authorization");
    }

    /**
     * 获取 Token（从 Authorization 头或 token 参数）
     *
     * @return Token 值
     */
    public String getToken() {
        String auth = getAuthorization();
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return getHeader("token");
    }
}
