package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Host-style Function URLs are rewritten to the path-style route by a pre-matching filter. The
 * function must still receive the request line exactly as sent: {@code %2B} is a literal plus,
 * while {@code +} is a space.
 */
@QuarkusTest
class LambdaFunctionUrlRawQueryTest {

    private static final String ACCOUNT = "567890123456";
    private static final String FUNCTION_NAME = "function-url-raw-query";
    private static final String RAW_QUERY = "key=presign%2Fa+b%2B%25%3F%23%2F%E9%9B%AA.txt&n=1";

    @Test
    void hostStyleUrlPassesRawPathAndQueryVerbatim() throws Exception {
        String functionUrl = createFunctionUrl();
        String urlId = urlId(functionUrl);

        given()
                .urlEncodingEnabled(false)
                .header("Host", urlId + ".lambda-url.us-east-1.localhost")
                .when().get("/presign/a%20b%2Bc?" + RAW_QUERY)
                .then()
                .statusCode(200)
                .body("rawQueryString", equalTo(RAW_QUERY))
                .body("rawPath", equalTo("/presign/a%20b%2Bc"))
                .body("key", equalTo("presign/a b+%?#/\u96ea.txt"));
    }

    @Test
    void pathStyleUrlPassesRawQueryVerbatim() throws Exception {
        String functionUrl = createFunctionUrl();
        String urlId = urlId(functionUrl);

        given()
                .urlEncodingEnabled(false)
                .when().get("/lambda-url/" + urlId + "/presign?" + RAW_QUERY)
                .then()
                .statusCode(200)
                .body("rawQueryString", equalTo(RAW_QUERY))
                .body("rawPath", equalTo("/presign"));
    }

    private String createFunctionUrl() throws Exception {
        String authorization = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260803/us-east-1/lambda/aws4_request";
        String zip = Base64.getEncoder().encodeToString(lambdaZip());

        int status = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "FunctionName":"%s",
                          "Runtime":"nodejs20.x",
                          "Role":"arn:aws:iam::%s:role/lambda-role",
                          "Handler":"index.handler",
                          "Code":{"ZipFile":"%s"}
                        }
                        """.formatted(FUNCTION_NAME, ACCOUNT, zip))
                .when().post("/2015-03-31/functions")
                .then()
                .extract().statusCode();
        if (status == 201) {
            given()
                    .contentType("application/json")
                    .header("Authorization", authorization)
                    .body("""
                            {"AuthType":"NONE"}
                            """)
                    .when().post("/2021-10-31/functions/" + FUNCTION_NAME + "/url")
                    .then()
                    .statusCode(201);
        }
        return given()
                .header("Authorization", authorization)
                .when().get("/2021-10-31/functions/" + FUNCTION_NAME + "/url")
                .then()
                .statusCode(200)
                .extract().path("FunctionUrl");
    }

    private byte[] lambdaZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("""
                    exports.handler = async (event) => ({
                      statusCode: 200,
                      headers: { "content-type": "application/json; charset=utf-8" },
                      body: JSON.stringify({
                        rawPath: event.rawPath,
                        rawQueryString: event.rawQueryString,
                        key: new URLSearchParams(event.rawQueryString).get("key")
                      })
                    });
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private String urlId(String functionUrl) {
        int scheme = functionUrl.indexOf("://");
        int firstDot = functionUrl.indexOf('.', scheme + 3);
        return functionUrl.substring(scheme + 3, firstDot);
    }
}
