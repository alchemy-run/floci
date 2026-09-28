package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers CreateUserPoolDomain/DescribeUserPoolDomain/UpdateUserPoolDomain/DeleteUserPoolDomain (lex00/floci#63)
 * for both an Amazon Cognito prefix domain and a custom domain fronted by an ACM certificate, including
 * the certificate's InUseBy bookkeeping in ACM.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoUserPoolDomainIntegrationTest {

    private static String poolId;
    private static String prefixDomain;
    private static String customDomain;
    private static String CERTIFICATE_ARN;
    private static String RENEWED_CERTIFICATE_ARN;
    private static final String AWS_CERTIFICATE_MESSAGE = "The specified SSL certificate doesn't exist, "
            + "isn't in us-east-1 region, isn't valid, or doesn't include a valid certificate chain.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createPool() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "DomainTestPool"
                }
                """);
        poolId = poolResponse.path("UserPool").path("Id").asText();
        prefixDomain = "floci-test-" + UUID.randomUUID().toString().substring(0, 8);
        customDomain = "auth-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        CERTIFICATE_ARN = requestCertificate(customDomain);
        RENEWED_CERTIFICATE_ARN = requestCertificate(customDomain);
    }

    private static String requestCertificate(String domainName) throws Exception {
        String arn = RestAssuredJsonUtils.awsActionJson("CertificateManager", "RequestCertificate", """
                {
                  "DomainName": "%s",
                  "ValidationMethod": "DNS"
                }
                """.formatted(domainName)).path("CertificateArn").asText();
        JsonNode certificate = RestAssuredJsonUtils.awsActionJson("CertificateManager", "DescribeCertificate",
                "{\"CertificateArn\": \"" + arn + "\"}").path("Certificate");
        assertEquals("PENDING_VALIDATION", certificate.path("Status").asText());
        assertEquals(1, certificate.path("DomainValidationOptions").size());
        JsonNode record = certificate.path("DomainValidationOptions").get(0).path("ResourceRecord");
        String zoneId = given().contentType("application/xml").body("""
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name><CallerReference>%s</CallerReference>
                  <HostedZoneConfig><PrivateZone>false</PrivateZone></HostedZoneConfig>
                </CreateHostedZoneRequest>
                """.formatted(domainName, arn))
                .when().post("/2013-04-01/hostedzone").then().statusCode(201)
                .body("CreateHostedZoneResponse.HostedZone.Config.PrivateZone", equalTo("false"))
                .extract().xmlPath().getString("CreateHostedZoneResponse.HostedZone.Id")
                .replace("/hostedzone/", "");
        try {
            changeValidationRecord(zoneId, record, "UPSERT");
            try {
                acm("DescribeCertificate", arn).then().statusCode(200)
                        .body("Certificate.Status", equalTo("ISSUED"))
                        .body("Certificate.DomainValidationOptions[0].ValidationStatus", equalTo("SUCCESS"));
                return arn;
            } finally {
                changeValidationRecord(zoneId, record, "DELETE");
            }
        } finally {
            given().when().delete("/2013-04-01/hostedzone/" + zoneId).then().statusCode(200);
        }
    }

    private static void changeValidationRecord(String zoneId, JsonNode record, String action) {
        assertEquals("CNAME", record.path("Type").asText());
        given().contentType("application/xml").body("""
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch><Changes><Change><Action>%s</Action><ResourceRecordSet>
                    <Name>%s</Name><Type>CNAME</Type><TTL>60</TTL>
                    <ResourceRecords><ResourceRecord><Value>%s</Value></ResourceRecord></ResourceRecords>
                  </ResourceRecordSet></Change></Changes></ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """.formatted(action, record.path("Name").asText(), record.path("Value").asText()))
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset").then().statusCode(200);
    }

    private static io.restassured.response.Response acm(String action, String certificateArn) {
        return RestAssuredJsonUtils.awsAction("CertificateManager", action, """
                {
                  "CertificateArn": "%s"
                }
                """.formatted(certificateArn));
    }

    @Test
    @Order(2)
    void createPrefixDomainSucceeds() throws Exception {
        JsonNode response = cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(prefixDomain, poolId));

        // AWS returns CloudFrontDomain=null for a prefix domain; floci should not
        // fabricate one, so the field is simply absent from the response.
        assertNull(response.get("CloudFrontDomain"));
    }

    @Test
    @Order(3)
    void describePrefixDomainReturnsMatchingFields() throws Exception {
        JsonNode response = cognitoJson("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(prefixDomain));

        JsonNode description = response.path("DomainDescription");
        assertEquals(prefixDomain, description.path("Domain").asText());
        assertEquals(poolId, description.path("UserPoolId").asText());
        assertEquals("ACTIVE", description.path("Status").asText());
        assertTrue(description.path("CloudFrontDistribution").asText().endsWith(".cloudfront.net"));
        assertNull(description.get("CustomDomainConfig"));
    }

    @Test
    void prefixDistributionSurvivesUpdatesAndIsRemovedWithTheDomain() throws Exception {
        String domain = "prefix-lifecycle-" + UUID.randomUUID().toString().substring(0, 8);
        String owner = cognitoJson("CreateUserPool", """
                {"PoolName": "prefix lifecycle"}
                """).path("UserPool").path("Id").asText();
        try {
            JsonNode created = cognitoJson("CreateUserPoolDomain", """
                    {"Domain": "%s", "UserPoolId": "%s", "ManagedLoginVersion": 1}
                    """.formatted(domain, owner));
            assertNull(created.get("CloudFrontDomain"));
            try {
                JsonNode original = cognitoJson("DescribeUserPoolDomain", """
                        {"Domain": "%s"}
                        """.formatted(domain)).path("DomainDescription");
                String distribution = original.path("CloudFrontDistribution").asText();
                assertTrue(distribution.endsWith(".cloudfront.net"));
                assertEquals("000000000000", original.path("AWSAccountId").asText());
                assertFalse(original.path("S3Bucket").asText().isBlank());
                assertFalse(original.path("Version").asText().isBlank());

                JsonNode updated = cognitoJson("UpdateUserPoolDomain", """
                        {"Domain": "%s", "UserPoolId": "%s", "ManagedLoginVersion": 2}
                        """.formatted(domain, owner));
                assertNull(updated.get("CloudFrontDomain"));
                for (int attempt = 0; attempt < 2; attempt++) {
                    JsonNode observed = cognitoJson("DescribeUserPoolDomain", """
                            {"Domain": "%s"}
                            """.formatted(domain)).path("DomainDescription");
                    assertEquals(distribution, observed.path("CloudFrontDistribution").asText());
                    assertEquals(domain, observed.path("Domain").asText());
                    assertEquals(owner, observed.path("UserPoolId").asText());
                    assertEquals("ACTIVE", observed.path("Status").asText());
                    assertEquals(2, observed.path("ManagedLoginVersion").asInt());
                    assertNull(observed.get("CustomDomainConfig"));
                }
            } finally {
                cognitoAction("DeleteUserPoolDomain", """
                        {"Domain": "%s", "UserPoolId": "%s"}
                        """.formatted(domain, owner)).then().statusCode(200);
            }
            cognitoAction("DescribeUserPoolDomain", """
                    {"Domain": "%s"}
                    """.formatted(domain)).then().statusCode(200)
                    .body("DomainDescription.size()", equalTo(0));
        } finally {
            cognitoAction("DeleteUserPool", """
                    {"UserPoolId": "%s"}
                    """.formatted(owner)).then().statusCode(200);
        }
    }

    @Test
    @Order(4)
    void deletePrefixDomainSucceeds() {
        cognitoAction("DeleteUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(prefixDomain, poolId))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(5)
    void describeDeletedPrefixDomainReturnsEmptyDescription() {
        cognitoAction("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(prefixDomain))
                .then()
                .statusCode(200)
                .body("DomainDescription.size()", equalTo(0));
    }

    @Test
    @Order(6)
    void createCustomDomainReturnsCloudFrontDomain() throws Exception {
        JsonNode response = cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {
                    "CertificateArn": "%s"
                  }
                }
                """.formatted(customDomain, poolId, CERTIFICATE_ARN));

        org.junit.jupiter.api.Assertions.assertTrue(response.path("CloudFrontDomain").isTextual());
        org.junit.jupiter.api.Assertions.assertFalse(response.path("CloudFrontDomain").asText().isBlank());
    }

    @Test
    @Order(7)
    void describeCustomDomainReturnsCertificateArn() throws Exception {
        JsonNode response = cognitoJson("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(customDomain));

        JsonNode description = response.path("DomainDescription");
        assertEquals(customDomain, description.path("Domain").asText());
        assertEquals(poolId, description.path("UserPoolId").asText());
        assertEquals(CERTIFICATE_ARN, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals("ACTIVE", description.path("Status").asText());
        org.junit.jupiter.api.Assertions.assertNotNull(description.path("CloudFrontDistribution").asText(null));
    }

    @Test
    @Order(8)
    void certificateInUseByTheDomainCannotBeDeleted() {
        acm("DescribeCertificate", CERTIFICATE_ARN)
                .then()
                .statusCode(200)
                .body("Certificate.InUseBy.size()", equalTo(1))
                .body("Certificate.InUseBy[0]", startsWith("arn:aws:cloudfront::"));

        acm("DeleteCertificate", CERTIFICATE_ARN)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    @Order(9)
    void updateCustomDomainReplacesTheCertificateAndKeepsTheCloudFrontDistribution() throws Exception {
        String cloudFront = cognitoJson("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(customDomain)).path("DomainDescription").path("CloudFrontDistribution").asText();

        JsonNode response = cognitoJson("UpdateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {
                    "CertificateArn": "%s"
                  },
                  "ManagedLoginVersion": 2
                }
                """.formatted(customDomain, poolId, RENEWED_CERTIFICATE_ARN));

        // As on AWS, the distribution survives a certificate change, so a DNS alias stays valid.
        assertEquals(cloudFront, response.path("CloudFrontDomain").asText());
        assertEquals(2, response.path("ManagedLoginVersion").asInt());

        JsonNode description = cognitoJson("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(customDomain)).path("DomainDescription");
        assertEquals(RENEWED_CERTIFICATE_ARN, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals(cloudFront, description.path("CloudFrontDistribution").asText());
        assertEquals(2, description.path("ManagedLoginVersion").asInt());
    }

    @Test
    @Order(10)
    void updateMovesTheRegistrationToTheRenewedCertificate() {
        acm("DescribeCertificate", CERTIFICATE_ARN)
                .then()
                .statusCode(200)
                .body("Certificate.InUseBy.size()", equalTo(0));
        acm("DescribeCertificate", RENEWED_CERTIFICATE_ARN)
                .then()
                .statusCode(200)
                .body("Certificate.InUseBy.size()", equalTo(1));
    }

    @Test
    @Order(11)
    void updateDomainOfAnotherPoolIsNotFound() {
        cognitoAction("UpdateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "us-east-1_missing",
                  "CustomDomainConfig": {
                    "CertificateArn": "%s"
                  }
                }
                """.formatted(customDomain, CERTIFICATE_ARN))
                .then()
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(12)
    void createCustomDomainWithoutCertificateArnFails() {
        cognitoAction("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {}
                }
                """.formatted("bad-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com", poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(13)
    void createCustomDomainWithUnknownCertificateFails() {
        cognitoAction("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {
                    "CertificateArn": "arn:aws:acm:us-east-1:000000000000:certificate/%s"
                  }
                }
                """.formatted("unknown-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com", poolId, UUID.randomUUID()))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(AWS_CERTIFICATE_MESSAGE));
    }

    @Test
    @Order(14)
    void deleteCustomDomainThenDescribeReturnsEmptyDescription() {
        cognitoAction("DeleteUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(customDomain, poolId))
                .then()
                .statusCode(200);

        cognitoAction("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(customDomain))
                .then()
                .statusCode(200)
                .body("DomainDescription.size()", equalTo(0));
    }

    @Test
    @Order(15)
    void deletingTheDomainReleasesTheCertificate() {
        acm("DescribeCertificate", RENEWED_CERTIFICATE_ARN)
                .then()
                .statusCode(200)
                .body("Certificate.InUseBy.size()", equalTo(0));

        acm("DeleteCertificate", RENEWED_CERTIFICATE_ARN)
                .then()
                .statusCode(200);
    }

    @Test
    @Order(16)
    void deleteUserPoolWithDomainIsRejected() throws Exception {
        // The DeleteUserPool API reference's own example documents this refusal verbatim.
        String blockingDomain = "floci-block-" + UUID.randomUUID().toString().substring(0, 8);
        cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(blockingDomain, poolId));

        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "User pool cannot be deleted. It has a domain configured that should be deleted first."));

        cognitoAction("DeleteUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(blockingDomain, poolId))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(17)
    void deleteUserPoolSucceedsOnceDomainIsGone() {
        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);
    }
}
