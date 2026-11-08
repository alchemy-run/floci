package io.floci.aws.services.eventbridge;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Global endpoints: create, describe, update in place, publish through the endpoint id from another
 * Region, list and delete. The endpoint is ACTIVE at once and routes to its primary bus.
 */
@QuarkusTest
class EventBridgeGlobalEndpointIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PRIMARY = "arn:aws:events:us-east-1:000000000000:event-bus/default";
    private static final String SECONDARY = "arn:aws:events:us-west-2:000000000000:event-bus/default";
    private static final String HEALTH_CHECK =
            "arn:aws:route53:::healthcheck/00000000-0000-0000-0000-000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anEndpointIsCreatedUpdatedPublishedThroughAndDeleted() {
        String name = "global-endpoint-it";
        String endpointId = call("CreateEndpoint", """
                {"Name": "%s", "Description": "initial",
                 "RoutingConfig": {"FailoverConfig": {"Primary": {"HealthCheck": "%s"},
                                                      "Secondary": {"Route": "us-west-2"}}},
                 "ReplicationConfig": {"State": "ENABLED"},
                 "EventBuses": [{"EventBusArn": "%s"}, {"EventBusArn": "%s"}]}
                """.formatted(name, HEALTH_CHECK, PRIMARY, SECONDARY), "us-east-1")
                .then().statusCode(200)
                .body("Arn", endsWith(":endpoint/" + name))
                .body("State", equalTo("ACTIVE"))
                .body("EventBuses.EventBusArn", hasItem(SECONDARY))
                .extract().path("EndpointId");

        call("DescribeEndpoint", "{\"Name\":\"" + name + "\"}", "us-east-1")
                .then().statusCode(200)
                .body("Description", equalTo("initial"))
                .body("EndpointUrl", containsString(endpointId))
                .body("RoutingConfig.FailoverConfig.Secondary.Route", equalTo("us-west-2"))
                .body("CreationTime", notNullValue());

        call("UpdateEndpoint", """
                {"Name": "%s", "Description": "updated", "ReplicationConfig": {"State": "DISABLED"}}
                """.formatted(name), "us-east-1")
                .then().statusCode(200)
                .body("EndpointId", equalTo(endpointId))
                .body("ReplicationConfig.State", equalTo("DISABLED"));

        // The endpoint id is global: a publisher signing for the secondary Region reaches it.
        call("PutEvents", """
                {"EndpointId": "%s", "Entries": [{"Source": "it.endpoint", "DetailType": "Probe",
                 "Detail": "{}", "EventBusName": "default"}]}
                """.formatted(endpointId), "us-west-2")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        call("ListEndpoints", "{\"NamePrefix\":\"global-endpoint\"}", "us-east-1")
                .then().statusCode(200)
                .body("Endpoints.Name", hasItem(name))
                .body("Endpoints.Description", hasItem("updated"));

        call("DeleteEndpoint", "{\"Name\":\"" + name + "\"}", "us-east-1").then().statusCode(200);
        call("DescribeEndpoint", "{\"Name\":\"" + name + "\"}", "us-east-1")
                .then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        call("ListEndpoints", "{}", "us-east-1")
                .then().statusCode(200)
                .body("Endpoints.Name", not(hasItem(name)));
    }

    @Test
    void anEndpointNamesExactlyTwoEventBuses() {
        call("CreateEndpoint", """
                {"Name": "one-bus-endpoint",
                 "RoutingConfig": {"FailoverConfig": {"Primary": {"HealthCheck": "%s"},
                                                      "Secondary": {"Route": "us-west-2"}}},
                 "EventBuses": [{"EventBusArn": "%s"}]}
                """.formatted(HEALTH_CHECK, PRIMARY), "us-east-1")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    private static Response call(String action, String body, String region) {
        return given().contentType(CT).header("X-Amz-Target", "AWSEvents." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/" + region
                        + "/events/aws4_request, SignedHeaders=host, Signature=0")
                .body(body)
                .when().post("/");
    }
}
