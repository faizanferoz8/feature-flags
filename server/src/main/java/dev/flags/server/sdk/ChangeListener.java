package dev.flags.server.sdk;

import dev.flags.server.flag.ChangeMessage;
import dev.flags.server.flag.EnvironmentChanges;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Hears about every committed change, whichever instance made it.
 *
 * <p>Each instance holds one dedicated connection that LISTENs on the change channel.
 * When a change commits anywhere, Postgres notifies all of them; each refreshes its own
 * cache and pushes the new snapshot to the streams it holds. No instance needs to know
 * the others exist, and there is no broker to run: the database that made the change
 * durable is also what announces it.
 *
 * <p>Notifications are not queued for a listener that is disconnected. So after every
 * (re)connect the listener assumes it missed something and re-reads everything it has
 * cached.
 */
@Component
public class ChangeListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChangeListener.class);
    private static final int POLL_MILLIS = 500;
    private static final int POLLS_BETWEEN_PINGS = 20;

    private final String url;
    private final String username;
    private final String password;
    private final SnapshotService snapshots;
    private final StreamHub hub;
    private final CountDownLatch firstConnection = new CountDownLatch(1);

    private volatile boolean running;
    private volatile Connection connection;
    private Thread thread;

    ChangeListener(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            SnapshotService snapshots,
            StreamHub hub) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.snapshots = snapshots;
        this.hub = hub;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("flag-change-listener").daemon(true).start(this::run);
        try {
            // Do not start serving until changes can be heard, or the first requests could
            // cache snapshots that nothing would ever invalidate.
            if (!firstConnection.await(15, TimeUnit.SECONDS)) {
                log.warn("Still not listening for flag changes after 15s; continuing to retry in the background");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        long backoffMillis = 250;
        while (running) {
            try (Connection opened = DriverManager.getConnection(url, username, password)) {
                connection = opened;
                try (Statement statement = opened.createStatement()) {
                    statement.execute("LISTEN " + EnvironmentChanges.CHANNEL);
                }
                log.info("Listening for flag changes");
                backoffMillis = 250;
                republishEverything();
                firstConnection.countDown();
                listen(opened);
            } catch (SQLException e) {
                if (running) {
                    log.warn("Lost the change listener's connection, reconnecting in {} ms: {}", backoffMillis, e.getMessage());
                    sleep(backoffMillis);
                    backoffMillis = Math.min(backoffMillis * 2, 10_000);
                }
            }
        }
    }

    private void listen(Connection opened) throws SQLException {
        PGConnection postgres = opened.unwrap(PGConnection.class);
        int quietPolls = 0;
        while (running) {
            PGNotification[] notifications = postgres.getNotifications(POLL_MILLIS);
            if (notifications == null || notifications.length == 0) {
                // A connection that died quietly looks exactly like one with no news.
                if (++quietPolls >= POLLS_BETWEEN_PINGS) {
                    quietPolls = 0;
                    try (Statement ping = opened.createStatement()) {
                        ping.execute("select 1");
                    }
                }
                continue;
            }
            quietPolls = 0;
            for (PGNotification notification : notifications) {
                handle(notification.getParameter());
            }
        }
    }

    private void handle(String payload) {
        try {
            ChangeMessage message = ChangeMessage.decode(payload);
            switch (message.kind()) {
                case SNAPSHOT -> hub.publish(snapshots.refresh(message.tenantId(), message.subjectId()));
                case KEY_REVOKED -> hub.disconnectKey(message.subjectId());
            }
        } catch (RuntimeException e) {
            // One bad message must not stop the listener hearing the next.
            log.error("Could not act on change message '{}'", payload, e);
        }
    }

    private void republishEverything() {
        try {
            snapshots.refreshAll().forEach(hub::publish);
        } catch (RuntimeException e) {
            log.error("Could not refresh cached snapshots after reconnecting", e);
        }
    }

    @Override
    public void stop() {
        running = false;
        Connection open = connection;
        if (open != null) {
            try {
                open.close();
            } catch (SQLException e) {
                log.debug("Closing the listener connection failed", e);
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
