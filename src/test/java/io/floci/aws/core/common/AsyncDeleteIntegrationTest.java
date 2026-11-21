package io.floci.aws.core.common;

import io.floci.aws.testing.AsyncDeletesProfile;
import io.floci.aws.testing.Ec2Defaults;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * AWS deletes databases, cache clusters and tables in the background: the resource reads back in its deleting
 * state until it is gone, and a second delete meets the one already under way. A database or cache instance, a
 * table and a cluster endpoint refuse it; an RDS, DocumentDB or Neptune cluster accepts it. A deploy tool that
 * is interrupted mid-delete has to finish that delete, which can only be tested when the window exists.
 */
@QuarkusTest
@TestProfile(AsyncDeletesProfile.class)
class AsyncDeleteIntegrationTest {

    private static final Duration GONE_WITHIN = Duration.ofMillis(AsyncDeletesProfile.DELETING_MILLIS * 6);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aDynamoDbTableIsDeletingUntilItIsGone() {
        dynamodb("CreateTable", """
                {"TableName":"async-delete-table","BillingMode":"PAY_PER_REQUEST",
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}]}
                """).statusCode(200);

        dynamodb("DeleteTable", "{\"TableName\":\"async-delete-table\"}").statusCode(200);
        dynamodb("DescribeTable", "{\"TableName\":\"async-delete-table\"}").statusCode(200)
                .body("Table.TableStatus", equalTo("DELETING"));
        dynamodb("DeleteTable", "{\"TableName\":\"async-delete-table\"}").statusCode(400)
                .body("__type", containsString("ResourceInUseException"));

        await().atMost(GONE_WITHIN).untilAsserted(() ->
                dynamodb("DescribeTable", "{\"TableName\":\"async-delete-table\"}").statusCode(400)
                        .body("__type", containsString("ResourceNotFoundException")));
    }

    @Test
    void anRdsInstanceIsDeletingUntilItIsGone() {
        rds("CreateDBInstance", Map.of("DBInstanceIdentifier", "async-delete-db", "Engine", "mysql",
                "DBInstanceClass", "db.t3.micro", "MasterUsername", "admin", "MasterUserPassword", "password123",
                "AllocatedStorage", "20")).statusCode(200);

        rds("DeleteDBInstance", Map.of("DBInstanceIdentifier", "async-delete-db", "SkipFinalSnapshot", "true"))
                .statusCode(200);
        rds("DescribeDBInstances", Map.of("DBInstanceIdentifier", "async-delete-db")).statusCode(200)
                .body(containsString("<DBInstanceStatus>deleting</DBInstanceStatus>"));
        rds("DeleteDBInstance", Map.of("DBInstanceIdentifier", "async-delete-db", "SkipFinalSnapshot", "true"))
                .statusCode(400).body(containsString("InvalidDBInstanceState"));

        awaitGone(() -> rds("DescribeDBInstances", Map.of("DBInstanceIdentifier", "async-delete-db")),
                "DBInstanceNotFound");
    }

    @Test
    void anRdsClusterAndItsEndpointAreDeletingUntilTheyAreGone() {
        rds("CreateDBCluster", Map.of("DBClusterIdentifier", "async-delete-aurora", "Engine", "aurora-postgresql",
                "MasterUsername", "postgres", "MasterUserPassword", "password123")).statusCode(200);
        rds("CreateDBClusterEndpoint", Map.of("DBClusterIdentifier", "async-delete-aurora",
                "DBClusterEndpointIdentifier", "async-delete-reader", "EndpointType", "ANY")).statusCode(200);

        rds("DeleteDBClusterEndpoint", Map.of("DBClusterEndpointIdentifier", "async-delete-reader"))
                .statusCode(200).body(containsString("<Status>deleting</Status>"));
        rds("DescribeDBClusterEndpoints", Map.of("DBClusterEndpointIdentifier", "async-delete-reader"))
                .statusCode(200).body(containsString("<Status>deleting</Status>"));
        rds("DeleteDBClusterEndpoint", Map.of("DBClusterEndpointIdentifier", "async-delete-reader"))
                .statusCode(400).body(containsString("InvalidDBClusterEndpointStateFault"));
        await().atMost(GONE_WITHIN).untilAsserted(() ->
                rds("DescribeDBClusterEndpoints", Map.of("DBClusterEndpointIdentifier", "async-delete-reader"))
                        .body(not(containsString("async-delete-reader"))));

        assertClusterDeletesInTheBackground("async-delete-aurora");
    }

    @Test
    void aNeptuneClusterAndInstanceAreDeletingUntilTheyAreGone() {
        assertClusterAndInstanceDeleteInTheBackground("neptune", "async-delete-neptune", "db.r5.large");
    }

    @Test
    void aDocumentDbClusterAndInstanceAreDeletingUntilTheyAreGone() {
        assertClusterAndInstanceDeleteInTheBackground("docdb", "async-delete-docdb", "db.r5.large");
    }

