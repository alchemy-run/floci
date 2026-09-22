package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class EksPodIdentityIntegrationTest {
    private static final String ACCOUNT = "135791357913";
    private static final String REGION = "us-east-1";

    @Test
    void associationLifecycleTagsAndScopeIsolationUseAwsJson() {
        String name = "pod-identity-" + UUID.randomUUID().toString().substring(0, 8);
        String role = "pods-" + name;
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/" + role;
        String clusterPath = "/clusters/" + name;
        String path = clusterPath + "/pod-identity-associations";
        given().header("Authorization", auth(ACCOUNT, REGION, "iam")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
                .post("/").then().statusCode(200);
        createCluster(name);
        try {
            Map<String, Object> request = Map.of("namespace", "default", "serviceAccount", "api", "roleArn", roleArn,
                    "clientRequestToken", "create", "tags", Map.of("environment", "test"));
            String id = given().header("Authorization", auth(ACCOUNT, REGION, "eks")).contentType("application/json")
                    .body(request).post(path).then().statusCode(200)
                    .body("association.createdAt", instanceOf(Number.class))
                    .body("association.disableSessionTags", equalTo(false))
                    .body("association.roleArn", equalTo(roleArn)).extract().path("association.associationId");
            String arn = "arn:aws:eks:" + REGION + ":" + ACCOUNT + ":podidentityassociation/" + name + "/" + id;
            String resource = path + "/" + id;
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).contentType("application/json")
                    .body(request).post(path).then().statusCode(200).body("association.associationId", equalTo(id));
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).contentType("application/json")
                    .body(Map.of("disableSessionTags", true, "clientRequestToken", "update"))
                    .post(resource).then().statusCode(200).body("association.disableSessionTags", equalTo(true));
            String tags = "/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8);
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).urlEncodingEnabled(false)
                    .contentType("application/json").body(Map.of("tags", Map.of("owner", "platform")))
                    .post(tags).then().statusCode(200);
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).urlEncodingEnabled(false)
                    .queryParam("tagKeys", "environment").delete(tags).then().statusCode(200);
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).urlEncodingEnabled(false)
                    .get(tags).then().statusCode(200).body("tags", equalTo(Map.of("owner", "platform")));
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).get(resource).then().statusCode(200)
                    .body("association.associationArn", equalTo(arn)).body("association.disableSessionTags", equalTo(true))
                    .body("association.tags", equalTo(Map.of("owner", "platform")));
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).queryParam("namespace", "default")
                    .queryParam("serviceAccount", "api").get(path).then().statusCode(200)
                    .body("associations.associationId", contains(id)).body("associations[0].roleArn", nullValue());
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).queryParam("serviceAccount", "other")
                    .get(path).then().statusCode(200).body("associations", empty());
            for (String foreign : new String[] { auth("246802468024", REGION, "eks"), auth(ACCOUNT, "eu-west-1", "eks") }) {
                given().header("Authorization", foreign).get(path).then().statusCode(404)
                        .body("__type", equalTo("ResourceNotFoundException"));
                given().header("Authorization", foreign).get(resource).then().statusCode(404);
                given().header("Authorization", foreign).contentType("application/json")
                        .body(Map.of("disableSessionTags", false)).post(resource).then().statusCode(404);
                given().header("Authorization", foreign).delete(resource).then().statusCode(404);
                given().header("Authorization", foreign).urlEncodingEnabled(false).get(tags).then().statusCode(404);
                given().header("Authorization", foreign).urlEncodingEnabled(false).contentType("application/json")
                        .body(Map.of("tags", Map.of("owner", "foreign"))).post(tags).then().statusCode(404);
                given().header("Authorization", foreign).delete(clusterPath).then().statusCode(404);
                given().header("Authorization", foreign).get("/clusters").then().statusCode(200)
                        .body("clusters", not(hasItem(name)));
            }
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).delete(resource).then().statusCode(200)
                    .body("association.associationId", equalTo(id));
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).get(resource).then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).contentType("application/json")
                    .body(request).post(path).then().statusCode(200);
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).delete(clusterPath).then().statusCode(200);
            createCluster(name);
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).get(path).then().statusCode(200)
                    .body("associations", empty());
        } finally {
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).delete(clusterPath).then().statusCode(200);
            given().header("Authorization", auth(ACCOUNT, REGION, "iam")).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteRole").formParam("RoleName", role).post("/").then().statusCode(200);
        }
    }

    @Test
    void catalogReadsDoNotRequireACluster() {
        String token = given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("maxResults", "2").get("/access-policies")
                .then().statusCode(200).contentType(containsString("application/json"))
                .body("accessPolicies.name", contains("AmazonEKSAdminPolicy", "AmazonEKSClusterAdminPolicy"))
                .body("accessPolicies[0].arn", equalTo("arn:aws:eks::aws:cluster-access-policy/AmazonEKSAdminPolicy"))
                .extract().path("nextToken");
        given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("maxResults", "2").queryParam("nextToken", token).get("/access-policies")
                .then().statusCode(200).body("accessPolicies.name", contains("AmazonEKSEditPolicy", "AmazonEKSViewPolicy"))
                .body("nextToken", nullValue());
        given().header("Authorization", auth("246802468024", "us-gov-west-1", "eks"))
                .get("/access-policies").then().statusCode(200)
                .body("accessPolicies[0].arn", startsWith("arn:aws-us-gov:eks::aws:cluster-access-policy/"));
        given().header("Authorization", auth(ACCOUNT, REGION, "eks")).queryParam("defaultOnly", "true")
                .get("/cluster-versions").then().statusCode(200)
                .body("clusterVersions", hasSize(1)).body("clusterVersions[0].defaultVersion", equalTo(true))
                .body("clusterVersions[0].clusterVersion", equalTo("1.34"))
                .body("clusterVersions[0].releaseDate", instanceOf(Number.class))
                .body("clusterVersions[0].endOfStandardSupportDate", instanceOf(Number.class))
                .body("clusterVersions[0].versionStatus", equalTo("STANDARD_SUPPORT"));
        String version = given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("addonName", "vpc-cni").queryParam("maxResults", "1")
                .get("/addons/supported-versions").then().statusCode(200)
                .body("addons", hasSize(1)).body("addons[0].addonName", equalTo("vpc-cni"))
                .body("addons[0].owner", equalTo("aws")).body("addons[0].publisher", equalTo("eks"))
                .body("addons[0].addonVersions[0].architecture", contains("amd64", "arm64"))
                .extract().path("addons[0].addonVersions[0].addonVersion");
        String schema = given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("addonName", "vpc-cni").queryParam("addonVersion", version)
                .get("/addons/configuration-schemas").then().statusCode(200)
                .body("addonName", equalTo("vpc-cni")).body("addonVersion", equalTo(version))
                .extract().path("configurationSchema");
        JsonPath parsed = new JsonPath(schema);
        assertEquals("#/definitions/VpcCni", parsed.getString("'$ref'"));
        assertEquals("boolean",
                parsed.getString("definitions.Env.properties.ENABLE_PREFIX_DELEGATION.format"));
    }

    @Test
    void catalogFiltersAndPaginationUseAwsQueryNames() {
        String token = given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("maxResults", "2").get("/cluster-versions").then().statusCode(200)
                .body("clusterVersions.clusterVersion", contains("1.31", "1.32")).extract().path("nextToken");
        given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("maxResults", "2").queryParam("nextToken", token).get("/cluster-versions")
                .then().statusCode(200).body("clusterVersions.clusterVersion", contains("1.33", "1.34"))
                .body("nextToken", nullValue());
        given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("clusterVersions", "1.31", "1.34").queryParam("versionStatus", "STANDARD_SUPPORT")
                .get("/cluster-versions").then().statusCode(200)
                .body("clusterVersions.clusterVersion", contains("1.34"));
        given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("includeAll", "true").queryParam("status", "unsupported")
                .get("/cluster-versions").then().statusCode(200)
                .body("clusterVersions.clusterVersion", contains("1.30"));
        given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("clusterType", "other").get("/cluster-versions").then().statusCode(200)
                .body("clusterVersions", empty());
        given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                .queryParam("addonName", "vpc-cni").queryParam("kubernetesVersion", "1.24")
                .queryParam("types", "networking").queryParam("owners", "aws").queryParam("publishers", "eks")
                .get("/addons/supported-versions").then().statusCode(200)
                .body("addons[0].addonVersions[0].compatibilities.clusterVersion", contains("1.24"));
        for (Map.Entry<String, String> filter : Map.of("addonName", "missing", "kubernetesVersion", "1.34",
                "types", "storage", "owners", "other", "publishers", "other").entrySet()) {
            given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                    .queryParam(filter.getKey(), filter.getValue()).get("/addons/supported-versions")
                    .then().statusCode(200).body("addons", empty());
        }
        for (String path : new String[] { "/access-policies", "/cluster-versions", "/addons/supported-versions" }) {
            for (String limit : new String[] { "invalid", "0", "101" }) {
                given().header("Authorization", auth(ACCOUNT, REGION, "eks")).queryParam("maxResults", limit)
                        .get(path).then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
            }
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).queryParam("nextToken", "!")
                    .get(path).then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        }
        for (Map.Entry<String, String> filter : Map.of("defaultOnly", "invalid", "includeAll", "invalid",
                "status", "invalid", "versionStatus", "invalid").entrySet()) {
            given().header("Authorization", auth(ACCOUNT, REGION, "eks"))
                    .queryParam(filter.getKey(), filter.getValue()).get("/cluster-versions")
                    .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        }
    }

    @Test
    void catalogPresenceDoesNotAdvertiseInstalledWorkloads() {
        given().header("Authorization", auth(ACCOUNT, REGION, "eks")).get("/addons/configuration-schemas")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        given().header("Authorization", auth(ACCOUNT, REGION, "eks")).queryParam("addonName", "vpc-cni")
                .queryParam("addonVersion", "unavailable").get("/addons/configuration-schemas")
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        String name = "no-addons-" + UUID.randomUUID().toString().substring(0, 8);
        createCluster(name);
        String path = "/clusters/" + name + "/addons";
        try {
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).contentType("application/json")
                    .body(Map.of("addonName", "vpc-cni")).post(path).then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).get(path).then().statusCode(200)
                    .body("addons", empty());
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).get(path + "/vpc-cni")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).contentType("application/json")
                    .body(Map.of()).post(path + "/vpc-cni/update").then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).delete(path + "/vpc-cni")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            for (String limit : new String[] { "invalid", "0", "101" }) {
                given().header("Authorization", auth(ACCOUNT, REGION, "eks")).queryParam("maxResults", limit)
                        .get(path).then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
                given().header("Authorization", auth(ACCOUNT, REGION, "eks")).queryParam("maxResults", limit)
                        .get("/clusters/" + name + "/pod-identity-associations").then().statusCode(400)
                        .body("__type", equalTo("InvalidParameterException"));
            }
        } finally {
            given().header("Authorization", auth(ACCOUNT, REGION, "eks")).delete("/clusters/" + name)
                    .then().statusCode(200);
        }
    }

    private static void createCluster(String name) {
        given().header("Authorization", auth(ACCOUNT, REGION, "eks")).contentType("application/json")
                .body(Map.of("name", name)).post("/clusters").then().statusCode(200);
    }

    private static String auth(String account, String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
