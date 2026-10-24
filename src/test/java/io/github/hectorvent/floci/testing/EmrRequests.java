package io.github.hectorvent.floci.testing;

import io.restassured.response.Response;

import static io.restassured.RestAssured.given;

/** One EMR JSON-protocol call, in the default Region or a named one. */
public final class EmrRequests {
    private EmrRequests() {}

    public static Response emr(String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "ElasticMapReduce." + action)
                .body(body).when().post("/");
    }

    public static Response emr(String region, String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region
                        + "/elasticmapreduce/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", "ElasticMapReduce." + action)
                .body(body).when().post("/");
    }
}
