package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Cluster-scoped policy and inspection operations over the JSON 1.1 wire protocol:
 * auto-termination and managed scaling policies (put, get, remove), bootstrap actions,
 * and instance group resizing.
 */
@QuarkusTest
class EmrClusterPoliciesIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "ElasticMapReduce.";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    private static String runCluster(String extra) {
        return call("RunJobFlow",
                "{\"Name\":\"policies\",\"ReleaseLabel\":\"emr-7.5.0\","
                        + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                        + "\"InstanceGroups\":[{\"Name\":\"Master\",\"InstanceRole\":\"MASTER\","
                        + "\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                        + "{\"Name\":\"Core\",\"InstanceRole\":\"CORE\","
                        + "\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}"
                        + extra + "}")
                .then().statusCode(200)
                .extract().jsonPath().getString("JobFlowId");
    }

    @Test
    void autoTerminationPolicyFromRunJobFlowCanBeUpdatedAndRemoved() {
        String id = runCluster(",\"AutoTerminationPolicy\":{\"IdleTimeout\":3600}");
        call("GetAutoTerminationPolicy", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("AutoTerminationPolicy.IdleTimeout", equalTo(3600));

        call("PutAutoTerminationPolicy",
                "{\"ClusterId\":\"" + id + "\",\"AutoTerminationPolicy\":{\"IdleTimeout\":7200}}")
                .then().statusCode(200);
        call("GetAutoTerminationPolicy", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("AutoTerminationPolicy.IdleTimeout", equalTo(7200));

        call("RemoveAutoTerminationPolicy", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200);
        call("GetAutoTerminationPolicy", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("AutoTerminationPolicy", nullValue());
        // Removing an absent policy is not an error.
        call("RemoveAutoTerminationPolicy", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200);
    }

    @Test
    void autoTerminationPolicyIdleTimeoutIsBounded() {
        String id = runCluster("");
        call("PutAutoTerminationPolicy",
                "{\"ClusterId\":\"" + id + "\",\"AutoTerminationPolicy\":{\"IdleTimeout\":59}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("PutAutoTerminationPolicy",
                "{\"ClusterId\":\"" + id + "\",\"AutoTerminationPolicy\":{\"IdleTimeout\":604801}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("RunJobFlow", "{\"Name\":\"bad-timeout\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true},"
                        + "\"AutoTerminationPolicy\":{\"IdleTimeout\":10}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void policyOperationsOnUnknownClusterAreInvalidRequest() {
        for (String action : new String[] {"GetAutoTerminationPolicy", "PutAutoTerminationPolicy",
                "RemoveAutoTerminationPolicy", "GetManagedScalingPolicy", "RemoveManagedScalingPolicy",
                "ListBootstrapActions"}) {
            call(action, "{\"ClusterId\":\"j-DOESNOTEXIST0\"}")
                    .then().statusCode(400)
                    .body("__type", equalTo("InvalidRequestException"))
                    .body("message", equalTo("Cluster id 'j-DOESNOTEXIST0' is not valid."));
        }
    }

    @Test
    void managedScalingPolicyRoundTrip() {
        String id = runCluster("");
        call("GetManagedScalingPolicy", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("ManagedScalingPolicy", nullValue());

        call("PutManagedScalingPolicy", "{\"ClusterId\":\"" + id + "\",\"ManagedScalingPolicy\":"
                        + "{\"ComputeLimits\":{\"UnitType\":\"Instances\",\"MinimumCapacityUnits\":1,"
                        + "\"MaximumCapacityUnits\":4}}}")
                .then().statusCode(200);
        call("GetManagedScalingPolicy", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("ManagedScalingPolicy.ComputeLimits.UnitType", equalTo("Instances"))
                .body("ManagedScalingPolicy.ComputeLimits.MinimumCapacityUnits", equalTo(1))
                .body("ManagedScalingPolicy.ComputeLimits.MaximumCapacityUnits", equalTo(4));

        call("RemoveManagedScalingPolicy", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200);
        call("GetManagedScalingPolicy", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("ManagedScalingPolicy", nullValue());
    }

    @Test
    void managedScalingPolicyValidation() {
        String id = runCluster("");
        call("PutManagedScalingPolicy", "{\"ClusterId\":\"" + id + "\",\"ManagedScalingPolicy\":"
                        + "{\"ComputeLimits\":{\"UnitType\":\"Instances\",\"MinimumCapacityUnits\":5,"
                        + "\"MaximumCapacityUnits\":4}}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("PutManagedScalingPolicy", "{\"ClusterId\":\"" + id + "\",\"ManagedScalingPolicy\":"
                        + "{\"ComputeLimits\":{\"UnitType\":\"InstanceFleetUnits\",\"MinimumCapacityUnits\":1,"
                        + "\"MaximumCapacityUnits\":4}}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("PutManagedScalingPolicy", "{\"ClusterId\":\"" + id + "\",\"ManagedScalingPolicy\":"
                        + "{\"ComputeLimits\":{\"UnitType\":\"Instances\",\"MinimumCapacityUnits\":1}}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
        call("GetManagedScalingPolicy", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("ManagedScalingPolicy", nullValue());
    }

    @Test
    void bootstrapActionsFromRunJobFlowAreListed() {
        String id = runCluster(",\"BootstrapActions\":[{\"Name\":\"install\",\"ScriptBootstrapAction\":"
                + "{\"Path\":\"s3://bucket/install.sh\",\"Args\":[\"--fast\"]}}]");
        call("ListBootstrapActions", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("BootstrapActions", hasSize(1))
                .body("BootstrapActions[0].Name", equalTo("install"))
                .body("BootstrapActions[0].ScriptPath", equalTo("s3://bucket/install.sh"))
                .body("BootstrapActions[0].Args", contains("--fast"));

        String bare = runCluster("");
        call("ListBootstrapActions", "{\"ClusterId\":\"" + bare + "\"}")
                .then().statusCode(200)
                .body("BootstrapActions", hasSize(0));
    }

    @Test
    void modifyInstanceGroupsResizesCoreGroup() {
        String id = runCluster("");
        String coreId = call("ListInstanceGroups", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .extract().jsonPath().getString("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Id");

        call("ModifyInstanceGroups", "{\"ClusterId\":\"" + id + "\",\"InstanceGroups\":"
                        + "[{\"InstanceGroupId\":\"" + coreId + "\",\"InstanceCount\":3}]}")
                .then().statusCode(200);
        call("ListInstanceGroups", "{\"ClusterId\":\"" + id + "\"}")
                .then().statusCode(200)
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RequestedInstanceCount",
                        equalTo(3));

        call("ModifyInstanceGroups", "{\"ClusterId\":\"" + id + "\",\"InstanceGroups\":"
                        + "[{\"InstanceGroupId\":\"ig-DOESNOTEXIST0\",\"InstanceCount\":3}]}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }
}
