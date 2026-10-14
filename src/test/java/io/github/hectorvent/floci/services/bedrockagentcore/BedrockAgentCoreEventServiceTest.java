package io.github.hectorvent.floci.services.bedrockagentcore;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrockagentcore.BedrockAgentCoreEventService.SessionSummary;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BedrockAgentCoreEventServiceTest {

    private static final String REGION = "us-east-1";
    private static final String MEMORY = "TestMemory-abcdefghij";

    private BedrockAgentCoreEventService service;

    @BeforeEach
    void setUp() {
        service = new BedrockAgentCoreEventService(new InMemoryStorage<>(), mock(BedrockAgentCoreMemoryService.class));
    }

    private void event(String actorId, String sessionId, double timestamp) {
        service.createEvent(MEMORY, actorId, sessionId, timestamp, List.of(), true, null, REGION);
    }

    @Test
    void listActorsReturnsEachActorWithEventsOnce() {
        event("actor-b", "s1", 1d);
        event("actor-a", "s1", 2d);
        event("actor-a", "s2", 3d);

        PaginatedResult<String> actors = service.listActors(MEMORY, null, null, REGION);
        assertEquals(List.of("actor-a", "actor-b"), actors.items());
        assertNull(actors.nextToken());
    }

    @Test
    void listActorsPaginates() {
        event("actor-a", "s", 1d);
        event("actor-b", "s", 1d);
        event("actor-c", "s", 1d);

        PaginatedResult<String> first = service.listActors(MEMORY, 2, null, REGION);
        assertEquals(List.of("actor-a", "actor-b"), first.items());
        assertNotNull(first.nextToken());
        assertEquals(List.of("actor-c"), service.listActors(MEMORY, 2, first.nextToken(), REGION).items());
    }

    @Test
    void listActorsOnAMemoryWithoutEventsIsEmpty() {
        assertTrue(service.listActors(MEMORY, null, null, REGION).items().isEmpty());
    }

    @Test
    void listSessionsReturnsTheActorsSessionsNewestFirst() {
        event("actor-1", "older", 100d);
        event("actor-1", "older", 500d);
        event("actor-1", "newer", 300d);
        event("actor-10", "someone-else", 900d);

        List<SessionSummary> sessions = service.listSessions(MEMORY, "actor-1", null, null, null, REGION).items();
        assertEquals(2, sessions.size());
        assertEquals("newer", sessions.get(0).sessionId());
        assertEquals("older", sessions.get(1).sessionId());
        assertEquals(100d, sessions.get(1).createdAt(), "a session is created by its first event");
        assertEquals("actor-1", sessions.get(0).actorId());
    }

    @Test
    void listSessionsAcceptsOnlyTheHasEventsFilter() {
        event("actor-1", "s", 1d);
        assertEquals(1, service.listSessions(MEMORY, "actor-1", "HAS_EVENTS", null, null, REGION).items().size());
        AwsException e = assertThrows(AwsException.class,
                () -> service.listSessions(MEMORY, "actor-1", "NO_EVENTS", null, null, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void malformedMemoryIdIsAValidationError() {
        AwsException e = assertThrows(AwsException.class, () -> service.listActors("bad", null, null, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }
}
