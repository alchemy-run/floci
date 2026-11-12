package io.floci.aws.services.s3files;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

/**
 * S3 Files: file systems checked against S3 (a versioned bucket) and IAM (a role the EFS service may assume),
 * access points, mount targets holding EC2 network interfaces, policies, synchronization rules, tags, and a bucket
 * that can't be deleted while a file system is on it.
 */
@QuarkusTest
class S3FilesIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification files() {
        return given().header("Authorization", auth("s3files")).contentType("application/json");
    }

    static RequestSpecification query(String service) {
        return given().header("Authorization", auth(service)).contentType("application/x-www-form-urlencoded");
    }

    static String xmlValue(String xml, String element) {
        return xml.replaceAll("(?s).*<" + element + ">([^<]+)</" + element + ">.*", "$1");
    }

    static String role(String name, String service) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\""
                + service + "\"},\"Action\":\"sts:AssumeRole\"}]}";
        String xml = query("iam").formParam("Action", "CreateRole").formParam("Version", "2010-05-08")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().asString();
        return xmlValue(xml, "Arn");
    }

    static String ec2(String action, String... params) {
        RequestSpecification spec = query("ec2").formParam("Action", action).formParam("Version", "2016-11-15");
        for (int i = 0; i < params.length; i += 2) {
            spec = spec.formParam(params[i], params[i + 1]);
        }
        return spec.post("/").then().statusCode(200).extract().asString();
    }

    static void awaitStatus(String path, String status) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> status.equals(files().get(path).then().extract().path("status")));
    }

    @Test
    void fileSystemsOverVersionedBuckets() {
        given().header("Authorization", auth("s3")).put("/s3files-source").then().statusCode(200);
        String bucket = "arn:aws:s3:::s3files-source";
        String good = role("s3files-role", "elasticfilesystem.amazonaws.com");
        String wrong = role("s3files-wrong", "lambda.amazonaws.com");

        // The bucket must exist and be versioned; the role must trust EFS.
        files().body("{\"bucket\":\"arn:aws:s3:::s3files-nope\",\"roleArn\":\"" + good + "\"}").put("/file-systems")
                .then().statusCode(404);
        files().body("{\"bucket\":\"" + bucket + "\",\"roleArn\":\"" + good + "\"}").put("/file-systems").then()
                .statusCode(400).body("message", containsString("versioning"));
        given().header("Authorization", auth("s3")).contentType("application/xml")
                .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .put("/s3files-source?versioning").then().statusCode(200);
        files().body("{\"bucket\":\"" + bucket + "\",\"roleArn\":\"" + wrong + "\"}").put("/file-systems").then()
                .statusCode(400).body("message", containsString("elasticfilesystem"));

        String fs = files().body("{\"bucket\":\"" + bucket + "\",\"roleArn\":\"" + good + "\",\"clientToken\":\"t1\","
                + "\"tags\":[{\"key\":\"Name\",\"value\":\"docs\"}]}").put("/file-systems").then().statusCode(201)
                .body("status", equalTo("creating")).body("name", equalTo("docs"))
                .body("fileSystemArn", containsString(":file-system/")).extract().path("fileSystemId");
        files().body("{\"bucket\":\"" + bucket + "\",\"roleArn\":\"" + good + "\",\"clientToken\":\"t1\"}")
                .put("/file-systems").then().statusCode(201).body("fileSystemId", equalTo(fs));
        files().body("{\"bucket\":\"" + bucket + "\",\"prefix\":\"docs/\",\"roleArn\":\"" + good + "\"}")
                .put("/file-systems").then().statusCode(409);
        awaitStatus("/file-systems/" + fs, "available");
        files().get("/file-systems?bucket=" + bucket).then().body("fileSystems.fileSystemId", hasItem(fs));

        // A bucket with a file system can't be deleted.
        given().header("Authorization", auth("s3")).delete("/s3files-source").then().statusCode(409)
                .body(containsString("BucketHasS3FileSystemAttached"));

        // Policy and synchronization rules.
        files().get("/file-systems/" + fs + "/policy").then().statusCode(404);
        files().body("{\"policy\":\"not json\"}").put("/file-systems/" + fs + "/policy").then().statusCode(400);
        files().body("{\"policy\":\"{\\\"Statement\\\":[]}\"}").put("/file-systems/" + fs + "/policy").then()
                .statusCode(200);
        files().get("/file-systems/" + fs + "/policy").then().statusCode(200).body("fileSystemId", equalTo(fs));
        files().get("/file-systems/" + fs + "/synchronization-configuration").then().statusCode(200)
                .body("latestVersionNumber", equalTo(1))
                .body("expirationDataRules[0].daysAfterLastAccess", equalTo(30));
        files().body("{\"latestVersionNumber\":7,\"importDataRules\":[{\"prefix\":\"\",\"trigger\":"
                + "\"ON_FILE_ACCESS\",\"sizeLessThan\":1}],\"expirationDataRules\":[{\"daysAfterLastAccess\":7}]}")
                .put("/file-systems/" + fs + "/synchronization-configuration").then().statusCode(409);
        files().body("{\"latestVersionNumber\":1,\"importDataRules\":[{\"prefix\":\"docs/\",\"trigger\":"
                + "\"ON_FILE_ACCESS\",\"sizeLessThan\":1048576}],\"expirationDataRules\":[{\"daysAfterLastAccess\":"
                + "7}]}").put("/file-systems/" + fs + "/synchronization-configuration").then().statusCode(200);
        files().get("/file-systems/" + fs + "/synchronization-configuration").then()
                .body("latestVersionNumber", equalTo(2)).body("importDataRules[0].trigger", equalTo("ON_FILE_ACCESS"));

        // Access points.
        files().body("{\"fileSystemId\":\"" + fs + "\",\"rootDirectory\":{\"path\":\"app\"}}").put("/access-points")
                .then().statusCode(400);
        String ap = files().body("{\"fileSystemId\":\"" + fs + "\",\"posixUser\":{\"uid\":1000,\"gid\":1000},"
                + "\"rootDirectory\":{\"path\":\"/app\",\"creationPermissions\":{\"ownerUid\":1000,\"ownerGid\":1000,"
                + "\"permissions\":\"0755\"}}}").put("/access-points").then().statusCode(200)
                .body("accessPointId", startsWith("fsap-")).extract().path("accessPointId");
        awaitStatus("/access-points/" + ap, "available");
        files().get("/access-points?fileSystemId=" + fs).then().body("accessPoints", hasSize(1))
                .body("accessPoints[0].rootDirectory.path", equalTo("/app"));

        // Mount targets hold an EC2 network interface in the subnet.
        String vpc = xmlValue(ec2("CreateVpc", "CidrBlock", "10.81.0.0/16"), "vpcId");
        String subnet = xmlValue(ec2("CreateSubnet", "VpcId", vpc, "CidrBlock", "10.81.1.0/24"), "subnetId");
        String group = xmlValue(ec2("CreateSecurityGroup", "VpcId", vpc, "GroupName", "nfs", "GroupDescription",
                "nfs"), "groupId");
        String mt = files().body("{\"fileSystemId\":\"" + fs + "\",\"subnetId\":\"" + subnet
                + "\",\"securityGroups\":[\"" + group + "\"]}").put("/mount-targets").then().statusCode(200)
                .body("vpcId", equalTo(vpc)).body("securityGroups", equalTo(List.of(group)))
                .extract().path("mountTargetId");
        String eni = files().get("/mount-targets/" + mt).then().extract().path("networkInterfaceId");
        ec2("DescribeNetworkInterfaces", "NetworkInterfaceId.1", eni);
        files().body("{\"fileSystemId\":\"" + fs + "\",\"subnetId\":\"" + subnet + "\"}").put("/mount-targets").then()
                .statusCode(409);
        files().get("/mount-targets?accessPointId=" + ap).then().body("mountTargets.mountTargetId", hasItem(mt));

        // Tags.
        String fsArn = files().get("/file-systems/" + fs).then().extract().path("fileSystemArn");
        files().body("{\"tags\":[{\"key\":\"team\",\"value\":\"docs\"}]}").post("/resource-tags/" + fs).then()
                .statusCode(200);
        files().get("/resource-tags/" + fs).then().body("tags.key", hasItem("team"));
        files().delete("/resource-tags/" + fs + "?tagKeys=team").then().statusCode(200);
        files().get("/resource-tags/" + fs).then().body("tags.key", equalTo(List.of("Name")));

        // Deletion: children first, unless forced; then the bucket can go.
        files().delete("/file-systems/" + fs).then().statusCode(409);
        files().delete("/mount-targets/" + mt).then().statusCode(204);
        query("ec2").formParam("Action", "DescribeNetworkInterfaces").formParam("Version", "2016-11-15")
                .formParam("NetworkInterfaceId.1", eni).post("/").then().statusCode(400);
        files().delete("/file-systems/" + fs + "?forceDelete=true").then().statusCode(204);
        files().get("/file-systems/" + fs).then().body("status", equalTo("deleting"));
        await().atMost(Duration.ofSeconds(10)).until(() -> files().get("/file-systems/" + fs).then().extract()
                .statusCode() == 404);
        files().get("/access-points/" + ap).then().statusCode(404);
        files().get("/resource-tags/" + fsArn).then().statusCode(404);
        given().header("Authorization", auth("s3")).delete("/s3files-source").then().statusCode(204);
    }
}
