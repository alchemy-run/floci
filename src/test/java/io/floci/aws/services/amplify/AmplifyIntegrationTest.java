package io.floci.aws.services.amplify;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/** Amplify Hosting: apps and branches, manual deployments served by host, domains, webhooks and backends. */
@QuarkusTest
class AmplifyIntegrationTest {

    private static RequestSpecification amplify() {
        return given().contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/amplify/aws4_request");
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String createApp(String extra) {
        return amplify().body("{\"name\":\"site-" + UUID.randomUUID().toString().substring(0, 6) + "\"" + extra
                        + ",\"tags\":{\"team\":\"web\"}}")
                .post("/apps").then().statusCode(200).extract().path("app.appId");
    }

    /** Stages a zip, uploads it through the pre-signed URL, and starts the deployment; returns its job id. */
    private static String deploy(String appId, String branch, byte[] zip) {
        Map<String, String> staged = amplify().body("{}").post("/apps/" + appId + "/branches/" + branch
                + "/deployments").then().statusCode(200).extract().jsonPath().getMap("", String.class, String.class);
        String upload = staged.get("zipUploadUrl");
        given().body(zip).contentType("application/zip").put(URI.create(upload).getPath()).then().statusCode(200);
        amplify().body("{\"jobId\":\"" + staged.get("jobId") + "\"}").post("/apps/" + appId + "/branches/" + branch
                + "/deployments/start").then().statusCode(200).body("jobSummary.status", equalTo("RUNNING"));
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).until(() ->
                "SUCCEED".equals(amplify().get("/apps/" + appId + "/branches/" + branch + "/jobs/"
                        + staged.get("jobId")).then().extract().path("job.summary.status")));
        return staged.get("jobId");
    }

    @Test
    void aManualDeploymentIsServedAtTheBranchHost() throws Exception {
        String appId = createApp(",\"customRules\":[{\"source\":\"/old\",\"target\":\"/about.html\",\"status\":"
                + "\"301\"},{\"source\":\"</^[^.]+$|\\\\.(?!(css|html|js)$)([^.]+$)/>\",\"target\":\"/index.html\","
                + "\"status\":\"200\"}]");
        amplify().get("/apps/" + appId).then().statusCode(200)
                .body("app.defaultDomain", equalTo(appId + ".amplifyapp.com"))
                .body("app.appArn", containsString(":apps/" + appId)).body("app.tags.team", equalTo("web"));
        amplify().body("{\"branchName\":\"main\",\"stage\":\"PRODUCTION\",\"enableAutoBuild\":false}")
                .post("/apps/" + appId + "/branches").then().statusCode(200)
                .body("branch.branchArn", containsString("/branches/main"));
        amplify().body("{\"branchName\":\"main\"}").post("/apps/" + appId + "/branches").then().statusCode(400)
                .body("__type", containsString("BadRequestException"));

        String host = "main." + appId + ".amplifyapp.com";
        given().header("Host", host).get("/").then().statusCode(404);

        String first = deploy(appId, "main", zip(Map.of("index.html", "<h1>hello amplify</h1>",
                "about.html", "about", "app.js", "console.log(1)")));
        given().header("Host", host).get("/").then().statusCode(200).body(containsString("hello amplify"))
                .header("Content-Type", startsWith("text/html"));
        given().header("Host", host).get("/app.js").then().statusCode(200)
                .header("Content-Type", startsWith("application/javascript"));
        given().header("Host", host).redirects().follow(false).get("/old").then().statusCode(301)
                .header("Location", "/about.html");
        // The SPA rewrite serves index.html for a client-side route.
        given().header("Host", host).get("/dashboard/settings").then().statusCode(200)
                .body(containsString("hello amplify"));
        amplify().get("/apps/" + appId).then().body("app.productionBranch.branchName", equalTo("main"))
                .body("app.productionBranch.status", equalTo("SUCCEED"));

        String jobs = "/apps/" + appId + "/branches/main/jobs/";
        amplify().delete(jobs + first).then().statusCode(400).body("message", containsString("active job"));
        amplify().delete(jobs + first + "/stop").then().statusCode(400);
        amplify().body("{\"jobType\":\"RELEASE\"}").post("/apps/" + appId + "/branches/main/jobs").then()
                .statusCode(400).body("__type", containsString("BadRequestException"));
        amplify().get(jobs + first + "/artifacts").then().statusCode(200).body("artifacts", hasSize(0));

        String second = deploy(appId, "main", zip(Map.of("index.html", "<h1>second</h1>")));
        given().header("Host", host).get("/").then().statusCode(200).body(containsString("second"));
        amplify().get("/apps/" + appId + "/branches/main/jobs").then().statusCode(200)
                .body("jobSummaries.jobId", hasItem(second)).body("jobSummaries[0].jobId", equalTo(second));
        amplify().delete(jobs + first).then().statusCode(200);
        amplify().get("/apps/" + appId + "/branches/main/jobs").then()
                .body("jobSummaries.jobId", not(hasItem(first)));

        // A RETRY job deploys the files of an earlier job again.
        amplify().body("{\"jobType\":\"RETRY\",\"jobId\":\"" + second + "\"}")
                .post("/apps/" + appId + "/branches/main/jobs").then().statusCode(200)
                .body("jobSummary.jobType", equalTo("RETRY"));

        String logUrl = amplify().body("{\"domainName\":\"" + appId + ".amplifyapp.com\"}")
                .post("/apps/" + appId + "/accesslogs").then().statusCode(200).extract().path("logUrl");
        given().get(URI.create(logUrl).getPath()).then().statusCode(200).body(containsString(host))
                .body(containsString("/dashboard/settings"));

        // A deployment that never uploads anything fails.
        String empty = amplify().body("{}").post("/apps/" + appId + "/branches/main/deployments").then()
                .statusCode(200).extract().path("jobId");
        amplify().body("{\"jobId\":\"" + empty + "\"}").post("/apps/" + appId + "/branches/main/deployments/start")
                .then().statusCode(200);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "FAILED".equals(amplify().get(jobs + empty)
                .then().extract().path("job.summary.status")));

