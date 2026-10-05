package acceptance;
import java.net.*;
import java.net.http.*;
import java.time.*;
public final class Probe {
    public static void main(String[] args) throws Exception {
        System.out.println("PROBE_MAIN " + Instant.now() + " home=" + System.getProperty("user.home")
            + " loader=" + ClassLoader.getSystemClassLoader().getClass().getName());
        int before = Gate.rejected;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            try {
                client.send(HttpRequest.newBuilder(URI.create("https://acceptance.invalid/probe"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                throw new IllegalStateException("Proxy must reject");
            } catch (java.io.IOException expected) {
                if (Gate.rejected != before + 1 || !expected.getMessage().contains("403")) throw expected;
                System.out.println("PROBE_CONFIRMED_REAL_CONNECT_403 " + Instant.now());
            }
        }
    }
}
