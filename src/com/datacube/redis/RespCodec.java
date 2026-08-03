package com.datacube.redis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** RESP2 命令编码与响应解码。 */
final class RespCodec {

    private static final byte[] CRLF = {'\r', '\n'};

    private RespCodec() {}

    static byte[] encode(String... args) {
        Objects.requireNonNull(args, "args");
        byte[][] values = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            values[i] = Objects.requireNonNull(args[i], "Redis command argument")
                    .getBytes(StandardCharsets.UTF_8);
        }
        return encode(values);
    }

    static byte[] encode(byte[]... args) {
        Objects.requireNonNull(args, "args");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "*" + args.length);
        out.writeBytes(CRLF);
        for (byte[] arg : args) {
            byte[] value = Objects.requireNonNull(arg, "Redis command argument");
            writeAscii(out, "$" + value.length);
            out.writeBytes(CRLF);
            out.writeBytes(value);
            out.writeBytes(CRLF);
        }
        return out.toByteArray();
    }

    static Object decode(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        int marker = input.read();
        if (marker < 0) throw new IOException("RESP response ended before type marker");
        return switch (marker) {
            case '+' -> readLine(input);
            case '-' -> throw new RedisException(new String(readLine(input), StandardCharsets.UTF_8));
            case ':' -> parseLong(readLine(input), "integer");
            case '$' -> readBulk(input);
            case '*' -> readArray(input);
            default -> throw new IOException("Unknown RESP type marker: " + (char) marker);
        };
    }

    private static byte[] readBulk(InputStream input) throws IOException {
        long length = parseLong(readLine(input), "bulk length");
        if (length == -1) return null;
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IOException("Invalid RESP bulk length: " + length);
        }
        byte[] value = input.readNBytes((int) length);
        if (value.length != length) throw new IOException("Truncated RESP bulk payload");
        requireCrlf(input);
        return value;
    }

    private static List<Object> readArray(InputStream input) throws IOException {
        long length = parseLong(readLine(input), "array length");
        if (length == -1) return null;
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IOException("Invalid RESP array length: " + length);
        }
        List<Object> values = new ArrayList<>((int) length);
        for (int i = 0; i < length; i++) values.add(decode(input));
        return values;
    }

    private static byte[] readLine(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0) throw new IOException("Truncated RESP line");
            if (next == '\r') {
                if (input.read() != '\n') throw new IOException("RESP line must end with CRLF");
                return out.toByteArray();
            }
            if (next == '\n') throw new IOException("RESP line must end with CRLF");
            out.write(next);
        }
    }

    private static void requireCrlf(InputStream input) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("RESP bulk payload must end with CRLF");
        }
    }

    private static long parseLong(byte[] bytes, String label) throws IOException {
        try {
            return Long.parseLong(new String(bytes, StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid RESP " + label, e);
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }
}
