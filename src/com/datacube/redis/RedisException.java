package com.datacube.redis;

/** Redis 返回的协议级错误（例如 ERR、WRONGTYPE 或 NOAUTH）。 */
public final class RedisException extends RuntimeException {

    public RedisException(String message) {
        super(message);
    }

    public RedisException(String message, Throwable cause) {
        super(message, cause);
    }
}
