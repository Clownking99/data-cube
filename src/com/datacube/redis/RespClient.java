package com.datacube.redis;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Single-flight RESP2 client; close can detach the active socket without waiting for I/O. */
public final class RespClient implements Closeable, RedisCommandExecutor {
    private static final Set<String> RETRY_SAFE = Set.of("PING", "GET", "STRLEN", "GETRANGE", "TYPE", "TTL", "EXISTS", "DBSIZE", "INFO", "SCAN", "HSCAN", "SSCAN", "ZSCAN", "LLEN", "LRANGE", "ZCARD");
    // These modes may change authentication/DB/transaction/reply state we cannot reconstruct.
    private static final Set<String> RAW_CONTEXT = Set.of("AUTH", "MULTI", "EXEC", "DISCARD", "RESET", "WATCH", "UNWATCH", "HELLO", "CLIENT", "SUBSCRIBE", "PSUBSCRIBE", "SSUBSCRIBE", "UNSUBSCRIBE", "PUNSUBSCRIBE", "SUNSUBSCRIBE", "MONITOR");
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final RedisResourceLimits limits;
    private final LongSupplier clock;
    private final Object flight = new Object();
    private final Object state = new Object();
    private Socket current;
    private boolean closed;
    private int confirmedDatabase;
    private boolean restoreTrusted = true;
    private boolean contextInvalid;

    public RespClient(String host, int port, String username, String password, int database) {
        this(host, port, username, password, database, RedisResourceLimits.DEFAULT, System::nanoTime);
    }
    RespClient(String host, int port, String username, String password, int database, RedisResourceLimits limits, LongSupplier clock) {
        this.host = Objects.requireNonNull(host, "host"); this.port = port;
        this.username = username == null ? "" : username; this.password = password == null ? "" : password;
        this.confirmedDatabase = database; this.limits = Objects.requireNonNull(limits); this.clock = Objects.requireNonNull(clock);
    }
    public Object call(String... args) { return callArguments(args); }
    @Override public Object callBytes(byte[]... args) { return callArguments(args); }
    RedisResourceLimits limits() { return limits; }

