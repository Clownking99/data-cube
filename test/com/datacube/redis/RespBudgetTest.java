package com.datacube.redis;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RespBudgetTest {
    static RedisResourceLimits limits(int bulk, int line, int numeric, int wire, int nodes, int array, int depth,
                                      int payload, int frame, int args, long nanos) {
        return new RedisResourceLimits(bulk, line, numeric, wire, nodes, array, depth, payload, frame, args, nanos);
    }
    private static final RedisResourceLimits SMALL = limits(4, 4, 20, 64, 8, 4, 2, 8, 64, 4, 1_000_000_000);
    static Object decode(String frame, RedisResourceLimits limits) throws IOException {
        return RespCodec.decode(new ByteArrayInputStream(frame.getBytes(UTF_8)), limits,
                new RespCodec.Deadline(limits.readNanos(), () -> 0), ignored -> {});
    }
    static void rejected(String frame, RedisResourceLimits limits, RedisException.Kind kind) {
        assertEquals(kind, assertThrows(RespCodec.ReadFailure.class, () -> decode(frame, limits)).kind);
    }
    @Test void bulkAndLineBoundariesAndExactWire() throws Exception {
        assertArrayEquals("abcd".getBytes(UTF_8), (byte[]) decode("$4\r\nabcd\r\n", SMALL));
        rejected("$5\r\n", SMALL, RedisException.Kind.RESOURCE);
        assertArrayEquals("abcd".getBytes(UTF_8), (byte[]) decode("+abcd\r\n", SMALL));
        rejected("+abcde", SMALL, RedisException.Kind.RESOURCE);
        RedisResourceLimits exact = limits(4, 4, 20, 24, 8, 4, 2, 8, 64, 4, 1000);
        assertEquals(2, ((List<?>) decode("*2\r\n$4\r\nabcd\r\n$4\r\nefgh\r\n", exact)).size());
        rejected("*2\r\n$4\r\nabcd\r\n$4\r\nefgh\r\n", limits(4, 4, 20, 23, 8, 4, 2, 8, 64, 4, 1000), RedisException.Kind.RESOURCE);
        assertArrayEquals("a".getBytes(UTF_8), (byte[]) decode("+a\r\n", limits(4, 4, 20, 4, 8, 4, 2, 8, 64, 4, 1000)));
    }
    @Test void arraysShareNodesAndDepthIncludingEmptyAndNull() throws Exception {
        RedisResourceLimits threeNodes = limits(4, 4, 20, 64, 3, 4, 2, 8, 64, 4, 1000);
        assertEquals(List.of(1L, 2L), decode("*2\r\n:1\r\n:2\r\n", threeNodes));
        rejected("*3\r\n", threeNodes, RedisException.Kind.RESOURCE);
        rejected("*2\r\n*2\r\n", threeNodes, RedisException.Kind.RESOURCE);
        assertInstanceOf(List.class, decode("*1\r\n*0\r\n", SMALL));
        rejected("*1\r\n*1\r\n*-1\r\n", SMALL, RedisException.Kind.RESOURCE);
        rejected("*5\r\n", SMALL, RedisException.Kind.RESOURCE);
        assertNull(decode("*-1\r\n", SMALL));
    }
    @Test void declarationsRejectBeforeTouchingTailAndNestedErrorDoesNotDrain() throws Exception {
        for (String header : List.of("*2147483647\r\n", "$2147483647\r\n", "*1\r\n-")) {
            byte[] prefix = header.getBytes(UTF_8);
            InputStream guarded = new InputStream() {
                int p;
                @Override public int read() { if (p == prefix.length) fail("read unsafe tail"); return prefix[p++]; }
            };
            RespCodec.ReadFailure error = assertThrows(RespCodec.ReadFailure.class, () -> RespCodec.decode(guarded,
                    RedisResourceLimits.DEFAULT, new RespCodec.Deadline(1000, () -> 0), ignored -> {}));
            assertEquals(header.endsWith("-") ? RedisException.Kind.NESTED_SERVER_ERROR : RedisException.Kind.RESOURCE, error.kind);
        }
    }
    @Test void grammarOverflowAndEofAreDistinct() throws Exception {
        assertEquals(Long.MIN_VALUE, decode(":-9223372036854775808\r\n", SMALL));
        assertEquals(Long.MAX_VALUE, decode(":+9223372036854775807\r\n", SMALL));
        for (String frame : List.of(":9223372036854775808\r\n", ":\r\n", "$-2\r\n", "$+1\r\n", "*-0\r\n", ":secret\r\n", "$0\r\nx\n", "!")) {
            rejected(frame, SMALL, RedisException.Kind.PROTOCOL);
        }
        rejected(":" + "0".repeat(21), SMALL, RedisException.Kind.RESOURCE);
        assertThrows(EOFException.class, () -> decode("$4\r\nab", SMALL));
    }
    @Test void requestPayloadFrameAndArgumentBoundaries() {
        assertArrayEquals(RespCodec.encode("a", "1234567"), RespCodec.encodeArguments(new Object[]{"a", "1234567"}, SMALL));
        assertEquals(RedisException.Kind.RESOURCE, assertThrows(RedisException.class,
                () -> RespCodec.encodeArguments(new Object[]{"a", "12345678"}, SMALL)).kind());
        byte[] normal = RespCodec.encode("a");
        assertArrayEquals(normal, RespCodec.encodeArguments(new Object[]{"a"}, limits(4, 4, 20, 64, 8, 4, 2, 8, normal.length, 4, 1000)));
        assertThrows(RedisException.class, () -> RespCodec.encodeArguments(new Object[]{"a"}, limits(4, 4, 20, 64, 8, 4, 2, 8, normal.length - 1, 4, 1000)));
        assertThrows(RedisException.class, () -> RespCodec.arguments(new Object[]{"a", "b", "c", "d", "e"}, SMALL));
        Object hostile = new Object() { @Override public String toString() { fail("arbitrary toString invoked"); return ""; } };
        assertEquals(RedisException.Kind.PROTOCOL, assertThrows(RedisException.class,
                () -> RespCodec.arguments(new Object[]{hostile}, SMALL)).kind());
    }
    @Test void utf8CountingAndDirectEncodingMatchJdkIncludingUnpairedSurrogates() {
        for (String text : List.of("", "ascii", "中文", "😀", "\ud800", "\udc00", "x\ud800😀中\udc00")) {
            byte[] bytes = text.getBytes(UTF_8);
            assertEquals(bytes.length, RespCodec.utf8Length(text, 100));
            assertArrayEquals(RespCodec.encode(new byte[][]{bytes}), RespCodec.encode(text));
            if (bytes.length > 0) assertThrows(RedisException.class, () -> RespCodec.utf8Length(text, bytes.length - 1));
        }
    }
    @Test void continuousInputExpiresAndDeadlineIsNotResetAcrossResponses() throws Exception {
        AtomicLong now = new AtomicLong();
        byte[] bytes = "+abcd\r\n".getBytes(UTF_8);
        InputStream flowing = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read() { now.addAndGet(2); return super.read(); }
        };
        RespCodec.Deadline deadline = new RespCodec.Deadline(10, now::get);
        assertEquals(RedisException.Kind.DEADLINE, assertThrows(RespCodec.ReadFailure.class,
                () -> RespCodec.decode(flowing, SMALL, deadline, timeout -> assertTrue(timeout > 0))).kind);
        now.set(0);
        RespCodec.Deadline shared = new RespCodec.Deadline(10, now::get);
        assertArrayEquals(new byte[0], (byte[]) RespCodec.decode(new ByteArrayInputStream("+\r\n".getBytes(UTF_8)), SMALL, shared, ignored -> {}));
        now.set(10);
        assertThrows(RespCodec.ReadFailure.class, () -> RespCodec.decode(new ByteArrayInputStream("+\r\n".getBytes(UTF_8)), SMALL, shared, ignored -> fail("read after deadline")));
        now.set(Long.MAX_VALUE - 4);
        RespCodec.Deadline wrap = new RespCodec.Deadline(10, now::get);
        assertEquals(1, wrap.readMillis());
        now.addAndGet(8);
        assertEquals(2, wrap.remaining());
    }
}
