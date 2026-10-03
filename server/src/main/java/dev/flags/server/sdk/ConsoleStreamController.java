package dev.flags.server.sdk;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The same stream SDKs get, for the console, so a change made in one browser shows up
 * in every other one that has the flag open.
 */
@RestController
class ConsoleStreamController {

    /** A console stream re-authenticates when it reconnects, so a removed member loses it within this long. */
    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(15).toMillis();

    private final SnapshotService snapshots;
    private final StreamHub hub;

    ConsoleStreamController(SnapshotService snapshots, StreamHub hub) {
        this.snapshots = snapshots;
        this.hub = hub;
    }

    @GetMapping(value = "/api/environments/{environment}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('VIEWER')")
    SseEmitter stream(@PathVariable String environment, HttpServletResponse response) {
        UUID environmentId = snapshots.environmentId(environment);
        Streams.prepare(response);
        StreamHub.Subscriber subscriber = hub.subscribe(environmentId, null, TIMEOUT_MILLIS);
        subscriber.offer(snapshots.current(environmentId));
        return subscriber.emitter();
    }
}
