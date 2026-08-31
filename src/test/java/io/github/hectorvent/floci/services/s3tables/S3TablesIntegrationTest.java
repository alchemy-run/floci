package io.github.hectorvent.floci.services.s3tables;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3TablesIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=000000000810/20260205/us-east-1/s3tables/aws4_request";
    private static final String BUCKET = "alchemy-s3tables-lifecycle";
    private static final String NAMESPACE = "events";
    private static final String TABLE = "page_views";

    private String bucketArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createTableBucket() {
        bucketArn = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"name\":\"" + BUCKET + "\"}")
                .when()
                .put("/buckets")
                .then()
                .statusCode(200)
                .body("arn", containsString("arn:aws:s3tables:"))
                .body("arn", containsString(":bucket/" + BUCKET))
                .extract()
                .path("arn");
    }

    @Test
    @Order(2)
    void getTableBucket() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .get("/buckets/" + encode(bucketArn))
                .then()
                .statusCode(200)
                .body("name", equalTo(BUCKET))
                .body("arn", equalTo(bucketArn))
                .body("ownerAccountId", notNullValue())
                .body("createdAt", notNullValue());
    }

    @Test
    @Order(3)
    void listTableBuckets() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/buckets")
                .then()
                .statusCode(200)
                .body("tableBuckets", notNullValue())
                .body("tableBuckets.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(4)
    void duplicateTableBucketConflicts() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"name\":\"" + BUCKET + "\"}")
                .when()
                .put("/buckets")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    @Order(5)
    void createNamespace() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"namespace\":[\"" + NAMESPACE + "\"]}")
                .when()
                .put("/namespaces/" + encode(bucketArn))
                .then()
                .statusCode(200)
                .body("tableBucketARN", equalTo(bucketArn))
                .body("namespace[0]", equalTo(NAMESPACE));
    }

    @Test
    @Order(6)
    void getNamespace() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .get("/namespaces/" + encode(bucketArn) + "/" + NAMESPACE)
                .then()
                .statusCode(200)
                .body("namespace[0]", equalTo(NAMESPACE))
                .body("ownerAccountId", notNullValue())
                .body("createdAt", notNullValue());
    }

    @Test
    @Order(7)
    void listNamespaces() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .get("/namespaces/" + encode(bucketArn))
                .then()
                .statusCode(200)
                .body("namespaces.size()", greaterThanOrEqualTo(1))
                .body("namespaces[0].namespace[0]", equalTo(NAMESPACE));
    }

    @Test
    @Order(8)
    void createTable() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("""
                        {
                          "name": "%s",
                          "format": "ICEBERG",
                          "metadata": {
                            "iceberg": {
                              "schema": {
                                "fields": [
                                  {"name": "id", "type": "long", "required": true}
                                ]
                              }
                            }
                          }
                        }
                        """.formatted(TABLE))
                .when()
                .put("/tables/" + encode(bucketArn) + "/" + NAMESPACE)
                .then()
                .statusCode(200)
                .body("tableARN", containsString("/table/"))
                .body("versionToken", notNullValue());
    }

    @Test
    @Order(9)
    void getTable() {
        given()
                .header("Authorization", AUTH)
                .queryParam("tableBucketARN", bucketArn)
                .queryParam("namespace", NAMESPACE)
                .queryParam("name", TABLE)
                .when()
                .get("/get-table")
                .then()
                .statusCode(200)
                .body("name", equalTo(TABLE))
                .body("namespace[0]", equalTo(NAMESPACE))
                .body("format", equalTo("ICEBERG"))
                .body("tableARN", containsString("/table/"))
                .body("warehouseLocation", notNullValue())
                .body("versionToken", notNullValue());
    }

    @Test
    @Order(10)
    void listTables() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .get("/tables/" + encode(bucketArn))
                .then()
                .statusCode(200)
                .body("tables.size()", greaterThanOrEqualTo(1))
                .body("tables[0].name", equalTo(TABLE));
    }

    @Test
    @Order(11)
    void deleteTableBucketProtectsChildren() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .delete("/buckets/" + encode(bucketArn))
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
    }

    @Test
    @Order(12)
    void deleteNamespaceProtectsTables() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .delete("/namespaces/" + encode(bucketArn) + "/" + NAMESPACE)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }

    @Test
    @Order(13)
    void deleteTableNamespaceAndBucket() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .delete("/tables/" + encode(bucketArn) + "/" + NAMESPACE + "/" + TABLE)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .delete("/namespaces/" + encode(bucketArn) + "/" + NAMESPACE)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .delete("/buckets/" + encode(bucketArn))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(14)
    void getDeletedTableBucketIsNotFound() {
        given()
                .header("Authorization", AUTH)
                .urlEncodingEnabled(false)
                .when()
                .get("/buckets/" + encode(bucketArn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
