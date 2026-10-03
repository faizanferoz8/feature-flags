package dev.flags.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flags.sdk.ServerSentEvents.Event;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServerSentEventsTest {

    private static List<Event> parse(String stream) throws IOException {
        List<Event> events = new ArrayList<>();
        ServerSentEvents.read(new BufferedReader(new StringReader(stream)), events::add, () -> {});
        return events;
    }

    @Test
    void readsNameIdAndData() throws IOException {
        assertThat(parse("event:put\nid:7\ndata:{\"a\":1}\n\n")).containsExactly(new Event("put", "7", "{\"a\":1}"));
    }

    @Test
    void stripsOneLeadingSpaceAndJoinsDataLines() throws IOException {
        assertThat(parse("data: first\ndata:  second\n\n")).containsExactly(new Event("message", null, "first\n second"));
    }

    @Test
    void commentsAreNotEventsButDoCountAsActivity() throws IOException {
        AtomicInteger activity = new AtomicInteger();
        List<Event> events = new ArrayList<>();

        ServerSentEvents.read(
                new BufferedReader(new StringReader(":keep-alive\n\n:keep-alive\n\n")), events::add, activity::incrementAndGet);

        assertThat(events).isEmpty();
        assertThat(activity).hasValue(4);
    }

    @Test
    void anEventNameDoesNotCarryOverToTheNextEvent() throws IOException {
        assertThat(parse("event:put\ndata:1\n\ndata:2\n\n"))
                .containsExactly(new Event("put", null, "1"), new Event("message", null, "2"));
    }

    @Test
    void anEventCutOffBeforeItsBlankLineIsNotDelivered() throws IOException {
        assertThat(parse("event:put\ndata:{\"half\":")).isEmpty();
    }
}
