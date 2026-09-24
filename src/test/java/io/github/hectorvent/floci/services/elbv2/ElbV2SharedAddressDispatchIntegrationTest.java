package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/**
 * Every Floci load balancer resolves to the same local address, so a client that resolved a load
 * balancer's DNS name and sends its request to the resulting IP carries no load balancer identity
 * in the Host header. With two listeners on one port, the request must reach the listener whose
 * explicit rule matches it, and later requests from that client that only match default rules must
 * stay on the same load balancer.
 */
@QuarkusTest
@TestProfile(ElbV2SharedAddressDispatchIntegrationTest.RealElbV2DataPlaneProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2SharedAddressDispatchIntegrationTest {

    public static final class RealElbV2DataPlaneProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elbv2.mock", "false");
        }
    }

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260923/us-east-1/elasticloadbalancing/aws4_request";
    private static final int LISTENER_PORT = 7794;

    private static String routedLbArn;
    private static String routedListenerArn;
    private static String otherLbArn;
    private static String otherListenerArn;

    @Test
    @Order(1)
    void createTwoLoadBalancersOnTheSamePort() {
        routedLbArn = createLoadBalancer("shared-address-routed");
        routedListenerArn = createFixedResponseListener(routedLbArn, "404", "routed default");
        given()
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", routedListenerArn)
                .formParam("Priority", "10")
                .formParam("Conditions.member.1.Field", "path-pattern")
                .formParam("Conditions.member.1.PathPatternConfig.Values.member.1", "/api/*")
                .formParam("Actions.member.1.Type", "fixed-response")
                .formParam("Actions.member.1.FixedResponseConfig.StatusCode", "200")
                .formParam("Actions.member.1.FixedResponseConfig.ContentType", "text/plain")
                .formParam("Actions.member.1.FixedResponseConfig.MessageBody", "routed api")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        otherLbArn = createLoadBalancer("shared-address-other");
        otherListenerArn = createFixedResponseListener(otherLbArn, "200", "other default");
    }

    @Test
    @Order(2)
    void requestWithOnlyDefaultMatchesIsAmbiguousBeforeAnyExplicitMatch() {
        given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
            .when()
                .get("/nothing")
            .then()
                .statusCode(502)
                .body(equalTo("No listener for host"));
    }

    @Test
    @Order(3)
    void ipAddressedRequestReachesTheListenerWhoseExplicitRuleMatches() {
        given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
            .when()
                .get("/api/hello")
            .then()
                .statusCode(200)
                .body(equalTo("routed api"));
    }

    @Test
    @Order(4)
    void laterDefaultOnlyRequestStaysOnTheSameLoadBalancer() {
        given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
            .when()
                .get("/nope")
            .then()
                .statusCode(404)
                .body(equalTo("routed default"));
    }

    @Test
    @Order(5)
    void unknownHostNameStillRejected() {
        given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
                .header("Host", "unknown.example.test")
            .when()
                .get("/api/hello")
            .then()
                .statusCode(502)
                .body(equalTo("No listener for host"));
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void cleanup() {
        deleteListener(routedListenerArn);
        deleteListener(otherListenerArn);
        deleteLoadBalancer(routedLbArn);
        deleteLoadBalancer(otherLbArn);
    }

    private static String createLoadBalancer(String name) {
        return given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", name)
                .formParam("Type", "application")
                .formParam("Scheme", "internet-facing")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    private static String createFixedResponseListener(String lbArn, String statusCode, String body) {
        return given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", String.valueOf(LISTENER_PORT))
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", statusCode)
                .formParam("DefaultActions.member.1.FixedResponseConfig.ContentType", "text/plain")
                .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", body)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    private static void deleteListener(String listenerArn) {
        if (listenerArn != null) {
            given()
                    .formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", listenerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteLoadBalancer(String lbArn) {
        if (lbArn != null) {
            given()
                    .formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", lbArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
}
