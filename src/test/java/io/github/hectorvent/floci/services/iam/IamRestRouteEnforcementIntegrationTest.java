package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * REST requests whose route no rule mapped to an IAM action were let through under enforcement
 * unchecked. These routes are mapped now, and a caller needs the action AWS checks for them.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamRestRouteEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ACCOUNT = "000000000000";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    /** Each user's access key ID, by user name. */
    private final Map<String, String> keys = new HashMap<>();

    @Test
    void apiGatewayManagementRoutesBeyondRestApisAreCheckedByMethod() {
        String user = user("route-apigateway");
        String plan = "{\"name\":\"route-plan-" + suffix() + "\"}";

        policy(user, allow("apigateway:GET", "*"));
        refused(as(user, "apigateway").body(plan).post("/usageplans").then(), "apigateway:POST");
        as(user, "apigateway").get("/usageplans").then().statusCode(200);

        policy(user, allow("apigateway:POST", "*"));
        String id = as(user, "apigateway").body(plan).post("/usageplans").then().statusCode(201).extract().path("id");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, "apigateway")).delete("/usageplans/" + id));
    }

    @Test
    void getLayerVersionByArnIsCheckedAsGetLayerVersion() {
        String user = user("route-layer");
        String arn = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":layer:route-layer-" + suffix() + ":1";

        policy(user, allow("lambda:ListLayers", "*"));
        refused(as(user, "lambda").queryParam("find", "LayerVersion").queryParam("Arn", arn)
                .get("/2018-10-31/layers").then(), "lambda:GetLayerVersion");
        policy(user, allow("lambda:GetLayerVersion", "*"));
        // Allowed, the request reaches Lambda, which has no such layer.
        as(user, "lambda").queryParam("find", "LayerVersion").queryParam("Arn", arn)
                .get("/2018-10-31/layers").then().statusCode(404);
    }

    @Test
    void aScheduleRequestSignedForAnotherServiceIsStillCheckedAsScheduler() {
        // Checked by the route it reached, not by the credential scope it was signed with: an S3
        // grant does not let the request through as an S3 request.
        String user = user("route-scheduler-scope");
        policy(user, allow("s3:*", "*"));
        refused(as(user, "s3").get("/schedules/route-schedule-" + suffix()).then(), "scheduler:GetSchedule");
    }

    @Test
    void schedulerTaggingIsCheckedOnTheBareTagsRouteToo() {
        // The shared tagging routes also take the ARN in the body of POST /tags.
        String user = user("route-scheduler-tags");
        String group = scheduleGroup("route-group-" + suffix());

        policy(user, allow("scheduler:ListTagsForResource", "*"));
        refused(as(user, "scheduler").body(tagBody(group)).post("/tags").then(), "scheduler:TagResource");
        policy(user, allow("scheduler:TagResource", group));
        // Allowed on that group, the request reaches Scheduler, which has no such group.
        as(user, "scheduler").body(tagBody(group)).post("/tags").then().statusCode(not(403));
    }

    @Test
    void postTagsIsCheckedAgainstTheArnItsBodyNames() {
        // POST /tags tags the ARN its body names: one named in the query does not move the check.
        String user = user("route-tags-body");
        String allowed = scheduleGroup("route-allowed-" + suffix());
        String other = scheduleGroup("route-other-" + suffix());

        policy(user, allow("scheduler:TagResource", allowed));
        refused(as(user, "scheduler").queryParam("resourceArn", allowed).body(tagBody(other)).post("/tags").then(),
                "scheduler:TagResource");
    }

    @Test
    void sharedTaggingIsCheckedAsTheServiceItsArnNames() {
        // The shared tagging routes hand the request to the service its ARN names, whatever scope it is
        // signed with: a Pipes grant does not let the caller tag a schedule group, and a scope with no
        // tagging rule does not let the request through unchecked.
        String user = user("route-tags-scope");
        String group = scheduleGroup("route-group-" + suffix());
        String queue = "arn:aws:batch:us-east-1:" + ACCOUNT + ":job-queue/route-queue-" + suffix();

        // Nor is an ARN of a service with no tagging rule checked as the signing scope's action.
        as(user, "pipes").body(tagBody(queue)).post("/tags").then().body(not(containsString("pipes:TagResource")));
        policy(user, allow("pipes:TagResource", "*"));
        refused(as(user, "pipes").body(tagBody(group)).post("/tags").then(), "scheduler:TagResource");
        refused(as(user, "sqs").body(tagBody(group)).post("/tags").then(), "scheduler:TagResource");
    }

    @Test
    void anEksAccessEntrysPolicyRoutesAreCheckedAsTheEntryRoutesTheyRunAs() {
        // EKS takes an access entry's principal as the rest of the path, so a request on an entry's access
        // policies reads or deletes the entry of a longer principal, and is checked as that.
        String user = user("route-eks-entry");
        String name = "route-entry-" + suffix();
        eksCluster(name);
        String principal = encoded("arn:aws:iam::" + ACCOUNT + ":role/route-entry");
        String policies = "/clusters/" + name + "/access-entries/" + principal + "/access-policies";
        String admin = encoded("arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy");

        policy(user, allow("eks:ListAssociatedAccessPolicies", "*") + "," + allow("eks:DisassociateAccessPolicy", "*"));
        refused(as(user, "eks").urlEncodingEnabled(false).get(policies).then(), "eks:DescribeAccessEntry");
        refused(as(user, "eks").urlEncodingEnabled(false).delete(policies + "/" + admin).then(),
                "eks:DeleteAccessEntry");
        // Allowed, it reaches EKS, which looks up the entry of a principal ending in /access-policies.
        policy(user, allow("eks:DescribeAccessEntry", "*"));
        as(user, "eks").urlEncodingEnabled(false).get(policies).then().statusCode(404)
                .body("message", equalTo("Access entry not found"));
    }

    @Test
    void aTrailingSlashDoesNotLetARouteThroughUnchecked() {
        // The router matches /clusters/ as /clusters, so the request is checked as the route it reaches.
        String user = user("route-trailing-slash");
        policy(user, allow("sqs:ListQueues", "*"));
        refused(as(user, "eks").body("{\"name\":\"route-slash-" + suffix() + "\"}").post("/clusters/").then(),
                "eks:CreateCluster");
        refused(as(user, "eks").get("/clusters/").then(), "eks:ListClusters");
        refused(as(user, "scheduler").get("/schedules/").then(), "scheduler:ListSchedules");
        refused(as(user, "lambda").get("/2015-03-31/functions/route-fn/").then(), "lambda:GetFunction");
    }

    /** Creates an EKS cluster as the account's admin, removed afterwards, and returns its ARN. */
    private String eksCluster(String name) {
        String arn = eks().body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT
                + ":role/route-cluster\",\"accessConfig\":{\"authenticationMode\":\"API\"}}")
                .post("/clusters").then().statusCode(200).extract().path("cluster.arn");
        cleanup.register(() -> eks().delete("/clusters/" + name));
        return arn;
    }

    private static RequestSpecification eks() {
        return given().header("Authorization", auth(ADMIN, "eks")).contentType("application/json");
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String scheduleGroup(String name) {
        return "arn:aws:scheduler:us-east-1:" + ACCOUNT + ":schedule-group/" + name;
    }

    private static String tagBody(String arn) {
        return "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"team\",\"value\":\"a\"}]}";
    }

    @Test
    void lambdaTaggingIsCheckedAgainstTheTaggedFunction() {
        String user = user("route-lambda-tags");
        String function = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:route-fn-" + suffix();
        String path = "/2017-03-31/tags/" + function;
        String body = "{\"Tags\":{\"team\":\"a\"}}";

        policy(user, allow("lambda:TagResource", function + "-other"));
        refused(as(user, "lambda").body(body).post(path).then(), "lambda:TagResource");
        // A grant on the function itself covers it; the request reaches Lambda, which has no such function.
        policy(user, allow("lambda:TagResource", function));
        as(user, "lambda").body(body).post(path).then().statusCode(not(403));
    }

    @Test
    void lambdaTaggingIsCheckedAgainstTheCallersOwnResourceWhateverAccountTheArnNames() {
        // Lambda tags the caller's own function or MicroVM image of the ARN's name, so a grant on another
        // account's ARN of that name does not cover it, and a grant on the caller's own does.
        String user = user("route-lambda-tags-account");
        String name = "route-tagged-" + suffix();
        String body = "{\"Tags\":{\"team\":\"a\"}}";

        for (String type : new String[] {"function:", "microvm-image:"}) {
            String own = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":" + type + name;
            String foreign = "arn:aws:lambda:us-east-1:111122223333:" + type + name;
            policy(user, allow("lambda:TagResource", foreign));
            refused(as(user, "lambda").body(body).post("/2017-03-31/tags/" + foreign).then(), "lambda:TagResource");
            policy(user, allow("lambda:TagResource", own));
            as(user, "lambda").body(body).post("/2017-03-31/tags/" + foreign).then().statusCode(not(403));
        }
    }

    @Test
    void lambdaFunctionSettingsRoutesAreCheckedAgainstTheFunction() {
        // Each is checked as its own action against the function it names: a grant on another function
        // does not cover it, and one on the function does. The request then reaches Lambda, which has no
        // such function.
        String user = user("route-lambda-settings");
        String name = "route-settings-" + suffix();
        String function = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:" + name;
        String[][] routes = {
            {"GET", "/2015-03-31/functions/" + name + "/configuration", "lambda:GetFunctionConfiguration"},
            {"GET", "/2015-03-31/functions/" + name + "/versions", "lambda:ListVersionsByFunction"},
            {"POST", "/2015-03-31/functions/" + name + "/versions", "lambda:PublishVersion"},
            {"PUT", "/2017-10-31/functions/" + name + "/concurrency", "lambda:PutFunctionConcurrency"},
            {"GET", "/2019-09-30/functions/" + name + "/concurrency", "lambda:GetFunctionConcurrency"},
            {"PUT", "/2019-09-25/functions/" + name + "/event-invoke-config", "lambda:PutFunctionEventInvokeConfig"},
            {"GET", "/2020-06-30/functions/" + name + "/code-signing-config", "lambda:GetFunctionCodeSigningConfig"},
        };
        for (String[] route : routes) {
            policy(user, allow(route[2], function + "-other"));
            refused(send(user, route[0], route[1]), route[2]);
            policy(user, allow(route[2], function));
            send(user, route[0], route[1]).statusCode(not(403));
        }
    }

    @Test
    void lambdaMappingConfigAndLayerRoutesAreCheckedAgainstWhatTheyName() {
        // A grant on the same resource in another account does not cover the caller's own.
        String user = user("route-lambda-resources");
        String uuid = "14e0db71-5d35-4eb5-b481-8945cf9d10c2";
        String layerName = "route-layer-" + suffix();
        String mapping = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":event-source-mapping:" + uuid;
        String config = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":code-signing-config:csc-0123456789abcdef0";
        String layer = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":layer:" + layerName;
        String[][] routes = {
            {"GET", "/2015-03-31/event-source-mappings/" + uuid, "lambda:GetEventSourceMapping", mapping},
            {"PUT", "/2015-03-31/event-source-mappings/" + uuid, "lambda:UpdateEventSourceMapping", mapping},
            {"GET", "/2020-04-22/code-signing-configs/" + config, "lambda:GetCodeSigningConfig", config},
            {"GET", "/2020-04-22/code-signing-configs/" + config + "/functions",
                "lambda:ListFunctionsByCodeSigningConfig", config},
            {"GET", "/2018-10-31/layers/" + layerName + "/versions/1", "lambda:GetLayerVersion", layer + ":1"},
            {"DELETE", "/2018-10-31/layers/" + layerName + "/versions/1", "lambda:DeleteLayerVersion", layer + ":1"},
            {"POST", "/2018-10-31/layers/" + layerName + "/versions", "lambda:PublishLayerVersion", layer},
        };
        for (String[] route : routes) {
            policy(user, allow(route[2], route[3].replace(ACCOUNT, "111122223333")));
            refused(send(user, route[0], route[1]), route[2]);
            policy(user, allow(route[2], route[3]));
            send(user, route[0], route[1]).statusCode(not(403));
        }
        // A config's function list ends in /functions, as ListFunctions does, and is not checked as it.
        policy(user, allow("lambda:ListFunctions", "*"));
        refused(send(user, "GET", "/2020-04-22/code-signing-configs/" + config + "/functions"),
                "lambda:ListFunctionsByCodeSigningConfig");
    }

    @Test
    void aLayerVersionIsCheckedAsLambdaReadsItsNameAndNumber() {
        // Lambda parses version 01 as version 1, and the router keeps an encoded / inside the layer's name, so
        // a Deny on that layer version applies however the request spells them.
        String user = user("route-layer-spelling");
        String name = "route-spelt-" + suffix();
        String layer = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":layer:" + name;
        policy(user, allow("lambda:GetLayerVersion", "*") + "," + deny("lambda:GetLayerVersion", layer + ":1")
                + "," + deny("lambda:GetLayerVersion", layer + "/x:1"));
        refused(as(user, "lambda").get("/2018-10-31/layers/" + name + "/versions/01").then(),
                "lambda:GetLayerVersion");
        refused(as(user, "lambda").queryParam("find", "LayerVersion").queryParam("Arn", layer + ":01")
                .get("/2018-10-31/layers").then(), "lambda:GetLayerVersion");
        refused(as(user, "lambda").urlEncodingEnabled(false).get("/2018-10-31/layers/" + name + "%2Fx/versions/1")
                .then(), "lambda:GetLayerVersion");
        // Allowed, each reaches Lambda, which looks up that version of that layer.
        policy(user, allow("lambda:GetLayerVersion", "*"));
        as(user, "lambda").get("/2018-10-31/layers/" + name + "/versions/01").then().statusCode(404)
                .body("message", containsString("Layer version 1 for layer " + name + " not found"));
        as(user, "lambda").urlEncodingEnabled(false).get("/2018-10-31/layers/" + name + "%2Fx/versions/1").then()
                .statusCode(404).body("message", containsString("for layer " + name + "/x not found"));
    }

    @Test
    void lambdaAccountSettingsAreChecked() {
        String user = user("route-lambda-account");
        policy(user, allow("lambda:ListFunctions", "*"));
        refused(as(user, "lambda").get("/2016-08-19/account-settings").then(), "lambda:GetAccountSettings");
        policy(user, allow("lambda:GetAccountSettings", "*"));
        as(user, "lambda").get("/2016-08-19/account-settings").then().statusCode(200);
    }

    /** Creates a MicroVM image as the account's admin, removed afterwards, and returns its ARN. */
    private String microvmImage(String name) {
        admin().body("""
                {"name":"%s","baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"arn:aws:iam::%s:role/route-build","codeArtifact":{"uri":"s3://bucket/code.zip"}}"""
                .formatted(name, ACCOUNT)).post("/2025-09-09/microvm-images").then().statusCode(201);
        cleanup.register(() -> admin().delete("/2025-09-09/microvm-images/" + name));
        return "arn:aws:lambda:us-east-1:" + ACCOUNT + ":microvm-image:" + name;
    }

    private static RequestSpecification admin() {
        return given().header("Authorization", auth(ADMIN, "lambda")).contentType("application/json");
    }

    private static String deny(String action, String resource) {
        return "{\"Effect\":\"Deny\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static void refused(ValidatableResponse response, String action) {
        response.statusCode(403).body(containsString(action)).body(not(containsString("iam:PassRole")));
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), user, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    /** Creates a user, removed afterwards, and returns its access key ID under the user's name. */
    private String user(String name) {
        String userName = name + "-" + suffix();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"), userName)
                .accessKeyId();
        keys.put(userName, accessKeyId);
        return userName;
    }

    private RequestSpecification as(String user, String service) {
        return given().header("Authorization", auth(keys.get(user), service)).contentType("application/json");
    }

    /** A Lambda request as {@code user}, with an empty JSON body on a write. */
    private ValidatableResponse send(String user, String method, String path) {
        RequestSpecification request = as(user, "lambda");
        if ("POST".equals(method) || "PUT".equals(method)) {
            request.body("{}");
        }
        return request.request(method, path).then();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
