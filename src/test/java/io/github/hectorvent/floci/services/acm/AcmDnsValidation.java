package io.github.hectorvent.floci.services.acm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.specification.RequestSpecification;

import java.util.Locale;

import static io.github.hectorvent.floci.core.common.AwsJson11Controller.CONTENT_TYPE_AWS_JSON_1_1;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Requests a DNS-validated ACM certificate and issues it the way AWS does: the validation CNAME
 * of every domain is published in a public Route 53 hosted zone of the requesting account. The
 * zone and records are removed once the certificate is ISSUED, which ACM keeps.
 */
public final class AcmDnsValidation {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AcmDnsValidation() {
    }

    /** An ISSUED certificate for {@code domainName} in the default account. */
    public static String requestIssuedCertificate(String domainName) throws Exception {
        return requestIssuedCertificateAs(null, domainName);
    }

    /** An ISSUED certificate for {@code domainName} in {@code accountId}, or the default account when null. */
    public static String requestIssuedCertificateAs(String accountId, String domainName) throws Exception {
        String arn = acm(accountId, "RequestCertificate",
                "{\"DomainName\": \"" + domainName + "\", \"ValidationMethod\": \"DNS\"}")
                .path("CertificateArn").asText();
        validate(accountId, arn);
        return arn;
    }

    /** Publishes the validation records of a PENDING_VALIDATION certificate until ACM issues it. */
    public static void validate(String accountId, String certificateArn) throws Exception {
        JsonNode certificate = describe(accountId, certificateArn);
        assertEquals("PENDING_VALIDATION", certificate.path("Status").asText(), certificate.toString());
        for (JsonNode option : certificate.path("DomainValidationOptions")) {
            JsonNode record = option.path("ResourceRecord");
            assertEquals("CNAME", record.path("Type").asText(), option.toString());
            String zoneName = option.path("DomainName").asText().toLowerCase(Locale.ROOT);
            if (zoneName.startsWith("*.")) {
                zoneName = zoneName.substring(2);
            }
            String zoneId = createZone(accountId, zoneName, certificateArn + "/" + zoneName);
            try {
                changeRecord(accountId, zoneId, record, "UPSERT");
                try {
                    describe(accountId, certificateArn);
                } finally {
                    changeRecord(accountId, zoneId, record, "DELETE");
                }
            } finally {
                route53(accountId).when().delete("/2013-04-01/hostedzone/" + zoneId).then().statusCode(200);
            }
        }
        JsonNode issued = describe(accountId, certificateArn);
        assertEquals("ISSUED", issued.path("Status").asText(), issued.toString());
    }

    private static JsonNode describe(String accountId, String certificateArn) throws Exception {
        return acm(accountId, "DescribeCertificate", "{\"CertificateArn\": \"" + certificateArn + "\"}")
                .path("Certificate");
    }

    private static JsonNode acm(String accountId, String action, String body) throws Exception {
        String response = signedFor(accountId, "acm")
                .header("X-Amz-Target", "CertificateManager." + action)
                .contentType(CONTENT_TYPE_AWS_JSON_1_1)
                .body(body)
                .when().post("/")
                .then().statusCode(200).extract().asString();
        return OBJECT_MAPPER.readTree(response);
    }

    private static String createZone(String accountId, String zoneName, String callerReference) {
        return route53(accountId).contentType("application/xml").body("""
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name><CallerReference>%s</CallerReference>
                  <HostedZoneConfig><PrivateZone>false</PrivateZone></HostedZoneConfig>
                </CreateHostedZoneRequest>
                """.formatted(zoneName, callerReference))
                .when().post("/2013-04-01/hostedzone").then().statusCode(201)
                .body("CreateHostedZoneResponse.HostedZone.Config.PrivateZone", equalTo("false"))
                .extract().xmlPath().getString("CreateHostedZoneResponse.HostedZone.Id")
                .replace("/hostedzone/", "");
    }

    private static void changeRecord(String accountId, String zoneId, JsonNode record, String action) {
        route53(accountId).contentType("application/xml").body("""
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch><Changes><Change><Action>%s</Action><ResourceRecordSet>
                    <Name>%s</Name><Type>CNAME</Type><TTL>60</TTL>
                    <ResourceRecords><ResourceRecord><Value>%s</Value></ResourceRecord></ResourceRecords>
                  </ResourceRecordSet></Change></Changes></ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """.formatted(action, record.path("Name").asText(), record.path("Value").asText()))
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset").then().statusCode(200);
    }

    private static RequestSpecification route53(String accountId) {
        return signedFor(accountId, "route53");
    }

    /** The account comes from the credential scope; without one the request runs in the default account. */
    private static RequestSpecification signedFor(String accountId, String service) {
        RequestSpecification request = given();
        if (accountId != null) {
            request.header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accountId
                    + "/20260905/us-east-1/" + service + "/aws4_request");
        }
        return request;
    }
}
