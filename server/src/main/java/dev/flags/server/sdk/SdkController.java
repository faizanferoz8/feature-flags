package dev.flags.server.sdk;

import dev.flags.core.Evaluation;
import dev.flags.core.EvaluationContext;
import dev.flags.core.FlagDefinition;
import dev.flags.core.FlagEvaluator;
import dev.flags.server.security.ApiKeyPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * What applications talk to. The API key decides the tenant and the environment, so
 * none of these endpoints takes either as a parameter: there is nothing for a caller to
 * get wrong, or to tamper with.
 */
@RestController
@RequestMapping("/sdk")
class SdkController {

    record EvaluateRequest(@NotNull EvaluationContext context) {}

    record EvaluateResponse(String environment, long revision, Map<String, Evaluation> flags) {}

    private final SnapshotService snapshots;
    private final StreamHub hub;

    SdkController(SnapshotService snapshots, StreamHub hub) {
        this.snapshots = snapshots;
        this.hub = hub;
    }

    /** The whole rule set, for SDKs that evaluate locally. Polling clients send If-None-Match. */
    @GetMapping(value = "/flags", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> flags(@AuthenticationPrincipal ApiKeyPrincipal key, WebRequest request) {
        Published published = snapshots.current(key.environmentId());
        if (request.checkNotModified(published.etag())) {
            return null;
        }
        return ResponseEntity.ok()
                .eTag(published.etag())
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(published.json());
    }

    /** The current snapshot at once, then a new one after every change. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal ApiKeyPrincipal key, HttpServletResponse response) {
        Streams.prepare(response);
        StreamHub.Subscriber subscriber = hub.subscribe(key.environmentId(), key.keyId(), Streams.NO_TIMEOUT);
        subscriber.offer(snapshots.current(key.environmentId()));
        return subscriber.emitter();
    }

    /** Server-side evaluation, for clients that should not hold the rules themselves. */
    @PostMapping("/evaluate")
    EvaluateResponse evaluate(@AuthenticationPrincipal ApiKeyPrincipal key, @Valid @RequestBody EvaluateRequest request) {
        Published published = snapshots.current(key.environmentId());
        Map<String, Evaluation> results = new LinkedHashMap<>();
        for (FlagDefinition flag : published.snapshot().flags()) {
            results.put(flag.key(), FlagEvaluator.evaluate(flag, request.context()));
        }
        return new EvaluateResponse(published.snapshot().environment(), published.revision(), results);
    }
}
