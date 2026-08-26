package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Lambda FileSystemConfigs round-trip: CreateFunction stores the EFS access-point
 * mount and GetFunctionConfiguration returns it. Used by Alchemy's EFS LambdaMount
 * suite, which then writes through {@code /mnt/test} on a Docker named volume.
 */
@QuarkusTest
class LambdaFileSystemConfigIntegrationTest {

    private static final String EAST = "us-east-1";

    @Inject
    Ec2Service ec2Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createFunctionStoresFileSystemConfigs() throws Exception {
        String efsAuth = "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + EAST + "/elasticfilesystem/aws4_request";
        Subnet subnet = defaultSubnet();
        String token = UUID.randomUUID().toString();
        String fileSystemId = given()
                .contentType("application/json")
                .header("Authorization", efsAuth)
                .body("{\"CreationToken\":\"" + token + "\",\"Encrypted\":true}")
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .extract()
                .path("FileSystemId");

        given()
                .contentType("application/json")
                .header("Authorization", efsAuth)
                .body("{\"FileSystemId\":\"" + fileSystemId + "\",\"SubnetId\":\"" + subnet.getSubnetId() + "\"}")
                .when()
                .post("/2015-02-01/mount-targets")
                .then()
                .statusCode(200);

        String accessPointArn = given()
                .contentType("application/json")
                .header("Authorization", efsAuth)
                .body("""
                        {
                          "ClientToken":"%s-ap",
                          "FileSystemId":"%s",
                          "PosixUser":{"Uid":1000,"Gid":1000},
                          "RootDirectory":{
                            "Path":"/lambda",
                            "CreationInfo":{"OwnerUid":1000,"OwnerGid":1000,"Permissions":"750"}
                          }
                        }
                        """.formatted(token, fileSystemId))
                .when()
                .post("/2015-02-01/access-points")
                .then()
                .statusCode(200)
                .body("AccessPointId", startsWith("fsap-"))
                .extract()
                .path("AccessPointArn");

        String functionName = "efs-mount-" + token.substring(0, 8);
        given()
                .contentType("application/json")
                .body("""
                        {
                          "FunctionName": "%s",
                          "Runtime": "nodejs20.x",
                          "Role": "arn:aws:iam::000000000000:role/lambda-role",
                          "Handler": "index.handler",
                          "Code": { "ZipFile": "%s" },
                          "Tags": {"alchemy::id": "EfsFn", "alchemy::stack": "mount"},
                          "FileSystemConfigs": [
                            { "Arn": "%s", "LocalMountPath": "/mnt/test" }
                          ]
                        }
                        """.formatted(functionName, functionZipBase64(), accessPointArn))
                .when()
                .post("/2015-03-31/functions")
                .then()
                .statusCode(201)
                .body("FileSystemConfigs[0].Arn", equalTo(accessPointArn))
                .body("FileSystemConfigs[0].LocalMountPath", equalTo("/mnt/test"));

        given()
                .when()
                .get("/2015-03-31/functions/" + functionName)
                .then()
                .statusCode(200)
                .body("Tags", hasEntry("alchemy::id", "EfsFn"))
                .body("Tags", hasEntry("alchemy::stack", "mount"));

        given()
                .when()
                .get("/2015-03-31/functions/" + functionName + "/configuration")
                .then()
                .statusCode(200)
                .body("FileSystemConfigs[0].Arn", equalTo(accessPointArn))
                .body("FileSystemConfigs[0].LocalMountPath", equalTo("/mnt/test"));

        given()
                .contentType("application/json")
                .body("""
                        {
                          "FileSystemConfigs": [
                            { "Arn": "%s", "LocalMountPath": "/mnt/data" }
                          ]
                        }
                        """.formatted(accessPointArn))
                .when()
                .put("/2015-03-31/functions/" + functionName + "/configuration")
                .then()
                .statusCode(200)
                .body("FileSystemConfigs[0].LocalMountPath", equalTo("/mnt/data"));

        given()
                .contentType("application/json")
                .body("{\"FileSystemConfigs\": []}")
                .when()
                .put("/2015-03-31/functions/" + functionName + "/configuration")
                .then()
                .statusCode(200)
                .body("FileSystemConfigs", nullValue());

        given().when().delete("/2015-03-31/functions/" + functionName).then().statusCode(204);
    }

