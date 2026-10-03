package dev.flags.server.sdk;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

final class Streams {

    static final long NO_TIMEOUT = 0L;

    private Streams() {}

    static void prepare(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        // Asks nginx-style proxies not to buffer the response, which would hold events back.
        response.setHeader("X-Accel-Buffering", "no");
    }
}
