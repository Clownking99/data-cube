package com.datacube.redis;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Standalone child-JVM diagnostic. No sockets, profiles, or production services. */
public final class BudgetProbe {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        try {
            if (mode.equals("request")) {
                RespCodec.encode("SET", "synthetic", "x".repeat(8 * 1024 * 1024));
            } else {
                String frame = switch (mode) {
                    case "array" -> "*2147483647\r\n";
                    case "bulk" -> "$2147483647\r\n";
                    case "depth" -> "*1\r\n".repeat(6000) + "+x\r\n";
                    case "line" -> "+" + "x".repeat(100_000) + "\r\n";
                    case "nodes" -> "*10\r\n" + ("*10000\r\n" + ":0\r\n".repeat(10000)).repeat(10);
                    default -> throw new AssertionError("unknown probe");
                };
                byte[] bytes = frame.getBytes(StandardCharsets.US_ASCII);
                final int[] reads = {0};
                InputStream input = new ByteArrayInputStream(bytes) {
                    @Override public synchronized int read() { reads[0]++; return super.read(); }
                };
                try { RespCodec.decode(input); }
                catch (RespCodec.ReadFailure rejected) {
                    if (rejected.kind != RedisException.Kind.RESOURCE) throw new AssertionError(rejected);
                    if ((mode.equals("array") || mode.equals("bulk")) && reads[0] != bytes.length) throw new AssertionError("read beyond declaration");
                    System.out.println("RESOURCE before unsafe allocation; mode="+mode+"; readCalls="+reads[0]+"; inputBytes="+bytes.length);
                    return;
                }
            }
        } catch (RedisException rejected) {
            if (!mode.equals("request") || rejected.kind() != RedisException.Kind.RESOURCE || rejected.delivery() != RedisException.Delivery.NOT_SENT) throw new AssertionError(rejected);
            System.out.println("RESOURCE before UTF-8/frame copies; mode=request; NOT_SENT"); return;
        }
        throw new AssertionError("unsafe input accepted");
    }
}
