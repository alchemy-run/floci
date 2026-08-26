package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsAction;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 coverage for Alchemy {@code test/AWS/RedshiftServerless/Connect.test.ts}:
 * typed {@code ResourceNotFoundException} on a missing workgroup, plus
 * IAM-mapped temporary credentials for an existing workgroup.
 */
@QuarkusTest
class RedshiftServerlessConnectIntegrationTest {

    private static final String TARGET = "RedshiftServerless";
    private static final String MISSING = "alchemy-nonexistent-rsconn-probe";
    private static final String NAMESPACE = "alchemy-rsconn-ns";
    private static final String WORKGROUP = "alchemy-rsconn-wg";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getCredentials_missingWorkgroup_resourceNotFound() {
        awsAction(TARGET, "GetCredentials", "{\"workgroupName\":\"" + MISSING + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getCredentials_mintsIamMappedTemporaryCredentials() {
        awsAction(TARGET, "CreateNamespace", "{"
                + "\"namespaceName\":\"" + NAMESPACE + "\","
                + "\"dbName\":\"dev\","
                + "\"adminUsername\":\"alchemyadmin\","
                + "\"manageAdminPassword\":true"
                + "}")
                .then()
                .statusCode(200)
                .body("namespace.status", equalTo("AVAILABLE"));

        awsAction(TARGET, "CreateWorkgroup", "{"
                + "\"workgroupName\":\"" + WORKGROUP + "\","
                + "\"namespaceName\":\"" + NAMESPACE + "\","
                + "\"baseCapacity\":8,"
                + "\"publiclyAccessible\":false"
                + "}")
                .then()
                .statusCode(200)
                .body("workgroup.status", equalTo("AVAILABLE"))
                .body("workgroup.endpoint.port", equalTo(5439));

        long now = System.currentTimeMillis() / 1000L;
        awsAction(TARGET, "GetCredentials", "{\"workgroupName\":\"" + WORKGROUP + "\"}")
                .then()
                .statusCode(200)
                .body("dbUser", startsWith("IAM"))
                .body("dbPassword", notNullValue())
                .body("expiration", greaterThan((int) now))
                .body("nextRefreshTime", notNullValue());
    }
}
