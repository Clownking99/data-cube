package acceptance;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** External startup gate; never defines or transforms application classes. */
public final class Gate extends ClassLoader {
    private static PrintWriter log;
    private static ProxySelector selector; public static volatile int rejected;
    public Gate(ClassLoader parent) throws Exception {
        super(parent);
        Path home = Path.of(required("acceptance.home")).toRealPath();
        UUID token = UUID.fromString(required("acceptance.token"));
        if (!home.getFileName().toString().equals("datacube-formal-" + token)
                || !Path.of(System.getProperty("user.home")).toRealPath().equals(home)
                || !Files.readString(home.resolve("ownership.txt")).trim().equals(token.toString()))
            throw new IllegalStateException("Profile ownership/home mismatch; application blocked");
        Path run = Path.of(required("acceptance.run")).toRealPath();
        if (!run.getParent().equals(home.resolve("runs"))) throw new IllegalStateException("Run outside owned profile");
        log = new PrintWriter(Files.newBufferedWriter(run.resolve("gate.log"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW), true);
        emit("GATE_BEGIN pid=" + ProcessHandle.current().pid() + " home=" + home);
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), 0));
        int port = server.getLocalPort();
        selector = new ProxySelector() {
            public List<Proxy> select(URI uri) {
                emit("PROXY_SELECT scheme=" + uri.getScheme() + " host=" + uri.getHost() + " port=" + uri.getPort());
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port)));
            }
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                emit("PROXY_FAILED endpoint=" + address + " type=" + failure.getClass().getName());
            }
        };
        ProxySelector.setDefault(selector);
        Thread rejector = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = input.readLine();
                    emit("PROXY_REJECT " + first); rejected++;
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException failure) { emit("REJECTOR_IO " + failure.getClass().getName()); }
            }
        }, "acceptance-loopback-deny");
        rejector.setDaemon(true); rejector.start();
        if (ProxySelector.getDefault() != selector) throw new IllegalStateException("Selector changed");
        emit("GATE_READY proxy=127.0.0.1:" + port + " parentDelegate=true");
        Files.writeString(run.resolve("ready.txt"), ProcessHandle.current().pid() + "\n" + port + "\n", StandardOpenOption.CREATE_NEW);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { emit("JVM_SHUTDOWN"); log.close(); }, "acceptance-shutdown-observer"));
    }
    private static String required(String key) { return Objects.requireNonNull(System.getProperty(key), key); }
    private static synchronized void emit(String value) { log.println(Instant.now() + " " + value); }
}

