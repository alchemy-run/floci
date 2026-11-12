package io.floci.aws.services.securitylake;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security Lake: a data lake backed by a real S3 bucket (with its lifecycle rules) and Glue database, AWS and custom
 * log sources with their Glue tables and crawler, subscribers with assumable roles, an SQS notification fed by the
 * bucket, exceptions, and offboarding that keeps the bucket.
 */
@QuarkusTest
class SecurityLakeIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification lake() {
        return given().header("Authorization", auth("securitylake")).contentType("application/json");
    }

    static RequestSpecification query(String service) {
        return given().header("Authorization", auth(service)).contentType("application/x-www-form-urlencoded");
    }

    static RequestSpecification glue(String action, String body) {
        return given().header("Authorization", auth("glue")).header("X-Amz-Target", "AWSGlue." + action)
                .contentType("application/x-amz-json-1.1").body(body);
    }

    static String role(String name, String service) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\""
                + service + "\"},\"Action\":\"sts:AssumeRole\"}]}";
        String xml = query("iam").formParam("Action", "CreateRole").formParam("Version", "2010-05-08")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().asString();
        return xml.replaceAll("(?s).*<Arn>([^<]+)</Arn>.*", "$1");
    }

    @Test
    void dataLakeSourcesAndSubscribers() {
        // Before onboarding.
        lake().get("/v1/subscribers/00000000-0000-4000-8000-000000000000").then().statusCode(401)
                .body("__type", containsString("UnauthorizedException"));
        lake().get("/v1/datalakes").then().statusCode(200).body("dataLakes", hasSize(0));

        String metastore = role("SecurityLakeMetastore", "lambda.amazonaws.com");
        lake().body("{\"configurations\":[{\"region\":\"us-east-1\"}],\"metaStoreManagerRoleArn\":"
                + "\"arn:aws:iam::000000000000:role/missing\"}").post("/v1/datalake").then().statusCode(400);
        String bucketArn = lake().body("{\"configurations\":[{\"region\":\"us-east-1\",\"lifecycleConfiguration\":"
                + "{\"expiration\":{\"days\":30}}}],\"metaStoreManagerRoleArn\":\"" + metastore + "\",\"tags\":"
                + "[{\"key\":\"team\",\"value\":\"sec\"}]}").post("/v1/datalake").then().statusCode(200)
                .body("dataLakes[0].createStatus", equalTo("INITIALIZED")).extract().path("dataLakes[0].s3BucketArn");
        String bucket = bucketArn.substring(bucketArn.lastIndexOf(':') + 1);
        assertTrue(bucket.startsWith("aws-security-data-lake-us-east-1-"), bucket);
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(lake().get("/v1/datalakes").then()
                .extract().path("dataLakes[0].createStatus")));
        given().header("Authorization", auth("s3")).get("/" + bucket + "?lifecycle").then().statusCode(200)
                .body(containsString("<Days>30</Days>"));
        glue("GetDatabase", "{\"Name\":\"amazon_security_lake_glue_db_us_east_1\"}").post("/").then()
                .statusCode(200);

        // Sources.
        lake().body("{\"sources\":[{\"sourceName\":\"ROUTE53\",\"regions\":[\"us-east-1\",\"eu-west-1\"]}]}")
                .post("/v1/datalake/logsources/aws").then().statusCode(200)
                .body("failed", equalTo(List.of("eu-west-1")));
        glue("GetTable", "{\"DatabaseName\":\"amazon_security_lake_glue_db_us_east_1\",\"Name\":"
                + "\"amazon_security_lake_table_us_east_1_route53_2_0\"}").post("/").then().statusCode(200)
                .body("Table.StorageDescriptor.Location", containsString("/aws/ROUTE53/2.0/"));
        String crawlerRole = role("SecurityLakeCrawler", "glue.amazonaws.com");
        String providerRole = lake().body("{\"sourceName\":\"app-audit\",\"eventClasses\":[\"FILE_ACTIVITY\"],"
                + "\"configuration\":{\"crawlerConfiguration\":{\"roleArn\":\"" + crawlerRole + "\"},"
                + "\"providerIdentity\":{\"principal\":\"000000000000\",\"externalId\":\"app\"}}}")
                .post("/v1/datalake/logsources/custom").then().statusCode(200)
                .body("source.provider.location", containsString("/ext/app-audit/1.0/"))
                .body("source.attributes.crawlerArn", containsString(":crawler/")).extract()
                .path("source.provider.roleArn");
        glue("GetCrawler", "{\"Name\":\"SecurityLake-app-audit-us-east-1\"}").post("/").then().statusCode(200);
        lake().body("{}").post("/v1/datalake/logsources/list").then()
                .body("sources[0].sources.awsLogSource.sourceName", hasItem("ROUTE53"))
                .body("sources[0].sources.customLogSource.sourceName", hasItem("app-audit"));

        // A subscriber reads through a role its identity assumes; its queue hears of new objects.
        lake().body("{\"subscriberName\":\"siem\",\"subscriberIdentity\":{\"principal\":\"111122223333\","
                + "\"externalId\":\"soc-x\"},\"sources\":[{\"awsLogSource\":{\"sourceName\":\"WAF\"}}]}")
                .post("/v1/subscribers").then().statusCode(400);
        String subscriber = lake().body("{\"subscriberName\":\"siem\",\"subscriberIdentity\":{\"principal\":"
                + "\"111122223333\",\"externalId\":\"soc-x\"},\"sources\":[{\"awsLogSource\":{\"sourceName\":\"ROUTE53\"}}],"
                + "\"tags\":[{\"key\":\"team\",\"value\":\"soc\"}]}").post("/v1/subscribers").then().statusCode(200)
                .body("subscriber.s3BucketArn", equalTo(bucketArn)).body("subscriber.roleArn", containsString("role/"))
                .extract().path("subscriber.subscriberId");
        String roleXml = query("iam").formParam("Action", "GetRole").formParam("Version", "2010-05-08")
                .formParam("RoleName", "AmazonSecurityLake-" + subscriber).post("/").then().statusCode(200).extract()
                .asString();
        assertTrue(roleXml.contains("111122223333") && roleXml.contains("ExternalId"), roleXml);
        String queue = lake().body("{\"configuration\":{\"sqsNotificationConfiguration\":{}}}")
                .post("/v1/subscribers/" + subscriber + "/notification").then().statusCode(200)
                .body("subscriberEndpoint", startsWith("arn:aws:sqs:")).extract().path("subscriberEndpoint");
        lake().body("{\"configuration\":{\"sqsNotificationConfiguration\":{}}}")
                .post("/v1/subscribers/" + subscriber + "/notification").then().statusCode(409);
        given().header("Authorization", auth("s3")).body("{\"finding\":1}")
                .put("/" + bucket + "/aws/ROUTE53/2.0/region=us-east-1/part-1.json").then().statusCode(200);
        String queueUrl = query("sqs").formParam("Action", "GetQueueUrl").formParam("QueueName",
                queue.substring(queue.lastIndexOf(':') + 1)).post("/").then().statusCode(200).extract().asString()
                .replaceAll("(?s).*<QueueUrl>([^<]+)</QueueUrl>.*", "$1");
        await().atMost(Duration.ofSeconds(10)).until(() -> query("sqs").formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl).post("/").then().extract().asString().contains("part-1.json"));

        // Exceptions: a custom source whose provider role is gone can't collect.
        lake().body("{\"subscriptionProtocol\":\"email\",\"notificationEndpoint\":\"sec@example.com\","
                + "\"exceptionTimeToLive\":30}").post("/v1/datalake/exceptions/subscription").then().statusCode(200);
        lake().body("{\"subscriptionProtocol\":\"email\",\"notificationEndpoint\":\"sec@example.com\"}")
                .post("/v1/datalake/exceptions/subscription").then().statusCode(409);
        lake().get("/v1/datalake/exceptions/subscription").then().body("exceptionTimeToLive", equalTo(30));
        String providerName = providerRole.substring(providerRole.lastIndexOf('/') + 1);
        query("iam").formParam("Action", "DeleteRolePolicy").formParam("Version", "2010-05-08")
                .formParam("RoleName", providerName).formParam("PolicyName", "SecurityLakeAccess").post("/").then()
                .statusCode(200);
        query("iam").formParam("Action", "DeleteRole").formParam("Version", "2010-05-08")
                .formParam("RoleName", providerName).post("/").then().statusCode(200);
        lake().body("{}").post("/v1/datalake/exceptions").then().body("exceptions", hasSize(1))
                .body("exceptions[0].exception", containsString(providerName));
        lake().body("{}").post("/v1/datalake/sources").then()
                .body("dataLakeSources.find { it.sourceName == 'app-audit' }.sourceStatuses[0].status",
                        equalTo("MISCONFIGURED"));

        // Offboarding: subscribers first; the bucket stays.
        lake().body("{\"regions\":[\"us-east-1\"]}").post("/v1/datalake/delete").then().statusCode(409);
        lake().delete("/v1/subscribers/" + subscriber).then().statusCode(200);
        query("sqs").formParam("Action", "GetQueueUrl").formParam("QueueName", queue.substring(queue.lastIndexOf(':')
                + 1)).post("/").then().statusCode(400);
        lake().body("{\"regions\":[\"us-east-1\"]}").post("/v1/datalake/delete").then().statusCode(200);
        lake().get("/v1/datalakes").then().body("dataLakes", hasSize(0));
        given().header("Authorization", auth("s3")).head("/" + bucket).then().statusCode(200);
        lake().get("/v1/subscribers").then().statusCode(401);
    }
}