    @Test
    void anElastiCacheClusterIsDeletingUntilItIsGone() {
        elasticache("CreateCacheCluster", Map.of("CacheClusterId", "async-delete-cache", "Engine", "memcached"))
                .statusCode(200);

        elasticache("DeleteCacheCluster", Map.of("CacheClusterId", "async-delete-cache")).statusCode(200)
                .body(containsString("<CacheClusterStatus>deleting</CacheClusterStatus>"));
        elasticache("DescribeCacheClusters", Map.of("CacheClusterId", "async-delete-cache")).statusCode(200)
                .body(containsString("<CacheClusterStatus>deleting</CacheClusterStatus>"));
        elasticache("DeleteCacheCluster", Map.of("CacheClusterId", "async-delete-cache")).statusCode(400)
                .body(containsString("InvalidCacheClusterState"));

        awaitGone(() -> elasticache("DescribeCacheClusters", Map.of("CacheClusterId", "async-delete-cache")),
                "CacheClusterNotFound");
    }

    @Test
    void anInfluxDbInstanceIsDeletingUntilItIsGone() {
        String id = influx("CreateDbInstance", """
                {"name":"async-delete-influx","password":"password123","dbInstanceType":"db.influx.medium",
                 "allocatedStorage":20,"vpcSubnetIds":["%s"],"vpcSecurityGroupIds":["%s"]}
                """.formatted(Ec2Defaults.SUBNET_A, Ec2Defaults.SECURITY_GROUP)).statusCode(200).extract().path("id");

        influx("DeleteDbInstance", "{\"identifier\":\"" + id + "\"}").statusCode(200)
                .body("status", equalTo("DELETING"));
        influx("GetDbInstance", "{\"identifier\":\"" + id + "\"}").statusCode(200)
                .body("status", equalTo("DELETING"));
        influx("DeleteDbInstance", "{\"identifier\":\"" + id + "\"}").statusCode(400)
                .body("__type", containsString("ValidationException"));

        await().atMost(GONE_WITHIN).untilAsserted(() ->
                influx("GetDbInstance", "{\"identifier\":\"" + id + "\"}")
                        .body("__type", containsString("ResourceNotFoundException")));
    }

    /** The instance refuses a second delete; the cluster, deleted once the instance is gone, accepts one. */
    private static void assertClusterAndInstanceDeleteInTheBackground(String engine, String clusterId,
                                                                      String instanceClass) {
        String instanceId = clusterId + "-instance";
        rds("CreateDBCluster", Map.of("DBClusterIdentifier", clusterId, "Engine", engine,
                "MasterUsername", "admin1", "MasterUserPassword", "password123")).statusCode(200);
        rds("CreateDBInstance", Map.of("DBInstanceIdentifier", instanceId, "DBClusterIdentifier", clusterId,
                "Engine", engine, "DBInstanceClass", instanceClass)).statusCode(200);

        rds("DeleteDBInstance", Map.of("DBInstanceIdentifier", instanceId)).statusCode(200);
        rds("DescribeDBInstances", Map.of("DBInstanceIdentifier", instanceId)).statusCode(200)
                .body(containsString("<DBInstanceStatus>deleting</DBInstanceStatus>"));
        rds("DeleteDBInstance", Map.of("DBInstanceIdentifier", instanceId)).statusCode(400)
                .body(containsString("InvalidDBInstanceState"));
        awaitGone(() -> rds("DescribeDBInstances", Map.of("DBInstanceIdentifier", instanceId)), "DBInstanceNotFound");

        assertClusterDeletesInTheBackground(clusterId);
    }

    private static void assertClusterDeletesInTheBackground(String clusterId) {
        Map<String, String> delete = Map.of("DBClusterIdentifier", clusterId, "SkipFinalSnapshot", "true");
        rds("DeleteDBCluster", delete).statusCode(200);
        rds("DescribeDBClusters", Map.of("DBClusterIdentifier", clusterId)).statusCode(200)
                .body(containsString("<Status>deleting</Status>"));
        rds("DeleteDBCluster", delete).statusCode(200);
        awaitGone(() -> rds("DescribeDBClusters", Map.of("DBClusterIdentifier", clusterId)),
                "DBClusterNotFoundFault");
    }

    private static void awaitGone(Supplier<ValidatableResponse> describe, String notFound) {
        await().atMost(GONE_WITHIN).untilAsserted(() ->
                describe.get().statusCode(404).body(containsString(notFound)));
    }

    private static ValidatableResponse rds(String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261120/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static ValidatableResponse elasticache(String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261120/us-east-1/elasticache/"
                        + "aws4_request, SignedHeaders=content-type;host, Signature=test")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static ValidatableResponse dynamodb(String action, String body) {
        return given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .body(body).when().post("/").then();
    }

    private static ValidatableResponse influx(String action, String body) {
        return given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AmazonTimestreamInfluxDB." + action)
                .body(body).when().post("/").then();
    }
}
