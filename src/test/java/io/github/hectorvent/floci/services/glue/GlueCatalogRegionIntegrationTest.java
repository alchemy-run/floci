package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class GlueCatalogRegionIntegrationTest {

    private static final String DATABASE = "glue_region_isolation";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final List<CatalogScope> SCOPES = List.of(
            new CatalogScope("777788889999", "us-east-1"),
            new CatalogScope("777788889999", "us-west-2"),
            new CatalogScope("888899990000", "us-east-1"),
            new CatalogScope("888899990000", "us-west-2"));

    @TestHTTPResource("/")
    URI endpoint;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void signedRequestsIsolateSameNameCatalogsAcrossAccountsAndRegions() throws Exception {
        List<CatalogScope> created = new ArrayList<>();
        try {
            for (CatalogScope scope : SCOPES) {
                post(scope, "GetDatabase", "{\"Name\":\"" + DATABASE + "\"}", 400)
                        .body("__type", equalTo("EntityNotFoundException"));
                post(scope, "GetTable", "{\"DatabaseName\":\"" + DATABASE + "\",\"Name\":\"records\"}", 400)
                        .body("__type", equalTo("EntityNotFoundException"));
                post(scope, "GetDatabases", "{}", 200)
                        .body("DatabaseList.findAll { it.Name == '" + DATABASE + "' }", empty());
                post(scope, "GetTables", "{\"DatabaseName\":\"" + DATABASE + "\"}", 200)
                        .body("TableList", empty());
                post(scope, "CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + DATABASE
                        + "\",\"Description\":\"" + scope.marker() + "\"}}", 200);
                created.add(scope);
                post(scope, "CreateTable", tableInput(scope.marker() + "-v0"), 200);
                post(scope, "UpdateTable", tableInput(scope.marker() + "-v1"), 200);
                post(scope, "CreatePartition", "{\"DatabaseName\":\"" + DATABASE
                        + "\",\"TableName\":\"records\",\"PartitionInput\":{\"Values\":[\"2026\"],"
                        + "\"Parameters\":{\"scope\":\"" + scope.marker() + "\"}}}", 200);
                post(scope, "CreateUserDefinedFunction", "{\"DatabaseName\":\"" + DATABASE
                        + "\",\"FunctionInput\":{\"FunctionName\":\"transform\",\"ClassName\":\""
                        + scope.marker() + "\"}}", 200);
            }
            for (CatalogScope scope : SCOPES) {
                assertCatalog(scope);
            }
            CatalogScope removed = created.getFirst();
            post(removed, "DeleteDatabase", "{\"Name\":\"" + DATABASE + "\"}", 200);
            created.removeFirst();
            post(removed, "GetDatabases", "{}", 200)
                    .body("DatabaseList.findAll { it.Name == '" + DATABASE + "' }", empty());
            post(removed, "GetTables", "{\"DatabaseName\":\"" + DATABASE + "\"}", 200)
                    .body("TableList", empty());
            post(removed, "GetUserDefinedFunctions", "{\"Pattern\":\"transform\"}", 200)
                    .body("UserDefinedFunctions.findAll { it.DatabaseName == '" + DATABASE + "' }", empty());
            for (CatalogScope scope : created) {
                assertCatalog(scope);
            }
        } finally {
            for (CatalogScope scope : created) {
                post(scope, "DeleteDatabase", "{\"Name\":\"" + DATABASE + "\"}", 200);
            }
        }
    }

    private void assertCatalog(CatalogScope scope) throws Exception {
        post(scope, "GetDatabase", "{\"Name\":\"" + DATABASE + "\"}", 200)
                .body("Database.Description", equalTo(scope.marker()))
                .body("Database.CatalogId", equalTo(scope.account()));
        post(scope, "GetDatabases", "{}", 200)
                .body("DatabaseList.findAll { it.Name == '" + DATABASE + "' }.Description", contains(scope.marker()));
        post(scope, "GetTable", "{\"DatabaseName\":\"" + DATABASE + "\",\"Name\":\"records\"}", 200)
                .body("Table.Description", equalTo(scope.marker() + "-v1"));
        post(scope, "GetTables", "{\"DatabaseName\":\"" + DATABASE + "\"}", 200)
                .body("TableList.Description", contains(scope.marker() + "-v1"));
        post(scope, "SearchTables", "{\"SearchText\":\"" + DATABASE + "\"}", 200)
                .body("TableList.Description", contains(scope.marker() + "-v1"));
        post(scope, "GetTableVersions", "{\"DatabaseName\":\"" + DATABASE + "\",\"TableName\":\"records\"}", 200)
                .body("TableVersions.Table.Description", contains(scope.marker() + "-v1", scope.marker() + "-v0"));
        post(scope, "GetPartitions", "{\"DatabaseName\":\"" + DATABASE + "\",\"TableName\":\"records\"}", 200)
                .body("Partitions.Parameters.scope", contains(scope.marker()));
        post(scope, "GetUserDefinedFunctions", "{\"DatabaseName\":\"" + DATABASE + "\",\"Pattern\":\".*\"}", 200)
                .body("UserDefinedFunctions.ClassName", contains(scope.marker()));
    }

    private static String tableInput(String description) {
        return "{\"DatabaseName\":\"" + DATABASE + "\",\"TableInput\":{\"Name\":\"records\","
                + "\"Description\":\"" + description + "\",\"PartitionKeys\":[{\"Name\":\"year\",\"Type\":\"string\"}]}}";
    }

    private ValidatableResponse post(CatalogScope scope, String operation, String body, int status) throws Exception {
        String target = "AWSGlue." + operation;
        String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String day = date.substring(0, 8);
        String credentialScope = day + "/" + scope.region() + "/glue/aws4_request";
        String signedHeaders = "host;x-amz-date;x-amz-target";
        String canonicalRequest = "POST\n/\n\nhost:" + endpoint.getRawAuthority()
                + "\nx-amz-date:" + date + "\nx-amz-target:" + target + "\n\n"
                + signedHeaders + "\n" + sha256(body);
        String stringToSign = "AWS4-HMAC-SHA256\n" + date + "\n" + credentialScope + "\n" + sha256(canonicalRequest);
        byte[] key = hmac("AWS4test".getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, scope.region());
        key = hmac(key, "glue");
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + scope.account() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return given().contentType(CONTENT_TYPE).header("Host", endpoint.getRawAuthority())
                .header("X-Amz-Date", date).header("X-Amz-Target", target).header("Authorization", authorization)
                .body(body).post(endpoint).then().statusCode(status);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private record CatalogScope(String account, String region) {
        String marker() {
            return account + "-" + region;
        }
    }
}
