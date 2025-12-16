package com.gateway.controller;

import com.gateway.config.GatewayConfigHolder;
import com.gateway.config.GatewayProperties;
import com.gateway.util.ConfigDebugUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置刷新和调试控制器
 * 提供配置刷新和调试相关的端点
 *
 * @author Gateway Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/config")
public class ConfigRefreshController {

    private final GatewayConfigHolder configHolder;
    private final ConfigDebugUtil configDebugUtil;

    public ConfigRefreshController(GatewayConfigHolder configHolder,
                                  ConfigDebugUtil configDebugUtil) {
        this.configHolder = configHolder;
        this.configDebugUtil = configDebugUtil;
    }

    /**
     * 获取当前配置状态
     *
     * @return 当前配置信息
     */
    @GetMapping("/status")
    public ResponseEntity<String> getConfigStatus() {
        StringBuilder status = new StringBuilder();
        GatewayProperties properties = configHolder.getProperties();
        if (properties != null && properties.getRoutes() != null) {
            status.append("Current Gateway Configuration:\n");
            status.append("Routes:\n");
            for (GatewayProperties.RouteProperties route : properties.getRoutes()) {
                status.append("  ").append(route.getId())
                      .append(" - requireAuth: ").append(route.isRequireAuth()).append("\n");
            }
        } else {
            status.append("GatewayProperties not available\n");
        }

        return ResponseEntity.ok(status.toString());
    }

    /**
     * 触发配置刷新
     * 
     * @return 操作结果
     */
    @PostMapping("/refresh")
    public ResponseEntity<String> refreshConfig() {
        // 通过调用 ConfigDebugUtil 来触发配置状态打印
        configDebugUtil.printCurrentConfig();
        return ResponseEntity.ok("Config refresh triggered and status printed to logs\n");
    }
}