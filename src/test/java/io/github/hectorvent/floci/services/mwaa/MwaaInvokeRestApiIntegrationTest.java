package io.github.hectorvent.floci.services.mwaa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * InvokeRestApi (POST /restapi/{Name}) against mock MWAA. The missing-environment
 * 404 is the wire shape distilled maps to ResourceNotFoundException.
 */
@QuarkusTest
class MwaaInvokeRestApiIntegrationTest {

    private static final String JSON = "application/json";
    private static final String ENV = "mwaa-invoke-rest-api-it";

    private static String createBody() {
        return "{\"ExecutionRoleArn\":\"arn:aws:iam::000000000000:role/mwaa-execution-role\","
                + "\"SourceBucketArn\":\"arn:aws:s3:::mwaa-it-bucket\","
                + "\"DagS3Path\":\"dags\","
                + "\"NetworkConfiguration\":{\"SubnetIds\":[\"subnet-1\",\"subnet-2\"],"
                + "\"SecurityGroupIds\":[\"sg-1\"]}}";
    }

    @Test
    void invokeRestApiOnMissingEnvironmentReturnsResourceNotFound() {
        given().contentType(JSON)
                .body("{\"Method\":\"GET\",\"Path\":\"/dags\"}")
                .when().post("/restapi/alchemy-mwaa-nonexistent-probe")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void invokeRestApiGetDagsOnExistingEnvironment() {
        given().contentType(JSON)
                .body(createBody())
                .when().put("/environments/" + ENV)
                .then().statusCode(200);

        given().contentType(JSON)
                .body("{\"Method\":\"GET\",\"Path\":\"/dags\"}")
                .when().post("/restapi/" + ENV)
                .then().statusCode(200)
                .body("RestApiStatusCode", equalTo(200))
                .body("RestApiResponse.total_entries", equalTo(0))
                .body("RestApiResponse.dags", notNullValue())
                .body("RestApiResponse.dags", hasSize(0));

        given().contentType(JSON)
                .when().delete("/environments/" + ENV)
                .then().statusCode(200);
    }

    @Test
    void invokeRestApiMissingMethodIsValidationException() {
        given().contentType(JSON)
                .body(createBody())
                .when().put("/environments/" + ENV + "-validation")
                .then().statusCode(200);

        given().contentType(JSON)
                .body("{\"Path\":\"/dags\"}")
                .when().post("/restapi/" + ENV + "-validation")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given().contentType(JSON)
                .when().delete("/environments/" + ENV + "-validation")
                .then().statusCode(200);
    }
}
