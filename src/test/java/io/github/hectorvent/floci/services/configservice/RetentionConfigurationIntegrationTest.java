package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetentionConfigurationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void describeNonexistentNameThrows() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeRetentionConfigurations")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionConfigurationNames": ["alchemy-nonexistent-retention-probe"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchRetentionConfigurationException"));
    }

    @Test
    @Order(2)
    void describeEmptyWhenNoneExist() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeRetentionConfigurations")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfigurations", anyOf(nullValue(), empty()));
    }

    @Test
    @Order(3)
    void putRetentionConfiguration() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutRetentionConfiguration")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionPeriodInDays": 90}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfiguration.Name", equalTo("default"))
            .body("RetentionConfiguration.RetentionPeriodInDays", equalTo(90));
    }

    @Test
    @Order(4)
    void describeRetentionConfigurations() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeRetentionConfigurations")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfigurations", hasSize(1))
            .body("RetentionConfigurations[0].Name", equalTo("default"))
            .body("RetentionConfigurations[0].RetentionPeriodInDays", equalTo(90));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeRetentionConfigurations")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionConfigurationNames": ["default"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfigurations", hasSize(1))
            .body("RetentionConfigurations[0].RetentionPeriodInDays", equalTo(90));
    }

    @Test
    @Order(5)
    void putUpdatesTheSingleton() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutRetentionConfiguration")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionPeriodInDays": 180}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfiguration.Name", equalTo("default"))
            .body("RetentionConfiguration.RetentionPeriodInDays", equalTo(180));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeRetentionConfigurations")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfigurations", hasSize(1))
            .body("RetentionConfigurations[0].RetentionPeriodInDays", equalTo(180));
    }

    @Test
    @Order(6)
    void putRejectsOutOfRangePeriod() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutRetentionConfiguration")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionPeriodInDays": 7}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(7)
    void deleteRetentionConfiguration() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteRetentionConfiguration")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionConfigurationName": "default"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeRetentionConfigurations")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RetentionConfigurations", anyOf(nullValue(), empty()));
    }

    @Test
    @Order(8)
    void deleteNonexistentThrows() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteRetentionConfiguration")
            .contentType(CONTENT_TYPE)
            .body("""
                {"RetentionConfigurationName": "default"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchRetentionConfigurationException"));
    }
}
