package io.floci.aws.services.docdbelastic;

import com.github.dockerjava.api.model.Container;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Amazon DocumentDB elastic clusters over REST: clusters placed in a VPC and served by MongoDB, whose data survives a
 * stop and start and travels in snapshots to copies and restored clusters, and the errors the API answers with.
 */
@QuarkusTest
class DocDbElasticIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String PASSWORD = "ElasticPassw0rd";
    private static final String MISSING = "00000000-0000-0000-0000-000000000000";

    @Inject
    ContainerLifecycleManager lifecycle;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static RequestSpecification elastic() {
        return given().header("Authorization", auth("docdb-elastic")).contentType("application/json")
                .urlEncodingEnabled(false);
    }

    private static ValidatableResponse post(String path, String body) {
        return elastic().body(body).post(path).then();
    }

    private static ValidatableResponse get(String path) {
        return elastic().get(path).then();
    }

    private static String ec2(String action, String path, String... params) {
        RequestSpecification request = given().header("Authorization", auth("ec2")).formParam("Action", action)
                .formParam("Version", "2016-11-15");
        for (int index = 0; index < params.length; index += 2) {
            request = request.formParam(params[index], params[index + 1]);
        }
        return request.post("/").then().statusCode(200).extract().xmlPath().getString(path);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private static String create(String name, String extra) {
        return post("/cluster", "{\"clusterName\":\"" + name + "\",\"adminUserName\":\"elasticadmin\","
                + "\"adminUserPassword\":\"" + PASSWORD + "\",\"authType\":\"PLAIN_TEXT\",\"shardCapacity\":2,"
                + "\"shardCount\":1" + extra + "}").statusCode(200).extract().path("cluster.clusterArn");
    }

    /** Runs mongosh in the cluster's MongoDB as its admin user and returns what it printed. */
    private String mongo(String clusterArn, String script) {
        String key = "elastic-" + clusterArn.substring(clusterArn.lastIndexOf('/') + 1);
        List<Container> containers = lifecycle.getDockerClient().listContainersCmd().withNameFilter(List.of(key))
                .exec();
        assertEquals(1, containers.size(), "one MongoDB container for " + clusterArn);
        ContainerExec.Result result = ContainerExec.run(lifecycle.getDockerClient(), containers.get(0).getId(),
                new String[] {"sh", "-c", "mongosh --quiet --port \"$FLOCI_DOCDB_PORT\" -u elasticadmin -p "
                        + PASSWORD + " --authenticationDatabase admin --eval \"" + script + "\""}, 60);
        assertEquals(0, result.exitCode(), result.summary());
        return result.stdout().trim();
    }

    private boolean hasContainer(String clusterArn) {
        String key = "elastic-" + clusterArn.substring(clusterArn.lastIndexOf('/') + 1);
        return !lifecycle.getDockerClient().listContainersCmd().withNameFilter(List.of(key)).exec().isEmpty();
    }

    @Test
    void dataSurvivesStopStartSnapshotsAndRestores() {
        String vpc = ec2("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", "10.71.0.0/16");
        String a = ec2("CreateSubnet", "CreateSubnetResponse.subnet.subnetId", "VpcId", vpc, "CidrBlock",
                "10.71.1.0/24", "AvailabilityZone", "us-east-1a");
        String b = ec2("CreateSubnet", "CreateSubnetResponse.subnet.subnetId", "VpcId", vpc, "CidrBlock",
                "10.71.2.0/24", "AvailabilityZone", "us-east-1b");
        String name = "orders-" + suffix();
        String arn = create(name, ",\"subnetIds\":[\"" + a + "\",\"" + b + "\"],\"tags\":{\"team\":\"data\"}");

        get("/cluster/" + arn).statusCode(200).body("cluster.status", equalTo("ACTIVE"))
                .body("cluster.clusterName", equalTo(name))
                .body("cluster.clusterEndpoint", containsString(name + "-" + ACCOUNT + ".us-east-1.docdb-elastic."))
                .body("cluster.kmsKeyId", equalTo("AWS_OWNED_KMS_KEY"))
                .body("cluster.shardInstanceCount", equalTo(2)).body("cluster.shards", hasSize(1))
                .body("cluster.subnetIds", hasSize(2)).body("cluster.vpcSecurityGroupIds", hasSize(1))
                .body("cluster.backupRetentionPeriod", equalTo(1));
        String interfaces = ec2("DescribeNetworkInterfaces",
                "DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", "Filter.1.Name", "vpc-id",
                "Filter.1.Value.1", vpc);
        assertEquals("2", interfaces);
        get("/tags/" + arn).statusCode(200).body("tags.team", equalTo("data"));
        get("/cluster-snapshots?clusterArn=" + arn + "&snapshotType=AUTOMATED").statusCode(200)
                .body("snapshots", hasSize(1));

        post("/cluster", "{\"clusterName\":\"" + name + "\",\"adminUserName\":\"elasticadmin\",\"adminUserPassword\":"
                + "\"" + PASSWORD + "\",\"authType\":\"PLAIN_TEXT\",\"shardCapacity\":2,\"shardCount\":1}")
                .statusCode(409).body("resourceType", equalTo("cluster"));

        mongo(arn, "db.getSiblingDB('shop').orders.insertMany([{n: 1}, {n: 2}, {n: 3}])");

        String snapshotName = name + "-snap";
        String snapshot = post("/cluster-snapshot", "{\"clusterArn\":\"" + arn + "\",\"snapshotName\":\""
                + snapshotName + "\",\"tags\":{\"kind\":\"manual\"}}").statusCode(200)
                .body("snapshot.snapshotType", equalTo("MANUAL")).body("snapshot.status", equalTo("ACTIVE"))
                .extract().path("snapshot.snapshotArn");
        post("/cluster-snapshot", "{\"clusterArn\":\"" + arn + "\",\"snapshotName\":\"" + snapshotName + "\"}")
                .statusCode(409).body("message", containsString("already exists"));

        post("/cluster/" + arn + "/stop", "").statusCode(200).body("cluster.status", equalTo("STOPPED"));
        assertFalse(hasContainer(arn));
        post("/cluster/" + arn + "/stop", "").statusCode(400).body("reason", equalTo("other"));
        elastic().delete("/cluster/" + arn).then().statusCode(400)
                .body("message", containsString("STOPPED state"));
        post("/cluster/" + arn + "/start", "").statusCode(200).body("cluster.status", equalTo("ACTIVE"));
        assertEquals("3", mongo(arn, "db.getSiblingDB('shop').orders.countDocuments()"));

        String copy = post("/cluster-snapshot/" + snapshot + "/copy", "{\"targetSnapshotName\":\"" + snapshotName
                + "-copy\",\"copyTags\":true}").statusCode(200).extract().path("snapshot.snapshotArn");
        get("/tags/" + copy).statusCode(200).body("tags.kind", equalTo("manual"));

        String restoredName = name + "-restored";
        String restored = post("/cluster-snapshot/" + copy + "/restore", "{\"clusterName\":\"" + restoredName
                + "\"}").statusCode(200).body("cluster.adminUserName", equalTo("elasticadmin"))
                .body("cluster.subnetIds", hasItem(a)).extract().path("cluster.clusterArn");
        assertEquals("3", mongo(restored, "db.getSiblingDB('shop').orders.countDocuments()"));

        elastic().body("{\"shardCount\":2,\"shardCapacity\":4}").put("/cluster/" + arn).then().statusCode(200)
                .body("cluster.shardCount", equalTo(2)).body("cluster.shards", hasSize(2))
                .body("cluster.shardCapacity", equalTo(4));
        elastic().body("{\"adminUserPassword\":\"Rotated" + PASSWORD + "\"}").put("/cluster/" + restored).then()
                .statusCode(200);

        elastic().delete("/cluster/" + arn).then().statusCode(200).body("cluster.status", equalTo("DELETING"));
        get("/cluster/" + arn).statusCode(404).body("resourceId", equalTo(arn));
        get("/cluster-snapshots?clusterArn=" + arn).statusCode(200)
                .body("snapshots.snapshotType", not(hasItem("AUTOMATED")))
                .body("snapshots.snapshotName", hasItem(snapshotName));
        elastic().delete("/cluster/" + restored).then().statusCode(200);
        assertFalse(hasContainer(restored));
        for (String id : List.of(snapshot, copy)) {
            elastic().delete("/cluster-snapshot/" + id).then().statusCode(200)
                    .body("snapshot.status", equalTo("DELETING"));
            get("/cluster-snapshot/" + id).statusCode(404);
        }
        assertEquals("", ec2("DescribeNetworkInterfaces",
                "DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId", "Filter.1.Name",
                "vpc-id", "Filter.1.Value.1", vpc));
    }

    @Test
    void requestsAreValidatedAndMissingResourcesReported() {
        String cluster = "arn:aws:docdb-elastic:us-east-1:" + ACCOUNT + ":cluster/" + MISSING;
        String snapshot = "arn:aws:docdb-elastic:us-east-1:" + ACCOUNT + ":cluster-snapshot/" + MISSING;
        get("/cluster/" + cluster).statusCode(404).body("resourceType", equalTo("cluster"));
        get("/cluster-snapshot/" + snapshot).statusCode(404);
        elastic().delete("/cluster-snapshot/" + snapshot).then().statusCode(404);
        get("/pending-action/" + cluster).statusCode(404);
        get("/tags/" + cluster).statusCode(404);
        get("/cluster/not-an-arn").statusCode(400).body("reason", equalTo("fieldValidationFailed"));
        post("/cluster-snapshot/" + snapshot + "/copy", "{\"targetSnapshotName\":\"copy\"}").statusCode(403);
        post("/cluster-snapshot/" + snapshot + "/restore", "{\"clusterName\":\"restored\"}").statusCode(403);

        post("/cluster", "{\"clusterName\":\"bad\",\"adminUserName\":\"elasticadmin\",\"adminUserPassword\":\""
                + PASSWORD + "\",\"authType\":\"PLAIN_TEXT\",\"shardCapacity\":3,\"shardCount\":1}")
                .statusCode(400).body("message", containsString("shardCapacity"));
        post("/cluster", "{\"clusterName\":\"bad--name\",\"adminUserName\":\"elasticadmin\",\"adminUserPassword\":\""
                + PASSWORD + "\",\"authType\":\"PLAIN_TEXT\",\"shardCapacity\":2,\"shardCount\":1}")
                .statusCode(400).body("message", containsString("consecutive hyphens"));
        post("/cluster", "{\"clusterName\":\"bad\",\"adminUserName\":\"elasticadmin\",\"adminUserPassword\":"
                + "\"short\",\"authType\":\"PLAIN_TEXT\",\"shardCapacity\":2,\"shardCount\":1}")
                .statusCode(400).body("message", containsString("8 to 100"));
        post("/cluster", "{\"clusterName\":\"bad\",\"adminUserName\":\"elasticadmin\",\"adminUserPassword\":\""
                + PASSWORD + "\",\"authType\":\"PLAIN_TEXT\",\"shardCapacity\":2,\"shardCount\":1,\"kmsKeyId\":"
                + "\"missing\"}").statusCode(400).body("message", containsString("KMS key"));

        String name = "defaults-" + suffix();
        String arn = create(name, "");
        get("/cluster/" + arn).statusCode(200).body("cluster.subnetIds.size()", oneOf(1, 2, 3));
        get("/clusters?maxResults=20").statusCode(200).body("clusters.clusterName", hasItem(name));
        get("/pending-action/" + arn).statusCode(200).body("resourcePendingMaintenanceAction.resourceArn",
                equalTo(arn)).body("resourcePendingMaintenanceAction.pendingMaintenanceActionDetails", hasSize(0));
        post("/pending-action", "{\"resourceArn\":\"" + arn + "\",\"applyAction\":\"ENGINE_UPDATE\","
                + "\"optInType\":\"IMMEDIATE\"}").statusCode(400).body("message", containsString("not pending"));
        get("/pending-actions").statusCode(200).body("resourcePendingMaintenanceActions", hasSize(0));
        post("/tags/" + arn, "{\"tags\":{\"aws:reserved\":\"x\"}}").statusCode(400);
        post("/tags/" + arn, "{\"tags\":{\"env\":\"test\",\"owner\":\"me\"}}").statusCode(200);
        elastic().delete("/tags/" + arn + "?tagKeys=owner").then().statusCode(200);
        get("/tags/" + arn).statusCode(200).body("tags.env", equalTo("test")).body("tags.owner", equalTo(null));
        elastic().delete("/cluster/" + arn).then().statusCode(200);
    }
}
