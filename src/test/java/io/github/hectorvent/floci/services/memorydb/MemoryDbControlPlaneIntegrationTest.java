package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MemoryDbControlPlaneIntegrationTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void parameterGroupLifecyclePersistsOverridesResetAndTags() {
        String name = "mdb-parameter-lifecycle";
        String arn = call("CreateParameterGroup", Map.of("ParameterGroupName", name,
                "Family", "memorydb_valkey7", "Description", "initial",
                "Tags", new Object[]{Map.of("Key", "owner", "Value", "original")}))
                .then().statusCode(200).body("ParameterGroup.Family", equalTo("memorydb_valkey7"))
                .extract().path("ParameterGroup.ARN");
        try {
            call("CreateParameterGroup", Map.of("ParameterGroupName", name, "Family", "memorydb_valkey7"))
                    .then().statusCode(400).body("__type", equalTo("ParameterGroupAlreadyExistsFault"));
            call("ListTags", Map.of("ResourceArn", arn)).then().statusCode(200)
                    .body("TagList[0].Value", equalTo("original"));
            for (String value : new String[]{"allkeys-lru", "volatile-lru"}) {
                call("UpdateParameterGroup", Map.of("ParameterGroupName", name, "ParameterNameValues",
                        new Object[]{Map.of("ParameterName", "maxmemory-policy", "ParameterValue", value)}))
                        .then().statusCode(200).body("ParameterGroup.Name", equalTo(name));
                call("DescribeParameters", Map.of("ParameterGroupName", name)).then().statusCode(200)
                        .body("Parameters.find { it.Name == 'maxmemory-policy' }.Value", equalTo(value));
            }
            call("ResetParameterGroup", Map.of("ParameterGroupName", name,
                    "ParameterNames", new String[]{"maxmemory-policy"})).then().statusCode(200);
            call("DescribeParameters", Map.of("ParameterGroupName", name)).then().statusCode(200)
                    .body("Parameters.find { it.Name == 'maxmemory-policy' }.Value", equalTo("noeviction"));
            call("UpdateParameterGroup", Map.of("ParameterGroupName", name, "ParameterNameValues",
                    new Object[]{Map.of("ParameterName", "maxmemory-policy", "ParameterValue", "not-a-policy")}))
                    .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
            call("TagResource", Map.of("ResourceArn", arn,
                    "Tags", new Object[]{Map.of("Key", "owner", "Value", "updated")}))
                    .then().statusCode(200).body("TagList[0].Value", equalTo("updated"));
            call("UntagResource", Map.of("ResourceArn", arn, "TagKeys", new String[]{"owner"}))
                    .then().statusCode(200).body("TagList", empty());
        } finally {
            call("DeleteParameterGroup", Map.of("ParameterGroupName", name)).then().statusCode(200);
        }
        call("DescribeParameterGroups", Map.of("ParameterGroupName", name)).then().statusCode(400)
                .body("__type", equalTo("ParameterGroupNotFoundFault"));
    }

    @Test
    void subnetGroupUsesScopedEc2SubnetsAndUpdatesDescription() {
        String name = "mdb-subnet-lifecycle";
        String first = "subnet-default-us-east-1-a";
        String second = "subnet-default-us-east-1-b";
        call("CreateSubnetGroup", Map.of("SubnetGroupName", name, "SubnetIds", new String[]{first}),
                "eu-west-1", ACCOUNT).then().statusCode(400).body("__type", equalTo("InvalidSubnet"));
        String arn = call("CreateSubnetGroup", Map.of("SubnetGroupName", name, "Description", "initial",
                "SubnetIds", new String[]{first, second})).then().statusCode(200)
                .body("SubnetGroup.VpcId", notNullValue())
                .body("SubnetGroup.Subnets.Identifier", hasItem(first))
                .extract().path("SubnetGroup.ARN");
        try {
            call("UpdateSubnetGroup", Map.of("SubnetGroupName", name, "Description", "updated",
                    "SubnetIds", new String[]{second})).then().statusCode(200)
                    .body("SubnetGroup.Description", equalTo("updated"))
                    .body("SubnetGroup.Subnets.size()", equalTo(1));
            call("DescribeSubnetGroups", Map.of("SubnetGroupName", name)).then().statusCode(200)
                    .body("SubnetGroups[0].Description", equalTo("updated"));
            call("UpdateSubnetGroup", Map.of("SubnetGroupName", name,
                    "SubnetIds", new String[]{"subnet-not-found"})).then().statusCode(400)
                    .body("__type", equalTo("InvalidSubnet"));
            call("DescribeSubnetGroups", Map.of("SubnetGroupName", name)).then().statusCode(200)
                    .body("SubnetGroups[0].Subnets[0].Identifier", equalTo(second));
            call("TagResource", Map.of("ResourceArn", arn,
                    "Tags", new Object[]{Map.of("Key", "stage", "Value", "test")}))
                    .then().statusCode(200).body("TagList[0].Value", equalTo("test"));
        } finally {
            call("DeleteSubnetGroup", Map.of("SubnetGroupName", name)).then().statusCode(200);
        }
        call("DescribeSubnetGroups", Map.of("SubnetGroupName", name)).then().statusCode(400)
                .body("__type", equalTo("SubnetGroupNotFoundFault"));
    }

    @Test
    void userUpdateAndTagsDoNotResolveAsClusters() {
        String name = "mdb-user-lifecycle";
        String arn = call("CreateUser", Map.of("UserName", name, "AccessString", "on ~* +@all",
                "AuthenticationMode", Map.of("Type", "password", "Passwords", new String[]{"TestMemoryDbPassword"}),
                "Tags", new Object[]{Map.of("Key", "owner", "Value", "test")}))
                .then().statusCode(200).extract().path("User.ARN");
        try {
            call("ListTags", Map.of("ResourceArn", arn)).then().statusCode(200)
                    .body("TagList[0].Value", equalTo("test"));
            call("UpdateUser", Map.of("UserName", name, "AccessString", "on ~app:* +@read"))
                    .then().statusCode(200).body("User.AccessString", equalTo("on ~app:* +@read"))
                    .body("User.Authentication.PasswordCount", equalTo(1));
            call("TagResource", Map.of("ResourceArn", arn,
                    "Tags", new Object[]{Map.of("Key", "env", "Value", "updated")})).then().statusCode(200);
            call("UntagResource", Map.of("ResourceArn", arn, "TagKeys", new String[]{"owner"}))
                    .then().statusCode(200).body("TagList.size()", equalTo(1));
            call("ListTags", Map.of("ResourceArn", arn), "eu-west-1", ACCOUNT).then().statusCode(400)
                    .body("__type", equalTo("UserNotFoundFault"));
            call("ListTags", Map.of("ResourceArn", arn), REGION, "222222222222").then().statusCode(400)
                    .body("__type", equalTo("UserNotFoundFault"));
        } finally {
            call("DeleteUser", Map.of("UserName", name)).then().statusCode(200);
        }
        call("ListTags", Map.of("ResourceArn", arn)).then().statusCode(400)
                .body("__type", equalTo("UserNotFoundFault"));
    }

    @Test
    void readOperationsAndSnapshotNegativesUseAwsWireContracts() {
        call("DescribeSnapshots", Map.of()).then().statusCode(200).body("Snapshots", notNullValue());
        call("DescribeEvents", Map.of("SourceType", "cluster")).then().statusCode(200)
                .body("Events", notNullValue());
        call("DescribeServiceUpdates", Map.of()).then().statusCode(200).body("ServiceUpdates", empty());
        call("DescribeEngineVersions", Map.of("Engine", "valkey")).then().statusCode(200)
                .body("EngineVersions.size()", greaterThanOrEqualTo(1));
        call("DescribeEngineVersions", Map.of("Engine", "valkey", "EngineVersion", "999.0"))
                .then().statusCode(200).body("EngineVersions", empty());
        call("BatchUpdateCluster", Map.of("ClusterNames", new String[]{"missing"},
                "ServiceUpdate", Map.of("ServiceUpdateNameToApply", "missing-update")))
                .then().statusCode(400).body("__type", equalTo("ServiceUpdateNotFoundFault"));
        call("BatchUpdateCluster", Map.of("ClusterNames", new String[]{"missing"}))
                .then().statusCode(400).body("__type", equalTo("InvalidParameterCombinationException"));
        call("DeleteSnapshot", Map.of("SnapshotName", "missing")).then().statusCode(400)
                .body("__type", equalTo("SnapshotNotFoundFault"));
        call("CopySnapshot", Map.of("SourceSnapshotName", "missing", "TargetSnapshotName", "copy"))
                .then().statusCode(400).body("__type", equalTo("SnapshotNotFoundFault"));
    }

    @Test
    void groupsAndContinuationTokensCannotCrossAccountOrRegion() {
        String name = "mdb-scoped-params";
        call("CreateParameterGroup", Map.of("ParameterGroupName", name, "Family", "memorydb_valkey7"))
                .then().statusCode(200);
        try {
            for (String[] scope : new String[][]{{"eu-west-1", ACCOUNT}, {REGION, "222222222222"}}) {
                call("DescribeParameterGroups", Map.of("ParameterGroupName", name), scope[0], scope[1])
                        .then().statusCode(400).body("__type", equalTo("ParameterGroupNotFoundFault"));
                call("DeleteParameterGroup", Map.of("ParameterGroupName", name), scope[0], scope[1])
                        .then().statusCode(400).body("__type", equalTo("ParameterGroupNotFoundFault"));
            }
            String token = call("DescribeParameters", Map.of("ParameterGroupName", name, "MaxResults", 1))
                    .then().statusCode(200).body("Parameters.size()", equalTo(1))
                    .extract().path("NextToken");
            call("DescribeParameters", Map.of("ParameterGroupName", name, "MaxResults", 1, "NextToken", token))
                    .then().statusCode(200).body("Parameters.size()", equalTo(1));
            call("DescribeParameterGroups", Map.of("NextToken", token)).then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterValueException"));
            String engineToken = call("DescribeEngineVersions", Map.of("MaxResults", 1))
                    .then().statusCode(200).extract().path("NextToken");
            for (String[] scope : new String[][]{{"eu-west-1", ACCOUNT}, {REGION, "222222222222"}}) {
                call("DescribeEngineVersions", Map.of("NextToken", engineToken), scope[0], scope[1])
                        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
            }
            call("CreateParameterGroup", Map.of("ParameterGroupName", name, "Family", "memorydb_valkey8"),
                    REGION, "222222222222").then().statusCode(200);
            try {
                call("DescribeParameterGroups", Map.of("ParameterGroupName", name), REGION, "222222222222")
                        .then().statusCode(200).body("ParameterGroups[0].Family", equalTo("memorydb_valkey8"));
                call("DescribeParameterGroups", Map.of("ParameterGroupName", name))
                        .then().statusCode(200).body("ParameterGroups[0].Family", equalTo("memorydb_valkey7"));
            } finally {
                call("DeleteParameterGroup", Map.of("ParameterGroupName", name), REGION, "222222222222")
                        .then().statusCode(200);
            }
        } finally {
            call("DeleteParameterGroup", Map.of("ParameterGroupName", name)).then().statusCode(200);
        }
    }

    private Response call(String action, Map<String, Object> body) {
        return call(action, body, REGION, ACCOUNT);
    }

    private Response call(String action, Map<String, Object> body, String region, String account) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/"
                        + region + "/memorydb/aws4_request")
                .header("X-Amz-Target", "AmazonMemoryDB." + action)
                .body(body).post("/");
    }
}
