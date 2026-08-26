package io.github.hectorvent.floci.services.qapps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QAppsRoutingFilterTest {

    private static final String QAPPS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/qapps/aws4_request";
    private static final String AMPLIFY_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/amplify/aws4_request";

    @Test
    void recognizesQAppsCredentialScope() {
        assertTrue(QAppsRoutingFilter.isQApps(QAPPS_AUTH));
        assertFalse(QAppsRoutingFilter.isQApps(AMPLIFY_AUTH));
        assertFalse(QAppsRoutingFilter.isQApps(null));
        assertFalse(QAppsRoutingFilter.isQApps(""));
    }

    @Test
    void prefixesDottedAppPaths() {
        assertEquals("/aws-qapps/apps.list", QAppsRoutingFilter.rewritePath("/apps.list"));
        assertEquals("/aws-qapps/apps.get", QAppsRoutingFilter.rewritePath("/apps.get"));
        assertEquals("/aws-qapps/apps.create", QAppsRoutingFilter.rewritePath("/apps.create"));
        assertEquals("/aws-qapps/runtime.startQAppSession",
                QAppsRoutingFilter.rewritePath("/runtime.startQAppSession"));
        assertEquals("/aws-qapps/catalog.listCategories",
                QAppsRoutingFilter.rewritePath("/catalog.listCategories"));
    }

    @Test
    void leavesSharedTagPathsAndAlreadyPrefixedPathsAlone() {
        assertEquals("/tags/arn:aws:qapps:us-east-1:000000000000:application/abc/qapp/def",
                QAppsRoutingFilter.rewritePath(
                        "/tags/arn:aws:qapps:us-east-1:000000000000:application/abc/qapp/def"));
        assertEquals("/aws-qapps/apps.list", QAppsRoutingFilter.rewritePath("/aws-qapps/apps.list"));
    }
}
