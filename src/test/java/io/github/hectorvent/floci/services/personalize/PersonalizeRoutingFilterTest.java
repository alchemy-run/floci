package io.github.hectorvent.floci.services.personalize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalizeRoutingFilterTest {

    private static final String PERSONALIZE_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/personalize/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesPersonalizeCredentialScope() {
        assertTrue(PersonalizeRoutingFilter.isPersonalize(PERSONALIZE_AUTH));
        assertFalse(PersonalizeRoutingFilter.isPersonalize(S3_AUTH));
        assertFalse(PersonalizeRoutingFilter.isPersonalize(null));
        assertFalse(PersonalizeRoutingFilter.isPersonalize(""));
    }

    @Test
    void recognizesPersonalizeEventsAndRuntimeHosts() {
        assertTrue(PersonalizeRoutingFilter.isPersonalizeHost(
                "personalize-events.us-east-1.amazonaws.com"));
        assertTrue(PersonalizeRoutingFilter.isPersonalizeHost(
                "personalize-runtime.us-west-2.amazonaws.com:443"));
        assertTrue(PersonalizeRoutingFilter.isPersonalizeHost(
                "personalize-events-fips.us-east-1.amazonaws.com"));
        assertTrue(PersonalizeRoutingFilter.isPersonalizeHost(
                "personalize.us-east-1.amazonaws.com"));
        assertFalse(PersonalizeRoutingFilter.isPersonalizeHost("s3.us-east-1.amazonaws.com"));
        assertFalse(PersonalizeRoutingFilter.isPersonalizeHost("localhost:4566"));
        assertFalse(PersonalizeRoutingFilter.isPersonalizeHost(null));
    }

    @Test
    void prefixesEventAndRuntimePathsAndStripsTrailingSlash() {
        assertEquals("/personalize-events/events",
                PersonalizeRoutingFilter.rewritePath("/events"));
        assertEquals("/personalize-events/events",
                PersonalizeRoutingFilter.rewritePath("/events/"));
        assertEquals("/personalize-events/items",
                PersonalizeRoutingFilter.rewritePath("/items"));
        assertEquals("/personalize-events/users",
                PersonalizeRoutingFilter.rewritePath("/users"));
        assertEquals("/personalize-events/actions",
                PersonalizeRoutingFilter.rewritePath("/actions"));
        assertEquals("/personalize-events/action-interactions",
                PersonalizeRoutingFilter.rewritePath("/action-interactions"));
        assertEquals("/personalize-runtime/recommendations",
                PersonalizeRoutingFilter.rewritePath("/recommendations"));
        assertEquals("/personalize-runtime/personalize-ranking",
                PersonalizeRoutingFilter.rewritePath("/personalize-ranking"));
        assertEquals("/personalize-runtime/action-recommendations",
                PersonalizeRoutingFilter.rewritePath("/action-recommendations"));
    }

    @Test
    void leavesAlreadyPrefixedPathsAndFunctionUrlsAlone() {
        assertEquals("/personalize-events/events",
                PersonalizeRoutingFilter.rewritePath("/personalize-events/events"));
        assertEquals("/personalize-runtime/recommendations",
                PersonalizeRoutingFilter.rewritePath("/personalize-runtime/recommendations"));
        assertEquals(
                "/lambda-url/abc123/bindings",
                PersonalizeRoutingFilter.rewritePath("/lambda-url/abc123/bindings"));
        assertEquals(
                "/lambda-url/abc123/bindings/",
                PersonalizeRoutingFilter.rewritePath("/lambda-url/abc123/bindings/"));
        assertEquals("/bindings", PersonalizeRoutingFilter.rewritePath("/bindings"));
    }

    @Test
    void lambdaUrlHostsAreNotPersonalize() {
        assertTrue(PersonalizeRoutingFilter.isLambdaUrlHost(
                "abc123.lambda-url.us-east-1.localhost:4566"));
        assertFalse(PersonalizeRoutingFilter.isLambdaUrlHost("localhost:4566"));
    }
}
