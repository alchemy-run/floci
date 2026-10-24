package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestShapeCheck.ByPosition;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The walk every service's request check shares, with a message per point so each test shows
 * where the walk refused. The services' own tests pin their measured wording.
 */
class RequestShapeCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RequestShapeModel MODEL = parse("""
            {
              "operations": {},
              "shapes": {
                "Request": {"type": "structure", "members": {
                  "Name": "Text", "Count": "Number", "On": "Flag", "Data": "Bytes", "When": "Time",
                  "Nested": "Request", "Items": "Requests", "Names": "Texts", "Labels": "Labelled"}},
                "Requests": {"type": "list", "member": "Request"},
                "Texts": {"type": "list", "member": "Text"},
                "Labelled": {"type": "map", "value": "Request"},
                "Text": {"type": "string"},
                "Number": {"type": "integer"},
                "Flag": {"type": "boolean"},
                "Bytes": {"type": "blob"},
                "Time": {"type": "timestamp"}
              }
            }
            """);

    private static final RequestShapeCheck.Messages MESSAGES = new RequestShapeCheck.Messages("Refused",
            "object", "list", new ByPosition("structure member", "structure element"),
            new ByPosition("list member", "list element"), new ByPosition("map member", "map element"),
            "%s as %s", true, "boolean text", "blob", Set.of("string", "integer", "boolean", "blob"));

    @Test
    void nullsAndUnknownMembersPass() {
        assertAccepted("{\"Name\": null, \"Nested\": null, \"Items\": [null], \"Other\": [1, {}]}");
    }

    @Test
    void aContainerOfTheWrongKindNamesWhereItWasRead() {
        assertRefused("{\"Nested\": 5}", "structure member");
        assertRefused("{\"Items\": [5]}", "structure element");
        assertRefused("{\"Labels\": {\"a\": 5}}", "structure element");
        assertRefused("{\"Nested\": []}", "list");
        assertRefused("{\"Items\": 5}", "list member");
        assertRefused("{\"Items\": {}}", "object");
        assertRefused("{\"Labels\": 5}", "map member");
        assertRefused("{\"Labels\": []}", "list");
    }

    @Test
    void aScalarOfTheWrongKindIsNamedByItsToken() {
        assertRefused("{\"Name\": 5}", "NUMBER_VALUE as String");
        assertRefused("{\"Name\": 5.5}", "DECIMAL_VALUE as String");
        assertRefused("{\"Name\": true}", "TRUE_VALUE as String");
        assertRefused("{\"Count\": \"5\"}", "STRING_VALUE as Integer");
        assertRefused("{\"On\": 0}", "NUMBER_VALUE as Boolean");
        assertRefused("{\"Name\": {}}", "object");
        assertRefused("{\"Names\": [[\"a\"]]}", "list");
        assertRefused("{\"Data\": 5}", "blob");
    }

    @Test
    void aBooleanReadsTrueOrFalseTextInAnyCase() {
        assertAccepted("{\"On\": \"TRUE\", \"Nested\": {\"On\": \"false\"}}");
        assertRefused("{\"On\": \"yes\"}", "boolean text");
    }

    @Test
    void aScalarTypeTheServiceWasNotMeasuredOnPasses() {
        assertAccepted("{\"When\": \"not a number\", \"Nested\": {\"When\": {}}}");
    }

    @Test
    void aMessageLeftOutAnswersWithTheCodeAlone() {
        RequestShapeCheck.Messages silent = new RequestShapeCheck.Messages("Refused", "object", null,
                ByPosition.same("structure"), ByPosition.same("list"), ByPosition.same("map"), "%s as %s", false,
                "boolean text", null, Set.of("string", "blob"));
        AwsException e = assertThrows(AwsException.class,
                () -> RequestShapeCheck.check(MODEL, "Request", MAPPER.readTree("{\"Nested\": []}"), silent));
        assertEquals("Refused", e.getErrorCode());
        assertNull(e.getMessage());
        AwsException blob = assertThrows(AwsException.class,
                () -> RequestShapeCheck.check(MODEL, "Request", MAPPER.readTree("{\"Data\": 5}"), silent));
        assertEquals("NUMBER_VALUE as Blob", blob.getMessage());
    }

    private static void assertRefused(String body, String message) {
        AwsException e = assertThrows(AwsException.class,
                () -> RequestShapeCheck.check(MODEL, "Request", MAPPER.readTree(body), MESSAGES), body);
        assertEquals("Refused", e.getErrorCode(), body);
        assertEquals(400, e.getHttpStatus(), body);
        assertEquals(message, e.getMessage(), body);
    }

    private static void assertAccepted(String body) {
        assertDoesNotThrow(() -> RequestShapeCheck.check(MODEL, "Request", MAPPER.readTree(body), MESSAGES), body);
    }

    private static RequestShapeModel parse(String json) {
        try {
            return RequestShapeModel.parse(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(json, e);
        }
    }
}
