package com.gateway.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * IP 地址工具类
 * 支持 CIDR 格式的 IP 白名单匹配
 *
 * @author Gateway Team
 * @version 1.0.0
 */
public final class IpUtils {

    private static final Logger log = LoggerFactory.getLogger(IpUtils.class);

    private IpUtils() {
        // 工具类禁止实例化
    }

    /**
     * 检查 IP 是否在白名单中
     *
     * @param clientIp  客户端 IP
     * @param whitelist 白名单列表（支持 CIDR 格式）
     * @return 是否在白名单中
     */
    public static boolean isInWhitelist(String clientIp, List<String> whitelist) {
        if (clientIp == null || clientIp.isEmpty()) {
            return false;
        }
        if (whitelist == null || whitelist.isEmpty()) {
            return true; // 空白名单表示允许所有
        }

        for (String pattern : whitelist) {
            if (matchIpPattern(clientIp, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 匹配 IP 模式
     *
     * @param clientIp 客户端 IP
     * @param pattern  IP 模式（支持单个 IP、CIDR 格式）
     * @return 是否匹配
     */
    public static boolean matchIpPattern(String clientIp, String pattern) {
        if (clientIp == null || pattern == null) {
            return false;
        }

        pattern = pattern.trim();
        clientIp = clientIp.trim();

        // 处理 IPv6 格式的 IPv4 地址
        if (clientIp.startsWith("::ffff:")) {
            clientIp = clientIp.substring(7);
        }

        // 精确匹配
        if (pattern.equals(clientIp)) {
            return true;
        }

        // 本地地址特殊处理
        if (isLocalAddress(clientIp) && isLocalPattern(pattern)) {
            return true;
        }

        // CIDR 格式匹配
        if (pattern.contains("/")) {
            return matchCidr(clientIp, pattern);
        }

        // 通配符匹配（例如：192.168.*.*）
        if (pattern.contains("*")) {
            return matchWildcard(clientIp, pattern);
        }

        return false;
    }

    /**
     * 检查是否是本地地址
     */
    private static boolean isLocalAddress(String ip) {
        return "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) || "localhost".equalsIgnoreCase(ip);
    }

    /**
     * 检查是否是本地地址模式
     */
    private static boolean isLocalPattern(String pattern) {
        return "127.0.0.1".equals(pattern) || "localhost".equalsIgnoreCase(pattern)
                || "127.0.0.0/8".equals(pattern) || "::1".equals(pattern);
    }

    /**
     * CIDR 格式匹配
     *
     * @param clientIp 客户端 IP
     * @param cidr     CIDR 格式（如 10.0.0.0/8）
     * @return 是否匹配
     */
    public static boolean matchCidr(String clientIp, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }

            String networkAddress = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] clientBytes = InetAddress.getByName(clientIp).getAddress();
            byte[] networkBytes = InetAddress.getByName(networkAddress).getAddress();

            // IPv4 和 IPv6 长度不同
            if (clientBytes.length != networkBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            // 检查完整字节
            for (int i = 0; i < fullBytes; i++) {
                if (clientBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            // 检查剩余位
            if (remainingBits > 0 && fullBytes < clientBytes.length) {
                int mask = 0xFF << (8 - remainingBits);
                if ((clientBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (UnknownHostException | NumberFormatException e) {
            log.warn("CIDR 匹配失败: clientIp={}, cidr={}, error={}", clientIp, cidr, e.getMessage());
            return false;
        }
    }

    /**
     * 通配符匹配
     *
     * @param clientIp 客户端 IP
     * @param pattern  通配符模式（如 192.168.*.*）
     * @return 是否匹配
     */
    public static boolean matchWildcard(String clientIp, String pattern) {
        String[] clientParts = clientIp.split("\\.");
        String[] patternParts = pattern.split("\\.");

        if (clientParts.length != patternParts.length) {
            return false;
        }

        for (int i = 0; i < clientParts.length; i++) {
            if (!"*".equals(patternParts[i]) && !clientParts[i].equals(patternParts[i])) {
                return false;
            }
        }

        return true;
    }

    /**
     * 从请求头获取真实 IP
     * 支持代理转发场景
     *
     * @param xForwardedFor X-Forwarded-For 头
     * @param xRealIp       X-Real-IP 头
     * @param remoteAddress 远程地址
     * @return 真实 IP
     */
    public static String getRealIp(String xForwardedFor, String xRealIp, String remoteAddress) {
        // 优先使用 X-Forwarded-For
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For 可能包含多个 IP，取第一个
            String[] ips = xForwardedFor.split(",");
            for (String ip : ips) {
                ip = ip.trim();
                if (!ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                    return ip;
                }
            }
        }

        // 其次使用 X-Real-IP
        if (xRealIp != null && !xRealIp.isEmpty() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp;
        }

        // 最后使用远程地址
        return remoteAddress != null ? remoteAddress : "unknown";
    }
}
