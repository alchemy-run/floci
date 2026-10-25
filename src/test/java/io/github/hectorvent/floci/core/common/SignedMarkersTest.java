package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SignedMarkersTest {

    private final SignedMarkers markers = new SignedMarkers();

    @Test
    void anIssuedMarkerNamesItsCursor() {
        assertEquals("role.with.dots", markers.cursorOf(markers.issue("role.with.dots")));
    }

    @Test
    void aMarkerIssuedByAnotherInstanceIsNotRecognised() {
        assertNull(markers.cursorOf(new SignedMarkers().issue("role-b")));
    }

    @Test
    void anUnsignedOrUndecodableMarkerIsNotRecognised() {
        assertNull(markers.cursorOf(Base64Url.encode("role-b")));
        assertNull(markers.cursorOf(Base64Url.encode("role-b.forged")));
        assertNull(markers.cursorOf("not base64!"));
    }
}
