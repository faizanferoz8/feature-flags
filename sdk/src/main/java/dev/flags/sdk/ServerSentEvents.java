package dev.flags.sdk;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Reads a {@code text/event-stream}, following the parts of the specification this
 * client needs: {@code event}, {@code id} and multi-line {@code data} fields, comments,
 * and a blank line to dispatch.
 */
final class ServerSentEvents {

    record Event(String name, String id, String data) {}

    private ServerSentEvents() {}

    /**
     * Blocks until the stream ends.
     *
     * @param onActivity called for every line received, comments included, so the caller
     *     can tell a quiet stream from a dead one
     */
    static void read(BufferedReader reader, Consumer<Event> onEvent, Runnable onActivity) throws IOException {
        String name = null;
        String id = null;
        StringBuilder data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            onActivity.run();
            if (line.isEmpty()) {
                if (data != null) {
                    onEvent.accept(new Event(name == null ? "message" : name, id, data.toString()));
                }
                name = null;
                data = null;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> name = value;
                case "id" -> id = value;
                case "data" -> {
                    if (data == null) {
                        data = new StringBuilder(value);
                    } else {
                        data.append('\n').append(value);
                    }
                }
                default -> {
                    // "retry" and unknown fields are ignored.
                }
            }
        }
    }
}
