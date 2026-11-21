package io.floci.aws.services.opensearchserverless;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Collections on OpenSearch: the index APIs and the collection endpoint need network and data access policies, index
 * and search documents, and ingestion jobs and streams index the documents of S3 objects.
 */
@QuarkusTest
@TestProfile(AossEngineProfile.class)
@Tag("docker")
class AossDockerIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return AossIntegrationTest.auth(service);
    }

    static long count(String host, String index) {
        Object count = given().header("Host", host).header("Authorization", auth("aoss")).get("/" + index
                + "/_count").then().extract().path("count");
        return count == null ? -1 : ((Number) count).longValue();
    }

    @Test
    void collectionsServeIndexesAndIngestion() {
        AossIntegrationTest.aoss("CreateSecurityPolicy", "{\"type\":\"encryption\",\"name\":\"engine-enc\",\"policy\":"
                + AossIntegrationTest.encryption("engine-*") + "}").post("/").then().statusCode(200);
        String id = AossIntegrationTest.aoss("CreateCollection", "{\"name\":\"engine-docs\",\"standbyReplicas\":"
                + "\"DISABLED\"}").post("/").then().statusCode(200).extract().path("createCollectionDetail.id");
        AossIntegrationTest.awaitActive(id);
        String endpoint = AossIntegrationTest.aoss("BatchGetCollection", "{\"ids\":[\"" + id + "\"]}").post("/").then()
                .extract().path("collectionDetails[0].collectionEndpoint");
        String host = URI.create(endpoint).getAuthority();

        AossIntegrationTest.aoss("CreateIndex", "{\"id\":\"" + id + "\",\"indexName\":\"docs\"}").post("/").then()
                .statusCode(403).body("message", containsString("No network policy"));
        AossIntegrationTest.aoss("CreateSecurityPolicy", "{\"type\":\"network\",\"name\":\"engine-net\",\"policy\":"
                + AossIntegrationTest.network("engine-*") + "}").post("/").then().statusCode(200);
        AossIntegrationTest.aoss("CreateIndex", "{\"id\":\"" + id + "\",\"indexName\":\"docs\"}").post("/").then()
                .statusCode(403).body("message", containsString("permissions"));
        AossIntegrationTest.aoss("CreateAccessPolicy", "{\"type\":\"data\",\"name\":\"engine-data\",\"policy\":"
                + AossIntegrationTest.data("engine-docs", "arn:aws:iam::000000000000:root") + "}").post("/").then()
                .statusCode(200);
        AossIntegrationTest.aoss("CreateIndex", "{\"id\":\"" + id + "\",\"indexName\":\"docs\",\"indexSchema\":{"
                + "\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}}").post("/").then().statusCode(200);
        AossIntegrationTest.aoss("CreateIndex", "{\"id\":\"" + id + "\",\"indexName\":\"docs\"}").post("/").then()
                .statusCode(400).body("__type", containsString("ConflictException"));
        AossIntegrationTest.aoss("UpdateIndex", "{\"id\":\"" + id + "\",\"indexName\":\"docs\",\"indexSchema\":{"
                + "\"mappings\":{\"properties\":{\"body\":{\"type\":\"keyword\"}}}}}").post("/").then().statusCode(200);
        AossIntegrationTest.aoss("GetIndex", "{\"id\":\"" + id + "\",\"indexName\":\"docs\"}").post("/").then()
                .statusCode(200).body("indexSchema.mappings.properties.title.type", equalTo("text"))
                .body("indexSchema.mappings.properties.body.type", equalTo("keyword"));

        given().header("Host", host).contentType("application/json").body("{\"title\":\"x\"}")
                .post("/docs/_doc").then().statusCode(403);
        given().header("Host", host).header("Authorization", auth("aoss")).contentType("application/json")
                .body("{\"title\":\"serverless search\",\"body\":\"one\"}").post("/docs/_doc?refresh=true").then()
                .statusCode(201);
        given().header("Host", host).header("Authorization", auth("aoss")).contentType("application/json")
                .body("{\"query\":{\"match\":{\"title\":\"serverless\"}}}").post("/docs/_search").then()
                .statusCode(200).body("hits.total.value", equalTo(1))
                .body("hits.hits[0]._source.body", equalTo("one"));

        given().header("Authorization", auth("s3")).put("/aoss-ingest");
        given().header("Authorization", auth("s3")).contentType("text/plain")
                .body("{\"title\":\"a\"}\n{\"title\":\"b\"}\nnot json\n").put("/aoss-ingest/batch/one.ndjson").then()
                .statusCode(200);
        String job = AossIntegrationTest.aoss("CreateIngestionJob", "{\"collectionId\":\"" + id + "\","
                + "\"ingestionJobName\":\"backfill\",\"indexName\":\"ingested\",\"failureIndexName\":\"ingest-failures\","
                + "\"source\":{\"s3\":{\"bucket\":\"aoss-ingest\",\"keyPrefix\":\"batch/\"}}}").post("/").then()
                .statusCode(200).extract().path("ingestionJobDetail.ingestionJobId");
        await().atMost(Duration.ofSeconds(60)).until(() -> "COMPLETED".equals(AossIntegrationTest.aoss(
                "GetIngestionJob", "{\"collectionId\":\"" + id + "\",\"ingestionJobId\":\"" + job + "\"}").post("/")
                .then().extract().path("ingestionJobDetail.status")));
        AossIntegrationTest.aoss("GetIngestionJob", "{\"collectionId\":\"" + id + "\",\"ingestionJobId\":\"" + job
                + "\"}").post("/").then().body("ingestionJobDetail.statistics.s3.objectsDiscovered", equalTo(1))
                .body("ingestionJobDetail.statistics.s3.objectsFailed", equalTo(1));
        await().atMost(Duration.ofSeconds(20)).until(() -> count(host, "ingested") == 2);
        await().atMost(Duration.ofSeconds(20)).until(() -> count(host, "ingest-failures") == 1);

        given().header("Authorization", auth("sqs")).header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .contentType("application/x-amz-json-1.0").body("{\"QueueName\":\"aoss-stream\"}").post("/").then()
                .statusCode(200);
        given().header("Authorization", auth("s3")).header("x-amz-skip-destination-validation", "true")
                .contentType("application/xml").queryParam("notification", "")
                .body("<NotificationConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><QueueConfiguration>"
                        + "<Id>s</Id><Queue>arn:aws:sqs:us-east-1:000000000000:aoss-stream</Queue>"
                        + "<Event>s3:ObjectCreated:*</Event></QueueConfiguration></NotificationConfiguration>")
                .put("/aoss-ingest").then().statusCode(200);
        String stream = AossIntegrationTest.aoss("CreateIngestionStream", "{\"collectionId\":\"" + id + "\","
                + "\"ingestionStreamName\":\"live\",\"indexName\":\"streamed\",\"roleArn\":"
                + "\"arn:aws:iam::000000000000:role/aoss-stream\",\"source\":{\"s3\":{\"sqsArn\":"
                + "\"arn:aws:sqs:us-east-1:000000000000:aoss-stream\",\"buckets\":[{\"bucket\":\"aoss-ingest\"}]}}}")
                .post("/").then().statusCode(200).extract().path("ingestionStreamDetail.ingestionStreamId");
        await().atMost(Duration.ofSeconds(20)).until(() -> "ACTIVE".equals(AossIntegrationTest.aoss(
                "GetIngestionStream", "{\"collectionId\":\"" + id + "\",\"ingestionStreamId\":\"" + stream + "\"}")
                .post("/").then().extract().path("ingestionStreamDetail.status")));
        given().header("Authorization", auth("s3")).contentType("text/plain").body("[{\"title\":\"c\"},{\"title\":"
                + "\"d\"},{\"title\":\"e\"}]").put("/aoss-ingest/live/two.json").then().statusCode(200);
        await().atMost(Duration.ofSeconds(60)).until(() -> count(host, "streamed") == 3);

        AossIntegrationTest.aoss("DeleteIndex", "{\"id\":\"" + id + "\",\"indexName\":\"docs\"}").post("/").then()
                .statusCode(200);
        AossIntegrationTest.aoss("GetIndex", "{\"id\":\"" + id + "\",\"indexName\":\"docs\"}").post("/").then()
                .statusCode(400).body("__type", containsString("ResourceNotFoundException"));
        AossIntegrationTest.aoss("DeleteCollection", "{\"id\":\"" + id + "\"}").post("/").then().statusCode(200);
    }
}
