package com.datacube.redis;

import java.util.function.Function;

/** Package-bound synthetic executor seam for FX tests. Never opens a service or reads configuration. */
public final class RedisTestSession {
    private RedisTestSession() {}
    public static RedisSession create(Function<byte[][],Object> executor,Runnable close) { return new RedisSession(executor::apply,close); }
    public static RedisSession create(RespClient client) { return new RedisSession(client); }
}
