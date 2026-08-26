package io.github.hectorvent.floci.services.ivschat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ivschat.model.LoggingConfiguration;
import io.github.hectorvent.floci.services.ivschat.model.Room;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Data-plane operations used by Alchemy {@code Bindings.test.ts}: CreateChatToken
 * honors sessionDurationInMinutes, SendEvent / DeleteMessage return ids, and
 * review fallback ALLOW / DENY matches the websocket ERROR 406 contract.
 */
class IvsChatServiceBindingsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IvsChatService service = new IvsChatService(
            new InMemoryStorage<String, Room>(),
            new InMemoryStorage<String, LoggingConfiguration>(),
            new RegionResolver("us-east-1", "000000000000"),
            null,
            mapper);

    @Test
    void createChatTokenHonorsSessionDurationInMinutes() throws Exception {
        Room room = service.createRoom("us-east-1", mapper.readTree("{\"name\":\"token-room\"}"));
        ObjectNode request = mapper.createObjectNode();
        request.put("roomIdentifier", room.getArn());
        request.put("userId", "alchemy-test-user");
        request.putArray("capabilities").add("SEND_MESSAGE");
        request.put("sessionDurationInMinutes", 30);

        IvsChatService.ChatToken token = service.createChatToken("us-east-1", request);
        assertTrue(token.token().startsWith("ivschat."));
        assertTrue(token.token().length() > 8);
        Instant session = Instant.parse(token.sessionExpirationTime());
        long minutes = Duration.between(Instant.now(), session).toMinutes();
        assertTrue(minutes > 20 && minutes < 40, "session duration was " + minutes + " minutes");

        IvsChatService.ParsedChatToken parsed = service.parseChatToken(token.token());
        assertEquals(room.getArn(), parsed.roomArn());
        assertEquals("alchemy-test-user", parsed.userId());
        assertTrue(parsed.capabilities().contains("SEND_MESSAGE"));
    }

    @Test
    void sendEventAndDeleteMessageReturnIds() throws Exception {
        Room room = service.createRoom("us-east-1", mapper.readTree("{\"name\":\"event-room\"}"));
        ObjectNode send = mapper.createObjectNode();
        send.put("roomIdentifier", room.getArn());
        send.put("eventName", "app:announcement");
        send.putObject("attributes").put("note", "hello");
        String eventId = service.sendEvent("us-east-1", send);
        assertFalse(eventId.isBlank());

        ObjectNode delete = mapper.createObjectNode();
        delete.put("roomIdentifier", room.getArn());
        delete.put("id", eventId);
        delete.put("reason", "moderated by alchemy test");
        assertEquals(eventId, service.deleteMessage("us-east-1", delete));
    }

    @Test
    void reviewWithoutHandlerAllowsOriginalContent() {
        Room room = new Room();
        IvsChatService.MessageReview review = service.reviewMessage(
                room, "hello moderators", "mid", "alchemy-test-user",
                Map.of(), Map.of(), "127.0.0.1");
        assertFalse(review.denied());
        assertEquals("hello moderators", review.content());
    }

    @Test
    void reviewFallbackDenyProducesDeniedVerdict() {
        Room room = new Room();
        room.setMessageReviewHandlerUri("arn:aws:lambda:us-east-1:000000000000:function:missing-review");
        room.setMessageReviewHandlerFallbackResult("DENY");
        IvsChatService.MessageReview review = service.reviewMessage(
                room, "please deny-me now", "mid", "alchemy-test-user",
                Map.of(), Map.of(), "127.0.0.1");
        assertTrue(review.denied());
    }

    @Test
    void reviewHandlerAllowModifiesContent() {
        String uri = "arn:aws:lambda:us-east-1:000000000000:function:review";
        IvsChatService reviewing = serviceWithLambda(uri,
                "{\"ReviewResult\":\"ALLOW\",\"Content\":\"hello moderators [reviewed]\"}");
        Room room = roomWithHandler(uri, "ALLOW");
        IvsChatService.MessageReview review = reviewing.reviewMessage(
                room, "hello moderators", "mid", "alchemy-test-user",
                Map.of(), Map.of(), "127.0.0.1");
        assertFalse(review.denied());
        assertEquals("hello moderators [reviewed]", review.content());
    }

    @Test
    void reviewHandlerDenySurfacesReasonAttribute() {
        String uri = "arn:aws:lambda:us-east-1:000000000000:function:review";
        IvsChatService reviewing = serviceWithLambda(uri,
                "{\"ReviewResult\":\"DENY\",\"Attributes\":{\"Reason\":\"alchemy-moderated\"}}");
        Room room = roomWithHandler(uri, "ALLOW");
        IvsChatService.MessageReview review = reviewing.reviewMessage(
                room, "please deny-me now", "mid", "alchemy-test-user",
                Map.of(), Map.of(), "127.0.0.1");
        assertTrue(review.denied());
        assertEquals("alchemy-moderated", review.attributes().get("Reason"));
    }

    @Test
    void disconnectUserIsIdempotentWhenNoSessionsExist() throws Exception {
        Room room = service.createRoom("us-east-1", mapper.readTree("{\"name\":\"disconnect-room\"}"));
        ObjectNode request = mapper.createObjectNode();
        request.put("roomIdentifier", room.getArn());
        request.put("userId", "alchemy-test-user");
        request.put("reason", "alchemy test");
        service.disconnectUser("us-east-1", request);
    }

    @Test
    void createChatTokenOnUnknownRoomIsResourceNotFound() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("roomIdentifier", "arn:aws:ivschat:us-east-1:000000000000:room/AbCdEfGh1234");
        request.put("userId", "alchemy-test-user");
        try {
            service.createChatToken("us-east-1", request);
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            assertEquals("ResourceNotFoundException", e.jsonType());
            return;
        }
        throw new AssertionError("expected ResourceNotFoundException");
    }

    private static Room roomWithHandler(String uri, String fallback) {
        Room room = new Room();
        room.setArn("arn:aws:ivschat:us-east-1:000000000000:room/AbCdEfGh1234");
        room.setMessageReviewHandlerUri(uri);
        room.setMessageReviewHandlerFallbackResult(fallback);
        return room;
    }

    @SuppressWarnings("unchecked")
    private IvsChatService serviceWithLambda(String uri, String payload) {
        Instance<LambdaService> instance = Mockito.mock(Instance.class);
        LambdaService lambda = Mockito.mock(LambdaService.class);
        InvokeResult result = new InvokeResult();
        result.setStatusCode(200);
        result.setPayload(payload.getBytes(StandardCharsets.UTF_8));
        Mockito.when(instance.isResolvable()).thenReturn(true);
        Mockito.when(instance.get()).thenReturn(lambda);
        Mockito.when(lambda.invoke(eq("us-east-1"), eq(uri), any(), eq(InvocationType.RequestResponse)))
                .thenReturn(result);
        return new IvsChatService(
                new InMemoryStorage<String, Room>(),
                new InMemoryStorage<String, LoggingConfiguration>(),
                new RegionResolver("us-east-1", "000000000000"),
                instance,
                mapper);
    }
}
