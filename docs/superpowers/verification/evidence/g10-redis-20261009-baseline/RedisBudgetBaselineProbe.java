package com.datacube.redis;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Current-code diagnostic, isolated small heap; not a product protection strategy. */
public final class RedisBudgetBaselineProbe {
    public static void main(String[] args) throws Exception {
        String name = args[0];
        String frame = switch (name) {
            case "array-header" -> "*2147483647\r\n";
            case "deep-array" -> "*1\r\n".repeat(6000) + "+OK\r\n";
            case "long-line" -> "+" + "x".repeat(100000) + "\r\n";
            default -> throw new IllegalArgumentException("Unknown synthetic case");
        };
        String outcome;
        try {
            Object value = RespCodec.decode(new ByteArrayInputStream(frame.getBytes(StandardCharsets.US_ASCII)));
            outcome = value instanceof byte[] bytes ? "accepted-" + bytes.length + "-bytes" : "accepted";
        } catch (OutOfMemoryError | StackOverflowError failure) {
            outcome = failure.getClass().getSimpleName();
        }
        String expected = switch (name) {
            case "array-header" -> "OutOfMemoryError";
            case "deep-array" -> "StackOverflowError";
            default -> "accepted-100000-bytes";
        };
        if (!expected.equals(outcome)) throw new AssertionError("Baseline changed: " + name + ": " + outcome);
        System.out.println("{\"case\":\"" + name + "\",\"outcome\":\"" + outcome
                + "\",\"networkCalls\":0,\"heapMiB\":32,\"stackKiB\":256}");
    }
}
