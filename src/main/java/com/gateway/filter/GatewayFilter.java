package com.gateway.filter;

import com.gateway.model.GatewayRequest;
import com.gateway.model.GatewayResponse;
import reactor.core.publisher.Mono;

/**
 * 网关过滤器接口
 * 所有过滤器都需要实现此接口
 *
 * @author Gateway Team
 * @version 1.0.0
 */
public interface GatewayFilter {

    /**
     * 获取过滤器名称
     *
     * @return 过滤器名称
     */
    String getName();

    /**
     * 获取过滤器顺序
     * 数值越小优先级越高
     *
     * @return 顺序值
     */
    int getOrder();

    /**
     * 执行过滤逻辑
     *
     * @param request 网关请求
     * @param chain   过滤器链
     * @return 响应的 Mono
     */
    Mono<GatewayResponse> filter(GatewayRequest request, FilterChain chain);

    /**
     * 是否启用
     *
     * @return true 表示启用
     */
    default boolean isEnabled() {
        return true;
    }
}
