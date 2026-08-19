package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebSocketHandlerRoutingTest {

    @Test
    void parsePathStyle_extractsApiIdAndStage() {
        assertArrayEquals(new String[] {"abc123", "test"},
                WebSocketHandler.parsePathStyle("/ws/abc123/test"));
        assertArrayEquals(new String[] {"abc123", "prod"},
                WebSocketHandler.parsePathStyle("/ws/abc123/prod/extra"));
    }

    @Test
    void parsePathStyle_rejectsIncompletePaths() {
        assertNull(WebSocketHandler.parsePathStyle("/ws/abc123"));
        assertNull(WebSocketHandler.parsePathStyle("/ws/"));
        assertNull(WebSocketHandler.parsePathStyle("/test"));
        assertNull(WebSocketHandler.parsePathStyle(null));
    }
}
