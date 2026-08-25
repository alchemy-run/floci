package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * JSON 1.1 MemoryDB user lifecycle used by Alchemy
 * {@code test/AWS/MemoryDB/User.test.ts}: AmazonMemoryDB.CreateUser /
 * DescribeUsers / UpdateUser / ListTags / TagResource / UntagResource /
 * DeleteUser.
 */
@QuarkusTest
class MemoryDbUserIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/memorydb/aws4_request";
    private static final String USER = "it-mdb-user-lifecycle";
    private static final String PASSWORD = "AlchemyMemoryDbTestPass01";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeUsersMissingName_returnsUserNotFoundFault() {
        memorydb("DescribeUsers", "{\"UserName\":\"alchemy-nonexistent-memorydb-user-probe\"}")
            .then()
                .statusCode(404)
                .body("__type", equalTo("UserNotFoundFault"));
    }

    @Test
    void createUpdateTagDeleteUser() {
        try {
            memorydb("DeleteUser", "{\"UserName\":\"" + USER + "\"}");
        } catch (Exception ignored) {
            // best-effort cleanup from a previous run
        }

        String arn = memorydb("CreateUser", "{"
                + "\"UserName\":\"" + USER + "\","
                + "\"AccessString\":\"on ~* +@all\","
                + "\"AuthenticationMode\":{\"Type\":\"password\",\"Passwords\":[\"" + PASSWORD + "\"]},"
                + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"memorydb-user\"}]}")
            .then()
                .statusCode(200)
                .body("User.Name", equalTo(USER))
                .body("User.Status", equalTo("active"))
                .body("User.AccessString", containsString("~*"))
                .body("User.Authentication.Type", equalTo("password"))
                .body("User.ARN", containsString(":user/"))
            .extract()
                .path("User.ARN");

        memorydb("DescribeUsers", "{\"UserName\":\"" + USER + "\"}")
            .then()
                .statusCode(200)
                .body("Users[0].Name", equalTo(USER))
                .body("Users[0].Status", equalTo("active"))
                .body("Users[0].Authentication.Type", equalTo("password"));

        memorydb("UpdateUser", "{"
                + "\"UserName\":\"" + USER + "\","
                + "\"AccessString\":\"on ~app:* +@read\"}")
            .then()
                .statusCode(200)
                .body("User.Name", equalTo(USER))
                .body("User.AccessString", containsString("~app:*"))
                .body("User.Status", equalTo("active"));

        memorydb("DescribeUsers", "{\"UserName\":\"" + USER + "\"}")
            .then()
                .statusCode(200)
                .body("Users[0].AccessString", containsString("~app:*"));

        memorydb("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
            .then()
                .statusCode(200)
                .body("TagList.find { it.Key == 'fixture' }.Value",
                        equalTo("memorydb-user"));

        memorydb("TagResource", "{\"ResourceArn\":\"" + arn + "\","
                + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
            .then()
                .statusCode(200)
                .body("TagList.find { it.Key == 'env' }.Value", equalTo("test"));

        memorydb("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"env\"]}")
            .then()
                .statusCode(200);

        memorydb("DeleteUser", "{\"UserName\":\"" + USER + "\"}")
            .then()
                .statusCode(200)
                .body("User.Name", equalTo(USER));

        memorydb("DescribeUsers", "{\"UserName\":\"" + USER + "\"}")
            .then()
                .statusCode(404)
                .body("__type", equalTo("UserNotFoundFault"));
    }

    private static Response memorydb(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonMemoryDB." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}
