package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Drives the FeatureGroup control plane over the SageMaker JSON protocol and the online store
 * over the featurestore-runtime REST-JSON routes, both signed as {@code sagemaker}.
 */
@QuarkusTest
class SageMakerFeatureStoreIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sagemaker/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeOnMissingResourcesIsTypedResourceNotFound() {
        post("SageMaker.DescribeFeatureGroup", "{\"FeatureGroupName\":\"fs-missing-probe\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFound"));
        post("SageMaker.DescribeCluster", "{\"ClusterName\":\"hp-missing-probe\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFound"));
        post("SageMaker.DescribeClusterSchedulerConfig", "{\"ClusterSchedulerConfigId\":\"abcdef012345\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFound"));
        post("SageMaker.DescribeComputeQuota", "{\"ComputeQuotaId\":\"abcdef012345\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFound"));
    }

    @Test
    void onlineStoreRoundTripOverTheRuntimeRoutes() {
        String group = "fs-it-" + Long.toString(System.nanoTime(), 36);
        post("SageMaker.CreateFeatureGroup", """
                {"FeatureGroupName":"%s","RecordIdentifierFeatureName":"user_id","EventTimeFeatureName":"event_time",
                 "FeatureDefinitions":[{"FeatureName":"user_id","FeatureType":"String"},
                                       {"FeatureName":"event_time","FeatureType":"String"},
                                       {"FeatureName":"clicks","FeatureType":"Integral"}],
                 "OnlineStoreConfig":{"EnableOnlineStore":true}}
                """.formatted(group)).then().statusCode(200);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).until(() ->
                "Created".equals(post("SageMaker.DescribeFeatureGroup", "{\"FeatureGroupName\":\"" + group + "\"}")
                        .then().extract().path("FeatureGroupStatus")));

        runtime().body("""
                {"Record":[{"FeatureName":"user_id","ValueAsString":"u1"},
                           {"FeatureName":"event_time","ValueAsString":"2026-01-01T00:00:00.000Z"},
                           {"FeatureName":"clicks","ValueAsString":"42"}]}
                """).when().put("/FeatureGroup/" + group).then().statusCode(200);

        runtime().queryParam("RecordIdentifierValueAsString", "u1")
                .when().get("/FeatureGroup/" + group)
                .then().statusCode(200).body("Record.find { it.FeatureName == 'clicks' }.ValueAsString", equalTo("42"));

        runtime().body("{}").when().post("/FeatureGroup/" + group + "/ListRecords")
                .then().statusCode(200).body("RecordIdentifiers", hasItem("u1"));

        runtime().body("""
                {"Identifiers":[{"FeatureGroupName":"%s","RecordIdentifiersValueAsString":["u1","missing"]}]}
                """.formatted(group)).when().post("/BatchGetRecord")
                .then().statusCode(200).body("Records", hasSize(1)).body("Errors", hasSize(0));

        runtime().body("""
                {"Record":[{"FeatureName":"user_id","ValueAsString":"u2"},
                           {"FeatureName":"event_time","ValueAsString":"2026-01-01T00:00:00Z"},
                           {"FeatureName":"clicks","ValueAsString":"not-a-number"}]}
                """).when().put("/FeatureGroup/" + group)
                .then().statusCode(400).header("X-Amzn-Errortype", equalTo("ValidationError"));

        runtime().queryParam("RecordIdentifierValueAsString", "u1")
                .queryParam("EventTime", "2026-01-01T00:00:01Z")
                .when().delete("/FeatureGroup/" + group).then().statusCode(200);
        runtime().queryParam("RecordIdentifierValueAsString", "u1")
                .when().get("/FeatureGroup/" + group)
                .then().statusCode(200).body("$", not(org.hamcrest.Matchers.hasKey("Record")));

        runtime().queryParam("RecordIdentifierValueAsString", "u1")
                .when().get("/FeatureGroup/fs-it-missing-group")
                .then().statusCode(400).body("message", org.hamcrest.Matchers.containsString("Resource Not Found"));

        post("SageMaker.DeleteFeatureGroup", "{\"FeatureGroupName\":\"" + group + "\"}").then().statusCode(200);
    }

    private io.restassured.specification.RequestSpecification runtime() {
        return given().header("Authorization", AUTH).contentType("application/json");
    }

    private Response post(String target, String body) {
        return given().header("Authorization", AUTH)
                .header("X-Amz-Target", target)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }
}
