package dev.flags.sdk;

import dev.flags.core.Evaluation;
import dev.flags.core.EvaluationContext;
import dev.flags.core.FlagDefinition;
import dev.flags.core.FlagEvaluator;
import dev.flags.core.Snapshot;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Evaluates flags inside the application, against rules it keeps up to date over a
 * stream.
 *
 * <p>{@link #isEnabled} never touches the network. It reads the rule set held in memory
 * and runs the same evaluator the server uses, so a flag check costs nanoseconds and
 * keeps working if the flag service is unreachable: the client carries on with the last
 * rules it received and reconnects in the background. Before the first rules arrive,
 * and for a flag that does not exist, the caller's default is returned.
 *
 * <pre>{@code
 * try (FlagClient flags = FlagClient.connect(URI.create("https://flags.example.com"), apiKey)) {
 *     flags.awaitReady(Duration.ofSeconds(5));
 *     if (flags.isEnabled("new-checkout", EvaluationContext.of(userId, Map.of("plan", plan)), false)) {
 *         ...
 *     }
 * }
 * }</pre>
 */
public final class FlagClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlagClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration FIRST_RETRY = Duration.ofMillis(500);
    private static final Duration LONGEST_RETRY = Duration.ofSeconds(30);

    private record Held(Snapshot snapshot, Map<String, FlagDefinition> byKey) {}

    private final URI streamUri;
    private final String apiKey;
    private final Duration silenceLimit;
    private final HttpClient http;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private final Thread thread;

    private volatile Held held;
    private volatile boolean closed;
    private volatile boolean rejected;
    private volatile InputStream openStream;
    private volatile long lastActivityNanos;

    private FlagClient(URI baseUri, String apiKey, Duration silenceLimit) {
        this.streamUri = baseUri.resolve("/sdk/stream");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.silenceLimit = silenceLimit;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.thread = Thread.ofPlatform().name("flag-client").daemon(true).start(this::run);
    }

    /** Starts connecting in the background and returns at once. */
    public static FlagClient connect(URI baseUri, String apiKey) {
        return new FlagClient(baseUri, apiKey, Duration.ofSeconds(45));
    }

    /**
     * @param silenceLimit how long the stream may go without a byte before it is treated
     *     as dead and reopened; the server sends a keep-alive well inside the default
     */
    public static FlagClient connect(URI baseUri, String apiKey, Duration silenceLimit) {
        return new FlagClient(baseUri, apiKey, silenceLimit);
    }

    /** Waits for the first rule set. Returns false if it has not arrived in time. */
    public boolean awaitReady(Duration timeout) throws InterruptedException {
        return ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isEnabled(String flagKey, EvaluationContext context, boolean defaultValue) {
        return evaluate(flagKey, context, defaultValue).value();
    }

    public Evaluation evaluate(String flagKey, EvaluationContext context, boolean defaultValue) {
        Held current = held;
        FlagDefinition flag = current == null ? null : current.byKey().get(flagKey);
        return flag == null ? Evaluation.notFound(flagKey, defaultValue) : FlagEvaluator.evaluate(flag, context);
    }

    /** The revision of the rules in use, or -1 before any have arrived. */
    public long revision() {
        Held current = held;
        return current == null ? -1 : current.snapshot().revision();
    }

    /** True once the server has refused the API key. The client stops retrying when that happens. */
    public boolean isRejected() {
        return rejected;
    }

    /** Called on the client's own thread each time newer rules are applied. Keep it quick. */
    public void onChange(Consumer<Snapshot> listener) {
        listeners.add(listener);
    }

    @Override
    public void close() {
        closed = true;
        closeStream();
        thread.interrupt();
    }

    private void run() {
        Duration retry = FIRST_RETRY;
        while (!closed && !rejected) {
            try {
                boolean receivedAnything = stream();
                if (receivedAnything) {
                    retry = FIRST_RETRY;
                }
            } catch (IOException | RuntimeException e) {
                if (!closed) {
                    log.warn("Flag stream interrupted, will reconnect: {}", e.toString());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed || rejected) {
                return;
            }
            // Jitter, so that a fleet of clients dropped by one restart does not return in step.
            long millis = retry.toMillis() / 2 + ThreadLocalRandom.current().nextLong(retry.toMillis() / 2 + 1);
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            retry = retry.multipliedBy(2).compareTo(LONGEST_RETRY) > 0 ? LONGEST_RETRY : retry.multipliedBy(2);
        }
    }

    /** Holds one connection open until it ends. Returns whether it delivered anything. */
    private boolean stream() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(streamUri)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                rejected = true;
                log.error("The flag service refused the API key ({}). Not retrying.", response.statusCode());
                return false;
            }
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status " + response.statusCode());
            }
            openStream = body;
            lastActivityNanos = System.nanoTime();
            Thread watchdog = Thread.ofVirtual().name("flag-client-watchdog").start(() -> watch(body));
            boolean[] received = {false};
            try {
                ServerSentEvents.read(
                        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)),
                        event -> {
                            if ("put".equals(event.name())) {
                                apply(JSON.readValue(event.data(), Snapshot.class));
                                received[0] = true;
                            }
                        },
                        () -> lastActivityNanos = System.nanoTime());
            } finally {
                openStream = null;
                watchdog.interrupt();
            }
            return received[0];
        }
    }

    /**
     * A connection whose other end vanished without closing it never fails a read; it
     * just stays silent. The server's keep-alives make silence meaningful, so a stream
     * quiet for longer than the limit is closed here, which fails the read and triggers
     * a reconnect.
     */
    private void watch(InputStream body) {
        try {
            while (openStream == body) {
                Thread.sleep(Math.max(50, silenceLimit.toMillis() / 4));
                if (System.nanoTime() - lastActivityNanos > silenceLimit.toNanos()) {
                    log.warn("No data from the flag service for {}; reopening the stream", silenceLimit);
                    closeStream();
                    return;
                }
            }
        } catch (InterruptedException e) {
            // The stream ended on its own.
        }
    }

    /** Keeps the newer of what is held and what arrived, so a stale reconnect cannot move rules backwards. */
    private void apply(Snapshot snapshot) {
        Held current = held;
        if (current != null && snapshot.revision() <= current.snapshot().revision()) {
            ready.countDown();
            return;
        }
        held = new Held(
                snapshot, snapshot.flags().stream().collect(Collectors.toUnmodifiableMap(FlagDefinition::key, Function.identity())));
        ready.countDown();
        for (Consumer<Snapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException e) {
                log.warn("A change listener failed", e);
            }
        }
    }

    private void closeStream() {
        InputStream stream = openStream;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                log.debug("Closing the flag stream failed", e);
            }
        }
    }
}
