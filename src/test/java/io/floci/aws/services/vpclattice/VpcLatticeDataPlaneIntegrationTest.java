package io.floci.aws.services.vpclattice;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * VPC Lattice's data plane: requests to a service's DNS name on a listener port are matched against the listener's
 * rules (method, path, headers, priority), answered with fixed responses or forwarded to IP targets with the
 * x-amzn-lattice headers, checked against AWS_IAM auth policies, and written to access log subscriptions.
 */
@QuarkusTest
class VpcLatticeDataPlaneIntegrationTest {

    private static final String ACCOUNT = "000000000000";

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

    private static String vpc(String cidr) {
        String xml = given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260101/us-east-1/ec2/aws4_request").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateVpc").formParam("Version", "2016-11-15").formParam("CidrBlock", cidr)
                .post("/").then().statusCode(200).extract().asString();
        return xml.substring(xml.indexOf("<vpcId>") + 7, xml.indexOf("</vpcId>"));
    }

    private static void subnet(String vpcId, String cidr) {
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/ec2/aws4_request")
                .contentType("application/x-www-form-urlencoded").formParam("Action", "CreateSubnet")
                .formParam("Version", "2016-11-15").formParam("VpcId", vpcId).formParam("CidrBlock", cidr).post("/")
                .then().statusCode(200);
    }

    private static void awaitActive(String path) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> lattice().get(path).then().statusCode(200)
                .body("status", equalTo("ACTIVE")));
    }

    /** A request to the service through Floci's listener for the Lattice listener port. */
    private RequestSpecification call(String host) {
        int port = await().atMost(Duration.ofSeconds(10)).until(() -> dataPlane.flociPort("HTTP", 80).orElse(0),
                p -> p > 0);
        return given().baseUri("http://127.0.0.1").port(port).header("Host", host);
    }

    @Test
    void rulesFixedResponsesAuthAndAccessLogs() {
        String network = lattice().body("{\"name\":\"dp-net\"}").post("/servicenetworks").then().statusCode(201)
                .extract().path("id");
        String service = lattice().body("{\"name\":\"dp-shop\"}").post("/services").then().statusCode(201)
                .extract().path("id");
        awaitActive("/services/" + service);
        String host = lattice().get("/services/" + service).then().extract().path("dnsEntry.domainName");
        String association = lattice().body("{\"serviceNetworkIdentifier\":\"" + network + "\",\"serviceIdentifier\":\""
                + service + "\"}").post("/servicenetworkserviceassociations").then().statusCode(200).extract()
                .path("id");
        awaitActive("/servicenetworkserviceassociations/" + association);
        String listener = lattice().body("{\"name\":\"http\",\"protocol\":\"HTTP\",\"defaultAction\":"
                + "{\"fixedResponse\":{\"statusCode\":404}}}").post("/services/" + service + "/listeners").then()
                .statusCode(201).extract().path("id");
        String rules = "/services/" + service + "/listeners/" + listener + "/rules";
        lattice().body("{\"name\":\"health\",\"priority\":10,\"match\":{\"httpMatch\":{\"method\":\"GET\","
                + "\"pathMatch\":{\"match\":{\"exact\":\"/health\"}}}},\"action\":{\"fixedResponse\":"
                + "{\"statusCode\":204}}}").post(rules).then().statusCode(201);
        lattice().body("{\"name\":\"canary\",\"priority\":5,\"match\":{\"httpMatch\":{\"headerMatches\":[{\"name\":"
                + "\"x-canary\",\"match\":{\"exact\":\"yes\"}}]}},\"action\":{\"fixedResponse\":{\"statusCode\":"
                + "418}}}").post(rules).then().statusCode(201);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> call(host).get("/health").then().statusCode(204));
        call(host).post("/health").then().statusCode(404);
        call(host).get("/other").then().statusCode(404);
        call(host).header("x-canary", "YES").get("/health").then().statusCode(418);
        call("nothing.example.com").get("/").then().statusCode(404)
                .body(containsString("No VPC Lattice service"));

        // Access logs go to the service's subscriptions.
        given().header("X-Amz-Target", "Logs_20140328.CreateLogGroup").contentType("application/x-amz-json-1.1")
                .body("{\"logGroupName\":\"lattice-access\"}").post("/").then().statusCode(200);
        lattice().body("{\"resourceIdentifier\":\"" + service + "\",\"destinationArn\":\"arn:aws:logs:us-east-1:"
                + ACCOUNT + ":log-group:lattice-access\"}").post("/accesslogsubscriptions").then().statusCode(201);

        // AWS_IAM: no auth policy denies everything; an allow lets anyone in but an explicit deny wins.
        lattice().body("{\"authType\":\"AWS_IAM\"}").patch("/services/" + service).then().statusCode(200);
        call(host).get("/health").then().statusCode(403).body(containsString("AccessDeniedException"));
        String allow = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"vpc-lattice-svcs:Invoke\",\"Resource\":\"*\"},{\"Effect\":\"Deny\",\"Principal\":\"*\","
                + "\"Action\":\"vpc-lattice-svcs:Invoke\",\"Resource\":\"*/admin*\"}]}";
        lattice().body("{\"policy\":\"" + allow.replace("\"", "\\\"") + "\"}").put("/authpolicy/" + service).then()
                .statusCode(200);
        call(host).get("/health").then().statusCode(204);
        call(host).get("/admin/users").then().statusCode(403);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given()
                .header("X-Amz-Target", "Logs_20140328.FilterLogEvents").contentType("application/x-amz-json-1.1")
                .body("{\"logGroupName\":\"lattice-access\"}").post("/").then().statusCode(200)
                .body("events.message", hasItem(containsString("\"responseCode\":403"))));
    }

    @Test
    void forwardsToIpTargetsWithLatticeHeaders() throws Exception {
        Optional<String> address = privateAddress();
        Assumptions.assumeTrue(address.isPresent(), "needs a private IPv4 address on this machine");
        HttpServer backend = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        backend.createContext("/", exchange -> {
            String target = String.valueOf(exchange.getRequestHeaders().getFirst("x-amzn-lattice-target"));
            byte[] body = ("hello from " + exchange.getRequestURI().getPath() + " via " + target)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();
        try {
            String[] octets = address.get().split("\\.");
            String vpcId = vpc(octets[0] + "." + octets[1] + ".0.0/16");
            subnet(vpcId, octets[0] + "." + octets[1] + "." + octets[2] + ".0/24");
            String group = lattice().body("{\"name\":\"dp-backend\",\"type\":\"IP\",\"config\":{\"port\":"
                    + backend.getAddress().getPort() + ",\"protocol\":\"HTTP\",\"vpcIdentifier\":\"" + vpcId
                    + "\",\"healthCheck\":{\"enabled\":false}}}").post("/targetgroups").then().statusCode(201)
                    .extract().path("id");
            awaitActive("/targetgroups/" + group);
            lattice().body("{\"targets\":[{\"id\":\"" + address.get() + "\"}]}")
                    .post("/targetgroups/" + group + "/registertargets").then().body("successful.size()", equalTo(1));
            String service = lattice().body("{\"name\":\"dp-api\"}").post("/services").then().statusCode(201)
                    .extract().path("id");
            awaitActive("/services/" + service);
            String host = lattice().get("/services/" + service).then().extract().path("dnsEntry.domainName");
            lattice().body("{\"name\":\"http\",\"protocol\":\"HTTP\",\"defaultAction\":{\"forward\":{\"targetGroups\":"
                    + "[{\"targetGroupIdentifier\":\"" + group + "\"}]}}}").post("/services/" + service + "/listeners")
                    .then().statusCode(201);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> call(host).get("/orders/7").then()
                    .statusCode(200).body(containsString("hello from /orders/7"))
                    .body(containsString("TargetGroupArn=arn:aws:vpc-lattice:us-east-1:" + ACCOUNT + ":targetgroup/"
                            + group)));
        } finally {
            backend.stop(0);
        }
    }

    private static Optional<String> privateAddress() throws Exception {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    return Optional.of(address.getHostAddress());
                }
            }
        }
        return Optional.empty();
    }
}
