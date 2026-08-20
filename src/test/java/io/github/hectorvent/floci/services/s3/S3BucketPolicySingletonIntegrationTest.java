package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3BucketPolicySingletonIntegrationTest {

    private static final String BUCKET = "policy-singleton-int-test";

    @Test
    @Order(1)
    void createBucket() {
        given().when().put("/" + BUCKET).then().statusCode(200);
    }

    @Test
    @Order(2)
    void putCollapsesSingletonActionAndResourceArrays() {
        given()
            .body("""
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": { "Service": "cloudfront.amazonaws.com" },
                        "Action": ["s3:GetObject"],
                        "Resource": ["arn:aws:s3:::policy-singleton-int-test/*"]
                      }]
                    }
                    """)
        .when()
            .put("/" + BUCKET + "?policy")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?policy")
        .then()
            .statusCode(200)
            .body(containsString("s3:GetObject"))
            .body(containsString("arn:aws:s3:::policy-singleton-int-test/*"))
            .body(not(containsString("[\"s3:GetObject\"]")));
    }

    @Test
    @Order(3)
    void deleteBucket() {
        given().when().delete("/" + BUCKET).then().statusCode(204);
    }
}
