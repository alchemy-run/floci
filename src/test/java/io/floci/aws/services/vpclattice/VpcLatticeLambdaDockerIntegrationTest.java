package io.floci.aws.services.vpclattice;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * A Lambda target behind a VPC Lattice service, on a real container: the function receives Lattice's V2 event
 * (method, path, headers, query, body, request context) and its response becomes the HTTP response.
 */
@QuarkusTest
@Tag("docker")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class VpcLatticeLambdaDockerIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String HANDLER = """
            exports.handler = async (event) => ({
              statusCode: 201,
              headers: { "content-type": "application/json", "x-from": "lambda" },
              body: JSON.stringify({ version: event.version, method: event.method, path: event.path,
                q: event.queryStringParameters.q, agent: event.headers["x-agent"], body: event.body,
                service: event.requestContext.serviceArn, group: event.requestContext.targetGroupArn })
            });
            """;

    @Inject
    VpcLatticeDataPlane dataPlane;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification lattice() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/vpc-lattice/aws4_request")
                .contentType("application/json");
    }

    private static void awaitActive(String path) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> lattice().get(path).then().statusCode(200)
                .body("status", equalTo("ACTIVE")));
    }

    @Test
    void lambdaTargetsGetLatticeEvents() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(HANDLER.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String function = given().contentType("application/json").body("{\"FunctionName\":\"lattice-target-fn\","
                        + "\"Runtime\":\"nodejs20.x\",\"Handler\":\"index.handler\",\"Role\":\"arn:aws:iam::" + ACCOUNT
                        + ":role/lambda-role\",\"Timeout\":30,\"Code\":{\"ZipFile\":\"" + Base64.getEncoder()
                        .encodeToString(bytes.toByteArray()) + "\"}}").post("/2015-03-31/functions").then()
                .statusCode(201).extract().path("FunctionArn");
        try {
            String group = lattice().body("{\"name\":\"fn-target\",\"type\":\"LAMBDA\",\"config\":"
                    + "{\"lambdaEventStructureVersion\":\"V2\"}}").post("/targetgroups").then().statusCode(201)
                    .extract().path("id");
            awaitActive("/targetgroups/" + group);
            lattice().body("{\"targets\":[{\"id\":\"" + function + "\"}]}")
                    .post("/targetgroups/" + group + "/registertargets").then().body("successful.size()", equalTo(1));
            String service = lattice().body("{\"name\":\"fn-service\"}").post("/services").then().statusCode(201)
                    .extract().path("id");
            awaitActive("/services/" + service);
            String host = lattice().get("/services/" + service).then().extract().path("dnsEntry.domainName");
            lattice().body("{\"name\":\"http\",\"protocol\":\"HTTP\",\"defaultAction\":{\"forward\":{\"targetGroups\":"
                    + "[{\"targetGroupIdentifier\":\"" + group + "\"}]}}}").post("/services/" + service + "/listeners")
                    .then().statusCode(201);
            int port = await().atMost(Duration.ofSeconds(10)).until(() -> dataPlane.flociPort("HTTP", 80).orElse(0),
                    p -> p > 0);
            await().atMost(Duration.ofMinutes(2)).untilAsserted(() -> given().baseUri("http://127.0.0.1").port(port)
                    .header("Host", host).header("x-agent", "tester").contentType("text/plain").body("ping")
                    .queryParam("q", "shoes").post("/search").then().statusCode(201).header("x-from", "lambda")
                    .body("version", equalTo("2.0")).body("method", equalTo("POST")).body("path", equalTo("/search"))
                    .body("q", equalTo(List.of("shoes"))).body("agent", equalTo(List.of("tester")))
                    .body("body", equalTo("ping")).body("group", equalTo("arn:aws:vpc-lattice:us-east-1:" + ACCOUNT
                            + ":targetgroup/" + group)));
        } finally {
            given().delete("/2015-03-31/functions/lattice-target-fn").then().statusCode(204);
        }
    }
}
