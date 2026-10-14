package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElbV2LambdaTargetEventTest {

    private static final String TG_ARN =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/api/0123456789abcdef";

    @Test
    void singleValueEventCarriesTargetGroupArnAndOnlySingleValueMaps() {
        List<Map.Entry<String, String>> headers = List.of(
                Map.entry("Host", "127.0.0.1"),
                Map.entry("X-Forwarded-For", "10.0.0.1"),
                Map.entry("X-Forwarded-For", "10.0.0.2"));

        Map<String, Object> event = ElbV2DataPlane.buildAlbEvent(
                "GET", "/api/hello", "a=1&a=2&b=%20x", headers, new byte[0], TG_ARN, false);

        assertEquals(Map.of("elb", Map.of("targetGroupArn", TG_ARN)), event.get("requestContext"));
        assertEquals("GET", event.get("httpMethod"));
        assertEquals("/api/hello", event.get("path"));
        assertEquals(Map.of("a", "2", "b", "%20x"), event.get("queryStringParameters"));
        assertEquals(Map.of("host", "127.0.0.1", "x-forwarded-for", "10.0.0.2"), event.get("headers"));
        assertFalse(event.containsKey("multiValueHeaders"));
        assertFalse(event.containsKey("multiValueQueryStringParameters"));
        assertEquals("", event.get("body"));
        assertEquals(false, event.get("isBase64Encoded"));
    }

    @Test
    void multiValueEventCarriesOnlyMultiValueMaps() {
        List<Map.Entry<String, String>> headers = List.of(
                Map.entry("Accept", "text/html"),
                Map.entry("Accept", "application/json"));

        Map<String, Object> event = ElbV2DataPlane.buildAlbEvent(
                "GET", "/web/hello", "k=v1&k=v2", headers, new byte[0], TG_ARN, true);

        assertEquals(Map.of("k", List.of("v1", "v2")), event.get("multiValueQueryStringParameters"));
        assertEquals(Map.of("accept", List.of("text/html", "application/json")), event.get("multiValueHeaders"));
        assertFalse(event.containsKey("headers"));
        assertFalse(event.containsKey("queryStringParameters"));
    }

    @Test
    void emptyQueryIsAnEmptyMapAndTextBodyIsPassedThrough() {
        Map<String, Object> event = ElbV2DataPlane.buildAlbEvent(
                "POST", "/api/items", null, List.of(Map.entry("Content-Type", "application/json")),
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8), TG_ARN, false);

        assertEquals(Map.of(), event.get("queryStringParameters"));
        assertEquals("{\"a\":1}", event.get("body"));
        assertEquals(false, event.get("isBase64Encoded"));
    }

    @Test
    void binaryBodyIsBase64Encoded() {
        Map<String, Object> event = ElbV2DataPlane.buildAlbEvent(
                "POST", "/upload", "", List.of(Map.entry("Content-Type", "application/octet-stream")),
                new byte[] {1, 2, 3}, TG_ARN, false);

        assertEquals("AQID", event.get("body"));
        assertEquals(true, event.get("isBase64Encoded"));
    }

    @Test
    void singleValueResponseReadsHeadersAndDropsFramingHeaders() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", 200);
        response.put("headers", Map.of("content-type", "application/json", "content-length", "999"));
        response.put("multiValueHeaders", Map.of("x-ignored", List.of("1")));

        List<Map.Entry<String, String>> headers = ElbV2DataPlane.lambdaResponseHeaders(response, false);

        assertEquals(List.of(Map.entry("content-type", "application/json")), headers);
    }

    @Test
    void multiValueResponseKeepsEveryValue() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", 200);
        response.put("headers", Map.of("x-ignored", "1"));
        response.put("multiValueHeaders", Map.of("set-cookie", List.of("a=1", "b=2")));

        List<Map.Entry<String, String>> headers = ElbV2DataPlane.lambdaResponseHeaders(response, true);

        assertEquals(List.of(Map.entry("set-cookie", "a=1"), Map.entry("set-cookie", "b=2")), headers);
    }

    @Test
    void multiValueAttributeDefaultsToDisabled() {
        TargetGroup tg = new TargetGroup();
        tg.setTargetType("lambda");
        assertFalse(ElbV2DataPlane.multiValueHeadersEnabled(tg));

        tg.getAttributes().put(ElbV2DataPlane.LAMBDA_MULTI_VALUE_HEADERS_ATTRIBUTE, "true");
        assertTrue(ElbV2DataPlane.multiValueHeadersEnabled(tg));
    }

    @Test
    void sharedAddressPrefersTheOnlyListenerWithAnExplicitRuleMatch() {
        List<String> candidates = List.of("lambda-listener", "ecs-listener");

        assertEquals("lambda-listener",
                ElbV2DataPlane.selectSharedAddressListener(List.of("lambda-listener"), candidates, null));
    }

    @Test
    void sharedAddressFallsBackToAffinityOnlyWhileItIsStillRegistered() {
        List<String> candidates = List.of("lambda-listener", "ecs-listener");

        assertEquals("lambda-listener",
                ElbV2DataPlane.selectSharedAddressListener(List.of(), candidates, "lambda-listener"));
        assertNull(ElbV2DataPlane.selectSharedAddressListener(List.of(), candidates, "deleted-listener"));
        assertNull(ElbV2DataPlane.selectSharedAddressListener(List.of(), candidates, null));
    }

    @Test
    void sharedAddressStaysAmbiguousWhenSeveralExplicitRulesMatchOutsideTheAffinity() {
        List<String> candidates = List.of("first", "second", "third");

        assertNull(ElbV2DataPlane.selectSharedAddressListener(List.of("first", "second"), candidates, "third"));
        assertEquals("second",
                ElbV2DataPlane.selectSharedAddressListener(List.of("first", "second"), candidates, "second"));
    }
}
