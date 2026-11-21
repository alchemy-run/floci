package io.floci.aws.services.b2bi;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2B Data Interchange: the test operations answer what AWS answers for the same input (the golden files under
 * {@code src/test/resources/b2bi} are AWS's own responses), and the resources, transformer jobs and the service's
 * processing of partner files work end to end.
 */
@QuarkusTest
class B2biIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String X12_850 = "{\"x12Details\":{\"transactionSet\":\"X12_850\",\"version\":\"VERSION_4010\"}}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static ValidatableResponse call(String action, String body) {
        return given().contentType("application/x-amz-json-1.0")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/b2bi/aws4_request")
                .header("X-Amz-Target", "B2BI." + action).body(body).post("/").then();
    }

    private static String golden(String name) {
        try (InputStream in = B2biIntegrationTest.class.getClassLoader().getResourceAsStream("b2bi/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String s3(String method, String path, String body) {
        RequestSpecification request = given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request");
        if (body != null) {
            request = request.body(body);
        }
        return request.request(method, path).then().statusCode(200).extract().asString();
    }

    private static String bucket() {
        String name = "b2bi-" + UUID.randomUUID().toString().substring(0, 8);
        s3("PUT", "/" + name, null);
        return name;
    }

    private static String json(String name) {
        return MAPPER.valueToTree(name).toString();
    }

    @Test
    void parsingMatchesAws() {
        String bucket = bucket();
        s3("PUT", "/" + bucket + "/sample.edi", golden("sample-850.edi"));
        String location = "{\"bucketName\":\"" + bucket + "\",\"key\":\"sample.edi\"}";
        String parsed = call("TestParsing", "{\"inputFile\":" + location + ",\"fileFormat\":\"JSON\",\"ediType\":"
                + X12_850 + "}").statusCode(200).extract().path("parsedFileContent");
        assertEquals(golden("sample-850.parsed.json"), parsed);
        String xml = call("TestParsing", "{\"inputFile\":" + location + ",\"fileFormat\":\"XML\",\"ediType\":"
                + X12_850 + "}").statusCode(200).extract().path("parsedFileContent");
        assertEquals(golden("sample-850.parsed.xml"), xml);
        call("TestParsing", "{\"inputFile\":" + location + ",\"fileFormat\":\"JSON\",\"ediType\":"
                + "{\"x12Details\":{\"transactionSet\":\"X12_810\",\"version\":\"VERSION_4010\"}}}").statusCode(400)
                .body("message", equalTo("The EDI file could not be parsed. EDI transaction set does not match 810"));
        call("TestParsing", "{\"inputFile\":" + location + ",\"fileFormat\":\"JSON\",\"ediType\":" + X12_850
                + ",\"advancedOptions\":{\"x12\":{\"splitOptions\":{\"splitBy\":\"TRANSACTION\"}}}}").statusCode(200)
                .body("parsedSplitFileContents", hasSize(1));

        String broken = golden("sample-850.edi").replace("SE*10*0001", "SE*9*0001").replace("REF*DP*038~",
                "REF*DP*038~\nZZZ*1~");
        s3("PUT", "/" + bucket + "/broken.edi", broken);
        call("TestParsing", "{\"inputFile\":{\"bucketName\":\"" + bucket + "\",\"key\":\"broken.edi\"},"
                + "\"fileFormat\":\"JSON\",\"ediType\":" + X12_850 + ",\"advancedOptions\":{\"x12\":"
                + "{\"validationOptions\":{\"validationRules\":[{\"elementLengthValidationRule\":{\"elementId\":"
                + "\"BEG03\",\"minLength\":1,\"maxLength\":4}}]}}}}").statusCode(200)
                .body("validationMessages", hasItem(containsString("ZZZ")))
                .body("validationMessages", hasItem(containsString("SE01")))
                .body("validationMessages", hasItem(containsString("BEG03")));
        call("TestParsing", "{\"inputFile\":{\"bucketName\":\"" + bucket + "\",\"key\":\"missing.edi\"},"
                + "\"fileFormat\":\"JSON\",\"ediType\":" + X12_850 + "}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void conversionMappingAndTemplatesMatchAws() {
        String parsed = golden("sample-850.parsed.json");
        call("TestConversion", "{\"source\":{\"fileFormat\":\"JSON\",\"inputFile\":{\"fileContent\":" + json(parsed)
                + "}},\"target\":{\"fileFormat\":\"X12\",\"formatDetails\":{\"x12\":{\"transactionSet\":\"X12_850\","
                + "\"version\":\"VERSION_4010\"}}}}").statusCode(200)
                .body("convertedFileContent", equalTo(golden("sample-850.converted.x12")))
                .body("validationMessages", hasSize(0));
        call("TestConversion", "{\"source\":{\"fileFormat\":\"XML\",\"inputFile\":{\"fileContent\":"
                + json(golden("sample-850.parsed.xml")) + "}},\"target\":{\"fileFormat\":\"X12\",\"formatDetails\":"
                + "{\"x12\":{\"transactionSet\":\"X12_850\",\"version\":\"VERSION_4010\"}}}}").statusCode(200)
                .body("convertedFileContent", equalTo(golden("sample-850.converted.x12")));

        call("TestMapping", "{\"inputFileContent\":\"{\\\"customer\\\":\\\"acme\\\"}\",\"mappingTemplate\":"
                + "\"{ \\\"name\\\": customer }\",\"fileFormat\":\"JSON\"}").statusCode(200)
                .body("mappedFileContent", equalTo("\"{\\n  \\\"name\\\": \\\"acme\\\"\\n}\""));
        call("TestMapping", "{\"inputFileContent\":\"<a><b>x</b></a>\",\"mappingTemplate\":"
                + json("<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">"
                        + "<xsl:output method=\"text\"/><xsl:template match=\"/\"><xsl:value-of select=\"/a/b\"/>"
                        + "</xsl:template></xsl:stylesheet>") + ",\"fileFormat\":\"XML\"}").statusCode(200)
                .body("mappedFileContent", equalTo("x"));
        call("TestMapping", "{\"inputFileContent\":\"{}\",\"mappingTemplate\":\"{ \\\"a\\\": \",\"fileFormat\":"
                + "\"JSON\"}").statusCode(400);

        String template = call("CreateStarterMappingTemplate", "{\"mappingType\":\"JSONATA\",\"templateDetails\":"
                + "{\"x12\":{\"transactionSet\":\"X12_850\",\"version\":\"VERSION_4010\"}}}").statusCode(200)
                .extract().path("mappingTemplate");
        assertEquals(golden("starter-850-4010.json"), template);
        call("CreateStarterMappingTemplate", "{\"mappingType\":\"XSLT\",\"templateDetails\":{\"x12\":"
                + "{\"transactionSet\":\"X12_810\",\"version\":\"VERSION_5010\"}}}").statusCode(200)
                .body("mappingTemplate", containsString("<transaction DocType=\"810\" Name=\"Invoice\""));

        String generated = call("GenerateMapping", "{\"inputFileContent\":\"{\\\"customer\\\":{\\\"name\\\":"
                + "\\\"acme\\\",\\\"city\\\":\\\"portland\\\"},\\\"lines\\\":[{\\\"sku\\\":\\\"A1\\\",\\\"qty\\\":2}]}\","
                + "\"outputFileContent\":\"{\\\"buyer\\\":\\\"acme\\\",\\\"items\\\":[{\\\"code\\\":\\\"A1\\\","
                + "\\\"count\\\":2}],\\\"channel\\\":\\\"edi\\\"}\",\"mappingType\":\"JSONATA\"}").statusCode(200)
                .body("mappingAccuracy", equalTo(1.0f)).extract().path("mappingTemplate");
        assertTrue(generated.contains("customer.name"), generated);
    }

    private static String profile() {
        return call("CreateProfile", "{\"name\":\"acme\",\"businessName\":\"Acme\",\"phone\":\"+15555550100\","
                + "\"logging\":\"ENABLED\"}").statusCode(200).extract().path("profileId");
    }

    private static String transformer(String status, String extra) {
        String id = call("CreateTransformer", "{\"name\":\"t-" + UUID.randomUUID().toString().substring(0, 6) + "\","
                + extra + "}").statusCode(200).extract().path("transformerId");
        if ("active".equals(status)) {
            call("UpdateTransformer", "{\"transformerId\":\"" + id + "\",\"status\":\"active\"}").statusCode(200);
        }
        return id;
    }

    private static final String INBOUND = "\"inputConversion\":{\"fromFormat\":\"X12\",\"formatOptions\":{\"x12\":"
            + "{\"transactionSet\":\"X12_850\",\"version\":\"VERSION_4010\"}}},\"mapping\":{\"templateLanguage\":"
            + "\"JSONATA\",\"template\":\"{ \\\"po\\\": interchanges.functional_groups.transactions.segments.BEG_03 }\"}";

    @Test
    void resourcesFollowAwsRules() {
        String profileId = profile();
        call("GetProfile", "{\"profileId\":\"" + profileId + "\"}").statusCode(200)
                .body("logGroupName", equalTo("/aws/vendedlogs/b2bi/profile/" + profileId))
                .body("profileArn", containsString(":b2bi:us-east-1:"));
        call("UpdateProfile", "{\"profileId\":\"" + profileId + "\",\"businessName\":\"Acme 2\"}").statusCode(200)
                .body("businessName", equalTo("Acme 2")).body("modifiedAt", notNullValue());
        call("GetProfile", "{\"profileId\":\"p-00000000000000000\"}").statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));

        String inactive = transformer("inactive", INBOUND);
        call("GetTransformer", "{\"transformerId\":\"" + inactive + "\"}").statusCode(200)
                .body("status", equalTo("inactive")).body("transformerId", startsWith("tr-"));
        call("UpdateTransformer", "{\"transformerId\":\"" + inactive + "\",\"mapping\":{\"templateLanguage\":"
                + "\"JSONATA\",\"template\":\"{ \\\"x\\\": 1 }\"}}").statusCode(200)
                .body("mapping.template", equalTo("{ \"x\": 1 }"));
        call("UpdateTransformer", "{\"transformerId\":\"" + inactive + "\",\"status\":\"active\"}").statusCode(200);
        call("UpdateTransformer", "{\"transformerId\":\"" + inactive + "\",\"status\":\"inactive\"}").statusCode(400)
                .body("message", equalTo("An active Transformer cannot be updated"));
        call("CreateTransformer", "{\"name\":\"bad\",\"mapping\":{\"templateLanguage\":\"JSONATA\",\"template\":"
                + "\"{ \\\"a\\\": \"}}").statusCode(400);

        String bucket = bucket();
        String capabilityId = call("CreateCapability", "{\"name\":\"orders\",\"type\":\"edi\",\"configuration\":"
                + "{\"edi\":{\"capabilityDirection\":\"INBOUND\",\"type\":" + X12_850 + ",\"inputLocation\":"
                + "{\"bucketName\":\"" + bucket + "\",\"key\":\"in\"},\"outputLocation\":{\"bucketName\":\"" + bucket
                + "\",\"key\":\"out\"},\"transformerId\":\"" + inactive + "\"}},\"tags\":[{\"Key\":\"team\","
                + "\"Value\":\"edi\"}]}").statusCode(200).body("capabilityId", startsWith("ca-"))
                .extract().path("capabilityId");
        call("CreateCapability", "{\"name\":\"x\",\"type\":\"edi\",\"configuration\":{\"edi\":{\"type\":" + X12_850
                + ",\"inputLocation\":{\"bucketName\":\"no-such-bucket-b2bi\",\"key\":\"in\"},\"outputLocation\":"
                + "{\"bucketName\":\"" + bucket + "\",\"key\":\"out\"},\"transformerId\":\"" + inactive + "\"}}}")
                .statusCode(400).body("__type", containsString("ResourceNotFoundException"));
        String partnershipId = call("CreatePartnership", "{\"profileId\":\"" + profileId + "\",\"name\":\"partner\","
                + "\"email\":\"edi@example.com\",\"capabilities\":[\"" + capabilityId + "\"]}").statusCode(200)
                .body("tradingPartnerId", startsWith("tp-")).extract().path("partnershipId");
        call("ListPartnerships", "{\"profileId\":\"" + profileId + "\"}").statusCode(200)
                .body("partnerships.partnershipId", hasItem(partnershipId));
        call("DeleteProfile", "{\"profileId\":\"" + profileId + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        call("DeleteCapability", "{\"capabilityId\":\"" + capabilityId + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));
        call("DeleteTransformer", "{\"transformerId\":\"" + inactive + "\"}").statusCode(400)
                .body("__type", containsString("ConflictException"));

        String arn = call("GetCapability", "{\"capabilityId\":\"" + capabilityId + "\"}").extract().path("capabilityArn");
        call("TagResource", "{\"ResourceARN\":\"" + arn + "\",\"Tags\":[{\"Key\":\"stage\",\"Value\":\"dev\"}]}")
                .statusCode(200);
        call("UntagResource", "{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"team\"]}").statusCode(200);
        call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}").statusCode(200)
                .body("Tags.Key", equalTo(List.of("stage")));

        call("DeletePartnership", "{\"partnershipId\":\"" + partnershipId + "\"}").statusCode(200);
        call("DeleteCapability", "{\"capabilityId\":\"" + capabilityId + "\"}").statusCode(200);
        call("DeleteTransformer", "{\"transformerId\":\"" + inactive + "\"}").statusCode(200);
        call("DeleteProfile", "{\"profileId\":\"" + profileId + "\"}").statusCode(200);
        call("ListProfiles", "{}").statusCode(200).body("profiles.profileId", not(hasItem(profileId)));
    }

    @Test
    void aTransformerJobWritesItsOutput() {
        String bucket = bucket();
        s3("PUT", "/" + bucket + "/in/order.edi", golden("sample-850.edi"));
        String id = transformer("active", INBOUND);
        String inactive = transformer("inactive", INBOUND);
        call("StartTransformerJob", "{\"transformerId\":\"" + inactive + "\",\"inputFile\":{\"bucketName\":\"" + bucket
                + "\",\"key\":\"in/order.edi\"},\"outputLocation\":{\"bucketName\":\"" + bucket + "\",\"key\":"
                + "\"out/\"}}").statusCode(400).body("__type", containsString("ConflictException"));
        String job = call("StartTransformerJob", "{\"transformerId\":\"" + id + "\",\"inputFile\":{\"bucketName\":\""
                + bucket + "\",\"key\":\"in/order.edi\"},\"outputLocation\":{\"bucketName\":\"" + bucket + "\",\"key\":"
                + "\"out/\"}}").statusCode(200).extract().path("transformerJobId");
        assertEquals(25, job.length());
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "succeeded".equals(call("GetTransformerJob",
                "{\"transformerJobId\":\"" + job + "\",\"transformerId\":\"" + id + "\"}").extract().path("status")));
        call("GetTransformerJob", "{\"transformerJobId\":\"" + job + "\",\"transformerId\":\"" + id + "\"}")
                .body("outputFiles[0].key", equalTo("out/order.json"));
        assertEquals("{\n  \"po\": \"XX-1234\"\n}", s3("GET", "/" + bucket + "/out/order.json", null));
    }

    @Test
    void partnerFilesAreProcessedWithAcknowledgments() {
        String bucket = bucket();
        String profileId = profile();
        String inbound = transformer("active", INBOUND);
        String capabilityId = call("CreateCapability", "{\"name\":\"orders\",\"type\":\"edi\",\"configuration\":"
                + "{\"edi\":{\"capabilityDirection\":\"INBOUND\",\"type\":" + X12_850 + ",\"inputLocation\":"
                + "{\"bucketName\":\"" + bucket + "\",\"key\":\"inbound/\"},\"outputLocation\":{\"bucketName\":\""
                + bucket + "\",\"key\":\"processed/\"},\"transformerId\":\"" + inbound + "\"}}}").statusCode(200)
                .extract().path("capabilityId");
        String partner = call("CreatePartnership", "{\"profileId\":\"" + profileId + "\",\"name\":\"partner\","
                + "\"email\":\"edi@example.com\",\"capabilities\":[\"" + capabilityId + "\"],\"capabilityOptions\":"
                + "{\"inboundEdi\":{\"x12\":{\"acknowledgmentOptions\":{\"functionalAcknowledgment\":"
                + "\"GENERATE_ALL_SEGMENTS\",\"technicalAcknowledgment\":\"GENERATE_ALL_SEGMENTS\"}}}}}")
                .statusCode(200).extract().path("tradingPartnerId");
        s3("PUT", "/" + bucket + "/inbound/" + partner + "/order.edi", golden("sample-850.edi"));
        Awaitility.await().atMost(Duration.ofSeconds(20)).ignoreExceptions().until(() -> s3("GET", "/" + bucket
                + "/processed/" + partner + "/order.json", null).contains("XX-1234"));
        String ack = s3("GET", "/" + bucket + "/processed/" + partner + "/acknowledgments/order.997", null);
        assertTrue(ack.contains("ST*997*0001~") && ack.contains("AK1*PO*1~") && ack.contains("AK5*A~")
                && ack.contains("AK9*A*1*1*1~"), ack);
        assertTrue(ack.startsWith("ISA*00*          *00*          *ZZ*RECEIVERID     *ZZ*SENDERID       *"), ack);
        String ta1 = s3("GET", "/" + bucket + "/processed/" + partner + "/acknowledgments/order.ta1", null);
        assertTrue(ta1.contains("TA1*000000001*210101*1253*A*000~"), ta1);

        String outboundTransformer = transformer("active", "\"outputConversion\":{\"toFormat\":\"X12\","
                + "\"formatOptions\":{\"x12\":{\"transactionSet\":\"X12_850\",\"version\":\"VERSION_4010\"}}}");
        String outbound = call("CreateCapability", "{\"name\":\"outbound\",\"type\":\"edi\",\"configuration\":"
                + "{\"edi\":{\"capabilityDirection\":\"OUTBOUND\",\"type\":" + X12_850 + ",\"inputLocation\":"
                + "{\"bucketName\":\"" + bucket + "\",\"key\":\"to-send\"},\"outputLocation\":{\"bucketName\":\""
                + bucket + "\",\"key\":\"sent\"},\"transformerId\":\"" + outboundTransformer + "\"}}}")
                .statusCode(200).extract().path("capabilityId");
        String outPartner = call("CreatePartnership", "{\"profileId\":\"" + profileId + "\",\"name\":\"out\","
                + "\"email\":\"edi@example.com\",\"capabilities\":[\"" + outbound + "\"],\"capabilityOptions\":"
                + "{\"outboundEdi\":{\"x12\":{\"common\":{\"interchangeControlHeaders\":{\"senderIdQualifier\":\"01\","
                + "\"senderId\":\"ALCHEMY        \",\"receiverIdQualifier\":\"01\",\"receiverId\":\"PARTNER        \"},"
                + "\"delimiters\":{\"componentSeparator\":\":\",\"dataElementSeparator\":\"|\",\"segmentTerminator\":"
                + "\"!\"},\"controlNumbers\":{\"startingInterchangeControlNumber\":700}},\"wrapOptions\":{\"wrapBy\":"
                + "\"ONE_LINE\"}}}}}").statusCode(200).extract().path("tradingPartnerId");
        s3("PUT", "/" + bucket + "/to-send/" + outPartner + "/po.json", golden("sample-850.parsed.json"));
        Awaitility.await().atMost(Duration.ofSeconds(20)).ignoreExceptions().until(() -> s3("GET", "/" + bucket
                + "/sent/" + outPartner + "/po.x12", null).startsWith("ISA|"));
        String x12 = s3("GET", "/" + bucket + "/sent/" + outPartner + "/po.x12", null);
        assertTrue(x12.contains("|01|ALCHEMY        |01|PARTNER        |") && x12.contains("|000000700|")
                && x12.contains("BEG|00|SA|XX-1234||20210101!") && !x12.contains("\n"), x12);
    }
}
