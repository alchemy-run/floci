package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.TEXT;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.config;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudControlIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @BeforeAll
    static void registerAwsJsonParser() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        RestAssured.registerParser("application/x-amz-json-1.1", Parser.JSON);
        RestAssured.registerParser("application/x-amz-json-1.0", Parser.JSON);
    }

    @Test
    void listResourcesReturnsCreatedS3Ec2AndIamResources() throws JsonProcessingException {
        String bucket = "cloudcontrol-test-bucket";
        given().when().put("/" + bucket).then().statusCode(200);

        String vpcId = given()
                .formParam("Action", "CreateVpc")
                .formParam("CidrBlock", "10.42.0.0/16")
                .formParam("TagSpecification.1.ResourceType", "vpc")
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", "cloudcontrol-vpc")
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");

        String subnetId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", vpcId)
                .formParam("CidrBlock", "10.42.1.0/24")
                .formParam("TagSpecification.1.ResourceType", "subnet")
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", "cloudcontrol-subnet")
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");

        String groupId = given()
                .formParam("Action", "CreateSecurityGroup")
                .formParam("GroupName", "cloudcontrol-sg")
                .formParam("GroupDescription", "cloudcontrol sg")
                .formParam("VpcId", vpcId)
                .formParam("TagSpecification.1.ResourceType", "security-group")
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", "cloudcontrol-sg")
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateSecurityGroupResponse.groupId");

        given()
                .formParam("Action", "CreateUser")
                .formParam("UserName", "cloudcontrol-user")
                .header("Authorization", IAM_AUTH)
                .when().post("/")
                .then().statusCode(200);

        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", "CloudControlRole")
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .header("Authorization", IAM_AUTH)
                .when().post("/")
                .then().statusCode(200);

        assertListed("AWS::S3::Bucket", bucket, "BucketName");
        assertListedWithTag("AWS::EC2::VPC", vpcId, "Name", "cloudcontrol-vpc");
        assertListed("AWS::EC2::VPC", vpcId, "CidrBlock");
        assertListedWithTag("AWS::EC2::Subnet", subnetId, "Name", "cloudcontrol-subnet");
        assertListed("AWS::EC2::Subnet", subnetId, "VpcId");
        assertListedWithTag("AWS::EC2::SecurityGroup", groupId, "Name", "cloudcontrol-sg");
        assertListed("AWS::EC2::SecurityGroup", groupId, "GroupName", "application/x-amz-json-1.0");
        assertListed("AWS::IAM::User", "cloudcontrol-user", "UserName");
        assertListed("AWS::IAM::Role", "CloudControlRole", "RoleName");
    }

    @Test
    void createUpdateDeleteSsmParameterViaJson10() throws JsonProcessingException {
        String name = "/alchemy-test/cloudcontrol/" + UUID.randomUUID();
        String desired = MAPPER.writeValueAsString(Map.of(
                "Name", name,
                "Type", "String",
                "Value", "hello"));
        String createBody = MAPPER.writeValueAsString(Map.of(
                "TypeName", "AWS::SSM::Parameter",
                "DesiredState", desired,
                "ClientToken", UUID.randomUUID().toString()));

        JsonNode created = cloudControl("CreateResource", createBody);
        JsonNode progress = created.path("ProgressEvent");
        assertEquals("SUCCESS", progress.path("OperationStatus").asText());
        assertEquals("CREATE", progress.path("Operation").asText());
        assertEquals(name, progress.path("Identifier").asText());
        assertEquals("hello", MAPPER.readTree(progress.path("ResourceModel").asText()).path("Value").asText());

        String requestToken = progress.path("RequestToken").asText();
        JsonNode status = cloudControl("GetResourceRequestStatus",
                MAPPER.writeValueAsString(Map.of("RequestToken", requestToken)));
        assertEquals("SUCCESS", status.path("ProgressEvent").path("OperationStatus").asText());
        assertEquals(name, status.path("ProgressEvent").path("Identifier").asText());

        JsonNode described = cloudControl("GetResource", MAPPER.writeValueAsString(Map.of(
                "TypeName", "AWS::SSM::Parameter",
                "Identifier", name)));
        assertEquals(name, described.path("ResourceDescription").path("Identifier").asText());
        assertEquals("hello", MAPPER.readTree(described.path("ResourceDescription").path("Properties").asText())
                .path("Value").asText());

        String patch = MAPPER.writeValueAsString(List.of(
                Map.of("op", "replace", "path", "/Value", "value", "world")));
        JsonNode updated = cloudControl("UpdateResource", MAPPER.writeValueAsString(Map.of(
                "TypeName", "AWS::SSM::Parameter",
                "Identifier", name,
                "PatchDocument", patch,
                "ClientToken", UUID.randomUUID().toString())));
        assertEquals("SUCCESS", updated.path("ProgressEvent").path("OperationStatus").asText());

        JsonNode reDescribed = cloudControl("GetResource", MAPPER.writeValueAsString(Map.of(
                "TypeName", "AWS::SSM::Parameter",
                "Identifier", name)));
        assertEquals("world", MAPPER.readTree(reDescribed.path("ResourceDescription").path("Properties").asText())
                .path("Value").asText());

        JsonNode deleted = cloudControl("DeleteResource", MAPPER.writeValueAsString(Map.of(
                "TypeName", "AWS::SSM::Parameter",
                "Identifier", name,
                "ClientToken", UUID.randomUUID().toString())));
        assertEquals("SUCCESS", deleted.path("ProgressEvent").path("OperationStatus").asText());

        given()
                .config(config().encoderConfig(
                        encoderConfig().encodeContentTypeAs(CONTENT_TYPE_AWS_JSON_1_0, TEXT)))
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .header("X-Amz-Target", "CloudApiService.GetResource")
                .body(MAPPER.writeValueAsString(Map.of(
                        "TypeName", "AWS::SSM::Parameter",
                        "Identifier", name)))
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listResourcesFindsSsmParameterAndListsRequestTokens() throws JsonProcessingException {
        String name = "/alchemy-test/cloudcontrol/list-" + UUID.randomUUID();
        String desired = MAPPER.writeValueAsString(Map.of(
                "Name", name,
                "Type", "String",
                "Value", "listed"));
        cloudControl("CreateResource", MAPPER.writeValueAsString(Map.of(
                "TypeName", "AWS::SSM::Parameter",
                "DesiredState", desired,
                "ClientToken", UUID.randomUUID().toString())));

        assertListed("AWS::SSM::Parameter", name, "Value", CONTENT_TYPE_AWS_JSON_1_0);

        JsonNode listed = cloudControl("ListResourceRequests",
                MAPPER.writeValueAsString(Map.of("MaxResults", 20)));
        assertTrue(listed.path("ResourceRequestStatusSummaries").size() >= 1);
    }

    @Test
    void unknownRequestTokenReturnsTypedNotFound() throws JsonProcessingException {
        String body = MAPPER.writeValueAsString(Map.of(
                "RequestToken", "00000000-0000-0000-0000-000000000000"));
        given()
                .config(config().encoderConfig(
                        encoderConfig().encodeContentTypeAs(CONTENT_TYPE_AWS_JSON_1_0, TEXT)))
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .header("X-Amz-Target", "CloudApiService.GetResourceRequestStatus")
                .body(body)
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("RequestTokenNotFoundException"));

        given()
                .config(config().encoderConfig(
                        encoderConfig().encodeContentTypeAs(CONTENT_TYPE_AWS_JSON_1_0, TEXT)))
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .header("X-Amz-Target", "CloudApiService.CancelResourceRequest")
                .body(body)
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("RequestTokenNotFoundException"));
    }

    private void assertListed(String typeName, String identifier, String propertyName) {
        assertListed(typeName, identifier, propertyName, "application/x-amz-json-1.1");
    }

    private void assertListed(String typeName, String identifier, String propertyName, String contentType) {
        String body = listResources(typeName, contentType);

        assertThat(body, containsString("\"TypeName\":\"" + typeName + "\""));
        assertThat(body, containsString("\"Identifier\":\"" + identifier + "\""));
        assertThat(body, containsString(propertyName));
    }

    private void assertListedWithTag(
            String typeName, String identifier, String key, String value) throws JsonProcessingException {
        JsonNode response = MAPPER.readTree(listResources(typeName, "application/x-amz-json-1.1"));
        JsonNode description = null;
        for (JsonNode candidate : response.path("ResourceDescriptions")) {
            if (identifier.equals(candidate.path("Identifier").asText())) {
                description = candidate;
                break;
            }
        }

        assertNotNull(description);
        assertEquals(identifier, description.path("Identifier").asText());
        JsonNode tags = MAPPER.readTree(description.path("Properties").asText()).path("Tags");
        assertTrue(tags.isArray());
        assertTrue(tags.valueStream().anyMatch(tag ->
                key.equals(tag.path("Key").asText())
                        && tag.path("Key").isTextual()
                        && value.equals(tag.path("Value").asText())
                        && tag.path("Value").isTextual()));
    }

    private JsonNode cloudControl(String action, String body) throws JsonProcessingException {
        String response = given()
                .config(config().encoderConfig(
                        encoderConfig().encodeContentTypeAs(CONTENT_TYPE_AWS_JSON_1_0, TEXT)))
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .header("X-Amz-Target", "CloudApiService." + action)
                .body(body)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().asString();
        return MAPPER.readTree(response);
    }

    private String listResources(String typeName, String contentType) {
        return given()
                .config(config().encoderConfig(
                        encoderConfig().encodeContentTypeAs(contentType, TEXT)))
                .contentType(contentType)
                .header("X-Amz-Target", "CloudApiService.ListResources")
                .body("{\"TypeName\":\"" + typeName + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().asString();
    }
}
