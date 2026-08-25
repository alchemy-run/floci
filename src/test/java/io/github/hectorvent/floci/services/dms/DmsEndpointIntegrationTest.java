package io.github.hectorvent.floci.services.dms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DmsEndpointIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/dms/aws4_request";
    private static final String TARGET = "AmazonDMSv20160101.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeEndpoints_unknownIdentifier_emptyPage() {
        invoke("DescribeEndpoints",
                "{\"Filters\":[{\"Name\":\"endpoint-id\",\"Values\":[\"missing-endpoint\"]}]}")
                .then()
                .statusCode(200)
                .body("Endpoints", hasSize(0));
    }

    @Test
    void deleteEndpoint_missingArn_resourceNotFound() {
        invoke("DeleteEndpoint",
                "{\"EndpointArn\":\"arn:aws:dms:us-east-1:000000000000:endpoint:missing\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void createDescribeModifyTagAndDelete() {
        String identifier = "src-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = invoke("CreateEndpoint", """
                {
                  "EndpointIdentifier": "%s",
                  "EndpointType": "source",
                  "EngineName": "mysql",
                  "ServerName": "source-db.example.com",
                  "Port": 3306,
                  "Username": "admin",
                  "Password": "correct-horse-battery-staple",
                  "DatabaseName": "app",
                  "Tags": [{"Key": "team", "Value": "data"}]
                }
                """.formatted(identifier));
        created.then()
                .statusCode(200)
                .body("Endpoint.EndpointIdentifier", equalTo(identifier))
                .body("Endpoint.EndpointType", equalTo("SOURCE"))
                .body("Endpoint.EngineName", equalTo("mysql"))
                .body("Endpoint.ServerName", equalTo("source-db.example.com"))
                .body("Endpoint.Port", equalTo(3306))
                .body("Endpoint.Username", equalTo("admin"))
                .body("Endpoint.Password", nullValue())
                .body("Endpoint.Status", equalTo("active"));
        String arn = created.jsonPath().getString("Endpoint.EndpointArn");
        assertTrue(arn.contains(":endpoint:"));

        invoke("DescribeEndpoints",
                "{\"Filters\":[{\"Name\":\"endpoint-id\",\"Values\":[\"" + identifier + "\"]}]}")
                .then()
                .statusCode(200)
                .body("Endpoints", hasSize(1))
                .body("Endpoints[0].EndpointArn", equalTo(arn))
                .body("Endpoints[0].EndpointType", equalTo("SOURCE"))
                .body("Endpoints[0].Port", equalTo(3306))
                .body("Endpoints[0].Password", nullValue());

        invoke("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("TagList[0].Key", equalTo("team"))
                .body("TagList[0].Value", equalTo("data"));

        invoke("AddTagsToResource", """
                {"ResourceArn":"%s","Tags":[{"Key":"team","Value":"platform"},{"Key":"env","Value":"test"}]}
                """.formatted(arn))
                .then()
                .statusCode(200);

        invoke("RemoveTagsFromResource",
                "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"env\"]}")
                .then()
                .statusCode(200);

        invoke("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("TagList", hasSize(1))
                .body("TagList[0].Value", equalTo("platform"));

        invoke("ModifyEndpoint", """
                {
                  "EndpointArn": "%s",
                  "EndpointIdentifier": "%s",
                  "EndpointType": "source",
                  "EngineName": "mysql",
                  "ServerName": "source-db.example.com",
                  "Port": 3307,
                  "Username": "readonly",
                  "DatabaseName": "app"
                }
                """.formatted(arn, identifier))
                .then()
                .statusCode(200)
                .body("Endpoint.EndpointArn", equalTo(arn))
                .body("Endpoint.Port", equalTo(3307))
                .body("Endpoint.Username", equalTo("readonly"));

        invoke("DescribeEndpoints",
                "{\"Filters\":[{\"Name\":\"endpoint-id\",\"Values\":[\"" + identifier + "\"]}]}")
                .then()
                .statusCode(200)
                .body("Endpoints[0].Port", equalTo(3307))
                .body("Endpoints[0].Username", equalTo("readonly"));

        invoke("CreateEndpoint", """
                {
                  "EndpointIdentifier": "%s",
                  "EndpointType": "source",
                  "EngineName": "mysql",
                  "ServerName": "source-db.example.com"
                }
                """.formatted(identifier))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceAlreadyExistsFault"));

        invoke("DeleteEndpoint", "{\"EndpointArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("Endpoint.EndpointArn", equalTo(arn));

        invoke("DescribeEndpoints",
                "{\"Filters\":[{\"Name\":\"endpoint-id\",\"Values\":[\"" + identifier + "\"]}]}")
                .then()
                .statusCode(200)
                .body("Endpoints", hasSize(0));
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
