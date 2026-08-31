package io.github.hectorvent.floci.services.mailmanager;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.0 coverage for the Alchemy Mail Manager bindings suite: address-list
 * member roundtrip and empty-archive search completion.
 */
@QuarkusTest
class MailManagerBindingsIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listArchives_empty_returnsArchivesArray() {
        mail("ListArchives", "{}")
                .then()
                .statusCode(200)
                .body("Archives", notNullValue());
    }

    @Test
    void addressListMemberRoundtrip_registerGetListDeregister() {
        String listId = mail("CreateAddressList",
                "{\"AddressListName\":\"floci-mm-list-members\",\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"mailmanager-bindings\"}]}")
                .then()
                .statusCode(200)
                .body("AddressListId", startsWith("al-"))
                .extract().path("AddressListId");

        mail("RegisterMemberToAddressList",
                "{\"AddressListId\":\"" + listId + "\",\"Address\":\"blocked@example.com\"}")
                .then()
                .statusCode(200);

        mail("GetMemberOfAddressList",
                "{\"AddressListId\":\"" + listId + "\",\"Address\":\"blocked@example.com\"}")
                .then()
                .statusCode(200)
                .body("Address", equalTo("blocked@example.com"))
                .body("CreatedTimestamp", notNullValue());

        mail("ListMembersOfAddressList", "{\"AddressListId\":\"" + listId + "\"}")
                .then()
                .statusCode(200)
                .body("Addresses", hasSize(1));

        mail("DeregisterMemberFromAddressList",
                "{\"AddressListId\":\"" + listId + "\",\"Address\":\"blocked@example.com\"}")
                .then()
                .statusCode(200);

        mail("GetMemberOfAddressList",
                "{\"AddressListId\":\"" + listId + "\",\"Address\":\"blocked@example.com\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        mail("ListAddressListImportJobs", "{\"AddressListId\":\"" + listId + "\"}")
                .then()
                .statusCode(200)
                .body("ImportJobs", hasSize(0));
    }

    @Test
    void archiveSearch_completesImmediatelyWithZeroRows() {
        String archiveId = mail("CreateArchive",
                "{\"ArchiveName\":\"floci-mm-archive-search\",\"Retention\":{\"RetentionPeriod\":\"THREE_MONTHS\"}}")
                .then()
                .statusCode(200)
                .body("ArchiveId", startsWith("a-"))
                .extract().path("ArchiveId");

        mail("GetArchive", "{\"ArchiveId\":\"" + archiveId + "\"}")
                .then()
                .statusCode(200)
                .body("ArchiveState", equalTo("ACTIVE"))
                .body("Retention.RetentionPeriod", equalTo("THREE_MONTHS"))
                .body("ArchiveArn", notNullValue());

        long now = System.currentTimeMillis() / 1000;
        String searchId = mail("StartArchiveSearch",
                "{\"ArchiveId\":\"" + archiveId + "\",\"FromTimestamp\":" + (now - 3600)
                        + ",\"ToTimestamp\":" + now + ",\"MaxResults\":5}")
                .then()
                .statusCode(200)
                .body("SearchId", notNullValue())
                .extract().path("SearchId");

        mail("GetArchiveSearch", "{\"SearchId\":\"" + searchId + "\"}")
                .then()
                .statusCode(200)
                .body("Status.State", equalTo("COMPLETED"));

        mail("GetArchiveSearchResults", "{\"SearchId\":\"" + searchId + "\"}")
                .then()
                .statusCode(200)
                .body("Rows", hasSize(0));

        mail("ListArchiveSearches", "{\"ArchiveId\":\"" + archiveId + "\"}")
                .then()
                .statusCode(200)
                .body("Searches", hasSize(1))
                .body("Searches[0].SearchId", equalTo(searchId));

        mail("ListArchiveExports", "{\"ArchiveId\":\"" + archiveId + "\"}")
                .then()
                .statusCode(200)
                .body("Exports", hasSize(0));
    }

    private static Response mail(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "MailManagerSvc." + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
