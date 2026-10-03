package dev.flags.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.flags.core.EvaluationContext;
import dev.flags.core.Reason;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives the client against a small scripted server, to exercise the paths a healthy server never takes. */
class FlagClientTest {

    private static final EvaluationContext USER = EvaluationContext.of("user-1");

    /** What the fake server does with each connection, in order. */
    private final LinkedBlockingQueue<Script> scripts = new LinkedBlockingQueue<>();

    private final AtomicInteger connections = new AtomicInteger();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private URI baseUri;

    private interface Script {
        void run(HttpExchange exchange, OutputStream out) throws Exception;
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/sdk/stream", exchange -> {
            connections.incrementAndGet();
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                Script script = scripts.poll(10, TimeUnit.SECONDS);
                if (script == null) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                script.run(exchange, exchange.getResponseBody());
            } catch (Exception e) {
                // The client closing its end mid-script is part of several tests.
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String put(long revision, boolean enabled) {
        String json = "{\"environment\":\"production\",\"revision\":" + revision + ",\"flags\":[{\"key\":\"new-checkout\","
                + "\"salt\":\"s\",\"enabled\":" + enabled + ",\"rules\":[],\"fallthroughPercentage\":100}]}";
        return "event:put\nid:" + revision + "\ndata:" + json + "\n\n";
    }

    private static void open(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }

    private static void send(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void await(Runnable assertion) throws InterruptedException {
        AssertionError last = null;
        for (int i = 0; i < 200; i++) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                Thread.sleep(25);
            }
        }
        throw last;
    }

    @Test
    void returnsTheDefaultUntilRulesArriveThenEvaluatesLocally() throws Exception {
        LinkedBlockingQueue<String> toSend = new LinkedBlockingQueue<>();
        scripts.add((exchange, out) -> {
            open(exchange);
            while (true) {
                send(out, toSend.take());
            }
        });

        try (FlagClient client = FlagClient.connect(baseUri, "ffk_test")) {
            assertThat(client.isEnabled("new-checkout", USER, true)).isTrue();
            assertThat(client.evaluate("new-checkout", USER, false).reason()).isEqualTo(Reason.FLAG_NOT_FOUND);
            assertThat(client.revision()).isEqualTo(-1);

            toSend.add(put(1, true));
            assertThat(client.awaitReady(Duration.ofSeconds(5))).isTrue();

            assertThat(client.isEnabled("new-checkout", USER, false)).isTrue();
            assertThat(client.isEnabled("no-such-flag", USER, false)).isFalse();
            assertThat(authorizations).containsExactly("Bearer ffk_test");

            toSend.add(put(2, false));
            await(() -> assertThat(client.isEnabled("new-checkout", USER, true)).isFalse());
            assertThat(client.revision()).isEqualTo(2);
        }
    }

    @Test
    void reconnectsAfterTheConnectionDropsAndKeepsServingMeanwhile() throws Exception {
        scripts.add((exchange, out) -> {
            open(exchange);
            send(out, put(1, true));
            // Returning closes the connection.
        });
        scripts.add((exchange, out) -> {
            open(exchange);
            send(out, put(2, false));
            Thread.sleep(10_000);
        });

        try (FlagClient client = FlagClient.connect(baseUri, "ffk_test")) {
            assertThat(client.awaitReady(Duration.ofSeconds(5))).isTrue();

            await(() -> assertThat(client.revision()).isEqualTo(2));
            assertThat(connections).hasValue(2);
        }
    }

    @Test
    void anOlderSnapshotAfterReconnectingDoesNotMoveRulesBackwards() throws Exception {
        scripts.add((exchange, out) -> {
            open(exchange);
            send(out, put(5, false));
        });
        scripts.add((exchange, out) -> {
            open(exchange);
            // A lagging instance behind the load balancer answers with something older.
            send(out, put(4, true));
            Thread.sleep(10_000);
        });

        try (FlagClient client = FlagClient.connect(baseUri, "ffk_test")) {
            await(() -> assertThat(connections).hasValue(2));
            Thread.sleep(200);

            assertThat(client.revision()).isEqualTo(5);
            assertThat(client.isEnabled("new-checkout", USER, true)).isFalse();
        }
    }

    @Test
    void reopensAStreamThatHasGoneSilent() throws Exception {
        scripts.add((exchange, out) -> {
            open(exchange);
            send(out, put(1, true));
            // Stays open and says nothing more: no events, no keep-alives.
            Thread.sleep(30_000);
        });
        scripts.add((exchange, out) -> {
            open(exchange);
            send(out, put(2, false));
            Thread.sleep(30_000);
        });

        try (FlagClient client = FlagClient.connect(baseUri, "ffk_test", Duration.ofMillis(400))) {
            await(() -> assertThat(client.revision()).isEqualTo(2));
        }
    }

    @Test
    void stopsForGoodWhenTheKeyIsRefused() throws Exception {
        scripts.add((exchange, out) -> exchange.sendResponseHeaders(401, -1));

        try (FlagClient client = FlagClient.connect(baseUri, "ffk_revoked")) {
            await(() -> assertThat(client.isRejected()).isTrue());
            Thread.sleep(1_200);

            // A revoked key will not start working again; hammering the server helps nobody.
            assertThat(connections).hasValue(1);
            assertThat(client.isEnabled("new-checkout", USER, true)).isTrue();
        }
    }

    @Test
    void notifiesListenersOncePerNewRevision() throws Exception {
        scripts.add((exchange, out) -> {
            open(exchange);
            send(out, put(1, true));
            send(out, put(1, true));
            send(out, put(2, true));
            Thread.sleep(10_000);
        });
        List<Long> seen = new CopyOnWriteArrayList<>();

        try (FlagClient client = FlagClient.connect(baseUri, "ffk_test")) {
            client.onChange(snapshot -> seen.add(snapshot.revision()));
            await(() -> assertThat(client.revision()).isEqualTo(2));
        }

        assertThat(seen).isSubsetOf(1L, 2L).doesNotHaveDuplicates().contains(2L);
    }
}
