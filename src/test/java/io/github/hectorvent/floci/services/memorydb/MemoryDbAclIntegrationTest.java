package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * JSON 1.1 MemoryDB ACL control-plane coverage used by Alchemy:
 * DescribeACLs (typed ACLNotFoundFault), CreateACL with custom users and
 * tags, UpdateACL membership, ListTags/TagResource, and DeleteACL.
 */
@QuarkusTest
class MemoryDbAclIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/memorydb/aws4_request";
    private static final String USER = "it-acl-user";
    private static final String USER_TWO = "it-acl-user-two";
    private static final String ACL = "it-app-acl";
    private static final String PASSWORD = "AlchemyMemoryDbTestPass01";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeAcls_missingName_returnsAclNotFoundFault() {
        memorydb("DescribeACLs", "{\"ACLName\":\"alchemy-nonexistent-acl-probe\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ACLNotFoundFault"));
    }

    @Test
    void aclLifecycle_createDescribeTagUpdateDelete() {
        memorydb("CreateUser", "{"
                + "\"UserName\":\"" + USER + "\","
                + "\"AccessString\":\"on ~* +@all\","
                + "\"AuthenticationMode\":{\"Type\":\"password\",\"Passwords\":[\"" + PASSWORD + "\"]}}")
                .then()
                .statusCode(200)
                .body("User.Name", equalTo(USER));

        memorydb("CreateUser", "{"
                + "\"UserName\":\"" + USER_TWO + "\","
                + "\"AccessString\":\"on ~app:* +@read\","
                + "\"AuthenticationMode\":{\"Type\":\"password\",\"Passwords\":[\"" + PASSWORD + "\"]}}")
                .then()
                .statusCode(200);

        String arn = memorydb("CreateACL", "{"
                + "\"ACLName\":\"" + ACL + "\","
                + "\"UserNames\":[\"" + USER + "\"],"
                + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"memorydb-acl\"}]}")
                .then()
                .statusCode(200)
                .body("ACL.Name", equalTo(ACL))
                .body("ACL.Status", equalTo("active"))
                .body("ACL.UserNames", hasItem(USER))
                .body("ACL.UserNames", not(hasItem("default")))
                .body("ACL.ARN", equalTo("arn:aws:memorydb:us-east-1:000000000000:acl/" + ACL))
                .extract()
                .path("ACL.ARN");

        memorydb("DescribeACLs", "{\"ACLName\":\"" + ACL + "\"}")
                .then()
                .statusCode(200)
                .body("ACLs[0].Name", equalTo(ACL))
                .body("ACLs[0].Status", equalTo("active"))
                .body("ACLs[0].UserNames", hasItem(USER));

        memorydb("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then()
                .statusCode(200)
                .body("TagList.find { it.Key == 'fixture' }.Value", equalTo("memorydb-acl"));

        memorydb("TagResource", "{"
                + "\"ResourceArn\":\"" + arn + "\","
                + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
                .then()
                .statusCode(200)
                .body("TagList.find { it.Key == 'env' }.Value", equalTo("test"));

        memorydb("UpdateACL", "{"
                + "\"ACLName\":\"" + ACL + "\","
                + "\"UserNamesToAdd\":[\"" + USER_TWO + "\"],"
                + "\"UserNamesToRemove\":[\"" + USER + "\"]}")
                .then()
                .statusCode(200)
                .body("ACL.UserNames", hasItem(USER_TWO))
                .body("ACL.UserNames", not(hasItem(USER)));

        memorydb("DeleteACL", "{\"ACLName\":\"" + ACL + "\"}")
                .then()
                .statusCode(200)
                .body("ACL.Name", equalTo(ACL));

        memorydb("DescribeACLs", "{\"ACLName\":\"" + ACL + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ACLNotFoundFault"));

        memorydb("DeleteUser", "{\"UserName\":\"" + USER + "\"}").then().statusCode(200);
        memorydb("DeleteUser", "{\"UserName\":\"" + USER_TWO + "\"}").then().statusCode(200);
    }

    @Test
    void createAcl_withDefaultUser_isRejected() {
        memorydb("CreateACL", "{\"ACLName\":\"it-bad-default\",\"UserNames\":[\"default\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
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
