package dev.flags.server.sdk;

import dev.flags.core.Snapshot;
import java.util.UUID;

/**
 * A snapshot ready to hand out: serialised once, however many SDKs ask for it.
 *
 * @param etag the revision, quoted; a client that already has it is answered with 304
 */
public record Published(UUID tenantId, UUID environmentId, Snapshot snapshot, String json, String etag) {

    public long revision() {
        return snapshot.revision();
    }
}