    /**
     * Alchemy's EFS LambdaMount suite writes {@code /mnt/test/persist.txt} through a Function
     * URL, then redeploys with a new env marker and reads the same file from a fresh sandbox.
     */
    @Test
    void invokeWritesReadsAndPersistsAcrossSandboxReplacement() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(), "Docker daemon must be available for EFS Lambda mounts");

        String efsAuth = "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + EAST + "/elasticfilesystem/aws4_request";
        Subnet subnet = defaultSubnet();
        String token = UUID.randomUUID().toString();
        String fileSystemId = given()
                .contentType("application/json")
                .header("Authorization", efsAuth)
                .body("{\"CreationToken\":\"" + token + "\",\"ThroughputMode\":\"elastic\"}")
                .when()
                .post("/2015-02-01/file-systems")
                .then()
                .statusCode(200)
                .extract()
                .path("FileSystemId");

        given()
                .contentType("application/json")
                .header("Authorization", efsAuth)
                .body("{\"FileSystemId\":\"" + fileSystemId + "\",\"SubnetId\":\"" + subnet.getSubnetId() + "\"}")
                .when()
                .post("/2015-02-01/mount-targets")
                .then()
                .statusCode(200);

        String accessPointArn = given()
                .contentType("application/json")
                .header("Authorization", efsAuth)
                .body("""
                        {
                          "ClientToken":"%s-ap",
                          "FileSystemId":"%s",
                          "PosixUser":{"Uid":1000,"Gid":1000},
                          "RootDirectory":{
                            "Path":"/lambda",
                            "CreationInfo":{"OwnerUid":1000,"OwnerGid":1000,"Permissions":"750"}
                          }
                        }
                        """.formatted(token, fileSystemId))
                .when()
                .post("/2015-02-01/access-points")
                .then()
                .statusCode(200)
                .extract()
                .path("AccessPointArn");

        String functionName = "efs-persist-" + token.substring(0, 8);
        given()
                .contentType("application/json")
                .body("""
                        {
                          "FunctionName": "%s",
                          "Runtime": "nodejs20.x",
                          "Role": "arn:aws:iam::000000000000:role/lambda-role",
                          "Handler": "index.handler",
                          "Timeout": 30,
                          "Code": { "ZipFile": "%s" },
                          "Environment": { "Variables": { "DEPLOY_MARKER": "first" } },
                          "VpcConfig": {
                            "SubnetIds": ["%s"],
                            "SecurityGroupIds": ["%s"]
                          },
                          "FileSystemConfigs": [
                            { "Arn": "%s", "LocalMountPath": "/mnt/test" }
                          ]
                        }
                        """.formatted(functionName, persistHandlerZipBase64(), subnet.getSubnetId(),
                        defaultSecurityGroupId(subnet), accessPointArn))
                .when()
                .post("/2015-03-31/functions")
                .then()
                .statusCode(201)
                .body("FileSystemConfigs[0].LocalMountPath", equalTo("/mnt/test"))
                .body("VpcConfig.SubnetIds[0]", equalTo(subnet.getSubnetId()));

        String functionUrl = given()
                .contentType("application/json")
                .body("{\"AuthType\":\"NONE\"}")
                .when()
                .post("/2021-10-31/functions/" + functionName + "/url")
                .then()
                .statusCode(201)
                .extract()
                .path("FunctionUrl");
        String urlId = URI.create(functionUrl).getHost().split("\\.")[0];

        given()
                .when()
                .get("/lambda-url/" + urlId + "/mount")
                .then()
                .statusCode(200)
                .body("mounted", equalTo(true));

        given()
                .when()
                .get("/lambda-url/" + urlId + "/write?content=hello-from-efs")
                .then()
                .statusCode(200)
                .body("written", equalTo("hello-from-efs"));

        given()
                .when()
                .get("/lambda-url/" + urlId + "/read")
                .then()
                .statusCode(200)
                .body("content", equalTo("hello-from-efs"))
                .body("marker", equalTo("first"));

        given()
                .contentType("application/json")
                .body("{\"Environment\":{\"Variables\":{\"DEPLOY_MARKER\":\"second\"}}}")
                .when()
                .put("/2015-03-31/functions/" + functionName + "/configuration")
                .then()
                .statusCode(200);

        given()
                .when()
                .get("/lambda-url/" + urlId + "/read")
                .then()
                .statusCode(200)
                .body("content", equalTo("hello-from-efs"))
                .body("marker", equalTo("second"));

        given().when().delete("/2015-03-31/functions/" + functionName).then().statusCode(204);
    }

    @Test
    void createFunctionRejectsUnknownAccessPoint() throws Exception {
        String functionName = "efs-missing-ap-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType("application/json")
                .body("""
                        {
                          "FunctionName": "%s",
                          "Runtime": "nodejs20.x",
                          "Role": "arn:aws:iam::000000000000:role/lambda-role",
                          "Handler": "index.handler",
                          "Code": { "ZipFile": "%s" },
                          "FileSystemConfigs": [
                            {
                              "Arn": "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-missing00000000",
                              "LocalMountPath": "/mnt/test"
                            }
                          ]
                        }
                        """.formatted(functionName, functionZipBase64()))
                .when()
                .post("/2015-03-31/functions")
                .then()
                .statusCode(400);
    }

    private Subnet defaultSubnet() {
        List<Subnet> subnets = ec2Service.describeSubnets(EAST, List.of(), Map.of("default-for-az", List.of("true")));
        if (subnets.isEmpty()) {
            subnets = ec2Service.describeSubnets(EAST, List.of(), Map.of());
        }
        return subnets.get(0);
    }

    private String defaultSecurityGroupId(Subnet subnet) {
        return ec2Service.describeSecurityGroups(EAST, List.of(), List.of("default"),
                Map.of("vpc-id", List.of(subnet.getVpcId()))).get(0).getGroupId();
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String functionZipBase64() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.js"));
            zos.write("exports.handler = async () => ({ statusCode: 200 });".getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String persistHandlerZipBase64() throws Exception {
        String source = """
                const fs = require('fs');
                const FILE = '/mnt/test/persist.txt';
                exports.handler = async (event) => {
                  const path = event.rawPath || (event.requestContext && event.requestContext.http
                    && event.requestContext.http.path) || '/';
                  const headers = { 'content-type': 'application/json' };
                  try {
                    if (path === '/write') {
                      const content = (event.queryStringParameters && event.queryStringParameters.content)
                        || 'hello-from-efs';
                      fs.writeFileSync(FILE, content, 'utf8');
                      return { statusCode: 200, headers, body: JSON.stringify({ written: content }) };
                    }
                    if (path === '/read') {
                      const content = fs.readFileSync(FILE, 'utf8');
                      return { statusCode: 200, headers, body: JSON.stringify({
                        content, marker: process.env.DEPLOY_MARKER || 'unset'
                      }) };
                    }
                    if (path === '/mount') {
                      const entries = fs.readdirSync('/mnt/test');
                      return { statusCode: 200, headers, body: JSON.stringify({ mounted: true, entries }) };
                    }
                    return { statusCode: 200, body: 'ok' };
                  } catch (error) {
                    return { statusCode: 500, headers, body: JSON.stringify({ error: String(error) }) };
                  }
                };
                """;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.js"));
            zos.write(source.getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
