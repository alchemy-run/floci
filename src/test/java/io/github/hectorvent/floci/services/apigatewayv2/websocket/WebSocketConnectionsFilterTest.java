package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebSocketConnectionsFilterTest {

    @Test
    void extractsConnectionIdFromManagementPaths() {
        assertEquals("abc", WebSocketConnectionsFilter.extractConnectionId("/@connections/abc"));
        assertEquals("abc", WebSocketConnectionsFilter.extractConnectionId("/test/@connections/abc"));
        assertEquals("abc", WebSocketConnectionsFilter.extractConnectionId(
                "/execute-api/apiid/test/@connections/abc"));
        assertEquals("x%20y", WebSocketConnectionsFilter.extractConnectionId("/@connections/x%20y"));
        assertEquals("abc", WebSocketConnectionsFilter.extractConnectionId(
                "/execute-api/apiid/test/%40connections/abc"));
        assertNull(WebSocketConnectionsFilter.extractConnectionId("/execute-api/apiid/test/echo"));
        assertNull(WebSocketConnectionsFilter.extractConnectionId("/ws/apiid/test"));
    }
}
