package com.gateway.util;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 链路追踪 ID 生成器
 * 生成全局唯一的追踪标识
 *
 * @author Gateway Team
 * @version 1.0.0
 */
public final class TraceIdGenerator {

    /**
     * 序列号，用于同一毫秒内的唯一性
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    /**
     * 上次生成时间戳
     */
    private static volatile long lastTimestamp = -1L;

    /**
     * 机器标识（取 MAC 地址后 16 位或随机数）
     */
    private static final long MACHINE_ID;

    static {
        // 生成机器标识
        MACHINE_ID = ThreadLocalRandom.current().nextLong(0, 0xFFFF);
    }

    private TraceIdGenerator() {
        // 工具类禁止实例化
    }

    /**
     * 生成追踪 ID
     * 格式：时间戳(13位) + 机器标识(4位) + 序列号(4位) + 随机数(4位)
     *
     * @return 追踪 ID（25 位十六进制字符串）
     */
    public static String generate() {
        long timestamp = System.currentTimeMillis();
        long seq = getNextSequence(timestamp);
        int random = ThreadLocalRandom.current().nextInt(0, 0xFFFF);

        return String.format("%013x%04x%04x%04x", timestamp, MACHINE_ID, seq & 0xFFFF, random);
    }

    /**
     * 生成简短追踪 ID
     * 格式：UUID 去除横线
     *
     * @return 32 位追踪 ID
     */
    public static String generateShort() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成兼容 Zipkin/Jaeger 的追踪 ID
     * 格式：16 位十六进制（64 位）
     *
     * @return 16 位追踪 ID
     */
    public static String generateZipkinCompatible() {
        long timestamp = System.currentTimeMillis();
        long random = ThreadLocalRandom.current().nextLong();
        return Long.toHexString(timestamp) + Long.toHexString(random).substring(0, 16 - Long.toHexString(timestamp).length());
    }

    /**
     * 获取下一个序列号
     */
    private static synchronized long getNextSequence(long timestamp) {
        if (timestamp == lastTimestamp) {
            return SEQUENCE.incrementAndGet();
        } else {
            lastTimestamp = timestamp;
            SEQUENCE.set(0);
            return 0;
        }
    }

    /**
     * 验证追踪 ID 格式
     *
     * @param traceId 追踪 ID
     * @return 是否有效
     */
    public static boolean isValid(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return false;
        }
        // 支持多种长度的 traceId
        return traceId.length() >= 16 && traceId.length() <= 64 && traceId.matches("^[a-fA-F0-9]+$");
    }
}
