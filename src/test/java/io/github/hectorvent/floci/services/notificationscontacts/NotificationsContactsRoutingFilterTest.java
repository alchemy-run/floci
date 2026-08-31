package io.github.hectorvent.floci.services.notificationscontacts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationsContactsRoutingFilterTest {

    private static final String CONTACTS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/notifications-contacts/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/s3/aws4_request";

    @Test
    void recognizesNotificationsContactsCredentialScope() {
        assertTrue(NotificationsContactsRoutingFilter.isNotificationsContacts(CONTACTS_AUTH));
        assertFalse(NotificationsContactsRoutingFilter.isNotificationsContacts(S3_AUTH));
        assertFalse(NotificationsContactsRoutingFilter.isNotificationsContacts(null));
        assertFalse(NotificationsContactsRoutingFilter.isNotificationsContacts(""));
    }

    @Test
    void prefixesEmailContactPathsAndStripsTrailingSlash() {
        assertEquals("/aws-notifications-contacts/emailcontacts",
                NotificationsContactsRoutingFilter.rewritePath("/emailcontacts"));
        assertEquals("/aws-notifications-contacts/emailcontacts",
                NotificationsContactsRoutingFilter.rewritePath("/emailcontacts/"));
        assertEquals("/aws-notifications-contacts/2022-09-19/emailcontacts",
                NotificationsContactsRoutingFilter.rewritePath("/2022-09-19/emailcontacts"));
        assertEquals("/aws-notifications-contacts/2022-10-31/emailcontacts/arn/activate/send",
                NotificationsContactsRoutingFilter.rewritePath(
                        "/2022-10-31/emailcontacts/arn/activate/send"));
        assertEquals("/aws-notifications-contacts/emailcontacts/arn/activate/000000",
                NotificationsContactsRoutingFilter.rewritePath(
                        "/emailcontacts/arn/activate/000000"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:notifications-contacts::000000000000:emailcontact/abc",
                NotificationsContactsRoutingFilter.rewritePath(
                        "/tags/arn:aws:notifications-contacts::000000000000:emailcontact/abc"));
        assertEquals("/aws-notifications-contacts/emailcontacts",
                NotificationsContactsRoutingFilter.rewritePath(
                        "/aws-notifications-contacts/emailcontacts"));
    }

    @Test
    void leavesFunctionUrlPathsAlone() {
        assertEquals("/lambda-url/abc123/ping",
                NotificationsContactsRoutingFilter.rewritePath("/lambda-url/abc123/ping"));
        assertEquals("/lambda-url/abc123/ping/",
                NotificationsContactsRoutingFilter.rewritePath("/lambda-url/abc123/ping/"));
        assertTrue(NotificationsContactsRoutingFilter.isLambdaUrlHost(
                "abc123.lambda-url.us-east-1.localhost:4566"));
        assertFalse(NotificationsContactsRoutingFilter.isLambdaUrlHost("localhost:4566"));
        assertFalse(NotificationsContactsRoutingFilter.isLambdaUrlHost(null));
    }
}
