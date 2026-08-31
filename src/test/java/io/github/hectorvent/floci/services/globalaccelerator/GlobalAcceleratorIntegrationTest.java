package io.github.hectorvent.floci.services.globalaccelerator;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.1 Global Accelerator coverage used by Alchemy Accelerator / Listener /
 * EndpointGroup: typed not-found, create/describe/update, tags, flow logs,
 * traffic dial, and ordered delete.
 */
@QuarkusTest
class GlobalAcceleratorIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/globalaccelerator/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeAccelerator_missingArn_returnsAcceleratorNotFoundException() {
        ga("DescribeAccelerator",
                "{\"AcceleratorArn\":\"arn:aws:globalaccelerator::000000000000:accelerator/does-not-exist\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("AcceleratorNotFoundException"));
    }

    @Test
    void listAccelerators_emptyOrExisting_returnsAcceleratorsArray() {
        ga("ListAccelerators", "{}")
                .then()
                .statusCode(200)
                .body("Accelerators", notNullValue());
    }

    @Test
    void acceleratorListenerEndpointGroup_roundTripTagsFlowLogsAndDelete() {
        String name = "ga-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String token = UUID.randomUUID().toString();

        String acceleratorArn = ga("CreateAccelerator", "{"
                + "\"Name\":\"" + name + "\","
                + "\"IdempotencyToken\":\"" + token + "\","
                + "\"Tags\":[{\"Key\":\"purpose\",\"Value\":\"alchemy-test\"},{\"Key\":\"alchemy::id\",\"Value\":\"TestAccelerator\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("Accelerator.AcceleratorArn", notNullValue())
                .body("Accelerator.Enabled", equalTo(true))
                .body("Accelerator.DnsName", notNullValue())
                .body("Accelerator.Status", equalTo("DEPLOYED"))
                .extract().path("Accelerator.AcceleratorArn");

        ga("DescribeAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
                .then()
                .statusCode(200)
                .body("Accelerator.Name", equalTo(name))
                .body("Accelerator.Enabled", equalTo(true));

        ga("DescribeAcceleratorAttributes", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
                .then()
                .statusCode(200)
                .body("AcceleratorAttributes.FlowLogsEnabled", equalTo(false));

        ga("ListTagsForResource", "{\"ResourceArn\":\"" + acceleratorArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy::id"));

        String listenerArn = ga("CreateListener", "{"
                + "\"AcceleratorArn\":\"" + acceleratorArn + "\","
                + "\"PortRanges\":[{\"FromPort\":80,\"ToPort\":80}],"
                + "\"Protocol\":\"TCP\","
                + "\"IdempotencyToken\":\"" + UUID.randomUUID() + "\""
                + "}")
                .then()
                .statusCode(200)
                .body("Listener.ListenerArn", notNullValue())
                .body("Listener.Protocol", equalTo("TCP"))
                .body("Listener.ClientAffinity", equalTo("NONE"))
                .body("Listener.PortRanges[0].FromPort", equalTo(80))
                .extract().path("Listener.ListenerArn");

        String groupArn = ga("CreateEndpointGroup", "{"
                + "\"ListenerArn\":\"" + listenerArn + "\","
                + "\"EndpointGroupRegion\":\"us-west-2\","
                + "\"HealthCheckProtocol\":\"TCP\","
                + "\"HealthCheckPort\":80,"
                + "\"IdempotencyToken\":\"" + UUID.randomUUID() + "\""
                + "}")
                .then()
                .statusCode(200)
                .body("EndpointGroup.EndpointGroupRegion", equalTo("us-west-2"))
                .body("EndpointGroup.TrafficDialPercentage", equalTo(100.0f))
                .body("EndpointGroup.HealthCheckPort", equalTo(80))
                .extract().path("EndpointGroup.EndpointGroupArn");

        ga("UpdateListener", "{"
                + "\"ListenerArn\":\"" + listenerArn + "\","
                + "\"PortRanges\":[{\"FromPort\":80,\"ToPort\":80},{\"FromPort\":443,\"ToPort\":443}],"
                + "\"ClientAffinity\":\"SOURCE_IP\""
                + "}")
                .then()
                .statusCode(200)
                .body("Listener.ClientAffinity", equalTo("SOURCE_IP"))
                .body("Listener.PortRanges", hasSize(2));

        ga("UpdateAcceleratorAttributes", "{"
                + "\"AcceleratorArn\":\"" + acceleratorArn + "\","
                + "\"FlowLogsEnabled\":true,"
                + "\"FlowLogsS3Bucket\":\"alchemy-test-ga-flow-logs-4f81c2\","
                + "\"FlowLogsS3Prefix\":\"ga-flow-logs\""
                + "}")
                .then()
                .statusCode(200)
                .body("AcceleratorAttributes.FlowLogsEnabled", equalTo(true))
                .body("AcceleratorAttributes.FlowLogsS3Bucket", equalTo("alchemy-test-ga-flow-logs-4f81c2"))
                .body("AcceleratorAttributes.FlowLogsS3Prefix", equalTo("ga-flow-logs"));

        ga("TagResource", "{\"ResourceArn\":\"" + acceleratorArn
                + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"platform\"}]}")
                .then()
                .statusCode(200);

        ga("ListTagsForResource", "{\"ResourceArn\":\"" + acceleratorArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("team"));

        ga("UpdateEndpointGroup", "{"
                + "\"EndpointGroupArn\":\"" + groupArn + "\","
                + "\"TrafficDialPercentage\":50"
                + "}")
                .then()
                .statusCode(200)
                .body("EndpointGroup.TrafficDialPercentage", equalTo(50.0f));

        ga("UpdateAcceleratorAttributes", "{"
                + "\"AcceleratorArn\":\"" + acceleratorArn + "\","
                + "\"FlowLogsEnabled\":false"
                + "}")
                .then()
                .statusCode(200)
                .body("AcceleratorAttributes.FlowLogsEnabled", equalTo(false));

        ga("DeleteAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("AcceleratorNotDisabledException"));

        ga("DeleteListener", "{\"ListenerArn\":\"" + listenerArn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("AssociatedEndpointGroupFoundException"));

        ga("DeleteEndpointGroup", "{\"EndpointGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200);

        ga("DeleteListener", "{\"ListenerArn\":\"" + listenerArn + "\"}")
                .then()
                .statusCode(200);

        ga("UpdateAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\",\"Enabled\":false}")
                .then()
                .statusCode(200)
                .body("Accelerator.Enabled", equalTo(false));

        ga("DeleteAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
                .then()
                .statusCode(200);

        ga("DescribeAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("AcceleratorNotFoundException"));
    }

    @Test
    void addAndRemoveEndpoints_roundTripOnEndpointGroup() {
        String name = "ga-ep-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String acceleratorArn = ga("CreateAccelerator", "{"
                + "\"Name\":\"" + name + "\","
                + "\"IdempotencyToken\":\"" + UUID.randomUUID() + "\""
                + "}")
                .then()
                .statusCode(200)
                .extract().path("Accelerator.AcceleratorArn");

        String listenerArn = ga("CreateListener", "{"
                + "\"AcceleratorArn\":\"" + acceleratorArn + "\","
                + "\"PortRanges\":[{\"FromPort\":80,\"ToPort\":80}],"
                + "\"Protocol\":\"TCP\","
                + "\"IdempotencyToken\":\"" + UUID.randomUUID() + "\""
                + "}")
                .then()
                .statusCode(200)
                .extract().path("Listener.ListenerArn");

        String groupArn = ga("CreateEndpointGroup", "{"
                + "\"ListenerArn\":\"" + listenerArn + "\","
                + "\"EndpointGroupRegion\":\"us-west-2\","
                + "\"IdempotencyToken\":\"" + UUID.randomUUID() + "\""
                + "}")
                .then()
                .statusCode(200)
                .extract().path("EndpointGroup.EndpointGroupArn");

        String allocationId = "eipalloc-ga-bindings-probe";
        ga("AddEndpoints", "{"
                + "\"EndpointGroupArn\":\"" + groupArn + "\","
                + "\"EndpointConfigurations\":[{\"EndpointId\":\"" + allocationId + "\",\"Weight\":64}]"
                + "}")
                .then()
                .statusCode(200)
                .body("EndpointDescriptions.EndpointId", contains(allocationId));

        ga("DescribeEndpointGroup", "{\"EndpointGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("EndpointGroup.EndpointDescriptions.EndpointId", hasItem(allocationId));

        ga("RemoveEndpoints", "{"
                + "\"EndpointGroupArn\":\"" + groupArn + "\","
                + "\"EndpointIdentifiers\":[{\"EndpointId\":\"" + allocationId + "\"}]"
                + "}")
                .then()
                .statusCode(200);

        ga("DescribeEndpointGroup", "{\"EndpointGroupArn\":\"" + groupArn + "\"}")
                .then()
                .statusCode(200)
                .body("EndpointGroup.EndpointDescriptions.EndpointId", not(hasItem(allocationId)));
    }

    private static Response ga(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", GlobalAcceleratorService.TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
