package io.floci.aws.services.vpclattice;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * VPC Lattice control plane against real EC2, Lambda, S3 and Route 53 resources: service networks and their
 * associations (one network per VPC, no delete while associated), services, target groups and their targets
 * (validated against the VPC's subnets, with use- and health-driven status), listeners and rules, policies, access
 * log subscriptions, domain verification through a published TXT record, idempotent creates and tags.
 */
@QuarkusTest
class VpcLatticeIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification lattice() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/vpc-lattice/aws4_request")
                .contentType("application/json");
    }

    private static String ec2(String action, String... params) {
        RequestSpecification spec = given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260101/us-east-1/ec2/aws4_request").contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("Version", "2016-11-15");
        for (int i = 0; i < params.length; i += 2) {
            spec = spec.formParam(params[i], params[i + 1]);
        }
        return spec.post("/").then().statusCode(200).extract().asString();
    }

    private static String between(String xml, String tag) {
        int start = xml.indexOf("<" + tag + ">") + tag.length() + 2;
        return xml.substring(start, xml.indexOf("</" + tag + ">", start));
    }

    private static String vpc(String cidr) {
        return between(ec2("CreateVpc", "CidrBlock", cidr), "vpcId");
    }

    private static ValidatableResponse awaitStatus(String path, String status) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                lattice().get(path).then().statusCode(200).body("status", equalTo(status)));
        return lattice().get(path).then();
    }

    private static void awaitGone(String path) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> lattice().get(path).then().statusCode(404));
    }

    private static String lambdaFunction(String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({ statusCode: 200 });".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return given().contentType("application/json").body("{\"FunctionName\":\"" + name + "\",\"Runtime\":"
                        + "\"nodejs20.x\",\"Handler\":\"index.handler\",\"Role\":\"arn:aws:iam::" + ACCOUNT
                        + ":role/lambda-role\",\"Code\":{\"ZipFile\":\"" + Base64.getEncoder()
                        .encodeToString(bytes.toByteArray()) + "\"}}").post("/2015-03-31/functions").then()
                .statusCode(201).extract().path("FunctionArn");
    }

    @Test
    void networksServicesAndVpcAssociations() {
        String created = lattice().body("{\"name\":\"orders-net\",\"clientToken\":\"t-1\",\"tags\":{\"env\":\"dev\"}}")
                .post("/servicenetworks").then().statusCode(201).body("authType", equalTo("NONE"))
                .body("arn", startsWith("arn:aws:vpc-lattice:us-east-1:" + ACCOUNT + ":servicenetwork/sn-"))
                .extract().path("id");
        // The same client token replays the create; different parameters with it conflict.
        lattice().body("{\"name\":\"orders-net\",\"clientToken\":\"t-1\",\"tags\":{\"env\":\"dev\"}}")
                .post("/servicenetworks").then().statusCode(201).body("id", equalTo(created));
        lattice().body("{\"name\":\"other-net\",\"clientToken\":\"t-1\"}").post("/servicenetworks").then()
                .statusCode(409);
        lattice().body("{\"name\":\"orders-net\"}").post("/servicenetworks").then().statusCode(409)
                .body("resourceId", equalTo(created));
        lattice().body("{\"authType\":\"AWS_IAM\"}").patch("/servicenetworks/" + created).then().statusCode(200)
                .body("authType", equalTo("AWS_IAM"));

        String service = lattice().body("{\"name\":\"orders\",\"idleTimeoutSeconds\":90}").post("/services").then()
                .statusCode(201).body("status", equalTo("CREATE_IN_PROGRESS"))
                .body("dnsEntry.domainName", startsWith("orders-"))
                .body("dnsEntry.domainName", endsWith(".vpc-lattice-svcs.us-east-1.on.aws")).extract().path("id");
        lattice().body("{\"idleTimeoutSeconds\":120}").patch("/services/" + service).then().statusCode(409);
        awaitStatus("/services/" + service, "ACTIVE").body("idleTimeoutSeconds", equalTo(90));
        lattice().body("{\"idleTimeoutSeconds\":120}").patch("/services/" + service).then().statusCode(200)
                .body("idleTimeoutSeconds", equalTo(120));

        String serviceAssociation = lattice().body("{\"serviceNetworkIdentifier\":\"" + created
                        + "\",\"serviceIdentifier\":\"" + service + "\"}").post("/servicenetworkserviceassociations")
                .then().statusCode(200).body("status", equalTo("CREATE_IN_PROGRESS")).extract().path("id");
        lattice().body("{\"serviceNetworkIdentifier\":\"" + created + "\",\"serviceIdentifier\":\"" + service + "\"}")
                .post("/servicenetworkserviceassociations").then().statusCode(409);
        awaitStatus("/servicenetworkserviceassociations/" + serviceAssociation, "ACTIVE")
                .body("serviceName", equalTo("orders")).body("serviceNetworkName", equalTo("orders-net"));

        // A VPC joins one service network.
        String vpcId = vpc("10.40.0.0/16");
        lattice().body("{\"serviceNetworkIdentifier\":\"" + created + "\",\"vpcIdentifier\":\"vpc-0000000000000dead\"}")
                .post("/servicenetworkvpcassociations").then().statusCode(404);
        String vpcAssociation = lattice().body("{\"serviceNetworkIdentifier\":\"" + created + "\",\"vpcIdentifier\":\""
                        + vpcId + "\"}").post("/servicenetworkvpcassociations").then().statusCode(200)
                .extract().path("id");
        String second = lattice().body("{\"name\":\"second-net\"}").post("/servicenetworks").then().statusCode(201)
                .extract().path("id");
        lattice().body("{\"serviceNetworkIdentifier\":\"" + second + "\",\"vpcIdentifier\":\"" + vpcId + "\"}")
                .post("/servicenetworkvpcassociations").then().statusCode(409);
        awaitStatus("/servicenetworkvpcassociations/" + vpcAssociation, "ACTIVE").body("vpcId", equalTo(vpcId));
        lattice().queryParam("vpcIdentifier", vpcId).get("/servicenetworkvpcassociations").then().statusCode(200)
                .body("items.id", equalTo(List.of(vpcAssociation)));
        lattice().get("/servicenetworks/" + created).then().statusCode(200)
                .body("numberOfAssociatedVPCs", equalTo(1)).body("numberOfAssociatedServices", equalTo(1));

        // Nothing in use can be deleted.
        lattice().delete("/servicenetworks/" + created).then().statusCode(409);
        lattice().delete("/services/" + service).then().statusCode(409);
        lattice().delete("/servicenetworkvpcassociations/" + vpcAssociation).then().statusCode(200)
                .body("status", equalTo("DELETE_IN_PROGRESS"));
        lattice().delete("/servicenetworkserviceassociations/" + serviceAssociation).then().statusCode(200);
        awaitGone("/servicenetworkvpcassociations/" + vpcAssociation);
        awaitGone("/servicenetworkserviceassociations/" + serviceAssociation);
        lattice().delete("/servicenetworks/" + created).then().statusCode(204);
        lattice().delete("/servicenetworks/" + second).then().statusCode(204);
        lattice().delete("/services/" + service).then().statusCode(200).body("status", equalTo("DELETE_IN_PROGRESS"));
        awaitGone("/services/" + service);
        lattice().get("/servicenetworks/" + created).then().statusCode(404)
                .body("resourceType", equalTo("SERVICE_NETWORK"));
    }

    @Test
    void targetGroupsListenersAndRules() throws Exception {
        String vpcId = vpc("10.41.0.0/16");
        ec2("CreateSubnet", "VpcId", vpcId, "CidrBlock", "10.41.1.0/24");
        lattice().body("{\"name\":\"web\",\"type\":\"IP\",\"config\":{\"port\":80,\"protocol\":\"HTTP\"}}")
                .post("/targetgroups").then().statusCode(400).body("message", containsString("vpcIdentifier"));
        String group = lattice().body("{\"name\":\"web\",\"type\":\"IP\",\"config\":{\"port\":80,\"protocol\":\"HTTP\","
                        + "\"vpcIdentifier\":\"" + vpcId + "\",\"healthCheck\":{\"enabled\":false}}}").post("/targetgroups")
                .then().statusCode(201).body("status", equalTo("CREATE_IN_PROGRESS"))
                .body("config.healthCheck.path", equalTo("/")).extract().path("id");
        lattice().body("{\"targets\":[{\"id\":\"10.41.1.10\"}]}").post("/targetgroups/" + group + "/registertargets")
                .then().statusCode(409);
        awaitStatus("/targetgroups/" + group, "ACTIVE");
        lattice().body("{\"targets\":[{\"id\":\"10.41.1.10\"},{\"id\":\"10.99.0.1\"}]}")
                .post("/targetgroups/" + group + "/registertargets").then().statusCode(200)
                .body("successful.id", equalTo(List.of("10.41.1.10")))
                .body("unsuccessful[0].failureMessage", containsString("not in a subnet"));
        lattice().body("{}").post("/targetgroups/" + group + "/listtargets").then().statusCode(200)
                .body("items[0].status", equalTo("UNUSED")).body("items[0].port", equalTo(80));

        // A LAMBDA group holds one existing function.
        String function = lambdaFunction("lattice-target");
        String lambdaGroup = lattice().body("{\"name\":\"fn-group\",\"type\":\"LAMBDA\"}").post("/targetgroups").then()
                .statusCode(201).body("config.lambdaEventStructureVersion", equalTo("V1")).extract().path("id");
        awaitStatus("/targetgroups/" + lambdaGroup, "ACTIVE");
        lattice().body("{\"targets\":[{\"id\":\"" + function + "\"}]}")
                .post("/targetgroups/" + lambdaGroup + "/registertargets").then().body("successful.size()", equalTo(1));
        lattice().body("{\"targets\":[{\"id\":\"" + function.replace("lattice-target", "missing") + "\"}]}")
                .post("/targetgroups/" + lambdaGroup + "/registertargets").then()
                .body("unsuccessful.size()", equalTo(1));

        String service = lattice().body("{\"name\":\"web\"}").post("/services").then().statusCode(201).extract()
                .path("id");
        awaitStatus("/services/" + service, "ACTIVE");
        String listener = lattice().body("{\"name\":\"http\",\"protocol\":\"HTTP\",\"defaultAction\":{\"forward\":"
                        + "{\"targetGroups\":[{\"targetGroupIdentifier\":\"" + group + "\",\"weight\":100}]}}}")
                .post("/services/" + service + "/listeners").then().statusCode(201).body("port", equalTo(80))
                .body("id", startsWith("listener-")).extract().path("id");
        lattice().body("{\"name\":\"http2\",\"protocol\":\"HTTP\",\"defaultAction\":{\"fixedResponse\":"
                + "{\"statusCode\":404}}}").post("/services/" + service + "/listeners").then().statusCode(409);
        lattice().get("/targetgroups/" + group).then().body("serviceArns", hasItem(endsWith(service)));
        lattice().body("{}").post("/targetgroups/" + group + "/listtargets").then()
                .body("items[0].status", equalTo("UNAVAILABLE"));

        String rule = lattice().body("{\"name\":\"api\",\"priority\":10,\"match\":{\"httpMatch\":{\"pathMatch\":"
                        + "{\"match\":{\"prefix\":\"/api\"}}}},\"action\":{\"forward\":{\"targetGroups\":"
                        + "[{\"targetGroupIdentifier\":\"" + lambdaGroup + "\"}]}}}")
                .post("/services/" + service + "/listeners/" + listener + "/rules").then().statusCode(201)
                .extract().path("id");
        lattice().body("{\"name\":\"other\",\"priority\":10,\"match\":{\"httpMatch\":{\"method\":\"GET\"}},"
                        + "\"action\":{\"fixedResponse\":{\"statusCode\":200}}}")
                .post("/services/" + service + "/listeners/" + listener + "/rules").then().statusCode(409);
        lattice().get("/services/" + service + "/listeners/" + listener + "/rules").then().statusCode(200)
                .body("items.name", equalTo(List.of("api", "default")))
                .body("items.isDefault", equalTo(List.of(false, true)));
        lattice().body("{\"rules\":[{\"ruleIdentifier\":\"" + rule + "\",\"priority\":20},{\"ruleIdentifier\":"
                        + "\"rule-00000000000000000\",\"priority\":30}]}")
                .patch("/services/" + service + "/listeners/" + listener + "/rules").then().statusCode(200)
                .body("successful[0].priority", equalTo(20)).body("unsuccessful[0].failureCode",
                        equalTo("ResourceNotFoundException"));

        // In-use target groups and groups with targets can't be deleted; deregistered targets drain away.
        lattice().delete("/targetgroups/" + group).then().statusCode(409);
        lattice().body("{\"defaultAction\":{\"fixedResponse\":{\"statusCode\":404}}}")
                .patch("/services/" + service + "/listeners/" + listener).then().statusCode(200);
        lattice().delete("/targetgroups/" + group).then().statusCode(409)
                .body("message", containsString("targets registered"));
        lattice().body("{\"targets\":[{\"id\":\"10.41.1.10\"}]}").post("/targetgroups/" + group + "/deregistertargets")
                .then().statusCode(200);
        lattice().body("{}").post("/targetgroups/" + group + "/listtargets").then()
                .body("items[0].status", equalTo("DRAINING"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> lattice().body("{}")
                .post("/targetgroups/" + group + "/listtargets").then().body("items.size()", equalTo(0)));
        lattice().delete("/targetgroups/" + group).then().statusCode(200);
        awaitGone("/targetgroups/" + group);
        lattice().delete("/services/" + service + "/listeners/" + listener).then().statusCode(204);
        lattice().get("/services/" + service + "/listeners/" + listener + "/rules/" + rule).then().statusCode(404);
    }

    @Test
    void unreachableTargetsTurnUnhealthy() {
        String vpcId = vpc("10.42.0.0/16");
        ec2("CreateSubnet", "VpcId", vpcId, "CidrBlock", "10.42.1.0/24");
        String group = lattice().body("{\"name\":\"probed\",\"type\":\"IP\",\"config\":{\"port\":8080,\"protocol\":"
                        + "\"HTTP\",\"vpcIdentifier\":\"" + vpcId + "\",\"healthCheck\":{\"healthCheckIntervalSeconds\":"
                        + "5,\"healthCheckTimeoutSeconds\":1,\"unhealthyThresholdCount\":2}}}").post("/targetgroups")
                .then().statusCode(201).extract().path("id");
        awaitStatus("/targetgroups/" + group, "ACTIVE");
        lattice().body("{\"targets\":[{\"id\":\"10.42.1.20\"}]}").post("/targetgroups/" + group + "/registertargets")
                .then().statusCode(200);
        String service = lattice().body("{\"name\":\"probed\"}").post("/services").then().extract().path("id");
        awaitStatus("/services/" + service, "ACTIVE");
        lattice().body("{\"name\":\"http\",\"protocol\":\"HTTP\",\"defaultAction\":{\"forward\":{\"targetGroups\":"
                + "[{\"targetGroupIdentifier\":\"" + group + "\"}]}}}").post("/services/" + service + "/listeners")
                .then().statusCode(201);
        lattice().body("{}").post("/targetgroups/" + group + "/listtargets").then()
                .body("items[0].status", equalTo("INITIAL"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> lattice().body("{}")
                .post("/targetgroups/" + group + "/listtargets").then().body("items[0].status", equalTo("UNHEALTHY")));
    }

    @Test
    void policiesAccessLogsDomainsAndTags() {
        String network = lattice().body("{\"name\":\"policy-net\"}").post("/servicenetworks").then().statusCode(201)
                .extract().path("id");
        String arn = lattice().get("/servicenetworks/" + network).then().extract().path("arn");
        lattice().get("/authpolicy/" + network).then().statusCode(404);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"vpc-lattice-svcs:Invoke\",\"Resource\":\"*\"}]}";
        lattice().body("{\"policy\":\"not json\"}").put("/authpolicy/" + network).then().statusCode(400);
        lattice().body("{\"policy\":" + quote(policy) + "}").put("/authpolicy/" + network).then().statusCode(200)
                .body("state", equalTo("Inactive"));
        lattice().body("{\"authType\":\"AWS_IAM\"}").patch("/servicenetworks/" + network).then().statusCode(200);
        lattice().get("/authpolicy/" + network).then().statusCode(200).body("state", equalTo("Active"))
                .body("policy", equalTo(policy));
        lattice().get("/resourcepolicy/{arn}", arn).then().statusCode(404);
        lattice().body("{\"policy\":" + quote(policy.replace("vpc-lattice-svcs:Invoke",
                "vpc-lattice:GetServiceNetwork")) + "}").put("/resourcepolicy/{arn}", arn).then().statusCode(200);
        lattice().get("/resourcepolicy/{arn}", arn).then().statusCode(200)
                .body("policy", containsString("GetServiceNetwork"));

        lattice().body("{\"resourceIdentifier\":\"" + network + "\",\"destinationArn\":\"arn:aws:s3:::lattice-logs\"}")
                .post("/accesslogsubscriptions").then().statusCode(404);
        given().put("/lattice-logs").then().statusCode(200);
        String subscription = lattice().body("{\"resourceIdentifier\":\"" + network
                        + "\",\"destinationArn\":\"arn:aws:s3:::lattice-logs\"}").post("/accesslogsubscriptions").then()
                .statusCode(201).body("serviceNetworkLogType", equalTo("SERVICE")).extract().path("id");
        lattice().body("{\"resourceIdentifier\":\"" + network + "\",\"destinationArn\":\"arn:aws:s3:::lattice-logs\"}")
                .post("/accesslogsubscriptions").then().statusCode(409);
        lattice().queryParam("resourceIdentifier", network).get("/accesslogsubscriptions").then().statusCode(200)
                .body("items.id", equalTo(List.of(subscription)));

        lattice().body("{\"tags\":{\"team\":\"edge\"}}").post("/tags/{arn}", arn).then().statusCode(200);
        lattice().get("/tags/{arn}", arn).then().statusCode(200).body("tags.team", equalTo("edge"));
        lattice().queryParam("tagKeys", "team").delete("/tags/{arn}", arn).then().statusCode(200);
        lattice().get("/tags/{arn}", arn).then().body("tags.size()", equalTo(0));

        // A domain is verified once its TXT record is published in a Route 53 hosted zone.
        String verification = lattice().body("{\"domainName\":\"lattice.example.com\"}").post("/domainverifications")
                .then().statusCode(201).body("status", equalTo("PENDING")).extract().path("id");
        String name = lattice().get("/domainverifications/" + verification).then().extract()
                .path("txtMethodConfig.name");
        String value = lattice().get("/domainverifications/" + verification).then().extract()
                .path("txtMethodConfig.value");
        String zone = given().contentType("application/xml").body("<CreateHostedZoneRequest xmlns=\"https://route53."
                        + "amazonaws.com/doc/2013-04-01/\"><Name>example.com</Name><CallerReference>lattice</CallerReference>"
                        + "</CreateHostedZoneRequest>").post("/2013-04-01/hostedzone").then().statusCode(201).extract()
                .xmlPath().getString("CreateHostedZoneResponse.HostedZone.Id");
        given().contentType("application/xml").body("<ChangeResourceRecordSetsRequest xmlns=\"https://route53."
                + "amazonaws.com/doc/2013-04-01/\"><ChangeBatch><Changes><Change><Action>CREATE</Action>"
                + "<ResourceRecordSet><Name>" + name + "</Name><Type>TXT</Type><TTL>60</TTL><ResourceRecords>"
                + "<ResourceRecord><Value>\"" + value + "\"</Value></ResourceRecord></ResourceRecords>"
                + "</ResourceRecordSet></Change></Changes></ChangeBatch></ChangeResourceRecordSetsRequest>")
                .post("/2013-04-01/hostedzone/" + zone.substring(zone.lastIndexOf('/') + 1) + "/rrset").then()
                .statusCode(200);
        lattice().get("/domainverifications/" + verification).then().statusCode(200)
                .body("status", equalTo("VERIFIED"));

        lattice().delete("/accesslogsubscriptions/" + subscription).then().statusCode(204);
        lattice().delete("/authpolicy/" + network).then().statusCode(204);
        lattice().delete("/servicenetworks/" + network).then().statusCode(204);
    }

    private static String quote(String json) {
        return "\"" + json.replace("\"", "\\\"") + "\"";
    }
}
