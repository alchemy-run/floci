package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * EMR release catalog over the AWS JSON 1.1 wire protocol: ListReleaseLabels
 * (newest first), DescribeReleaseLabel (applications including Spark), and
 * ListSupportedInstanceTypes.
 */
@QuarkusTest
class EmrCatalogIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "ElasticMapReduce.";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.response.Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    @Test
    void listReleaseLabelsNewestFirst() {
        call("ListReleaseLabels", "{}")
                .then().statusCode(200)
                .body("ReleaseLabels", hasSize(greaterThan(0)))
                .body("ReleaseLabels[0]", startsWith("emr-"))
                .body("ReleaseLabels[0]", equalTo("emr-7.9.0"))
                .body("ReleaseLabels", hasItem("emr-7.5.0"));
    }

    @Test
    void listReleaseLabelsPrefixFilter() {
        call("ListReleaseLabels", "{\"Filters\":{\"Prefix\":\"emr-7.5\"}}")
                .then().statusCode(200)
                .body("ReleaseLabels", hasItem("emr-7.5.0"))
                .body("ReleaseLabels", not(hasItem("emr-7.4.0")));
    }

    @Test
    void describeReleaseLabelIncludesSpark() {
        call("DescribeReleaseLabel", "{\"ReleaseLabel\":\"emr-7.5.0\"}")
                .then().statusCode(200)
                .body("ReleaseLabel", equalTo("emr-7.5.0"))
                .body("Applications.Name", hasItem("Spark"));
    }

    @Test
    void describeUnknownReleaseLabelIsInvalidRequest() {
        call("DescribeReleaseLabel", "{\"ReleaseLabel\":\"emr-0.0.0\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void listSupportedInstanceTypesForRelease() {
        call("ListSupportedInstanceTypes", "{\"ReleaseLabel\":\"emr-7.5.0\"}")
                .then().statusCode(200)
                .body("SupportedInstanceTypes", hasSize(greaterThan(0)))
                .body("SupportedInstanceTypes.Type", hasItem("m5.xlarge"));
    }

    @Test
    void listSupportedInstanceTypesRequiresReleaseLabel() {
        call("ListSupportedInstanceTypes", "{}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }
}
