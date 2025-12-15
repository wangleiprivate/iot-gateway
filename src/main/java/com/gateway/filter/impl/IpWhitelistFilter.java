package com.gateway.filter.impl;

import com.gateway.config.GatewayProperties;
import com.gateway.filter.FilterChain;
import com.gateway.filter.GatewayFilter;
import com.gateway.model.GatewayRequest;
import com.gateway.model.GatewayResponse;
import com.gateway.util.IpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * IP 白名单过滤器
 * 检查客户端 IP 是否在白名单中
 *
 * @author Claude Gateway Team
 * @version 1.0.0
 */
@Component
public class IpWhitelistFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(IpWhitelistFilter.class);

    private final GatewayProperties properties;

    public IpWhitelistFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "IpWhitelistFilter";
    }

    @Override
    public int getOrder() {
        return 100; // 最先执行
    }

    @Override
    public boolean isEnabled() {
        return properties.getSecurity() != null
                && properties.getSecurity().getIpWhitelist() != null
                && properties.getSecurity().getIpWhitelist().isEnabled();
    }

    @Override
    public Mono<GatewayResponse> filter(GatewayRequest request, FilterChain chain) {
        String clientIp = request.getRemoteIp();
        List<String> whitelist = properties.getSecurity().getIpWhitelist().getList();

        log.debug("[{}] IP白名单检查: clientIp={}, whitelist={}", request.getTraceId(), clientIp, whitelist);

        // 空白名单表示允许所有
        if (whitelist == null || whitelist.isEmpty()) {
            log.debug("[{}] IP白名单为空，允许所有请求", request.getTraceId());
            return chain.filter(request);
        }

        // 检查 IP 是否在白名单中
        if (IpUtils.isInWhitelist(clientIp, whitelist)) {
            log.debug("[{}] IP {} 在白名单中，允许通过", request.getTraceId(), clientIp);
            return chain.filter(request);
        }

        // IP 不在白名单中，拒绝请求
        log.warn("[{}] IP {} 不在白名单中，拒绝请求", request.getTraceId(), clientIp);
        return Mono.just(GatewayResponse.forbidden("IP " + clientIp + " is not in whitelist"));
    }
}
