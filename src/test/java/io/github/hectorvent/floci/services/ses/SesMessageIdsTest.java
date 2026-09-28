package io.github.hectorvent.floci.services.ses;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesMessageIdsTest {

    @Test
    void generatedIdsHaveTheSesShape() {
        String id = SesMessageIds.newMessageId();
        assertTrue(id.matches("01000[0-9a-f]{11}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-000000"), id);
        assertTrue(SesMessageIds.isWellFormed(id), id);
        assertNotEquals(id, SesMessageIds.newMessageId());
    }

    @Test
    void wellFormedAcceptsOnlyTheSesShape() {
        assertTrue(SesMessageIds.isWellFormed("0000000000000000-00000000-0000-0000-0000-000000000000-000000"));
        assertTrue(SesMessageIds.isWellFormed("0100018C7F3B1F2A-9F1C2D3E-4B5A-6C7D-8E9F-0A1B2C3D4E5F-000000"));
        assertFalse(SesMessageIds.isWellFormed(null));
        assertFalse(SesMessageIds.isWellFormed(""));
        assertFalse(SesMessageIds.isWellFormed("not-a-message-id"));
        assertFalse(SesMessageIds.isWellFormed("00000000-0000-0000-0000-000000000000"));
        assertFalse(SesMessageIds.isWellFormed("0000000000000000-00000000-0000-0000-0000-000000000000-00000a"));
    }
}
