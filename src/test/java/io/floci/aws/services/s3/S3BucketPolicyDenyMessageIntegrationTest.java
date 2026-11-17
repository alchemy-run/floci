package io.floci.aws.services.s3;

import io.floci.aws.testing.S3IamEnforcementProfile;
import io.floci.aws.testutil.S3RequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThan;

/**
 * S3 names a same-account caller's bucket-policy denial, as AWS does since it added context to
 * access-denied errors: "User: … is not authorized to perform: s3:PutObject on resource: "…" with
 * an explicit deny in a resource-based policy".
 */
@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3BucketPolicyDenyMessageIntegrationTest {

    private static final S3RequestSigner ROOT = S3RequestSigner.signedAs("test", "test");

    @Test
    void aSameAccountExplicitDenyNamesTheCallerActionResourceAndPolicy() {
        String bucket = "deny-message-" + UUID.randomUUID().toString().substring(0, 8);
        given().filter(ROOT).when().put("/" + bucket).then().statusCode(200);
        given().filter(ROOT).body("""
                {"Version":"2012-10-17","Statement":[{"Sid":"Fenced","Effect":"Deny","Principal":"*",
                "Action":"s3:PutObject","Resource":"arn:aws:s3:::%s/locked/*"}]}""".formatted(bucket))
                .when().put("/" + bucket + "?policy").then().statusCode(lessThan(300));

        given().filter(ROOT).body("x").when().put("/" + bucket + "/locked/lease").then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("is not authorized to perform: s3:PutObject on resource: "))
                .body(containsString("arn:aws:s3:::" + bucket + "/locked/lease"))
                .body(containsString("with an explicit deny in a resource-based policy"));
        given().filter(ROOT).body("x").when().put("/" + bucket + "/open/lease").then().statusCode(200);
    }
}
