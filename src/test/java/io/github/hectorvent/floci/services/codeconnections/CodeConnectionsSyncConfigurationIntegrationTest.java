package io.github.hectorvent.floci.services.codeconnections;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * Sync configuration lifecycle over JSON 1.0
 * ({@code Content-Type: application/x-amz-json-1.0},
 * {@code X-Amz-Target: CodeConnections_20231201.&lt;Action&gt;}).
 */
@QuarkusTest
class CodeConnectionsSyncConfigurationIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String TARGET = "CodeConnections_20231201.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/codeconnections/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSyncConfigurationOnANonexistentStackFailsWithResourceNotFoundException() {
        post("GetSyncConfiguration", """
                {"SyncType":"CFN_STACK_SYNC","ResourceName":"alchemy-test-nonexistent-stack"}
                """)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetUpdateListAndDeleteSyncConfiguration() {
        String connectionArn = post("CreateConnection", """
                {"ConnectionName":"floci-sync-conn","ProviderType":"GitHub"}
                """)
                .then()
                .statusCode(200)
                .body("ConnectionArn", startsWith("arn:aws:codeconnections:us-east-1:"))
                .extract().path("ConnectionArn");

        String repositoryLinkId = post("CreateRepositoryLink", """
                {
                  "ConnectionArn":"%s",
                  "OwnerId":"floci-org",
                  "RepositoryName":"floci-repo"
                }
                """.formatted(connectionArn))
                .then()
                .statusCode(200)
                .body("RepositoryLinkInfo.OwnerId", equalTo("floci-org"))
                .body("RepositoryLinkInfo.RepositoryName", equalTo("floci-repo"))
                .body("RepositoryLinkInfo.ProviderType", equalTo("GitHub"))
                .extract().path("RepositoryLinkInfo.RepositoryLinkId");

        post("CreateSyncConfiguration", """
                {
                  "Branch":"main",
                  "ConfigFile":"deployments/alchemy-test-stack.yaml",
                  "RepositoryLinkId":"%s",
                  "ResourceName":"alchemy-test-sync-stack",
                  "RoleArn":"arn:aws:iam::000000000000:role/GitSync",
                  "SyncType":"CFN_STACK_SYNC"
                }
                """.formatted(repositoryLinkId))
                .then()
                .statusCode(200)
                .body("SyncConfiguration.SyncType", equalTo("CFN_STACK_SYNC"))
                .body("SyncConfiguration.ResourceName", equalTo("alchemy-test-sync-stack"))
                .body("SyncConfiguration.Branch", equalTo("main"))
                .body("SyncConfiguration.OwnerId", equalTo("floci-org"))
                .body("SyncConfiguration.RepositoryName", equalTo("floci-repo"))
                .body("SyncConfiguration.TriggerResourceUpdateOn", equalTo("ANY_CHANGE"));

        post("GetSyncConfiguration", """
                {"SyncType":"CFN_STACK_SYNC","ResourceName":"alchemy-test-sync-stack"}
                """)
                .then()
                .statusCode(200)
                .body("SyncConfiguration.ConfigFile", equalTo("deployments/alchemy-test-stack.yaml"))
                .body("SyncConfiguration.RepositoryLinkId", equalTo(repositoryLinkId));

        post("UpdateSyncConfiguration", """
                {
                  "SyncType":"CFN_STACK_SYNC",
                  "ResourceName":"alchemy-test-sync-stack",
                  "TriggerResourceUpdateOn":"FILE_CHANGE"
                }
                """)
                .then()
                .statusCode(200)
                .body("SyncConfiguration.TriggerResourceUpdateOn", equalTo("FILE_CHANGE"))
                .body("SyncConfiguration.Branch", equalTo("main"));

        post("ListSyncConfigurations", """
                {"RepositoryLinkId":"%s","SyncType":"CFN_STACK_SYNC"}
                """.formatted(repositoryLinkId))
                .then()
                .statusCode(200)
                .body("SyncConfigurations", hasSize(1))
                .body("SyncConfigurations[0].TriggerResourceUpdateOn", equalTo("FILE_CHANGE"));

        post("DeleteSyncConfiguration", """
                {"SyncType":"CFN_STACK_SYNC","ResourceName":"alchemy-test-sync-stack"}
                """)
                .then()
                .statusCode(200);

        post("GetSyncConfiguration", """
                {"SyncType":"CFN_STACK_SYNC","ResourceName":"alchemy-test-sync-stack"}
                """)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response post(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
