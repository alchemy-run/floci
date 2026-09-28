package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudFrontResolvedOriginTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dynamicS3AccessIsRequestScopedAndInheritsUnspecifiedOriginSettings() throws Exception {
        Origin assigned = customOrigin();
        CloudFrontResolvedOrigin resolved = resolve(assigned, """
                {"domainName":"private.s3.us-east-1.amazonaws.com",
                 "originAccessControlConfig":{"enabled":true,"signingBehavior":"always",
                   "signingProtocol":"sigv4","originType":"s3"}}
                """, null);

        assertTrue(CloudFrontRequestRouter.isS3Origin(resolved.origin()));
        assertEquals("private.s3.us-east-1.amazonaws.com", resolved.origin().getDomainName());
        assertEquals("/content", resolved.origin().getOriginPath());
        assertEquals(assigned.getCustomHeaders(), resolved.origin().getCustomHeaders());
        assertEquals(2, resolved.origin().getConnectionAttempts());
        assertEquals(4, resolved.origin().getConnectionTimeout());
        assertEquals(30, resolved.origin().getResponseCompletionTimeout());
        assertTrue(resolved.accessControl().enabled());
        assertEquals("always", resolved.accessControl().signingBehavior());
        assertEquals("sigv4", resolved.accessControl().signingProtocol());
        assertEquals("s3", resolved.accessControl().originType());
        assertNotSame(assigned, resolved.origin());
        assertEquals("placeholder.invalid", assigned.getDomainName());
        assertNull(assigned.getS3OriginConfig());
        assertNotNull(assigned.getCustomOriginConfig());
        assertNull(resolved.origin().getOriginAccessControlId());
    }

    @Test
    void omittedAccessSettingsInheritTheConfiguredControlWithoutMutatingIt() throws Exception {
        Origin assigned = customOrigin();
        assigned.setOriginAccessControlId("configured-control");
        OriginAccessControl control = control();

        CloudFrontResolvedOrigin resolved = resolve(assigned,
                "{\"domainName\":\"private.s3.us-east-1.amazonaws.com\"}", control);

        assertEquals("configured-control", resolved.origin().getOriginAccessControlId());
        assertEquals("always", resolved.accessControl().signingBehavior());
        assertTrue(CloudFrontRequestRouter.isS3Origin(resolved.origin()));
        assertEquals("always", control.getSigningBehavior());
        assertEquals("placeholder.invalid", assigned.getDomainName());
    }

    @Test
    void disabledOverrideWithoutADomainSuppressesTheInheritedControl() throws Exception {
        Origin assigned = s3Origin();
        assigned.setOriginAccessControlId("configured-control");
        OriginAccessControl control = control();

        CloudFrontResolvedOrigin resolved = resolve(assigned,
                "{\"originAccessControlConfig\":{\"enabled\":false}}", control);

        assertFalse(resolved.accessControl().enabled());
        assertTrue(CloudFrontRequestRouter.isS3Origin(resolved.origin()));
        assertEquals(assigned.getDomainName(), resolved.origin().getDomainName());
        assertEquals("/content", resolved.origin().getOriginPath());
        assertEquals("configured-control", assigned.getOriginAccessControlId());
        assertEquals("always", control.getSigningBehavior());
    }

    @Test
    void acceptsEveryDocumentedS3SigningBehavior() throws Exception {
        for (String behavior : List.of("always", "never", "no-override")) {
            CloudFrontResolvedOrigin resolved = resolve(s3Origin(), """
                    {"originAccessControlConfig":{"enabled":true,"signingBehavior":"%s",
                      "signingProtocol":"sigv4","originType":"s3"}}
                    """.formatted(behavior), null);
            assertEquals(behavior, resolved.accessControl().signingBehavior());
        }
    }

    @Test
    void rejectsMalformedAccessSettingsInsteadOfFallingBackToUnsignedForwarding() throws Exception {
        for (String access : List.of("null", "[]", "{}", "{\"enabled\":\"true\"}", "{\"enabled\":true}",
                """
                {"enabled":true,"signingBehavior":"sometimes","signingProtocol":"sigv4","originType":"s3"}
                """,
                """
                {"enabled":true,"signingBehavior":"always","signingProtocol":"sigv2","originType":"s3"}
                """,
                """
                {"enabled":true,"signingBehavior":"always","signingProtocol":"sigv4","originType":"custom"}
                """,
                """
                {"enabled":true,"signingBehavior":"always","signingProtocol":"sigv4","originType":"lambda"}
                """)) {
            JsonNode override = mapper.readTree("{\"originAccessControlConfig\":" + access + "}");
            assertThrows(IllegalArgumentException.class,
                    () -> CloudFrontResolvedOrigin.resolve(s3Origin(), override, null), access);
        }
    }

    @Test
    void rejectsS3SigningForWebsiteAndUnrelatedDomainsIncludingInheritedSigning() throws Exception {
        for (String domain : List.of("private.s3-website-us-east-1.amazonaws.com", "example.com",
                "private.s3.us-east-1.amazonaws.com.attacker.invalid", "169.254.169.254", "localhost:8080")) {
            JsonNode override = mapper.readTree("{\"domainName\":\"" + domain + "\"}");
            assertThrows(IllegalArgumentException.class,
                    () -> CloudFrontResolvedOrigin.resolve(s3Origin(), override, control()), domain);
        }
    }

    @Test
    void domainOnlyS3UpdatesRemainAnonymousWithoutAControl() throws Exception {
        CloudFrontResolvedOrigin resolved = resolve(customOrigin(),
                "{\"domainName\":\"private.s3.us-east-1.amazonaws.com\"}", null);
        assertTrue(CloudFrontRequestRouter.isS3Origin(resolved.origin()));
        assertNull(resolved.accessControl());
    }

    @Test
    void partialCustomUpdatesRetainSettingsAndDoNotMutateTheAssignedOrigin() throws Exception {
        Origin assigned = customOrigin();
        CloudFrontResolvedOrigin resolved = resolve(assigned, """
                {"domainName":"updated.example.com","originPath":"/updated",
                 "customOriginConfig":{"port":8443}}
                """, null);
        assertEquals("https-only", resolved.origin().getCustomOriginConfig().get("OriginProtocolPolicy"));
        assertEquals("8443", resolved.origin().getCustomOriginConfig().get("HTTPSPort"));
        assertEquals("/updated", resolved.origin().getOriginPath());
        assertEquals("443", assigned.getCustomOriginConfig().get("HTTPSPort"));
        assertEquals("/content", assigned.getOriginPath());
    }

    @Test
    void preservesTheExplicitLoopbackPlainHttpExtension() throws Exception {
        CloudFrontResolvedOrigin resolved = resolve(customOrigin(), "{\"domainName\":\"localhost:5173\"}", null);
        assertNull(resolved.origin().getCustomOriginConfig());
        assertFalse(CloudFrontRequestRouter.isS3Origin(resolved.origin()));
    }

    private CloudFrontResolvedOrigin resolve(Origin assigned, String override, OriginAccessControl control)
            throws Exception {
        return CloudFrontResolvedOrigin.resolve(assigned, mapper.readTree(override), control);
    }

    private static Origin customOrigin() {
        Origin origin = new Origin();
        origin.setId("assigned");
        origin.setDomainName("placeholder.invalid");
        origin.setOriginPath("/content");
        origin.setCustomOriginConfig(Map.of("OriginProtocolPolicy", "https-only", "HTTPSPort", "443"));
        origin.setCustomHeaders(List.of(Map.of("HeaderName", "X-Origin", "HeaderValue", "inherited")));
        origin.setConnectionAttempts(2);
        origin.setConnectionTimeout(4);
        origin.setResponseCompletionTimeout(30);
        return origin;
    }

    private static Origin s3Origin() {
        Origin origin = customOrigin();
        origin.setDomainName("private.s3.us-east-1.amazonaws.com");
        origin.setCustomOriginConfig(null);
        origin.setS3OriginConfig(Map.of());
        return origin;
    }

    private static OriginAccessControl control() {
        OriginAccessControl control = new OriginAccessControl();
        control.setSigningBehavior("always");
        control.setSigningProtocol("sigv4");
        control.setOriginAccessControlOriginType("s3");
        return control;
    }
}
