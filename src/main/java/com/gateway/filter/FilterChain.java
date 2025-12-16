package com.gateway.filter;

import com.gateway.model.GatewayRequest;
import com.gateway.model.GatewayResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 过滤器链
 * 实现责任链模式，按顺序执行过滤器
 *
 * @author Gateway Team
 * @version 1.0.0
 */
public class FilterChain {

    private final List<GatewayFilter> filters;
    private final int currentIndex;

    /**
     * 构造过滤器链
     *
     * @param filters 过滤器列表
     */
    public FilterChain(List<GatewayFilter> filters) {
        this(filters, 0);
    }

    /**
     * 私有构造器，用于创建下一个链节点
     *
     * @param filters      过滤器列表
     * @param currentIndex 当前索引
     */
    private FilterChain(List<GatewayFilter> filters, int currentIndex) {
        this.filters = filters;
        this.currentIndex = currentIndex;
    }

    /**
     * 执行下一个过滤器
     *
     * @param request 网关请求
     * @return 响应的 Mono
     */
    public Mono<GatewayResponse> filter(GatewayRequest request) {
        if (filters == null || currentIndex >= filters.size()) {
            // 过滤器链执行完毕，返回空响应（由路由处理器处理）
            return Mono.empty();
        }

        GatewayFilter currentFilter = filters.get(currentIndex);

        // 跳过禁用的过滤器
        if (!currentFilter.isEnabled()) {
            return next().filter(request);
        }

        return currentFilter.filter(request, next());
    }

    /**
     * 获取下一个过滤器链节点
     *
     * @return 下一个 FilterChain
     */
    private FilterChain next() {
        return new FilterChain(filters, currentIndex + 1);
    }

    /**
     * 获取过滤器数量
     *
     * @return 过滤器数量
     */
    public int size() {
        return filters != null ? filters.size() : 0;
    }
}
