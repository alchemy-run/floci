package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Query-protocol coverage for NLB/TCP CreateListener (the InternalFailure NPE)
 * and SNI certificate IsDefault attach/detach.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2ListenerCertificateIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";
    private static final String DEFAULT_CERT =
            "arn:aws:acm:us-east-1:000000000000:certificate/default-elbv2";
    private static final String SNI_CERT =
            "arn:aws:acm:us-east-1:000000000000:certificate/sni-elbv2";

    private static String nlbArn;
    private static String nlbTgArn;
    private static String albArn;
    private static String listenerArn;

    @Test
    @Order(1)
    void createNlbTcpListenerWithForwardConfigOnly() {
        nlbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "sni-nlb")
                .formParam("Type", "network")
                .formParam("Scheme", "internal")
                .formParam("Subnets.member.1", "subnet-default-a")
                .formParam("Subnets.member.2", "subnet-default-b")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        nlbTgArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "sni-nlb-tg")
                .formParam("Protocol", "TCP")
                .formParam("Port", "80")
                .formParam("VpcId", "vpc-default")
                .formParam("TargetType", "ip")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");

        given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", nlbArn)
                .formParam("Protocol", "TCP")
                .formParam("Port", "80")
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.ForwardConfig.TargetGroups.member.1.TargetGroupArn", nlbTgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateListenerResponse.CreateListenerResult.Listeners.member.Protocol",
                        equalTo("TCP"))
                .body("CreateListenerResponse.CreateListenerResult.Listeners.member.Port",
                        equalTo("80"))
                .body("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn",
                        startsWith("arn:aws:elasticloadbalancing:"));
    }

    @Test
    @Order(2)
    void createHttpsListenerThenAttachAndDetachSniCertificate() {
        albArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "sni-alb")
                .formParam("Type", "application")
                .formParam("Scheme", "internal")
                .formParam("Subnets.member.1", "subnet-default-a")
                .formParam("Subnets.member.2", "subnet-default-b")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        listenerArn = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", albArn)
                .formParam("Protocol", "HTTPS")
                .formParam("Port", "443")
                .formParam("Certificates.member.1.CertificateArn", DEFAULT_CERT)
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");

        given()
                .formParam("Action", "AddListenerCertificates")
                .formParam("ListenerArn", listenerArn)
                .formParam("Certificates.member.1.CertificateArn", SNI_CERT)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("AddListenerCertificatesResponse.AddListenerCertificatesResult.Certificates.member.CertificateArn",
                        equalTo(SNI_CERT))
                .body("AddListenerCertificatesResponse.AddListenerCertificatesResult.Certificates.member.IsDefault",
                        equalTo("false"));

        given()
                .formParam("Action", "DescribeListenerCertificates")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member[0].CertificateArn",
                        equalTo(DEFAULT_CERT))
                .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member[0].IsDefault",
                        equalTo("true"))
                .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member[1].CertificateArn",
                        equalTo(SNI_CERT))
                .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member[1].IsDefault",
                        equalTo("false"));

        given()
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listenerArn)
                .formParam("Certificates.member.1.CertificateArn", DEFAULT_CERT)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeListenerCertificates")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member[1].CertificateArn",
                        equalTo(SNI_CERT));

        given()
                .formParam("Action", "RemoveListenerCertificates")
                .formParam("ListenerArn", listenerArn)
                .formParam("Certificates.member.1.CertificateArn", SNI_CERT)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeListenerCertificates")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member.CertificateArn",
                        equalTo(DEFAULT_CERT))
                .body("DescribeListenerCertificatesResponse.DescribeListenerCertificatesResult.Certificates.member.IsDefault",
                        equalTo("true"));
    }
}
