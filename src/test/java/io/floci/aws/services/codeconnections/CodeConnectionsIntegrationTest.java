package io.floci.aws.services.codeconnections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AWS CodeConnections over JSON 1.0 and Smithy RPC v2 CBOR: hosts, connections (pending, and the account's
 * available demo connection), repository links and sync configurations, and tags.
 */
@QuarkusTest
class CodeConnectionsIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String DEMO = "arn:aws:codeconnections:us-east-1:" + ACCOUNT + ":connection/"
            + CodeConnectionsService.DEMO_CONNECTION_ID;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static ValidatableResponse call(String action, String body) {
        return given().contentType("application/x-amz-json-1.0")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/codeconnections/"
                        + "aws4_request")
                .header("X-Amz-Target", "com.amazonaws.codeconnections.CodeConnections_20231201." + action)
                .body(body).post("/").then();
    }

    @Test
    void hostsAndConnections() {
        String host = call("CreateHost", "{\"Name\":\"ghes\",\"ProviderType\":\"GitHubEnterpriseServer\","
                + "\"ProviderEndpoint\":\"https://ghe.example.com\"}").statusCode(200).extract().path("HostArn");
        call("GetHost", "{\"HostArn\":\"" + host + "\"}").statusCode(200).body("Status", equalTo("PENDING"));
        call("UpdateHost", "{\"HostArn\":\"" + host + "\",\"ProviderEndpoint\":\"https://ghe2.example.com\"}")
                .statusCode(200);
        call("GetHost", "{\"HostArn\":\"" + host + "\"}").statusCode(200)
                .body("ProviderEndpoint", equalTo("https://ghe2.example.com"));
        call("CreateConnection", "{\"ConnectionName\":\"ghes\",\"ProviderType\":\"GitHubEnterpriseServer\"}")
                .statusCode(400).body("message", containsString("HostArn"));
        String onHost = call("CreateConnection", "{\"ConnectionName\":\"ghes-conn\",\"HostArn\":\"" + host + "\"}")
                .statusCode(200).extract().path("ConnectionArn");
        call("GetConnection", "{\"ConnectionArn\":\"" + onHost + "\"}").statusCode(200)
                .body("Connection.ProviderType", equalTo("GitHubEnterpriseServer"))
                .body("Connection.ConnectionStatus", equalTo("PENDING"));
        call("DeleteHost", "{\"HostArn\":\"" + host + "\"}").statusCode(400)
                .body("__type", containsString("ResourceUnavailableException"));
        call("ListConnections", "{\"HostArnFilter\":\"" + host + "\"}").statusCode(200)
                .body("Connections.ConnectionArn", hasItem(onHost));
        call("DeleteConnection", "{\"ConnectionArn\":\"" + onHost + "\"}").statusCode(200);
        call("DeleteHost", "{\"HostArn\":\"" + host + "\"}").statusCode(200);
        call("GetHost", "{\"HostArn\":\"" + host + "\"}").statusCode(404);

        call("ListConnections", "{}").statusCode(200).body("Connections.ConnectionArn", hasItem(DEMO));
        call("GetConnection", "{\"ConnectionArn\":\"" + DEMO + "\"}").statusCode(200)
                .body("Connection.ConnectionStatus", equalTo("AVAILABLE"));
    }

    @Test
    void repositoryLinksAndSyncConfigurations() {
        String pending = call("CreateConnection", "{\"ConnectionName\":\"pending\",\"ProviderType\":\"GitHub\"}")
                .statusCode(200).extract().path("ConnectionArn");
        call("CreateRepositoryLink", "{\"ConnectionArn\":\"" + pending + "\",\"OwnerId\":\"o\",\"RepositoryName\":"
                + "\"r\"}").statusCode(400).body("__type", containsString("InvalidInputException"));
        String repo = "repo-" + UUID.randomUUID().toString().substring(0, 8);
        String link = call("CreateRepositoryLink", "{\"ConnectionArn\":\"" + DEMO + "\",\"OwnerId\":\"floci-demo\","
                + "\"RepositoryName\":\"" + repo + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"infra\"}]}")
                .statusCode(200).body("RepositoryLinkInfo.ProviderType", equalTo("GitHub"))
                .extract().path("RepositoryLinkInfo.RepositoryLinkId");
        call("CreateRepositoryLink", "{\"ConnectionArn\":\"" + DEMO + "\",\"OwnerId\":\"floci-demo\","
                + "\"RepositoryName\":\"" + repo + "\"}").statusCode(409);
        String stack = "stack" + UUID.randomUUID().toString().substring(0, 8);
        call("CreateSyncConfiguration", "{\"Branch\":\"main\",\"ConfigFile\":\"deploy/stack.yaml\",\"RepositoryLinkId\":"
                + "\"" + link + "\",\"ResourceName\":\"" + stack + "\",\"RoleArn\":\"arn:aws:iam::" + ACCOUNT
                + ":role/sync\",\"SyncType\":\"CFN_STACK_SYNC\"}").statusCode(200)
                .body("SyncConfiguration.OwnerId", equalTo("floci-demo"))
                .body("SyncConfiguration.TriggerResourceUpdateOn", equalTo("ANY_CHANGE"));
        call("UpdateSyncConfiguration", "{\"ResourceName\":\"" + stack + "\",\"SyncType\":\"CFN_STACK_SYNC\","
                + "\"TriggerResourceUpdateOn\":\"FILE_CHANGE\"}").statusCode(200);
        call("GetSyncConfiguration", "{\"ResourceName\":\"" + stack + "\",\"SyncType\":\"CFN_STACK_SYNC\"}")
                .statusCode(200).body("SyncConfiguration.TriggerResourceUpdateOn", equalTo("FILE_CHANGE"));
        call("ListRepositorySyncDefinitions", "{\"RepositoryLinkId\":\"" + link + "\",\"SyncType\":\"CFN_STACK_SYNC\"}")
                .statusCode(200).body("RepositorySyncDefinitions[0].Directory", equalTo("deploy"));
        call("GetSyncBlockerSummary", "{\"ResourceName\":\"" + stack + "\",\"SyncType\":\"CFN_STACK_SYNC\"}")
                .statusCode(200).body("SyncBlockerSummary.LatestBlockers", hasSize(0));
        call("GetResourceSyncStatus", "{\"ResourceName\":\"" + stack + "\",\"SyncType\":\"CFN_STACK_SYNC\"}")
                .statusCode(404);
        call("DeleteRepositoryLink", "{\"RepositoryLinkId\":\"" + link + "\"}").statusCode(409)
                .body("__type", containsString("SyncConfigurationStillExistsException"));
        call("DeleteSyncConfiguration", "{\"ResourceName\":\"" + stack + "\",\"SyncType\":\"CFN_STACK_SYNC\"}")
                .statusCode(200);
        call("GetSyncConfiguration", "{\"ResourceName\":\"" + stack + "\",\"SyncType\":\"CFN_STACK_SYNC\"}")
                .statusCode(404);
        String linkArn = call("GetRepositoryLink", "{\"RepositoryLinkId\":\"" + link + "\"}").statusCode(200)
                .extract().path("RepositoryLinkInfo.RepositoryLinkArn");
        call("ListTagsForResource", "{\"ResourceArn\":\"" + linkArn + "\"}").statusCode(200)
                .body("Tags.Key", hasItem("team"));
        call("DeleteRepositoryLink", "{\"RepositoryLinkId\":\"" + link + "\"}").statusCode(200);
        call("ListRepositoryLinks", "{}").statusCode(200)
                .body("RepositoryLinks.RepositoryLinkId", not(hasItem(link)))
                .body("RepositoryLinks.RepositoryLinkId", hasItem(CodeConnectionsService.DEMO_LINK_ID));
        call("DeleteConnection", "{\"ConnectionArn\":\"" + pending + "\"}").statusCode(200);
    }

    @Test
    void servesSmithyRpcV2Cbor() throws Exception {
        CBORMapper cbor = new CBORMapper();
        byte[] body = cbor.writeValueAsBytes(cbor.createObjectNode().put("ConnectionArn", DEMO));
        byte[] response = given().contentType("application/cbor").header("smithy-protocol", "rpc-v2-cbor")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/codeconnections/"
                        + "aws4_request")
                .body(body).post("/service/CodeConnections_20231201/operation/GetConnection").then().statusCode(200)
                .extract().asByteArray();
        JsonNode parsed = cbor.readTree(response);
        assertEquals("AVAILABLE", parsed.path("Connection").path("ConnectionStatus").asText());
    }
}
