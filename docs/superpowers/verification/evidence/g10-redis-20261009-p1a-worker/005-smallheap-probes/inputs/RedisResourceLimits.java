package com.datacube.redis;

/** Per-command application budgets, not Redis protocol maxima or a JVM heap limit. */
public record RedisResourceLimits(int bulkBytes, int lineBytes, int numericBytes,
                                  int wireBytes, int nodes, int arrayElements, int depth,
                                  int requestPayloadBytes, int requestFrameBytes, int arguments,
                                  long readNanos) {
    public static final RedisResourceLimits DEFAULT = new RedisResourceLimits(
            8 * 1024 * 1024, 64 * 1024, 32, 16 * 1024 * 1024, 100_000, 10_000, 32,
            8 * 1024 * 1024, 16 * 1024 * 1024, 10_000, 30_000_000_000L);

    public RedisResourceLimits {
        if (bulkBytes < 1 || bulkBytes > 8 * 1024 * 1024 || lineBytes < 1 || lineBytes > 64 * 1024
                || numericBytes < 1 || numericBytes > 32 || wireBytes < 1 || wireBytes > 16 * 1024 * 1024
                || nodes < 1 || nodes > 100_000 || arrayElements < 1 || arrayElements > 10_000
                || depth < 1 || depth > 32 || requestPayloadBytes < 1 || requestPayloadBytes > 8 * 1024 * 1024
                || requestFrameBytes < 1 || requestFrameBytes > 16 * 1024 * 1024
                || arguments < 1 || arguments > 10_000 || readNanos < 1 || readNanos > 30_000_000_000L) {
            throw new IllegalArgumentException("Redis budgets must be positive and no larger than defaults");
        }
    }
}
