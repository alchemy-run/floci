package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.RestAssuredJsonUtils.awsAction;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 coverage for Alchemy {@code test/AWS/RedshiftServerless/Bindings.test.ts}:
 * namespace + workgroup CRUD, tags, and GetCredentials.
 */
@QuarkusTest
class RedshiftServerlessIntegrationTest {

    private static final String TARGET = "RedshiftServerless";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getWorkgroup_missing_resourceNotFound() {
        awsAction(TARGET, "GetWorkgroup",
                "{\"workgroupName\":\"alchemy-nonexistent-probe-wg\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getNamespace_missing_resourceNotFound() {
        awsAction(TARGET, "GetNamespace",
                "{\"namespaceName\":\"alchemy-nonexistent-probe-ns\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void namespaceWorkgroupTagsAndCredentials_roundTrip() {
        String namespace = "alchemy-rs-bind-ns";
        String workgroup = "alchemy-rs-bind-wg";

        awsAction(TARGET, "CreateNamespace", "{"
                + "\"namespaceName\":\"" + namespace + "\","
                + "\"dbName\":\"dev\","
                + "\"adminUsername\":\"alchemyadmin\","
                + "\"manageAdminPassword\":true,"
                + "\"tags\":[{\"key\":\"Environment\",\"value\":\"test\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("namespace.namespaceName", equalTo(namespace))
                .body("namespace.status", equalTo("AVAILABLE"))
                .body("namespace.dbName", equalTo("dev"))
                .body("namespace.adminPasswordSecretArn", notNullValue());

        String namespaceArn = awsAction(TARGET, "GetNamespace",
                "{\"namespaceName\":\"" + namespace + "\"}")
                .then()
                .statusCode(200)
                .body("namespace.status", equalTo("AVAILABLE"))
                .extract().path("namespace.namespaceArn");

        awsAction(TARGET, "ListTagsForResource", "{\"resourceArn\":\"" + namespaceArn + "\"}")
                .then()
                .statusCode(200)
                .body("tags[0].key", equalTo("Environment"))
                .body("tags[0].value", equalTo("test"));

        awsAction(TARGET, "TagResource", "{"
                + "\"resourceArn\":\"" + namespaceArn + "\","
                + "\"tags\":[{\"key\":\"Owner\",\"value\":\"alchemy\"}]"
                + "}")
                .then()
                .statusCode(200);

        awsAction(TARGET, "CreateWorkgroup", "{"
                + "\"workgroupName\":\"" + workgroup + "\","
                + "\"namespaceName\":\"" + namespace + "\","
                + "\"baseCapacity\":8,"
                + "\"publiclyAccessible\":false"
                + "}")
                .then()
                .statusCode(200)
                .body("workgroup.workgroupName", equalTo(workgroup))
                .body("workgroup.status", equalTo("AVAILABLE"))
                .body("workgroup.endpoint.address", notNullValue())
                .body("workgroup.endpoint.port", equalTo(5439));

        awsAction(TARGET, "GetWorkgroup", "{\"workgroupName\":\"" + workgroup + "\"}")
                .then()
                .statusCode(200)
                .body("workgroup.namespaceName", equalTo(namespace));

        awsAction(TARGET, "GetCredentials", "{\"workgroupName\":\"" + workgroup + "\"}")
                .then()
                .statusCode(200)
                .body("dbUser", org.hamcrest.Matchers.startsWith("IAM:"))
                .body("dbPassword", notNullValue());

        awsAction(TARGET, "DeleteWorkgroup", "{\"workgroupName\":\"" + workgroup + "\"}")
                .then()
                .statusCode(200);
        awsAction(TARGET, "GetWorkgroup", "{\"workgroupName\":\"" + workgroup + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        awsAction(TARGET, "DeleteNamespace", "{\"namespaceName\":\"" + namespace + "\"}")
                .then()
                .statusCode(200);
        awsAction(TARGET, "GetNamespace", "{\"namespaceName\":\"" + namespace + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}
