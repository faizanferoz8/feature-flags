package dev.flags.server.sdk;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The open streams, grouped by environment.
 *
 * <p>Each subscriber has a one-slot mailbox instead of a queue. A snapshot is the whole
 * state, not a delta, so a client that is slow to read has no use for the ones it
 * missed: a newer snapshot simply replaces whatever is waiting. That bounds memory per
 * client at one snapshot, and because every subscriber is written to from its own
 * virtual thread, one stalled connection cannot hold up the rest.
 */
@Component
public class StreamHub implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

    private final Map<UUID, Set<Subscriber>> byEnvironment = new ConcurrentHashMap<>();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;

    /**
     * Registers a stream. The caller must offer the current snapshot after this returns,
     * not before: a change that lands in between is then either delivered through the
     * hub or already part of what the caller reads, and cannot be missed.
     *
     * @param keyId the API key the stream was opened with, or null for a console user
     */
    public Subscriber subscribe(UUID environmentId, UUID keyId, long timeoutMillis) {
        Subscriber subscriber = new Subscriber(environmentId, keyId, new SseEmitter(timeoutMillis));
        byEnvironment
                .computeIfAbsent(environmentId, id -> ConcurrentHashMap.newKeySet())
                .add(subscriber);
        subscriber.emitter.onCompletion(() -> remove(subscriber));
        subscriber.emitter.onTimeout(() -> remove(subscriber));
        subscriber.emitter.onError(error -> remove(subscriber));
        return subscriber;
    }

    public void publish(Published published) {
        subscribers(published.environmentId()).forEach(subscriber -> subscriber.offer(published));
    }

    /** Ends every stream opened with a key that has just been revoked. */
    public void disconnectKey(UUID keyId) {
        byEnvironment.values().stream()
                .flatMap(Set::stream)
                .filter(subscriber -> keyId.equals(subscriber.keyId))
                .forEach(Subscriber::close);
    }

    /**
     * Keeps idle streams open through proxies that drop quiet connections, and finds the
     * ones whose client has gone: a write to a dead socket fails, and the subscriber is
     * dropped.
     */
    @Scheduled(fixedDelayString = "${flags.stream.heartbeat}")
    void heartbeat() {
        byEnvironment.values().stream().flatMap(Set::stream).forEach(Subscriber::beat);
    }

    public int subscriberCount() {
        return byEnvironment.values().stream().mapToInt(Set::size).sum();
    }

    private Set<Subscriber> subscribers(UUID environmentId) {
        return byEnvironment.getOrDefault(environmentId, Set.of());
    }

    private void remove(Subscriber subscriber) {
        Set<Subscriber> subscribers = byEnvironment.get(subscriber.environmentId);
        if (subscribers != null) {
            subscribers.remove(subscriber);
        }
    }

    @Override
    public void start() {
        running = true;
    }

    /**
     * Open streams count as requests in flight, and graceful shutdown waits for those.
     * Closing them first lets the server stop promptly; SDKs reconnect to another
     * instance.
     */
    @Override
    public void stop() {
        running = false;
        byEnvironment.values().stream().flatMap(Set::stream).toList().forEach(Subscriber::close);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** After everything else has started, and before the web server begins shutting down. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public final class Subscriber {

        private final UUID environmentId;
        private final UUID keyId;
        private final SseEmitter emitter;
        private final AtomicReference<Published> mailbox = new AtomicReference<>();
        private final AtomicBoolean beatDue = new AtomicBoolean();
        private final AtomicBoolean writing = new AtomicBoolean();
        private long lastSentRevision = -1;

        private Subscriber(UUID environmentId, UUID keyId, SseEmitter emitter) {
            this.environmentId = environmentId;
            this.keyId = keyId;
            this.emitter = emitter;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        public void offer(Published published) {
            mailbox.accumulateAndGet(
                    published, (waiting, fresh) -> waiting == null || fresh.revision() > waiting.revision() ? fresh : waiting);
            wake();
        }

        private void beat() {
            beatDue.set(true);
            wake();
        }

        private void close() {
            remove(this);
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Stream was already closed", e);
            }
        }

        /** At most one writer per subscriber at a time, so events cannot interleave. */
        private void wake() {
            if (writing.compareAndSet(false, true)) {
                writers.execute(this::write);
            }
        }

        private void write() {
            try {
                while (true) {
                    Published next = mailbox.getAndSet(null);
                    boolean beat = beatDue.getAndSet(false);
                    if (next == null && !beat) {
                        break;
                    }
                    if (next != null && next.revision() > lastSentRevision) {
                        emitter.send(SseEmitter.event()
                                .name("put")
                                .id(Long.toString(next.revision()))
                                .data(next.json()));
                        lastSentRevision = next.revision();
                    } else if (beat) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Dropping a stream that can no longer be written to: {}", e.toString());
                close();
            } finally {
                writing.set(false);
                // Something may have arrived between the last check and releasing the flag.
                if (mailbox.get() != null || beatDue.get()) {
                    wake();
                }
            }
        }
    }
}