    private Object callArguments(Object[] args) {
        synchronized (flight) {
            admit();
            byte[] frame = RespCodec.encodeArguments(args, limits);
            // Validate handshake too, before creating a socket or sending any bytes.
            Object[] auth = auth();
            if (auth != null) RespCodec.preflight(auth, limits);
            if (confirmedDatabase != 0) RespCodec.preflight(new Object[]{"SELECT", confirmedDatabase}, limits);
            FrameArguments actual = frameArguments(frame);
            String command = command(frame, actual.first);
            boolean retrySafe = RETRY_SAFE.contains(command);
            Integer selected = "SELECT".equals(command) && actual.count == 2 ? databaseArgument(frame, actual.second) : null;
            RespCodec.Deadline deadline = new RespCodec.Deadline(limits.readNanos(), clock);
            int attempts = retrySafe ? 2 : 1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                Socket lease = null;
                boolean sent = false;
                try {
                    admit();
                    deadline.checkIfStarted();
                    lease = ensureConnected(deadline);
                    OutputStream output = lease.getOutputStream();
                    admit();
                    deadline.checkIfStarted();
                    if (RAW_CONTEXT.contains(command)) restoreTrusted = false;
                    sent = true; // write may send only part of the frame.
                    output.write(frame); output.flush();
                    Object response = decode(lease, deadline);
                    admit();
                    if ("SELECT".equals(command)) {
                        if (ok(response)) {
                            if (selected == null) {
                                contextInvalid = true;
                                discard(lease);
                                throw RedisException.rejected(RedisException.Kind.CONTEXT, RedisException.Delivery.MAY_HAVE_SENT);
                            }
                            confirmedDatabase = selected;
                        } else restoreTrusted = false; // e.g. QUEUED: do not infer EXEC results.
                    }
                    return response;
                } catch (RespCodec.ReadFailure failure) {
                    discard(lease);
                    throw RedisException.rejected(failure.kind, delivery(sent));
                } catch (RedisException failure) {
                    if (failure.kind() == RedisException.Kind.SERVER) {
                        returnServerError(failure, sent);
                    }
                    discard(lease);
                    throw sent && failure.delivery() == RedisException.Delivery.NOT_SENT
                            ? RedisException.rejected(failure.kind(), delivery(true)) : failure;
                } catch (IOException failure) {
                    discard(lease);
                    try { deadline.checkIfStarted(); }
                    catch (RespCodec.ReadFailure expired) { throw RedisException.rejected(expired.kind, delivery(sent)); }
                    try { admit(); } // closed/untrusted context must never reopen silently.
                    catch (RedisException rejected) { throw RedisException.rejected(rejected.kind(), delivery(sent)); }
                    if (!retrySafe || attempt + 1 == attempts) {
                        throw RedisException.rejected(RedisException.Kind.TRANSPORT, delivery(sent));
                    }
                } catch (RuntimeException | Error failure) {
                    discard(lease); // Cleanup only; never convert VM/application errors to budget failures.
                    throw failure;
                }
            }
            throw new AssertionError("unreachable");
        }
    }

    private static void returnServerError(RedisException failure, boolean businessSent) {
        throw new RedisException(failure.getMessage(), null, RedisException.Kind.SERVER,
                businessSent ? RedisException.Delivery.REPLIED : RedisException.Delivery.NOT_SENT);
    }
    private static RedisException.Delivery delivery(boolean sent) {
        return sent ? RedisException.Delivery.MAY_HAVE_SENT : RedisException.Delivery.NOT_SENT;
    }
    private void admit() {
        synchronized (state) {
            if (closed) throw RedisException.rejected(RedisException.Kind.CLOSED, RedisException.Delivery.NOT_SENT);
        }
        if (contextInvalid) throw RedisException.rejected(RedisException.Kind.CONTEXT, RedisException.Delivery.NOT_SENT);
    }
    private Socket ensureConnected(RespCodec.Deadline deadline) throws IOException {
        synchronized (state) { if (current != null) return current; }
        admit();
        Socket created = new Socket();
        boolean rejected;
        synchronized (state) {
            rejected = closed;
            if (!rejected) current = created; // provisional lease: close can reach connect/handshake.
        }
        if (rejected) { closeSocket(created); throw RedisException.rejected(RedisException.Kind.CLOSED, RedisException.Delivery.NOT_SENT); }
        try {
            InetSocketAddress address = new InetSocketAddress(host, port); // DNS has no hard deadline here.
            deadline.checkIfStarted();
            created.connect(address, deadline.connectMillis());
            admit();
            Object[] auth = auth();
            if (auth != null) expectOk(exchange(created, RespCodec.encodeArguments(auth, limits), deadline));
            if (confirmedDatabase != 0) expectOk(exchange(created, RespCodec.encodeArguments(new Object[]{"SELECT", confirmedDatabase}, limits), deadline));
            admit();
            return created;
        } catch (IOException | RuntimeException | Error failure) {
            discard(created);
            throw failure;
        }
    }
    private Object[] auth() {
        if (password.isEmpty()) return null;
        return username.isBlank() ? new Object[]{"AUTH", password} : new Object[]{"AUTH", username, password};
    }
    private Object exchange(Socket socket, byte[] frame, RespCodec.Deadline deadline) throws IOException {
        admit();
        deadline.checkIfStarted();
        socket.getOutputStream().write(frame); socket.getOutputStream().flush();
        return decode(socket, deadline);
    }
    private Object decode(Socket socket, RespCodec.Deadline deadline) throws IOException {
        return RespCodec.decode(socket.getInputStream(), limits, deadline, socket::setSoTimeout);
    }
    private static boolean ok(Object response) {
        return response instanceof byte[] bytes && bytes.length == 2
                && (bytes[0] == 'O' || bytes[0] == 'o') && (bytes[1] == 'K' || bytes[1] == 'k');
    }
    private static void expectOk(Object response) throws RespCodec.ReadFailure {
        if (!ok(response)) throw RespCodec.failure(RedisException.Kind.PROTOCOL);
    }
    private static String command(byte[] frame, Slice arg) {
        int size = arg.length;
        if (size > 16) return "";
        char[] chars = new char[size];
        for (int i = 0; i < size; i++) {
            int c = frame[arg.offset + i] & 255;
            if (c > 127) return "";
            chars[i] = (char) (c >= 'a' && c <= 'z' ? c - 32 : c);
        }
        return new String(chars);
    }
    private static Integer databaseArgument(byte[] frame, Slice arg) {
        int size = arg.length;
        if (size == 0) return null;
        int i = 0;
        int first = frame[arg.offset] & 255;
        boolean negative = first == '-';
        if (negative || first == '+') i++;
        if (i == size) return null;
        int value = 0;
        for (; i < size; i++) {
            int digit = (frame[arg.offset + i] & 255) - '0';
            if (digit < 0 || digit > 9 || value > (Integer.MAX_VALUE - digit) / 10) return null;
            value = value * 10 + digit;
        }
        return negative && value != 0 ? null : value;
    }
    private record Slice(int offset, int length) {}
    private record FrameArguments(int count, Slice first, Slice second) {}
    private static FrameArguments frameArguments(byte[] frame) {
        // Only reads the trusted encoder's headers; policy uses actual immutable frame bytes.
        int p = 1, count = 0;
        while (frame[p] != '\r') count = count * 10 + frame[p++] - '0';
        p += 3; // CRLF and '$'.
        int size = 0;
        while (frame[p] != '\r') size = size * 10 + frame[p++] - '0';
        Slice first = new Slice(p + 2, size);
        Slice second = null;
        if (count == 2) {
            p = first.offset + first.length + 3;
            size = 0;
            while (frame[p] != '\r') size = size * 10 + frame[p++] - '0';
            second = new Slice(p + 2, size);
        }
        return new FrameArguments(count, first, second);
    }
    private void discard(Socket lease) {
        if (lease == null) return;
        synchronized (state) { if (current == lease) current = null; }
        if (!restoreTrusted) contextInvalid = true;
        closeSocket(lease);
    }
    @Override public void close() {
        Socket lease;
        synchronized (state) { closed = true; lease = current; current = null; }
        closeSocket(lease);
    }
    private static void closeSocket(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { /* Already detached; close is best effort. */ }
    }
}

@FunctionalInterface
interface RedisCommandExecutor { Object callBytes(byte[]... args); }
