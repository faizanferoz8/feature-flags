package dev.flags.server.audit;

import dev.flags.server.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "audit_events")
public class AuditEvent extends TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_email", nullable = false)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Column(nullable = false)
    private String target;

    private String environment;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String before;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String after;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AuditEvent() {}

    AuditEvent(
            UUID actorId,
            String actorEmail,
            AuditAction action,
            String target,
            String environment,
            String before,
            String after) {
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.target = target;
        this.environment = environment;
        this.before = before;
        this.after = after;
    }

    public Long getId() {
        return id;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getBefore() {
        return before;
    }

    public String getAfter() {
        return after;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