        amplify().delete("/apps/" + appId).then().statusCode(200);
        amplify().get("/apps/" + appId).then().statusCode(404).body("__type", containsString("NotFoundException"));
        given().header("Host", host).get("/").then().statusCode(not(equalTo(200)));
    }

    @Test
    void basicAuthAndCustomDomainsWithRoute53() throws Exception {
        String credentials = Base64.getEncoder().encodeToString("ada:secret".getBytes(StandardCharsets.UTF_8));
        String appId = createApp("");
        amplify().body("{\"branchName\":\"dev\",\"enableBasicAuth\":true,\"basicAuthCredentials\":\"" + credentials
                + "\",\"ttl\":\"300\"}").post("/apps/" + appId + "/branches").then().statusCode(200)
                .body("branch.ttl", equalTo("300"));
        deploy(appId, "dev", zip(Map.of("index.html", "private")));
        String host = "dev." + appId + ".amplifyapp.com";
        given().header("Host", host).get("/").then().statusCode(401).header("WWW-Authenticate", containsString("Basic"));
        given().header("Host", host).header("Authorization", "Basic " + credentials).get("/").then().statusCode(200)
                .body(equalTo("private"));

        String domain = "amp-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53/aws4_request")
                .contentType("application/xml")
                .body("<CreateHostedZoneRequest xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\"><Name>" + domain
                        + "</Name><CallerReference>" + UUID.randomUUID() + "</CallerReference></CreateHostedZoneRequest>")
                .post("/2013-04-01/hostedzone").then().statusCode(201);
        amplify().body("{\"domainName\":\"" + domain + "\",\"subDomainSettings\":[{\"prefix\":\"www\",\"branchName\":"
                + "\"dev\"}]}").post("/apps/" + appId + "/domains").then().statusCode(200)
                .body("domainAssociation.domainStatus", equalTo("AVAILABLE"))
                .body("domainAssociation.subDomains[0].verified", equalTo(true));
        amplify().get("/apps/" + appId + "/branches/dev").then().body("branch.customDomains", hasItem("www." + domain));
        given().header("Host", "www." + domain).header("Authorization", "Basic " + credentials).get("/").then()
                .statusCode(200).body(equalTo("private"));

        String unverified = "amp-" + UUID.randomUUID().toString().substring(0, 8) + ".example.org";
        amplify().body("{\"domainName\":\"" + unverified + "\",\"subDomainSettings\":[{\"prefix\":\"\",\"branchName\":"
                + "\"dev\"}]}").post("/apps/" + appId + "/domains").then().statusCode(200)
                .body("domainAssociation.domainStatus", equalTo("PENDING_VERIFICATION"))
                .body("domainAssociation.certificateVerificationDNSRecord", containsString("CNAME"));
        amplify().get("/apps/" + appId + "/domains").then().body("domainAssociations", hasSize(2));
        amplify().delete("/apps/" + appId + "/domains/" + domain).then().statusCode(200);
        amplify().get("/apps/" + appId + "/branches/dev").then()
                .body("branch.customDomains", not(hasItem("www." + domain)));
    }

    @Test
    void webhooksBackendsAndTags() {
        String appId = createApp("");
        amplify().body("{\"branchName\":\"main\"}").post("/apps/" + appId + "/branches").then().statusCode(200);
        String webhookId = amplify().body("{\"branchName\":\"main\",\"description\":\"ci\"}")
                .post("/apps/" + appId + "/webhooks").then().statusCode(200)
                .body("webhook.webhookUrl", startsWith("https://webhooks.amplify.us-east-1.")).extract()
                .path("webhook.webhookId");
        amplify().body("{\"description\":\"deploys\"}").post("/webhooks/" + webhookId).then().statusCode(200)
                .body("webhook.description", equalTo("deploys"));
        amplify().get("/apps/" + appId + "/webhooks").then().body("webhooks", hasSize(1));
        amplify().delete("/webhooks/" + webhookId).then().statusCode(200);
        amplify().get("/webhooks/" + webhookId).then().statusCode(404);

        amplify().body("{\"environmentName\":\"staging\"}").post("/apps/" + appId + "/backendenvironments").then()
                .statusCode(200).body("backendEnvironment.stackName", equalTo("amplify-" + appId + "-staging"));
        amplify().get("/apps/" + appId + "/backendenvironments").then().body("backendEnvironments", hasSize(1));
        amplify().delete("/apps/" + appId + "/backendenvironments/staging").then().statusCode(200);

        String arn = amplify().get("/apps/" + appId).then().extract().path("app.appArn");
        amplify().body("{\"tags\":{\"env\":\"dev\"}}").post("/tags/" + arn).then().statusCode(200);
        amplify().get("/tags/" + arn).then().statusCode(200).body("tags.env", equalTo("dev"))
                .body("tags.team", equalTo("web"));
        amplify().delete("/tags/" + arn + "?tagKeys=team").then().statusCode(200);
        amplify().get("/tags/" + arn).then().body("tags.team", equalTo(null));
        amplify().body("{\"name\":\"x\",\"repository\":\"https://github.com/example/site\"}").post("/apps").then()
                .statusCode(400).body("message", containsString("accessToken"));
    }
}
