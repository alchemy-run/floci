package io.github.hectorvent.floci.services.ivschat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IvsChatWebSocketHandlerTest {

    @Test
    void isIvsChatHostMatchesEdgeMessagingEndpoint() {
        assertTrue(IvsChatWebSocketHandler.isIvsChatHost("edge.ivschat.us-east-1.amazonaws.com"));
        assertTrue(IvsChatWebSocketHandler.isIvsChatHost("edge.ivschat.us-west-2.amazonaws.com:443"));
    }

    @Test
    void isIvsChatHostDoesNotMatchFunctionUrlOrLoopback() {
        assertFalse(IvsChatWebSocketHandler.isIvsChatHost(
                "0e3492f26e0637dfbe58cbdaf3e30561.lambda-url.us-east-1.localhost:4566"));
        assertFalse(IvsChatWebSocketHandler.isIvsChatHost("127.0.0.1:4566"));
        assertFalse(IvsChatWebSocketHandler.isIvsChatHost("localhost"));
        assertFalse(IvsChatWebSocketHandler.isIvsChatHost(null));
        assertFalse(IvsChatWebSocketHandler.isIvsChatHost(""));
    }

    @Test
    void selectedProtocolTakesTheFirstSubprotocol() {
        assertEquals("ivschat.abc", IvsChatWebSocketHandler.selectedProtocol("ivschat.abc"));
        assertEquals("ivschat.abc", IvsChatWebSocketHandler.selectedProtocol("ivschat.abc, chat"));
        assertNull(IvsChatWebSocketHandler.selectedProtocol(null));
        assertNull(IvsChatWebSocketHandler.selectedProtocol("  "));
    }
}
