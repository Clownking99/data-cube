package com.datacube.redis;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RespCodecTest {

    @Test
    void encodesUtf8CommandWithByteLengths() {
        byte[] encoded = RespCodec.encode("SET", "问候", "你好");

        assertArrayEquals("*3\r\n$3\r\nSET\r\n$6\r\n问候\r\n$6\r\n你好\r\n".getBytes(UTF_8), encoded);
    }

    @Test
    void encodesBinaryCommandArgumentWithoutTextConversion() {
        byte[] encoded = RespCodec.encode(new byte[][]{
                "SET".getBytes(UTF_8), "blob".getBytes(UTF_8), new byte[]{0, (byte) 0xff, 1}
        });

        assertArrayEquals(new byte[]{
                '*', '3', '\r', '\n',
                '$', '3', '\r', '\n', 'S', 'E', 'T', '\r', '\n',
                '$', '4', '\r', '\n', 'b', 'l', 'o', 'b', '\r', '\n',
                '$', '3', '\r', '\n', 0, (byte) 0xff, 1, '\r', '\n'
        }, encoded);
    }

    @Test
    void decodesSimpleStringAndInteger() throws Exception {
        assertArrayEquals("PONG".getBytes(UTF_8), (byte[]) decode("+PONG\r\n"));
        assertEquals(42L, decode(":42\r\n"));
    }

    @Test
    void decodesBinaryBulkString() throws Exception {
        byte[] frame = new byte[]{'$', '3', '\r', '\n', 0, (byte) 0xff, 1, '\r', '\n'};

        assertArrayEquals(new byte[]{0, (byte) 0xff, 1}, (byte[]) RespCodec.decode(new ByteArrayInputStream(frame)));
    }

    @Test
    void decodesNestedArraysAndNilValues() throws Exception {
        Object decoded = decode("*4\r\n$3\r\none\r\n:2\r\n$-1\r\n*2\r\n+OK\r\n*-1\r\n");

        List<?> values = assertInstanceOf(List.class, decoded);
        assertArrayEquals("one".getBytes(UTF_8), (byte[]) values.get(0));
        assertEquals(2L, values.get(1));
        assertNull(values.get(2));
        List<?> nested = assertInstanceOf(List.class, values.get(3));
        assertArrayEquals("OK".getBytes(UTF_8), (byte[]) nested.get(0));
        assertNull(nested.get(1));
    }

    @Test
    void serverErrorPreservesOriginalMessage() {
        RedisException error = assertThrows(RedisException.class,
                () -> decode("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n"));

        assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", error.getMessage());
    }

    @Test
    void rejectsUnknownMarkerAndInvalidTerminator() {
        assertThrows(IOException.class, () -> decode("!wat\r\n"));
        assertThrows(IOException.class, () -> decode("+OK\n"));
    }

    @Test
    void rejectsTruncatedBulkPayload() {
        assertThrows(IOException.class, () -> decode("$5\r\nabc"));
    }

    private static Object decode(String frame) throws IOException {
        return RespCodec.decode(new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8)));
    }
}
