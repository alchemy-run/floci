package io.github.hectorvent.floci.services.socialmessaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialMessagingRoutingFilterTest {

    private static final String SOCIAL_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/social-messaging/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesSocialMessagingCredentialScope() {
        assertTrue(SocialMessagingRoutingFilter.isSocialMessaging(SOCIAL_AUTH));
        assertFalse(SocialMessagingRoutingFilter.isSocialMessaging(S3_AUTH));
        assertFalse(SocialMessagingRoutingFilter.isSocialMessaging(null));
        assertFalse(SocialMessagingRoutingFilter.isSocialMessaging(""));
    }

    @Test
    void prefixesWhatsAppAndTagPaths() {
        assertEquals(
                "/aws-social-messaging/v1/whatsapp/waba/list",
                SocialMessagingRoutingFilter.rewritePath("/v1/whatsapp/waba/list"));
        assertEquals(
                "/aws-social-messaging/v1/whatsapp/waba/details",
                SocialMessagingRoutingFilter.rewritePath("/v1/whatsapp/waba/details"));
        assertEquals(
                "/aws-social-messaging/v1/tags/list",
                SocialMessagingRoutingFilter.rewritePath("/v1/tags/list"));
        assertEquals(
                "/aws-social-messaging/v1/tags/tag-resource",
                SocialMessagingRoutingFilter.rewritePath("/v1/tags/tag-resource"));
    }

    @Test
    void leavesAlreadyPrefixedPathsAlone() {
        assertEquals(
                "/aws-social-messaging/v1/whatsapp/waba/list",
                SocialMessagingRoutingFilter.rewritePath("/aws-social-messaging/v1/whatsapp/waba/list"));
    }
}
