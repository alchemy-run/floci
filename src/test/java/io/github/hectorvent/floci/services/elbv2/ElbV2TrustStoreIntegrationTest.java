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
 * Trust-store and capacity-reservation Query actions used by Alchemy's ELBv2 suite.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2TrustStoreIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";

    private static final String CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIB
            -----END CERTIFICATE-----
            """;

    private static String trustStoreArn;
    private static String loadBalancerArn;

    @Test
    @Order(1)
    void createTrustStoreMissingBundleIsCaCertificatesBundleNotFound() {
        given()
                .formParam("Action", "CreateTrustStore")
                .formParam("Name", "missing-bundle-store")
                .formParam("CaCertificatesBundleS3Bucket", "no-such-elb-ca-bucket")
                .formParam("CaCertificatesBundleS3Key", "missing.pem")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CaCertificatesBundleNotFound"));
    }

    @Test
    @Order(2)
    void createTrustStoreFromUploadedBundle() {
        given().when().put("/elb-ca-bundles").then().statusCode(200);
        given()
                .contentType("application/x-pem-file")
                .body(CA_PEM)
            .when()
                .put("/elb-ca-bundles/ca-bundle.pem")
            .then()
                .statusCode(200);

        trustStoreArn = given()
                .formParam("Action", "CreateTrustStore")
                .formParam("Name", "alchemy-mtls")
                .formParam("CaCertificatesBundleS3Bucket", "elb-ca-bundles")
                .formParam("CaCertificatesBundleS3Key", "ca-bundle.pem")
                .formParam("Tags.member.1.Key", "env")
                .formParam("Tags.member.1.Value", "test")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateTrustStoreResponse.CreateTrustStoreResult.TrustStores.member.Name",
                        equalTo("alchemy-mtls"))
                .body("CreateTrustStoreResponse.CreateTrustStoreResult.TrustStores.member.Status",
                        equalTo("ACTIVE"))
                .body("CreateTrustStoreResponse.CreateTrustStoreResult.TrustStores.member.NumberOfCaCertificates",
                        equalTo("1"))
                .extract()
                .path("CreateTrustStoreResponse.CreateTrustStoreResult.TrustStores.member.TrustStoreArn");
    }

    @Test
    @Order(3)
    void describeTrustStoresByName() {
        given()
                .formParam("Action", "DescribeTrustStores")
                .formParam("Names.member.1", "alchemy-mtls")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTrustStoresResponse.DescribeTrustStoresResult.TrustStores.member.TrustStoreArn",
                        equalTo(trustStoreArn))
                .body("DescribeTrustStoresResponse.DescribeTrustStoresResult.TrustStores.member.Status",
                        equalTo("ACTIVE"));
    }

    @Test
    @Order(4)
    void getTrustStoreCaCertificatesBundleReturnsHttpsLocation() {
        given()
                .formParam("Action", "GetTrustStoreCaCertificatesBundle")
                .formParam("TrustStoreArn", trustStoreArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("GetTrustStoreCaCertificatesBundleResponse.GetTrustStoreCaCertificatesBundleResult.Location",
                        startsWith("https://"));
    }

    @Test
    @Order(5)
    void getTrustStoreRevocationContentMissingIdIsRevocationIdNotFound() {
        given()
                .formParam("Action", "GetTrustStoreRevocationContent")
                .formParam("TrustStoreArn", trustStoreArn)
                .formParam("RevocationId", "424242")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("RevocationIdNotFound"));
    }

    @Test
    @Order(6)
    void modifyCapacityReservationResetIsAccepted() {
        loadBalancerArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "capacity-reset-lb")
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

        given()
                .formParam("Action", "ModifyCapacityReservation")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("ResetCapacityReservation", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(7)
    void deleteTrustStoreThenDescribeIsNotFound() {
        given()
                .formParam("Action", "DeleteTrustStore")
                .formParam("TrustStoreArn", trustStoreArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeTrustStores")
                .formParam("TrustStoreArns.member.1", trustStoreArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("TrustStoreNotFound"));
    }

    @Test
    @Order(8)
    void createListenerStoresAuthenticateOidcAction() {
        String listenerArn = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Protocol", "HTTPS")
                .formParam("Port", "443")
                .formParam("DefaultActions.member.1.Type", "authenticate-oidc")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.Issuer",
                        "https://idp.elbv2-test.alchemy.internal")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.AuthorizationEndpoint",
                        "https://idp.elbv2-test.alchemy.internal/authorize")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.TokenEndpoint",
                        "https://idp.elbv2-test.alchemy.internal/token")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.UserInfoEndpoint",
                        "https://idp.elbv2-test.alchemy.internal/userinfo")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.ClientId",
                        "alchemy-test-client")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.ClientSecret",
                        "alchemy-test-client-secret")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.SessionTimeout",
                        "604800")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.OnUnauthenticatedRequest",
                        "deny")
                .formParam("DefaultActions.member.2.Type", "fixed-response")
                .formParam("DefaultActions.member.2.FixedResponseConfig.StatusCode", "200")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");

        given()
                .formParam("Action", "DescribeListeners")
                .formParam("ListenerArns.member.1", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.DefaultActions.member[0].Type",
                        equalTo("authenticate-oidc"))
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.DefaultActions.member[0].AuthenticateOidcConfig.ClientId",
                        equalTo("alchemy-test-client"))
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.DefaultActions.member[0].AuthenticateOidcConfig.SessionTimeout",
                        equalTo("604800"))
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.DefaultActions.member[0].AuthenticateOidcConfig.OnUnauthenticatedRequest",
                        equalTo("deny"));
    }
}
