package io.floci.aws.services.route53domains;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Route 53 Domains, with Floci as the registrar. */
@QuarkusTest
class Route53DomainsIntegrationTest {
    private static final String CONTACT = """
            {"FirstName":"Ada","LastName":"Lovelace","ContactType":"PERSON","AddressLine1":"1 Main St",
             "City":"Seattle","State":"WA","CountryCode":"US","ZipCode":"98101","PhoneNumber":"+1.2065550100",
             "Email":"ada@example.org"}""";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return call("000000000000", action, body);
    }

    private static ValidatableResponse call(String account, String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20260101/us-east-1/route53domains/aws4_request")
                .header("X-Amz-Target", "Route53Domains_v20140515." + action).body(body)
                .when().post("/").then();
    }

    private static String register(String domain) {
        return call("RegisterDomain", "{\"DomainName\":\"" + domain + "\",\"DurationInYears\":1,\"AdminContact\":"
                + CONTACT + ",\"RegistrantContact\":" + CONTACT + ",\"TechContact\":" + CONTACT + "}")
                .statusCode(200).extract().path("OperationId");
    }

    @Test
    void aRegisteredDomainIsDelegatedToAHostedZoneAndCanBeChanged() {
        String domain = "floci-" + UUID.randomUUID().toString().substring(0, 8) + ".com";
        call("CheckDomainAvailability", "{\"DomainName\":\"" + domain + "\"}").statusCode(200)
                .body("Availability", equalTo("AVAILABLE")).body("Price.Currency", equalTo("USD"));
        String operation = register(domain);
        call("CheckDomainAvailability", "{\"DomainName\":\"" + domain + "\"}").statusCode(200)
                .body("Availability", equalTo("UNAVAILABLE"));
        call("RegisterDomain", "{\"DomainName\":\"" + domain + "\",\"DurationInYears\":1,\"AdminContact\":" + CONTACT
                + ",\"RegistrantContact\":" + CONTACT + ",\"TechContact\":" + CONTACT + "}").statusCode(400)
                .body("__type", equalTo("DuplicateRequest"));

        call("GetOperationDetail", "{\"OperationId\":\"" + operation + "\"}").statusCode(200)
                .body("Type", equalTo("REGISTER_DOMAIN")).body("DomainName", equalTo(domain));
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250)).until(() ->
                "SUCCESSFUL".equals(call("GetOperationDetail", "{\"OperationId\":\"" + operation + "\"}")
                        .extract().path("Status")));

        String zoneServer = call("GetDomainDetail", "{\"DomainName\":\"" + domain + "\"}").statusCode(200)
                .body("RegistrantPrivacy", equalTo(true))
                .body("RegistrantContact.LastName", equalTo("Identity Protection Service"))
                .body("StatusList", hasItem("clientTransferProhibited"))
                .body("Nameservers", hasSize(4))
                .extract().path("Nameservers[0].Name");
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53/aws4_request")
                .get("/2013-04-01/hostedzonesbyname?dnsname=" + domain).then().statusCode(200)
                .body(containsString("<Name>" + domain + ".</Name>"));

        call("UpdateDomainNameservers", "{\"DomainName\":\"" + domain + "\",\"Nameservers\":[{\"Name\":"
                + "\"ns1.example.net\"},{\"Name\":\"ns2.example.net\"}]}").statusCode(200)
                .body("OperationId", notNullValue());
        call("UpdateDomainContactPrivacy", "{\"DomainName\":\"" + domain + "\",\"RegistrantPrivacy\":false}")
                .statusCode(200);
        call("DisableDomainTransferLock", "{\"DomainName\":\"" + domain + "\"}").statusCode(200);
        call("DisableDomainAutoRenew", "{\"DomainName\":\"" + domain + "\"}").statusCode(200);
        call("GetDomainDetail", "{\"DomainName\":\"" + domain + "\"}").statusCode(200)
                .body("Nameservers.Name", hasItem("ns1.example.net"))
                .body("Nameservers.Name", not(hasItem(zoneServer)))
                .body("RegistrantContact.FirstName", equalTo("Ada"))
                .body("StatusList", not(hasItem("clientTransferProhibited")))
                .body("AutoRenew", equalTo(false));

        call("UpdateTagsForDomain", "{\"DomainName\":\"" + domain + "\",\"TagsToUpdate\":[{\"Key\":\"team\","
                + "\"Value\":\"web\"}]}").statusCode(200);
        call("ListTagsForDomain", "{\"DomainName\":\"" + domain + "\"}").statusCode(200)
                .body("TagList[0].Key", equalTo("team"));
        call("AssociateDelegationSignerToDomain", "{\"DomainName\":\"" + domain + "\",\"SigningAttributes\":"
                + "{\"Algorithm\":13,\"Flags\":257,\"PublicKey\":\"mdsswUyr3DPW132mOi8V9xESWE8jTo0dxCjjnopKl+GqJxpVXckHAeF+KkxLbxILfDLUT0rAK9iUzy1L53eKGQ==\"}}")
                .statusCode(200);
        call("GetDomainDetail", "{\"DomainName\":\"" + domain + "\"}").statusCode(200)
                .body("DnsSec", equalTo("signedDelegation")).body("DnssecKeys[0].KeyTag", greaterThan(0))
                .body("DnssecKeys[0].DigestType", equalTo(2));

        int expiryYear = Instant.now().atZone(ZoneOffset.UTC).plusYears(1).getYear();
        call("RenewDomain", "{\"DomainName\":\"" + domain + "\",\"DurationInYears\":1,\"CurrentExpiryYear\":"
                + (expiryYear - 1) + "}").statusCode(400).body("__type", equalTo("InvalidInput"));
        call("RenewDomain", "{\"DomainName\":\"" + domain + "\",\"DurationInYears\":1,\"CurrentExpiryYear\":"
                + expiryYear + "}").statusCode(200);
        call("GetDomainDetail", "{\"DomainName\":\"" + domain + "\"}").statusCode(200)
                .body("ExpirationDate", startsWith(Integer.toString(expiryYear + 1)));
        call("RetrieveDomainAuthCode", "{\"DomainName\":\"" + domain + "\"}").statusCode(200)
                .body("AuthCode", notNullValue());
        call("ListDomains", "{\"FilterConditions\":[{\"Name\":\"DomainName\",\"Operator\":\"BEGINS_WITH\","
                + "\"Values\":[\"floci-\"]}]}").statusCode(200).body("Domains.DomainName", hasItem(domain));
        call("ListOperations", "{\"Type\":[\"RENEW_DOMAIN\"]}").statusCode(200)
                .body("Operations.DomainName", hasItem(domain));
        call("ViewBilling", "{}").statusCode(200).body("BillingRecords.DomainName", hasItem(domain));

        call("DeleteDomain", "{\"DomainName\":\"" + domain + "\"}").statusCode(200);
        call("GetDomainDetail", "{\"DomainName\":\"" + domain + "\"}").statusCode(400)
                .body("message", containsString("not found in account"));
    }

    @Test
    void aDomainMovesToAnotherAccountWithItsPassword() {
        String domain = "floci-move-" + UUID.randomUUID().toString().substring(0, 8) + ".net";
        register(domain);
        String password = call("TransferDomainToAnotherAwsAccount", "{\"DomainName\":\"" + domain
                + "\",\"AccountId\":\"111122223333\"}").statusCode(200).extract().path("Password");
        call("111122223333", "AcceptDomainTransferFromAnotherAwsAccount", "{\"DomainName\":\"" + domain
                + "\",\"Password\":\"wrong\"}").statusCode(400);
        call("111122223333", "AcceptDomainTransferFromAnotherAwsAccount", "{\"DomainName\":\"" + domain
                + "\",\"Password\":\"" + password + "\"}").statusCode(200);
        call("111122223333", "GetDomainDetail", "{\"DomainName\":\"" + domain + "\"}").statusCode(200);
        call("GetDomainDetail", "{\"DomainName\":\"" + domain + "\"}").statusCode(400);
        call("CheckDomainTransferability", "{\"DomainName\":\"" + domain + "\"}").statusCode(200)
                .body("Transferability.Transferable", equalTo("DOMAIN_IN_ANOTHER_ACCOUNT"));
    }

    @Test
    void namesPricesAndWellKnownDomainsAreChecked() {
        call("CheckDomainAvailability", "{\"DomainName\":\"example.com\"}").statusCode(200)
                .body("Availability", equalTo("UNAVAILABLE"));
        call("CheckDomainTransferability", "{\"DomainName\":\"example.com\"}").statusCode(200)
                .body("Transferability.Transferable", equalTo("TRANSFERABLE"));
        call("CheckDomainAvailability", "{\"DomainName\":\"probe.invalidtld99\"}").statusCode(400)
                .body("__type", equalTo("UnsupportedTLD"));
        call("CheckDomainAvailability", "{\"DomainName\":\"-bad-.com\"}").statusCode(400)
                .body("__type", equalTo("InvalidInput"));
        call("ListPrices", "{\"Tld\":\"com\"}").statusCode(200).body("Prices[0].Name", equalTo("com"))
                .body("Prices[0].RegistrationPrice.Price", greaterThan(0f));
        call("GetDomainSuggestions", "{\"DomainName\":\"floci.com\",\"SuggestionCount\":3,\"OnlyAvailable\":true}")
                .statusCode(200).body("SuggestionsList", hasSize(3));
        call("GetOperationDetail", "{\"OperationId\":\"00000000-0000-0000-0000-000000000000\"}").statusCode(400)
                .body("__type", equalTo("InvalidInput"));
        call("RegisterDomain", "{\"DomainName\":\"floci-bad-contact.com\",\"DurationInYears\":1,\"AdminContact\":"
                + "{\"FirstName\":\"A\"},\"RegistrantContact\":" + CONTACT + ",\"TechContact\":" + CONTACT + "}")
                .statusCode(400).body("message", containsString("AdminContact"));
    }
}
