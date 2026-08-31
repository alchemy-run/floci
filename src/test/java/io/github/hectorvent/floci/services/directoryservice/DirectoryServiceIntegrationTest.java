package io.github.hectorvent.floci.services.directoryservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class DirectoryServiceIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ds/aws4_request";
    private static final String TARGET = "DirectoryService_20150416.";
    private static final String MISSING = "d-1234567890";
    private static final String VPC = """
            "VpcSettings":{"VpcId":"vpc-aaaa1111","SubnetIds":["subnet-aaaa1111","subnet-bbbb2222"]}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDirectoryLimits_returnsCloudOnlyCounters() {
        invoke("GetDirectoryLimits", "{}")
                .then()
                .statusCode(200)
                .body("DirectoryLimits.CloudOnlyDirectoriesLimit", greaterThan(0))
                .body("DirectoryLimits.CloudOnlyDirectoriesCurrentCount", greaterThanOrEqualTo(0));
    }

    @Test
    void describeDirectories_returnsDirectoryList() {
        invoke("DescribeDirectories", "{}")
                .then()
                .statusCode(200)
                .body("DirectoryDescriptions.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void describeDirectories_unknownId_entityDoesNotExist() {
        invoke("DescribeDirectories", "{\"DirectoryIds\":[\"" + MISSING + "\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityDoesNotExistException"));
    }

    @Test
    void describeConditionalForwarders_unknownDirectory_entityDoesNotExist() {
        invoke("DescribeConditionalForwarders",
                "{\"DirectoryId\":\"" + MISSING + "\",\"RemoteDomainNames\":[\"partner.alchemy-test.internal\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityDoesNotExistException"));
    }

    @Test
    void describeEventTopics_unknownDirectory_entityDoesNotExist() {
        invoke("DescribeEventTopics", "{\"DirectoryId\":\"" + MISSING + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityDoesNotExistException"));
    }

    @Test
    void registerEventTopic_unknownDirectory_entityDoesNotExist() {
        invoke("RegisterEventTopic",
                "{\"DirectoryId\":\"" + MISSING + "\",\"TopicName\":\"alchemy-directory-service-missing\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityDoesNotExistException"));
    }

    @Test
    void createDescribeTagEventTopicAndDeleteSimpleAd() {
        Response created = invoke("CreateDirectory", """
                {
                  "Name": "corp.alchemy-test.internal",
                  "Password": "AlchemyTest123!",
                  "Size": "Small",
                  "Description": "alchemy directory-service test",
                  "Tags": [{"Key": "fixture", "Value": "directory-service"}],
                  %s
                }
                """.formatted(VPC));
        created.then().statusCode(200).body("DirectoryId", startsWith("d-"));
        String directoryId = created.jsonPath().getString("DirectoryId");

        invoke("DescribeDirectories", "{\"DirectoryIds\":[\"" + directoryId + "\"]}")
                .then()
                .statusCode(200)
                .body("DirectoryDescriptions", hasSize(1))
                .body("DirectoryDescriptions[0].DirectoryId", equalTo(directoryId))
                .body("DirectoryDescriptions[0].Name", equalTo("corp.alchemy-test.internal"))
                .body("DirectoryDescriptions[0].Type", equalTo("SimpleAD"))
                .body("DirectoryDescriptions[0].Stage", equalTo("Active"))
                .body("DirectoryDescriptions[0].Size", equalTo("Small"))
                .body("DirectoryDescriptions[0].DnsIpAddrs", hasSize(2));

        invoke("ListTagsForResource", "{\"ResourceId\":\"" + directoryId + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'fixture' }.Value", equalTo("directory-service"));

        invoke("AddTagsToResource",
                "{\"ResourceId\":\"" + directoryId + "\",\"Tags\":[{\"Key\":\"wave\",\"Value\":\"2\"}]}")
                .then()
                .statusCode(200);
        invoke("ListTagsForResource", "{\"ResourceId\":\"" + directoryId + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'wave' }.Value", equalTo("2"));

        invoke("RegisterEventTopic",
                "{\"DirectoryId\":\"" + directoryId + "\",\"TopicName\":\"directory-status\"}")
                .then()
                .statusCode(200);
        invoke("DescribeEventTopics", "{\"DirectoryId\":\"" + directoryId + "\"}")
                .then()
                .statusCode(200)
                .body("EventTopics.TopicName", hasItem("directory-status"))
                .body("EventTopics[0].Status", equalTo("Registered"));

        invoke("DeleteDirectory", "{\"DirectoryId\":\"" + directoryId + "\"}")
                .then()
                .statusCode(200)
                .body("DirectoryId", equalTo(directoryId));
        invoke("DescribeDirectories", "{\"DirectoryIds\":[\"" + directoryId + "\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityDoesNotExistException"));
    }

    @Test
    void microsoftAdConditionalForwarderRoundTrip() {
        Response created = invoke("CreateMicrosoftAD", """
                {
                  "Name": "msad.alchemy-test.internal",
                  "Password": "AlchemyTest123!",
                  "Edition": "Standard",
                  %s
                }
                """.formatted(VPC));
        created.then().statusCode(200).body("DirectoryId", startsWith("d-"));
        String directoryId = created.jsonPath().getString("DirectoryId");

        invoke("DescribeDirectories", "{\"DirectoryIds\":[\"" + directoryId + "\"]}")
                .then()
                .statusCode(200)
                .body("DirectoryDescriptions[0].Type", equalTo("MicrosoftAD"))
                .body("DirectoryDescriptions[0].Stage", equalTo("Active"))
                .body("DirectoryDescriptions[0].Edition", equalTo("Standard"));

        invoke("CreateConditionalForwarder", """
                {
                  "DirectoryId": "%s",
                  "RemoteDomainName": "partner.alchemy-test.internal",
                  "DnsIpAddrs": ["10.200.0.2"]
                }
                """.formatted(directoryId))
                .then()
                .statusCode(200);

        invoke("DescribeConditionalForwarders", """
                {
                  "DirectoryId": "%s",
                  "RemoteDomainNames": ["partner.alchemy-test.internal"]
                }
                """.formatted(directoryId))
                .then()
                .statusCode(200)
                .body("ConditionalForwarders[0].RemoteDomainName", equalTo("partner.alchemy-test.internal"))
                .body("ConditionalForwarders[0].DnsIpAddrs", equalTo(java.util.List.of("10.200.0.2")));

        invoke("UpdateConditionalForwarder", """
                {
                  "DirectoryId": "%s",
                  "RemoteDomainName": "partner.alchemy-test.internal",
                  "DnsIpAddrs": ["10.200.0.2", "10.200.1.2"]
                }
                """.formatted(directoryId))
                .then()
                .statusCode(200);

        invoke("DescribeConditionalForwarders", """
                {
                  "DirectoryId": "%s",
                  "RemoteDomainNames": ["partner.alchemy-test.internal"]
                }
                """.formatted(directoryId))
                .then()
                .statusCode(200)
                .body("ConditionalForwarders[0].DnsIpAddrs", hasSize(2));

        invoke("DeleteConditionalForwarder", """
                {
                  "DirectoryId": "%s",
                  "RemoteDomainName": "partner.alchemy-test.internal"
                }
                """.formatted(directoryId))
                .then()
                .statusCode(200);

        invoke("DeleteDirectory", "{\"DirectoryId\":\"" + directoryId + "\"}")
                .then()
                .statusCode(200);
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}
