package io.floci.aws.services.ecs;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ECS Managed Instances capacity providers and Nitro Sandbox tasks: a provider belongs to the
 * cluster it is created in, a task placed through it runs on an EC2 instance launched from its
 * launch template, and a task definition's isolation mode needs instances with Nitro Enclaves.
 */
@QuarkusTest
class EcsManagedInstancesIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String SUBNET = "subnet-default-us-east-1-a";
    private static final String NETWORK =
            "\"networkConfiguration\":{\"awsvpcConfiguration\":{\"subnets\":[\"" + SUBNET + "\"]}}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** The instances a provider launches use its instance profile, which EC2 checks exists. */
    private static void createInstanceProfile() {
        given().formParam("Action", "CreateInstanceProfile")
                .formParam("InstanceProfileName", "ecs-instance")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request")
                .when().post("/")
                .then().statusCode(oneOf(200, 409));
    }

    private static Response call(String action, String body, int expectedStatus) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(expectedStatus)
                .extract().response();
    }

    private static String provider(String name, String cluster, boolean enclaves) {
        createInstanceProfile();
        return "{\"name\":\"" + name + "\",\"cluster\":\"" + cluster + "\","
                + "\"managedInstancesProvider\":{"
                + "\"infrastructureRoleArn\":\"arn:aws:iam::000000000000:role/ecs-infra\","
                + "\"instanceLaunchTemplate\":{"
                + "\"ec2InstanceProfileArn\":\"arn:aws:iam::000000000000:instance-profile/ecs-instance\","
                + "\"networkConfiguration\":{\"subnets\":[\"" + SUBNET + "\"]},"
                + "\"monitoring\":\"BASIC\","
                + "\"nitroEnclavesEnabled\":" + enclaves + ","
                + "\"instanceRequirements\":{\"vCpuCount\":{\"min\":4},\"memoryMiB\":{\"min\":8192},"
                + "\"allowedInstanceTypes\":[\"m6i.xlarge\"]}}}}";
    }

    private static void registerSandboxTask(String family, String isolationMode) {
        call("RegisterTaskDefinition", "{\"family\":\"" + family + "\","
                + "\"requiresCompatibilities\":[\"MANAGED_INSTANCES\"],\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"1024\",\"memory\":\"2048\","
                + "\"trustedExecutionConfiguration\":{\"isolationMode\":\"" + isolationMode + "\"},"
                + "\"containerDefinitions\":[{\"name\":\"app\",\"image\":\"busybox:stable\"}]}", 200);
    }

    @Test
    void aManagedInstancesProviderIsListedOnlyUnderItsCluster() {
        call("CreateCluster", "{\"clusterName\":\"mi-scope\"}", 200);
        Response created = call("CreateCapacityProvider", provider("mi-scope-cp", "mi-scope", true), 200);
        assertEquals("MANAGED_INSTANCES", created.path("capacityProvider.type"));
        assertEquals("mi-scope", created.path("capacityProvider.cluster"));
        assertTrue(created.<String>path("capacityProvider.capacityProviderArn")
                .endsWith(":capacity-provider/mi-scope/mi-scope-cp"));

        call("DescribeCapacityProviders", "{}", 200).then()
                .body("capacityProviders.name", not(hasItem("mi-scope-cp")));
        call("DescribeCapacityProviders", "{\"cluster\":\"mi-scope\"}", 200).then()
                .body("capacityProviders.find { it.name == 'mi-scope-cp' }.managedInstancesProvider"
                        + ".instanceLaunchTemplate.nitroEnclavesEnabled", equalTo(true));

        // A provider of the same name in another cluster is a different provider.
        call("CreateCluster", "{\"clusterName\":\"mi-scope-2\"}", 200);
        call("CreateCapacityProvider", provider("mi-scope-cp", "mi-scope-2", false), 200);
        call("CreateCapacityProvider", provider("mi-scope-cp", "mi-scope", false), 400)
                .then().body("message", containsString("already exists"));

        // An update changes the launch template members it names and keeps the rest.
        call("UpdateCapacityProvider", "{\"name\":\"mi-scope-cp\",\"cluster\":\"mi-scope\","
                + "\"managedInstancesProvider\":{"
                + "\"infrastructureRoleArn\":\"arn:aws:iam::000000000000:role/ecs-infra\","
                + "\"instanceLaunchTemplate\":{\"monitoring\":\"DETAILED\"}}}", 200)
                .then()
                .body("capacityProvider.updateStatus", equalTo("UPDATE_COMPLETE"))
                .body("capacityProvider.managedInstancesProvider.instanceLaunchTemplate.monitoring",
                        equalTo("DETAILED"))
                .body("capacityProvider.managedInstancesProvider.instanceLaunchTemplate.nitroEnclavesEnabled",
                        equalTo(true));

        // The cluster can't go while it still has the provider.
        call("DeleteCluster", "{\"cluster\":\"mi-scope\"}", 400)
                .then().body("__type", containsString("ClusterContainsCapacityProviderException"));
        call("DeleteCapacityProvider", "{\"capacityProvider\":\"mi-scope-cp\",\"cluster\":\"mi-scope\"}", 200);
        call("DescribeCapacityProviders", "{\"cluster\":\"mi-scope\"}", 200).then()
                .body("capacityProviders.name", not(hasItem("mi-scope-cp")));
        call("DescribeCapacityProviders", "{\"cluster\":\"mi-scope-2\"}", 200).then()
                .body("capacityProviders.name", hasItem("mi-scope-cp"));
        call("DeleteCluster", "{\"cluster\":\"mi-scope\"}", 200);
    }

    @Test
    void aManagedInstancesProviderNeedsAClusterAndALaunchTemplate() {
        String noCluster = provider("mi-no-cluster", "x", true).replace("\"cluster\":\"x\",", "");
        call("CreateCapacityProvider", noCluster, 400)
                .then().body("message", containsString("cluster is required"));
        call("CreateCluster", "{\"clusterName\":\"mi-invalid\"}", 200);
        call("CreateCapacityProvider", "{\"name\":\"mi-no-template\",\"cluster\":\"mi-invalid\","
                + "\"managedInstancesProvider\":{\"infrastructureRoleArn\":\"arn:aws:iam::000000000000:role/r\"}}", 400)
                .then().body("message", containsString("instanceLaunchTemplate"));
    }

    @Test
    void aSandboxTaskRunsOnAnInstanceWithNitroEnclaves() {
        call("CreateCluster", "{\"clusterName\":\"mi-sandbox\"}", 200);
        call("CreateCapacityProvider", provider("mi-sandbox-cp", "mi-sandbox", true), 200);
        registerSandboxTask("mi-sandbox-task", "ISOLATED_SANDBOX");

        Response run = call("RunTask", "{\"cluster\":\"mi-sandbox\",\"taskDefinition\":\"mi-sandbox-task\","
                + "\"capacityProviderStrategy\":[{\"capacityProvider\":\"mi-sandbox-cp\",\"weight\":1}],"
                + NETWORK + "}", 200);
        run.then()
                .body("tasks", hasSize(1))
                .body("tasks[0].launchType", equalTo("MANAGED_INSTANCES"))
                .body("tasks[0].capacityProviderName", equalTo("mi-sandbox-cp"))
                .body("tasks[0].trustedExecutionSummary.isolationMode", equalTo("ISOLATED_SANDBOX"))
                .body("tasks[0].containerInstanceArn", startsWith("arn:aws:ecs:"));

        Response instances = call("DescribeContainerInstances", "{\"cluster\":\"mi-sandbox\","
                + "\"containerInstances\":[\"" + run.path("tasks[0].containerInstanceArn") + "\"]}", 200);
        String ec2InstanceId = instances.path("containerInstances[0].ec2InstanceId");
        assertEquals("mi-sandbox-cp", instances.path("containerInstances[0].capacityProviderName"));

        given().formParam("Action", "DescribeInstances").formParam("InstanceId.1", ec2InstanceId)
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceType",
                        equalTo("m6i.xlarge"))
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.enclaveOptions.enabled",
                        equalTo("true"));

        // A second task reuses the provider's instance.
        Response second = call("RunTask", "{\"cluster\":\"mi-sandbox\",\"taskDefinition\":\"mi-sandbox-task\","
                + "\"capacityProviderStrategy\":[{\"capacityProvider\":\"mi-sandbox-cp\",\"weight\":1}],"
                + NETWORK + "}", 200);
        assertEquals(run.<String>path("tasks[0].containerInstanceArn"),
                second.<String>path("tasks[0].containerInstanceArn"));

        // Deleting the provider stops its tasks and terminates its instance.
        call("DeleteCapacityProvider", "{\"capacityProvider\":\"mi-sandbox-cp\",\"cluster\":\"mi-sandbox\"}", 200);
        call("DescribeTasks", "{\"cluster\":\"mi-sandbox\",\"tasks\":[\"" + run.path("tasks[0].taskArn") + "\"]}", 200)
                .then().body("tasks[0].desiredStatus", equalTo("STOPPED"));
        given().formParam("Action", "DescribeInstances").formParam("InstanceId.1", ec2InstanceId)
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                        oneOf("shutting-down", "terminated"));
    }

    @Test
    void aSandboxTaskNeedsManagedInstancesWithNitroEnclaves() {
        call("CreateCluster", "{\"clusterName\":\"mi-no-enclaves\"}", 200);
        call("CreateCapacityProvider", provider("mi-plain-cp", "mi-no-enclaves", false), 200);
        registerSandboxTask("mi-no-enclaves-task", "ISOLATED_SANDBOX");

        call("RunTask", "{\"cluster\":\"mi-no-enclaves\",\"taskDefinition\":\"mi-no-enclaves-task\","
                + "\"capacityProviderStrategy\":[{\"capacityProvider\":\"mi-plain-cp\",\"weight\":1}],"
                + NETWORK + "}", 400)
                .then().body("message", containsString("nitroEnclavesEnabled"));
        call("RunTask", "{\"cluster\":\"mi-no-enclaves\",\"taskDefinition\":\"mi-no-enclaves-task\","
                + "\"launchType\":\"FARGATE\"," + NETWORK + "}", 400)
                .then().body("message", containsString("nitroEnclavesEnabled"));

        // A SHARED task reports no trusted execution summary.
        registerSandboxTask("mi-shared-task", "SHARED");
        call("RunTask", "{\"cluster\":\"mi-no-enclaves\",\"taskDefinition\":\"mi-shared-task\","
                + "\"capacityProviderStrategy\":[{\"capacityProvider\":\"mi-plain-cp\",\"weight\":1}],"
                + NETWORK + "}", 200)
                .then().body("tasks[0].trustedExecutionSummary", nullValue());
    }
}
