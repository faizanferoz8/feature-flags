package dev.flags.server.audit;

import dev.flags.server.security.CurrentUser;
import dev.flags.server.security.UserAuthentication;
import dev.flags.server.web.Json;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Records who changed what. An event is written in the same transaction as the change
 * it describes, so the two commit or roll back together: there is no change without its
 * record and no record of a change that did not happen.
 */
@Service
public class AuditService {

    public record AuditView(
            long id,
            String actor,
            AuditAction action,
            String target,
            String environment,
            JsonNode before,
            JsonNode after,
            Instant at) {}

    public record AuditPage(List<AuditView> items, int page, int size, long total) {}

    private final AuditEventRepository events;

    AuditService(AuditEventRepository events) {
        this.events = events;
    }

    /** Records a change made by whoever is signed in on this request. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String target, String environment, Object before, Object after) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof UserAuthentication user)) {
            throw new IllegalStateException("An audited change needs a signed-in user");
        }
        record(user.getPrincipal(), action, target, environment, before, after);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            CurrentUser actor, AuditAction action, String target, String environment, Object before, Object after) {
        events.save(new AuditEvent(
                actor.id(), actor.email(), action, target, environment, toJson(before), toJson(after)));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('VIEWER')")
    public AuditPage list(String target, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100));
        Page<AuditEvent> found = target == null || target.isBlank()
                ? events.findAllByOrderByIdDesc(request)
                : events.findByTargetOrderByIdDesc(target, request);
        List<AuditView> items = found.getContent().stream()
                .map(event -> new AuditView(
                        event.getId(),
                        event.getActorEmail(),
                        event.getAction(),
                        event.getTarget(),
                        event.getEnvironment(),
                        fromJson(event.getBefore()),
                        fromJson(event.getAfter()),
                        event.getCreatedAt()))
                .toList();
        return new AuditPage(items, request.getPageNumber(), request.getPageSize(), found.getTotalElements());
    }

    private static String toJson(Object value) {
        return value == null ? null : Json.STORED.writeValueAsString(value);
    }

    private static JsonNode fromJson(String json) {
        return json == null ? null : Json.STORED.readTree(json);
    }
}
