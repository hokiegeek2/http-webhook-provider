package org.hokiegeek2.keycloakhttpwebhookprovider.provider;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerTransaction;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class KeycloakHttpWebhookProvider implements EventListenerProvider {

    private static final Logger log = Logger.getLogger(KeycloakHttpWebhookProvider.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * Hard operational limits:
     * - Bounded thread pool prevents unbounded thread growth if webhook target slows/hangs
     * - Bounded queue prevents unbounded memory growth during bursts/outages
     */
    private static final ExecutorService WEBHOOK_EXEC = new ThreadPoolExecutor(
        2, // core threads
        2, // max threads (keep it small; raise cautiously)
        0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(500),
        new ThreadFactory() {
            private int i = 0;
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "kc-webhook-" + (++i));
                t.setDaemon(true);
                return t;
            }
        },
        // When the queue is full, drop to protect Keycloak/node stability
        new ThreadPoolExecutor.DiscardPolicy()
    );

    /**
     * Share a single OkHttpClient across provider instances (providers are typically per-session/per-request).
     * Add timeouts + concurrency caps to prevent stampedes and hung calls.
     */
    private static final Dispatcher DISPATCHER = new Dispatcher();
    static {
        DISPATCHER.setMaxRequests(32);
        DISPATCHER.setMaxRequestsPerHost(8);
    }

    private static final okhttp3.OkHttpClient HTTP = new okhttp3.OkHttpClient.Builder()
        .dispatcher(DISPATCHER)
        .callTimeout(Duration.ofSeconds(5))     // hard cap for the entire call
        .connectTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(3))
        .writeTimeout(Duration.ofSeconds(3))
        .build();

    private final String serverUrl;

    // Defer actual sending until after the KC transaction commits
    private final EventListenerTransaction tx;

    public KeycloakHttpWebhookProvider(KeycloakSession session, String serverUrl) {
        this.serverUrl = serverUrl;

        this.tx = new EventListenerTransaction(
            // Admin events (run after commit)
            (adminEvent, includeRepresentation) -> safeSubmit(() -> {
                try {
                    sendAdminEvent(adminEvent, includeRepresentation);
                } catch (Exception e) {
                    log.error("Failed to POST admin-event webhook", e);
                }
            }),
            // User events (run after commit)
            event -> safeSubmit(() -> {
                try {
                    sendUserEvent(event);
                } catch (Exception e) {
                    log.error("Failed to POST event webhook", e);
                }
            })
        );

        session.getTransactionManager().enlistAfterCompletion(tx);
    }

    @Override
    public void onEvent(Event event) {
        if (event == null || event.getType() == null) return;

        // Filter noisy events
        if (event.getType() == EventType.USER_INFO_REQUEST) {
            return;
        }

        // Enqueue; actual send happens after commit (via tx above)
        tx.addEvent(event);
    }

    @Override
    public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
        // enqueue; actual send happens after commit
        tx.addAdminEvent(adminEvent, includeRepresentation);
    }

    @Override
    public void close() {
        // Provider is per-session; shared HTTP client + shared executor should NOT be closed here.
    }

    private void safeSubmit(Runnable r) {
        try {
            WEBHOOK_EXEC.execute(r);
        } catch (RejectedExecutionException rex) {
            // Queue full (DiscardPolicy may also silently drop). Log at warn to avoid log storms.
            log.warn("Webhook queue full; dropping webhook");
        }
    }

    private void sendUserEvent(Event event) throws IOException {
        // Avoid logging full JSON payloads at INFO under load
        // log.debugf("Sending user event %s userId=%s", event.getType(), event.getUserId());

        String json = MAPPER.writeValueAsString(event);
        sendJson(json);
    }

    private void sendAdminEvent(AdminEvent adminEvent, boolean includeRepresentation) throws IOException {
        ObjectNode node = MAPPER.valueToTree(adminEvent);

        // Only include/parse representation if Keycloak asked us to include it
        if (includeRepresentation
            && adminEvent.getRepresentation() != null
            && !adminEvent.getRepresentation().isBlank()) {

            JsonNode representationNode = MAPPER.readTree(adminEvent.getRepresentation());
            node.replace("representation", representationNode);
        } else {
            node.remove("representation");
        }

        String json = MAPPER.writeValueAsString(node);
        sendJson(json);
    }

    private void sendJson(String jsonString) {
        if (serverUrl == null || serverUrl.isBlank()) {
            log.error("WEBHOOK_URL not set; skipping webhook");
            return;
        }

        Request.Builder rb = new Request.Builder()
            .url(serverUrl)
            .addHeader("User-Agent", "Keycloak Webhook")
            .post(RequestBody.create(jsonString, JSON));

        try (Response response = HTTP.newCall(rb.build()).execute()) {
            if (!response.isSuccessful()) {
                log.warnf("Failed to POST webhook: %s %s", response.code(), response.message());
            }
        } catch (IOException e) {
            log.warn("Failed to POST webhook", e);
        }
    }
}
