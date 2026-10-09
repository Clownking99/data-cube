package com.datacube.redis;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** RESP2 codec. Untrusted declarations are checked before allocation. */
final class RespCodec {
    private RespCodec() {}

    static byte[] encode(String... args) { return encodeArguments(args, RedisResourceLimits.DEFAULT); }
    static byte[] encode(byte[]... args) { return encodeArguments(args, RedisResourceLimits.DEFAULT); }

    static byte[] encodeArguments(Object[] args, RedisResourceLimits limits) {
        args = snapshot(args, limits);
        int[] lengths = preflight(args, limits);
        int size = 1 + digits(args.length) + 2;
        for (int length : lengths) size += 1 + digits(length) + 2 + length + 2;
        byte[] frame = new byte[size];
        int offset = header(frame, 0, '*', args.length);
        for (int i = 0; i < args.length; i++) {
            offset = header(frame, offset, '$', lengths[i]);
            if (args[i] instanceof byte[] bytes) {
                System.arraycopy(bytes, 0, frame, offset, bytes.length);
                offset += bytes.length;
            } else offset = writeUtf8(textArgument(args[i]), frame, offset);
            frame[offset++] = '\r';
            frame[offset++] = '\n';
        }
        return frame;
    }

    static byte[][] arguments(Object[] args, RedisResourceLimits limits) {
        args = snapshot(args, limits);
        preflight(args, limits); // Before getBytes and the argument matrix.
        byte[][] result = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            result[i] = args[i] instanceof byte[] bytes ? bytes : textArgument(args[i]).getBytes(StandardCharsets.UTF_8);
        }
        return result;
    }

    private static Object[] snapshot(Object[] args, RedisResourceLimits limits) {
        Objects.requireNonNull(args, "args");
        if (args.length == 0 || args.length > limits.arguments()) throw requestFailure();
        return args.clone(); // Fixed references; immutable text and fixed-length byte arrays.
    }

    static int[] preflight(Object[] args, RedisResourceLimits limits) {
        Objects.requireNonNull(args, "args");
        if (args.length == 0 || args.length > limits.arguments()) throw requestFailure();
        int[] lengths = new int[args.length];
        long payload = 0;
        long frame = 1L + digits(args.length) + 2;
        for (int i = 0; i < args.length; i++) {
            Object arg = Objects.requireNonNull(args[i], "Redis command argument");
            int length = arg instanceof byte[] bytes ? bytes.length : utf8Length(textArgument(arg), limits.requestPayloadBytes());
            if (length > limits.requestPayloadBytes() - payload) throw requestFailure();
            payload += length;
            frame += 1L + digits(length) + 2 + length + 2;
            if (frame > limits.requestFrameBytes()) throw requestFailure();
            lengths[i] = length;
        }
        return lengths;
    }

    private static String textArgument(Object arg) {
        if (arg instanceof String text) return text;
        if (arg instanceof Integer || arg instanceof Long || arg instanceof Double) return arg.toString();
        throw RedisException.rejected(RedisException.Kind.PROTOCOL, RedisException.Delivery.NOT_SENT);
    }

    static int utf8Length(String text, int maximum) {
        if (text.length() > maximum) throw requestFailure();
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int count;
            if (c < 0x80) count = 1;
            else if (c < 0x800) count = 2;
            else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                count = 4; i++;
            } else count = Character.isSurrogate(c) ? 1 : 3; // JDK UTF-8 replacement is '?'.
            if (count > maximum - length) throw requestFailure();
            length += count;
        }
        return length;
    }

    private static int writeUtf8(String text, byte[] out, int p) {
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i);
            if (c < 0x80) out[p++] = (byte) c;
            else if (c < 0x800) {
                out[p++] = (byte) (0xc0 | c >> 6);
                out[p++] = (byte) (0x80 | c & 63);
            } else if (Character.isHighSurrogate((char) c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                c = Character.toCodePoint((char) c, text.charAt(++i));
                out[p++] = (byte) (0xf0 | c >> 18);
                out[p++] = (byte) (0x80 | c >> 12 & 63);
                out[p++] = (byte) (0x80 | c >> 6 & 63);
                out[p++] = (byte) (0x80 | c & 63);
            } else if (Character.isSurrogate((char) c)) out[p++] = '?';
            else {
                out[p++] = (byte) (0xe0 | c >> 12);
                out[p++] = (byte) (0x80 | c >> 6 & 63);
                out[p++] = (byte) (0x80 | c & 63);
            }
        }
        return p;
    }

    private static RedisException requestFailure() {
        return RedisException.rejected(RedisException.Kind.RESOURCE, RedisException.Delivery.NOT_SENT);
    }
    private static int digits(int number) { return Integer.toString(number).length(); }
    private static int header(byte[] out, int p, char marker, int value) {
        out[p++] = (byte) marker;
        byte[] ascii = Integer.toString(value).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, out, p, ascii.length);
        p += ascii.length;
        out[p++] = '\r'; out[p++] = '\n';
        return p;
    }

    static Object decode(InputStream input) throws IOException {
        return decode(input, RedisResourceLimits.DEFAULT, new Deadline(RedisResourceLimits.DEFAULT.readNanos(), System::nanoTime), timeout -> {});
    }

    static Object decode(InputStream input, RedisResourceLimits limits, Deadline deadline, ReadTimeout timeout) throws IOException {
        Reader reader = new Reader(Objects.requireNonNull(input), limits, deadline, timeout);
        ArrayDeque<ArrayFrame> stack = new ArrayDeque<>();
        long pending = 1;
        int nodes = 0;
        while (true) {
            deadline.check();
            int marker = reader.read();
            pending--;
            if (++nodes > limits.nodes()) throw failure(RedisException.Kind.RESOURCE);
            Object value;
            switch (marker) {
                case '+' -> value = reader.line(limits.lineBytes());
                case '-' -> {
                    if (!stack.isEmpty()) throw failure(RedisException.Kind.NESTED_SERVER_ERROR);
                    byte[] message = reader.line(limits.lineBytes());
                    deadline.check();
                    throw new RedisException(new String(message, StandardCharsets.UTF_8), null,
                            RedisException.Kind.SERVER, RedisException.Delivery.REPLIED);
                }
                case ':' -> value = number(reader.line(limits.numericBytes()), false);
                case '$' -> {
                    long length = number(reader.line(limits.numericBytes()), true);
                    if (length == -1) value = null;
                    else {
                        if (length > limits.bulkBytes() || length > reader.remaining() - 2L) throw failure(RedisException.Kind.RESOURCE);
                        deadline.check();
                        byte[] bytes = new byte[(int) length];
                        reader.fill(bytes);
                        if (reader.read() != '\r' || reader.read() != '\n') throw failure(RedisException.Kind.PROTOCOL);
                        value = bytes;
                    }
                }
                case '*' -> {
                    long length = number(reader.line(limits.numericBytes()), true);
                    if (stack.size() + 1 > limits.depth() || length > limits.arrayElements()) throw failure(RedisException.Kind.RESOURCE);
                    if (length == -1) value = null;
                    else {
                        pending += length;
                        if (pending > limits.nodes() - (long) nodes) throw failure(RedisException.Kind.RESOURCE);
                        List<Object> values = new ArrayList<>(0);
                        if (length > 0) { stack.push(new ArrayFrame(values, (int) length)); continue; }
                        value = values;
                    }
                }
                default -> throw failure(RedisException.Kind.PROTOCOL);
            }
            while (!stack.isEmpty()) {
                ArrayFrame parent = stack.peek();
                parent.values.add(value);
                if (--parent.remaining > 0) break;
                stack.pop();
                value = parent.values;
            }
            if (stack.isEmpty()) { deadline.check(); return value; }
        }
    }

    private static long number(byte[] bytes, boolean length) throws ReadFailure {
        if (bytes.length == 0) throw failure(RedisException.Kind.PROTOCOL);
        if (length && bytes[0] == '-') {
            if (bytes.length == 2 && bytes[1] == '1') return -1;
            throw failure(RedisException.Kind.PROTOCOL);
        }
        boolean negative = bytes[0] == '-';
        int i = negative || (!length && bytes[0] == '+') ? 1 : 0;
        if (i == bytes.length) throw failure(RedisException.Kind.PROTOCOL);
        long minimum = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long value = 0;
        for (; i < bytes.length; i++) {
            int digit = bytes[i] - '0';
            if (digit < 0 || digit > 9 || value < minimum / 10) throw failure(RedisException.Kind.PROTOCOL);
            value *= 10;
            if (value < minimum + digit) throw failure(RedisException.Kind.PROTOCOL);
            value -= digit;
        }
        return negative ? value : -value;
    }

    static ReadFailure failure(RedisException.Kind kind) { return new ReadFailure(kind); }
    static final class ReadFailure extends IOException {
        final RedisException.Kind kind;
        ReadFailure(RedisException.Kind kind) { super("Redis response rejected: " + kind.name()); this.kind = kind; }
    }
    @FunctionalInterface interface ReadTimeout { void set(int millis) throws IOException; }
    static final class Deadline {
        private final long budget;
        private final LongSupplier clock;
        private long start;
        private boolean started;
        Deadline(long budget, LongSupplier clock) { this.budget = budget; this.clock = clock; }
        long remaining() throws ReadFailure {
            long now = clock.getAsLong();
            if (!started) { start = now; started = true; }
            long elapsed = now - start;
            if (elapsed < 0 || elapsed >= budget) throw failure(RedisException.Kind.DEADLINE);
            return budget - elapsed;
        }
        void check() throws ReadFailure { remaining(); }
        void checkIfStarted() throws ReadFailure { if (started) check(); }
        int connectMillis() throws ReadFailure { return started ? Math.min(5_000, readMillis()) : 5_000; }
        int readMillis() throws ReadFailure { return (int) Math.min(10_000, 1 + (remaining() - 1) / 1_000_000); }
    }
    private static final class ArrayFrame {
        final List<Object> values;
        int remaining;
        ArrayFrame(List<Object> values, int remaining) { this.values = values; this.remaining = remaining; }
    }
    private static final class Reader {
        final InputStream input;
        final RedisResourceLimits limits;
        final Deadline deadline;
        final ReadTimeout timeout;
        int wire;
        Reader(InputStream input, RedisResourceLimits limits, Deadline deadline, ReadTimeout timeout) {
            this.input = input; this.limits = limits; this.deadline = deadline; this.timeout = timeout;
        }
        int remaining() { return limits.wireBytes() - wire; }
        int read() throws IOException {
            if (remaining() == 0) throw failure(RedisException.Kind.RESOURCE);
            timeout.set(deadline.readMillis());
            int result = input.read();
            deadline.check();
            if (result < 0) throw new EOFException("Truncated Redis response");
            wire++;
            return result;
        }
        void fill(byte[] target) throws IOException {
            int p = 0;
            while (p < target.length) {
                timeout.set(deadline.readMillis());
                int n = input.read(target, p, Math.min(target.length - p, remaining()));
                deadline.check();
                if (n < 0) throw new EOFException("Truncated Redis response");
                if (n == 0) continue;
                p += n; wire += n;
            }
        }
        byte[] line(int cap) throws IOException {
            byte[] bytes = new byte[Math.min(128, cap)];
            int n = 0;
            while (true) {
                int next = read();
                if (next == '\r') {
                    if (read() != '\n') throw failure(RedisException.Kind.PROTOCOL);
                    return Arrays.copyOf(bytes, n);
                }
                if (next == '\n') throw failure(RedisException.Kind.PROTOCOL);
                if (n == cap) throw failure(RedisException.Kind.RESOURCE);
                if (n == bytes.length) bytes = Arrays.copyOf(bytes, Math.min(cap, bytes.length * 2));
                bytes[n++] = (byte) next;
            }
        }
    }
}
