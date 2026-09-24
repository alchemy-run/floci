package io.github.hectorvent.floci.services.account;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class AccountInformationIntegrationTest {
    private static final String OWNER = "723000000001";
    private static final String OTHER = "723000000002";
    private static final List<String> OPERATIONS = List.of("getAccountInformation", "putAccountName",
            "getContactInformation", "putContactInformation", "listRegions", "getRegionOptStatus",
            "enableRegion", "disableRegion");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void accountMetadataAndPrimaryContactsAreGlobalWithinTheResolvedAccount() {
        Map<String, Object> original = post(OWNER, "us-east-1", "getAccountInformation", "{}")
                .statusCode(200).body("AccountId", equalTo(OWNER)).body("AccountState", equalTo("ACTIVE"))
                .extract().jsonPath().getMap("");
        assertNotNull(Instant.parse((String) original.get("AccountCreatedDate")));
        try {
            post(OWNER, "us-east-1", "putAccountName", "{\"AccountName\":\"HTTP owner\"}").statusCode(200);
            post(OWNER, "us-west-2", "getAccountInformation", "{}").statusCode(200)
                    .body("AccountName", equalTo("HTTP owner"))
                    .body("AccountCreatedDate", equalTo(original.get("AccountCreatedDate")));
            post(OTHER, "us-east-1", "getAccountInformation", "{}").statusCode(200)
                    .body("AccountName", equalTo(OTHER));
            post(OWNER, "us-east-1", "putContactInformation", """
                    {"ContactInformation":{"FullName":"HTTP owner","AddressLine1":"1 Main Street",
                    "City":"Paris","CountryCode":"FR","PostalCode":"75001","PhoneNumber":"+33155550123",
                    "CompanyName":"HTTP company"}}
                    """).statusCode(200);
            post(OWNER, "us-west-2", "getContactInformation", "{}").statusCode(200)
                    .body("ContactInformation.FullName", equalTo("HTTP owner"))
                    .body("ContactInformation.CompanyName", equalTo("HTTP company"));
            post(OWNER, "us-east-1", "putContactInformation", """
                    {"ContactInformation":{"FullName":"HTTP replacement","AddressLine1":"2 Main Street",
                    "City":"Paris","CountryCode":"FR","PostalCode":"75001","PhoneNumber":"+33155550123"}}
                    """).statusCode(200);
            post(OWNER, "us-east-1", "getContactInformation", "{}").statusCode(200)
                    .body("ContactInformation.FullName", equalTo("HTTP replacement"))
                    .body("ContactInformation", not(hasKey("CompanyName")));
            post(OTHER, "us-east-1", "getContactInformation", "{}").statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            given().contentType("application/json").header("Authorization", auth(OWNER, "us-east-1"))
                    .body(Map.of("AccountName", original.get("AccountName")))
                    .post("/putAccountName").then().statusCode(200);
        }
    }

    @Test
    void everyNewOperationRejectsMalformedDocumentsAndUnauthorizedAccountSelection() {
        for (String operation : OPERATIONS) {
            for (String body : List.of("[]", "null", "{} {}", "{", "42")) {
                post(OWNER, "us-east-1", operation, body).statusCode(400)
                        .body("__type", equalTo("SerializationException"));
            }
            post(OWNER, "us-east-1", operation, "{\"AccountId\":723000000002}").statusCode(400)
                    .body("__type", equalTo("SerializationException"));
            post(OWNER, "us-east-1", operation, "{\"AccountId\":\"invalid\"}").statusCode(400)
                    .body("__type", equalTo("ValidationException"));
            post(OWNER, "us-east-1", operation, "{\"AccountId\":\"" + OTHER + "\"}").statusCode(403)
                    .body("__type", equalTo("AccessDeniedException"));
        }
    }

    @Test
    void regionCatalogRejectsUnsupportedActivationAndMalformedPagination() {
        post(OWNER, "us-east-1", "getRegionOptStatus", "{\"RegionName\":\"us-east-1\"}")
                .statusCode(200).body("RegionOptStatus", equalTo("ENABLED_BY_DEFAULT"));
        post(OWNER, "us-east-1", "listRegions", "{\"MaxResults\":1}").statusCode(200)
                .body("Regions.size()", equalTo(1)).body("NextToken", not(emptyOrNullString()));
        for (String body : List.of("{\"MaxResults\":\"2\"}", "{\"MaxResults\":1.5}",
                "{\"MaxResults\":2147483648}", "{\"NextToken\":42}",
                "{\"RegionOptStatusContains\":\"ENABLED_BY_DEFAULT\"}", "{\"RegionOptStatusContains\":[42]}")) {
            post(OWNER, "us-east-1", "listRegions", body).statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        }
        post(OWNER, "us-east-1", "enableRegion", "{\"RegionName\":\"ap-east-1\"}")
                .statusCode(400).body("__type", equalTo("ValidationException"))
                .body("message", containsString("activation is not supported"));
        post(OWNER, "us-east-1", "disableRegion", "{\"RegionName\":\"us-east-1\"}")
                .statusCode(400).body("__type", equalTo("ValidationException"));
    }

    private static ValidatableResponse post(String account, String region, String operation, String body) {
        return given().contentType("application/json").header("Authorization", auth(account, region))
                .body(body).post("/" + operation).then();
    }

    private static String auth(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/" + region + "/account/aws4_request";
    }
}
