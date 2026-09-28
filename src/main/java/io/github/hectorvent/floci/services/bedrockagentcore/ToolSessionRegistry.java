package io.github.hectorvent.floci.services.bedrockagentcore;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.bedrockagentcore.model.ToolSession;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Session bookkeeping shared by the code interpreter and browser services: identity, expiry,
 * liveness, and the READY to TERMINATED transition. A session is READY while its container runs;
 * stopping it, reaching its {@code sessionTimeoutSeconds}, or losing its container (a restart, a
 * state reset, or the process inside dying) makes it TERMINATED for good.
 */
final class ToolSessionRegistry {

    static final String READY = "READY";
    static final String TERMINATED = "TERMINATED";
    private static final int DEFAULT_PAGE = 10;
    private static final int MAX_PAGE = 100;
    private static final long TERMINATED_RETENTION_MILLIS = Duration.ofDays(1).toMillis();

    private final String kind;
    private final StorageBackend<String, ToolSession> store;
    private final BedrockAgentCoreSandboxRuntime runtime;

    ToolSessionRegistry(String kind, StorageBackend<String, ToolSession> store,
                        BedrockAgentCoreSandboxRuntime runtime) {
        this.kind = kind;
        this.store = store;
        this.runtime = runtime;
    }

    /** AgentCore session ids are up to 40 alphanumerics. */
    static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    ToolSession newSession(String region, String identifier, String name, int timeoutSeconds, String clientToken) {
        long now = System.currentTimeMillis();
        ToolSession session = new ToolSession();
        session.setRegion(region);
        session.setIdentifier(identifier);
        session.setSessionId(newSessionId());
        session.setName(name);
        session.setStatus(READY);
        session.setCreatedAtMillis(now);
        session.setLastUpdatedAtMillis(now);
        session.setSessionTimeoutSeconds(timeoutSeconds);
        session.setClientToken(clientToken);
        return session;
    }

    void save(ToolSession session) {
        store.put(key(session.getRegion(), session.getIdentifier(), session.getSessionId()), session);
    }

    Optional<ToolSession> byClientToken(String region, String identifier, String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            return Optional.empty();
        }
        return store.scan(k -> k.startsWith(prefix(region, identifier))).stream()
                .filter(session -> clientToken.equals(session.getClientToken()))
                .findFirst();
    }

    /** The session, with expiry and container liveness applied first. */
    ToolSession require(String region, String identifier, String sessionId) {
        if (sessionId == null || !sessionId.matches("[0-9a-zA-Z]{1,40}")) {
            throw new AwsException("ValidationException", "sessionId must match [0-9a-zA-Z]{1,40}", 400);
        }
        ToolSession session = store.get(key(region, identifier, sessionId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Session not found: " + sessionId, 404));
        if (READY.equals(session.getStatus())
                && (expired(session, System.currentTimeMillis()) || !runtime.isLive(session.getContainerId()))) {
            terminate(session);
        }
        return session;
    }

    /** The session, which must still be READY. */
    ToolSession requireReady(String region, String identifier, String sessionId) {
        ToolSession session = require(region, identifier, sessionId);
        if (!READY.equals(session.getStatus())) {
            throw new AwsException("ConflictException",
                    "The " + kind + " session " + sessionId + " is " + session.getStatus(), 409);
        }
        return session;
    }

    /** Removes the session's container and records the session as TERMINATED. */
    ToolSession terminate(ToolSession session) {
        runtime.remove(session.getContainerId());
        session.setStatus(TERMINATED);
        session.setLastUpdatedAtMillis(System.currentTimeMillis());
        save(session);
        return session;
    }

    /** Stops a READY session; stopping one that is already TERMINATED conflicts. */
    ToolSession stop(String region, String identifier, String sessionId) {
        return terminate(requireReady(region, identifier, sessionId));
    }

    /**
     * Terminates READY sessions that outlived their timeout or whose container this process no
     * longer tracks, and forgets sessions that have been TERMINATED for over a day.
     */
    void sweep() {
        long now = System.currentTimeMillis();
        for (ToolSession session : store.scan(k -> true)) {
            if (READY.equals(session.getStatus())) {
                if (expired(session, now) || !runtime.isTracked(session.getContainerId())) {
                    terminate(session);
                }
            } else if (now - session.getLastUpdatedAtMillis() > TERMINATED_RETENTION_MILLIS) {
                store.delete(key(session.getRegion(), session.getIdentifier(), session.getSessionId()));
            }
        }
    }

    long activeCount() {
        return store.scan(k -> true).stream().filter(session -> READY.equals(session.getStatus())).count();
    }

    PaginatedResult<ToolSession> list(String region, String identifier, String status,
                                      Integer maxResults, String nextToken) {
        if (status != null && !READY.equals(status) && !TERMINATED.equals(status)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + status + "' at 'status' failed to satisfy "
                            + "constraint: Member must satisfy enum value set: [READY, TERMINATED]", 400);
        }
        List<ToolSession> sessions = store.scan(k -> k.startsWith(prefix(region, identifier))).stream()
                .filter(session -> status == null || status.equals(session.getStatus()))
                .toList();
        return Pagination.paginate(sessions, ToolSessionRegistry::newestFirst, maxResults, nextToken,
                DEFAULT_PAGE, MAX_PAGE, "ValidationException");
    }

    static int timeoutSeconds(Integer requested, int defaultSeconds) {
        int timeout = requested == null ? defaultSeconds : requested;
        if (timeout < 1 || timeout > 28_800) {
            throw new AwsException("ValidationException",
                    "sessionTimeoutSeconds must be between 1 and 28800", 400);
        }
        return timeout;
    }

    private static boolean expired(ToolSession session, long now) {
        return now >= session.getCreatedAtMillis() + session.getSessionTimeoutSeconds() * 1000L;
    }

    private static String newestFirst(ToolSession session) {
        return String.format("%019d", Long.MAX_VALUE - session.getCreatedAtMillis()) + "#" + session.getSessionId();
    }

    private static String prefix(String region, String identifier) {
        return region + "::" + identifier + "::";
    }

    private static String key(String region, String identifier, String sessionId) {
        return prefix(region, identifier) + sessionId;
    }
}
