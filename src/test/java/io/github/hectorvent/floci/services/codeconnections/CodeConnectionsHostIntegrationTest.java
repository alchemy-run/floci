package io.github.hectorvent.floci.services.codeconnections;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CodeConnectionsHostIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/codeconnections/aws4_request";
    private static final String TARGET = "CodeConnections_20231201.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void hostLifecycleCreateUpdateTagAndDelete() {
        String name = "floci-host-" + UUID.randomUUID().toString().substring(0, 8);

        String hostArn = post("CreateHost", """
                {
                  "Name": "%s",
                  "ProviderType": "GitHubEnterpriseServer",
                  "ProviderEndpoint": "https://ghe.example.com",
                  "Tags": [{"Key": "env", "Value": "test"}]
                }
                """.formatted(name))
                .then()
                .statusCode(200)
                .body("HostArn", startsWith("arn:aws:codeconnections:"))
                .extract().path("HostArn");

        post("GetHost", "{\"HostArn\":\"" + hostArn + "\"}")
                .then()
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("Status", equalTo("PENDING"))
                .body("ProviderType", equalTo("GitHubEnterpriseServer"))
                .body("ProviderEndpoint", equalTo("https://ghe.example.com"));

        post("ListHosts", "{}")
                .then()
                .statusCode(200)
                .body("Hosts.HostArn", hasItem(hostArn));

        post("UpdateHost", """
                {"HostArn":"%s","ProviderEndpoint":"https://ghe2.example.com"}
                """.formatted(hostArn))
                .then()
                .statusCode(200);

        post("GetHost", "{\"HostArn\":\"" + hostArn + "\"}")
                .then()
                .statusCode(200)
                .body("ProviderEndpoint", equalTo("https://ghe2.example.com"))
                .body("Status", equalTo("PENDING"));

        post("ListTagsForResource", "{\"ResourceArn\":\"" + hostArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("env"));

        post("TagResource", """
                {"ResourceArn":"%s","Tags":[{"Key":"owner","Value":"floci"}]}
                """.formatted(hostArn))
                .then()
                .statusCode(200);

        post("ListTagsForResource", "{\"ResourceArn\":\"" + hostArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("owner"));

        post("UntagResource", """
                {"ResourceArn":"%s","TagKeys":["env"]}
                """.formatted(hostArn))
                .then()
                .statusCode(200);

        post("DeleteHost", "{\"HostArn\":\"" + hostArn + "\"}")
                .then()
                .statusCode(200);

        post("GetHost", "{\"HostArn\":\"" + hostArn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listHostsUnknownOperationIsNotReturnedForKnownAction() {
        post("ListHosts", "{}")
                .then()
                .statusCode(200)
                .body("Hosts", org.hamcrest.Matchers.notNullValue());
    }

    private static io.restassured.response.Response post(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
