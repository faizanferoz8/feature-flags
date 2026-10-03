package dev.flags.server.flag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChangeMessageTest {

    @Test
    void survivesTheTripThroughTheNotificationChannel() {
        ChangeMessage snapshot = ChangeMessage.snapshot(UUID.randomUUID(), UUID.randomUUID());
        ChangeMessage revoked = ChangeMessage.revoked(UUID.randomUUID());

        assertThat(ChangeMessage.decode(snapshot.encode())).isEqualTo(snapshot);
        assertThat(ChangeMessage.decode(revoked.encode())).isEqualTo(revoked);
    }

    @Test
    void fitsInsideThePayloadLimitOfNotify() {
        // Postgres rejects NOTIFY payloads of 8000 bytes or more.
        assertThat(ChangeMessage.snapshot(UUID.randomUUID(), UUID.randomUUID()).encode().length()).isLessThan(100);
    }

    @Test
    void rejectsWhatItCannotRead() {
        assertThatIllegalArgumentException().isThrownBy(() -> ChangeMessage.decode("hello"));
        assertThatIllegalArgumentException().isThrownBy(() -> ChangeMessage.decode("UNKNOWN|a|b"));
    }
}
