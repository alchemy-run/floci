package io.floci.aws.services.lambdaruntimes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Customer-managed runtimes (Lambda Core 2026-10-01): runtime, version and alias lifecycles,
 * Lambda's delete and restore rules, runtime tags and resource policies, and functions that run
 * on a runtime alias. Each test uses its own account so runtime names never collide.
 */
@QuarkusTest
class LambdaRuntimesIntegrationTest {

    private static final String CORE = "/2026-10-01";
    private static final String IMAGE = "000000000000.dkr.ecr.us-east-1.amazonaws.com/node-runtime@sha256:"
            + "4f6a8c0e2b4d6f8a0c2e4b6d8f0a2c4e6b8d0f2a4c6e8b0d2f4a6c8e0b2d4f6a";
    private static final String IMAGE_V2 = "000000000000.dkr.ecr.us-east-1.amazonaws.com/node-runtime@sha256:"
            + "0b2d4f6a8c0e2b4d6f8a0c2e4b6d8f0a2c4e6b8d0f2a4c6e8b0d2f4a6c8e0b2d";

    @Test
    void runtimeVersionAndAliasLifecycle() {
        String auth = auth("000000000701");
        String arn = given().header("Authorization", auth).contentType("application/json")
                .body("""
                        {"Name": "companynode", "Description": "Node 24", "LicenseInfo": "MIT",
                         "Tags": {"team": "platform"}}
                        """)
                .when().post(CORE + "/runtimes")
                .then().statusCode(201)
                .body("Name", equalTo("companynode"))
                .body("Arn", endsWith(":runtime/companynode"))
                .body("LicenseInfo", equalTo("MIT"))
                .extract().path("Arn");

        given().header("Authorization", auth)
                .when().get(CORE + "/runtimes/companynode")
                .then().statusCode(200)
                .body("State", equalTo("ACTIVE"))
                .body("Description", equalTo("Node 24"));

        // A runtime can also be named by its ARN.
        given().header("Authorization", auth)
                .when().get(CORE + "/runtimes/{runtime}", arn)
                .then().statusCode(200)
                .body("Name", equalTo("companynode"));

        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Name\": \"companynode\"}")
                .when().post(CORE + "/runtimes")
                .then().statusCode(409);

        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Name\": \"v1\", \"ImageUri\": \"" + IMAGE + "\", \"ReleaseNotes\": \"first\"}")
                .when().post(CORE + "/runtimes/companynode/versions")
                .then().statusCode(201)
                .body("State", equalTo("CREATING"))
                .body("Arn", equalTo(arn + "/version/v1"))
                .body("ResolvedUri", equalTo(IMAGE))
                .body("Compatibility.Architectures", hasItem("x86_64"));

        given().header("Authorization", auth)
                .when().get(CORE + "/runtimes/companynode/versions/v1")
                .then().statusCode(200)
                .body("State", equalTo("ACTIVE"))
                .body("ReleaseNotes", equalTo("first"));

        given().header("Authorization", auth).contentType("application/json")
                .body("{\"ReleaseNotes\": \"updated\"}")
                .when().patch(CORE + "/runtimes/companynode/versions/v1")
                .then().statusCode(200)
                .body("ReleaseNotes", equalTo("updated"));

        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Name\": \"prod\", \"RuntimeVersion\": \"v1\", \"Description\": \"Production\"}")
                .when().post(CORE + "/runtimes/companynode/aliases")
                .then().statusCode(201)
                .body("State", equalTo("CREATING"))
                .body("RuntimeVersionArn", equalTo(arn + "/version/v1"));

        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Name\": \"v2\", \"ImageUri\": \"" + IMAGE_V2 + "\"}")
                .when().post(CORE + "/runtimes/companynode/versions")
                .then().statusCode(201);

        given().header("Authorization", auth).contentType("application/json")
                .body("{\"RuntimeVersion\": \"v2\", \"Description\": \"Production\"}")
                .when().put(CORE + "/runtimes/companynode/aliases/prod")
                .then().statusCode(200)
                .body("RuntimeVersion", equalTo("v2"))
                .body("RuntimeName", equalTo("companynode"));

        given().header("Authorization", auth)
                .when().get(CORE + "/runtimes/companynode/aliases")
                .then().statusCode(200)
                .body("Items[0].Name", equalTo("prod"))
                .body("Items[0].State", equalTo("ACTIVE"))
                .body("Items[0].RuntimeVersion", equalTo("v2"));

        given().header("Authorization", auth)
                .when().get(CORE + "/runtimes")
                .then().statusCode(200)
                .body("Items.Name", hasItem("companynode"));
    }

    @Test
    void deletionRulesAndRestore() {
        String auth = auth("000000000702");
        createRuntimeWithAlias(auth, "doomed");

        // A runtime with live children can't be deleted.
        given().header("Authorization", auth)
                .when().delete(CORE + "/runtimes/doomed")
                .then().statusCode(409)
                .body("message", containsString("Delete all versions and aliases"));

        // A version an alias points to can't be deleted.
        given().header("Authorization", auth)
                .when().delete(CORE + "/runtimes/doomed/versions/v1")
                .then().statusCode(409);

        given().header("Authorization", auth)
                .header("x-amzn-runtime-recovery-window-in-days", "7")
                .when().delete(CORE + "/runtimes/doomed/aliases/prod")
                .then().statusCode(202);
        given().header("Authorization", auth)
                .header("x-amzn-runtime-recovery-window-in-days", "7")
                .when().delete(CORE + "/runtimes/doomed/versions/v1")
                .then().statusCode(202);
        given().header("Authorization", auth)
                .header("x-amzn-runtime-recovery-window-in-days", "7")
                .when().delete(CORE + "/runtimes/doomed")
                .then().statusCode(202);

        given().header("Authorization", auth)
                .when().get(CORE + "/runtimes/doomed")
                .then().statusCode(200)
                .body("State", equalTo("PENDING_DELETION"))
                .body("PendingDeletionAt", notNullValue());

        // The name stays reserved while the runtime is pending deletion.
        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Name\": \"doomed\"}")
                .when().post(CORE + "/runtimes")
                .then().statusCode(409);

        // Children restore only after their parent.
        given().header("Authorization", auth)
                .when().post(CORE + "/runtimes/doomed/restore?RuntimeAlias=prod")
                .then().statusCode(409)
                .body("message", containsString("Restore the runtime first"));
        given().header("Authorization", auth)
                .when().post(CORE + "/runtimes/doomed/restore")
                .then().statusCode(200)
                .body("State", equalTo("ACTIVE"));
        given().header("Authorization", auth)
                .when().post(CORE + "/runtimes/doomed/restore?RuntimeAlias=prod")
                .then().statusCode(409)
                .body("message", containsString("Restore the runtime version first"));
        given().header("Authorization", auth)
                .when().post(CORE + "/runtimes/doomed/restore?RuntimeVersion=v1")
                .then().statusCode(200)
                .body("RuntimeVersion", equalTo("v1"));
        given().header("Authorization", auth)
                .when().post(CORE + "/runtimes/doomed/restore?RuntimeAlias=prod")
                .then().statusCode(200)
                .body("RuntimeAlias", equalTo("prod"))
                .body("State", equalTo("ACTIVE"));

        given().header("Authorization", auth)
                .when().get(CORE + "/runtimes/doomed/aliases/prod")
                .then().statusCode(200)
                .body("State", equalTo("ACTIVE"))
                .body("PendingDeletionAt", nullValue());
    }

    @Test
    void recoveryWindowOutOfRangeIsRejected() {
        String auth = auth("000000000703");
        createRuntimeWithAlias(auth, "window");
        given().header("Authorization", auth)
                .header("x-amzn-runtime-recovery-window-in-days", "3")
                .when().delete(CORE + "/runtimes/window/aliases/prod")
                .then().statusCode(400);
    }

    @Test
    void runtimeTagsAndResourcePolicy() {
        String auth = auth("000000000704");
        String arn = createRuntimeWithAlias(auth, "shared");

        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Tags\": {\"alchemy::id\": \"Node\"}}")
                .when().post("/2017-03-31/tags/{arn}", arn)
                .then().statusCode(204);
        given().header("Authorization", auth)
                .when().get("/2017-03-31/tags/{arn}", arn)
                .then().statusCode(200)
                .body("Tags.'alchemy::id'", equalTo("Node"));
        given().header("Authorization", auth)
                .queryParam("tagKeys", "alchemy::id")
                .when().delete("/2017-03-31/tags/{arn}", arn)
                .then().statusCode(204);
        given().header("Authorization", auth)
                .when().get("/2017-03-31/tags/{arn}", arn)
                .then().statusCode(200)
                .body("Tags.'alchemy::id'", nullValue());

        given().header("Authorization", auth)
                .when().get("/2026-07-09/resource-policy/{arn}", arn)
                .then().statusCode(404);

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:aws:iam::111122223333:root\"},"
                + "\"Action\":\"lambda:AssociateRuntimeAlias\",\"Resource\":\"" + arn + "/alias/*\"}]}";
        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Policy\": " + quote(policy) + "}")
                .when().put("/2026-07-09/resource-policy/{arn}", arn)
                .then().statusCode(200)
                .body("RevisionId", notNullValue());
        given().header("Authorization", auth)
                .when().get("/2026-07-09/resource-policy/{arn}", arn)
                .then().statusCode(200)
                .body("Policy", containsString("lambda:AssociateRuntimeAlias"));
        given().header("Authorization", auth)
                .when().delete("/2026-07-09/resource-policy/{arn}", arn)
                .then().statusCode(204);
        given().header("Authorization", auth)
                .when().get("/2026-07-09/resource-policy/{arn}", arn)
                .then().statusCode(404);
    }

    @Test
    void functionRunsOnRuntimeAlias() {
        String accountId = "000000000705";
        String auth = auth(accountId);
        String arn = createRuntimeWithAlias(auth, "fnruntime");

        given().header("Authorization", auth).contentType("application/json")
                .body("""
                        {"FunctionName": "on-custom", "Runtime": "custom",
                         "RuntimeConfig": {"RuntimeArn": "%s", "RuntimeAlias": "prod"},
                         "Role": "arn:aws:iam::%s:role/lambda-role", "Handler": "index.handler",
                         "Code": {"ZipFile": "%s"}}
                        """.formatted(arn, accountId, zipBase64()))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201)
                .body("Runtime", equalTo("custom"))
                .body("RuntimeConfig.RuntimeArn", equalTo(arn))
                .body("RuntimeConfig.RuntimeAlias", equalTo("prod"));

        given().header("Authorization", auth)
                .when().get("/2015-03-31/functions/on-custom/configuration")
                .then().statusCode(200)
                .body("RuntimeVersionConfig.RuntimeVersionArn", equalTo(arn + "/version/v1"));

        // A runtime named instead of by ARN is stored as its full ARN.
        given().header("Authorization", auth).contentType("application/json")
                .body("""
                        {"FunctionName": "on-custom-by-name", "Runtime": "custom",
                         "RuntimeConfig": {"RuntimeArn": "fnruntime", "RuntimeAlias": "prod"},
                         "Role": "arn:aws:iam::%s:role/lambda-role", "Handler": "index.handler",
                         "Code": {"ZipFile": "%s"}}
                        """.formatted(accountId, zipBase64()))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201)
                .body("RuntimeConfig.RuntimeArn", equalTo(arn));

        // A missing alias is rejected at create.
        given().header("Authorization", auth).contentType("application/json")
                .body("""
                        {"FunctionName": "on-missing", "Runtime": "custom",
                         "RuntimeConfig": {"RuntimeArn": "%s", "RuntimeAlias": "nope"},
                         "Role": "arn:aws:iam::%s:role/lambda-role", "Handler": "index.handler",
                         "Code": {"ZipFile": "%s"}}
                        """.formatted(arn, accountId, zipBase64()))
                .when().post("/2015-03-31/functions")
                .then().statusCode(400);

        // Back to a managed runtime: RuntimeConfig is dropped.
        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Runtime\": \"nodejs24.x\"}")
                .when().put("/2015-03-31/functions/on-custom/configuration")
                .then().statusCode(200)
                .body("Runtime", equalTo("nodejs24.x"))
                .body("RuntimeConfig", nullValue());
    }

    private static String createRuntimeWithAlias(String auth, String name) {
        String arn = given().header("Authorization", auth).contentType("application/json")
                .body("{\"Name\": \"" + name + "\"}")
                .when().post(CORE + "/runtimes")
                .then().statusCode(201)
                .extract().path("Arn");
        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Name\": \"v1\", \"ImageUri\": \"" + IMAGE + "\"}")
                .when().post(CORE + "/runtimes/" + name + "/versions")
                .then().statusCode(201);
        given().header("Authorization", auth).contentType("application/json")
                .body("{\"Name\": \"prod\", \"RuntimeVersion\": \"v1\"}")
                .when().post(CORE + "/runtimes/" + name + "/aliases")
                .then().statusCode(201);
        return arn;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/lambda/aws4_request";
    }

    private static String zipBase64() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("index.js"));
                zip.write("exports.handler = async () => ({ ok: true });".getBytes());
                zip.closeEntry();
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
