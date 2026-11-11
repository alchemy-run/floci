package io.floci.aws.services.entityresolution;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS Entity Resolution over REST: schema mappings and matching workflows over a Glue table of CSV in S3, a
 * matching job that writes match IDs to S3, real-time matching against the job's matches, ID mapping from a table
 * to a matching workflow's output, and the conflicts, denials and errors the API answers with.
 */
@QuarkusTest
class EntityResolutionIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CSV = "id,email,name\n1,jane@example.com,Jane Doe\n2,JANE@example.com ,Jane D\n"
            + "3,bob@example.com,Bob Roe\n4,alice@example.com,Alice Poe\n,nobody@example.com,No Id\n";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static RequestSpecification er() {
        return given().header("Authorization", auth("entityresolution")).contentType("application/json")
                .urlEncodingEnabled(false);
    }

    private static ValidatableResponse post(String path, String body) {
        return er().body(body).post(path).then();
    }

    private static ValidatableResponse get(String path) {
        return er().get(path).then();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void glue(String action, String body) {
        given().header("Authorization", auth("glue")).header("X-Amz-Target", "AWSGlue." + action)
                .contentType("application/x-amz-json-1.1").body(body).post("/").then().statusCode(200);
    }

    private static String role(String name, String service) {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\""
                + service + "\"},\"Action\":\"sts:AssumeRole\"}]}";
        return given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", name).formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    /** A bucket holding the customers CSV and a Glue table over it; returns the table ARN. */
    private static String table(String bucket, String database) {
        given().header("Authorization", auth("s3")).put("/" + bucket).then().statusCode(200);
        given().header("Authorization", auth("s3")).body(CSV).put("/" + bucket + "/input/customers.csv").then()
                .statusCode(200);
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        glue("CreateTable", "{\"DatabaseName\":\"" + database + "\",\"TableInput\":{\"Name\":\"customers\","
                + "\"Parameters\":{\"classification\":\"csv\",\"skip.header.line.count\":\"1\"},"
                + "\"StorageDescriptor\":{\"Location\":\"s3://" + bucket + "/input/\",\"Columns\":[{\"Name\":\"id\","
                + "\"Type\":\"string\"},{\"Name\":\"email\",\"Type\":\"string\"},{\"Name\":\"name\",\"Type\":"
                + "\"string\"}],\"SerdeInfo\":{\"SerializationLibrary\":"
                + "\"org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe\",\"Parameters\":{\"field.delim\":\",\"}}}}}");
        return "arn:aws:glue:us-east-1:" + ACCOUNT + ":table/" + database + "/customers";
    }

    private static String workflowBody(String name, String tableArn, String schema, String bucket, String role,
                                       String rules) {
        return "{\"workflowName\":\"" + name + "\",\"inputSourceConfig\":[{\"inputSourceARN\":\"" + tableArn
                + "\",\"schemaName\":\"" + schema + "\"}],\"outputSourceConfig\":[{\"outputS3Path\":\"s3://" + bucket
                + "/matches/\",\"output\":[{\"name\":\"id\"},{\"name\":\"email\",\"hashed\":true},{\"name\":"
                + "\"name\"}]}],\"resolutionTechniques\":{\"resolutionType\":\"RULE_MATCHING\","
                + "\"ruleBasedProperties\":{\"rules\":" + rules + ",\"attributeMatchingModel\":\"ONE_TO_ONE\"}},"
                + "\"roleArn\":\"" + role + "\",\"tags\":{\"team\":\"crm\"}}";
    }

    private static String awaitJob(String path) {
        for (int attempt = 0; attempt < 50; attempt++) {
            String status = get(path).statusCode(200).extract().path("status");
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return get(path).extract().path("status");
    }

    @Test
    void matchingJobsRealTimeMatchingAndIdMapping() {
        String id = suffix();
        String bucket = "er-" + id;
        String tableArn = table(bucket, "er_" + id);
        String role = role("er-role-" + id, "entityresolution.amazonaws.com");
        String schema = "customers-" + id;
        post("/schemas", "{\"schemaName\":\"" + schema + "\",\"mappedInputFields\":[{\"fieldName\":\"id\",\"type\":"
                + "\"UNIQUE_ID\"},{\"fieldName\":\"email\",\"type\":\"EMAIL_ADDRESS\",\"matchKey\":\"email\"},"
                + "{\"fieldName\":\"name\",\"type\":\"NAME\",\"matchKey\":\"name\"}],\"tags\":{\"k\":\"v\"}}")
                .statusCode(200).body("schemaArn", equalTo("arn:aws:entityresolution:us-east-1:" + ACCOUNT
                        + ":schemamapping/" + schema));
        String workflow = "dedupe-" + id;
        post("/matchingworkflows", workflowBody(workflow, tableArn, schema, bucket, role,
                "[{\"ruleName\":\"ByEmail\",\"matchingKeys\":[\"email\"]}]")).statusCode(200)
                .body("workflowArn", containsString(":matchingworkflow/" + workflow));
        get("/schemas/" + schema).statusCode(200).body("hasWorkflows", equalTo(true));
        er().body("{\"mappedInputFields\":[{\"fieldName\":\"id\",\"type\":\"UNIQUE_ID\"},{\"fieldName\":"
                + "\"email\",\"type\":\"EMAIL_ADDRESS\"}]}").put("/schemas/" + schema)
                .then().statusCode(400).body("__type", containsString("ConflictException"));
        er().delete("/schemas/" + schema).then().statusCode(400).body("__type", containsString("ConflictException"));

        String job = post("/matchingworkflows/" + workflow + "/jobs", "{}").statusCode(200).extract().path("jobId");
        assertEquals("SUCCEEDED", awaitJob("/matchingworkflows/" + workflow + "/jobs/" + job));
        get("/matchingworkflows/" + workflow + "/jobs/" + job).statusCode(200)
                .body("metrics.inputRecords", equalTo(5)).body("metrics.totalRecordsProcessed", equalTo(4))
                .body("metrics.recordsNotProcessed", equalTo(1)).body("metrics.matchIDs", equalTo(3));
        get("/matchingworkflows/" + workflow + "/jobs").statusCode(200).body("jobs", hasSize(1));
        String output = given().header("Authorization", auth("s3"))
                .get("/" + bucket + "/matches/" + workflow + "/" + job + "/success/run-0-part-00000.csv").then()
                .statusCode(200).extract().asString();
        List<String> lines = output.lines().toList();
        assertEquals("InputSourceARN,RecordId,MatchID,MatchRule,id,email,name", lines.get(0));
        String jane = lines.stream().filter(line -> line.contains(",1,")).findFirst().orElseThrow().split(",")[2];
        String janeToo = lines.stream().filter(line -> line.contains(",2,")).findFirst().orElseThrow().split(",")[2];
        String bob = lines.stream().filter(line -> line.contains(",3,")).findFirst().orElseThrow().split(",")[2];
        assertEquals(jane, janeToo);
        assertNotEquals(jane, bob);
        assertTrue(lines.get(1).contains(",ByEmail,") || lines.get(2).contains(",ByEmail,"));
        given().header("Authorization", auth("s3"))
                .get("/" + bucket + "/matches/" + workflow + "/" + job + "/error/run-0-part-00000.csv").then()
                .statusCode(200);

        post("/matchingworkflows/" + workflow + "/matches", "{\"record\":{\"id\":\"9\",\"email\":\"jane@example.com\","
                + "\"name\":\"J\"}}").statusCode(200).body("matchId", equalTo(jane)).body("matchRule", equalTo("ByEmail"));
        post("/matchingworkflows/" + workflow + "/generateMatches", "{\"records\":[{\"inputSourceARN\":\"" + tableArn
                + "\",\"uniqueId\":\"10\",\"recordAttributeMap\":{\"id\":\"10\",\"email\":\"bob@example.com\","
                + "\"name\":\"Robert\"}}]}").statusCode(200).body("matchGroups", hasSize(1))
                .body("matchGroups[0].matchId", equalTo(bob)).body("matchGroups[0].records.recordId", hasItem("3"))
                .body("failedRecords", hasSize(0));
        post("/matchingworkflows/" + workflow + "/generateMatches", "{\"records\":[{\"inputSourceARN\":"
                + "\"arn:aws:glue:us-east-1:" + ACCOUNT + ":table/other/t\",\"uniqueId\":\"11\","
                + "\"recordAttributeMap\":{\"id\":\"11\"}}]}").statusCode(200).body("failedRecords", hasSize(1));
        er().header("uniqueIds", "10,ghost").delete("/matchingworkflows/" + workflow + "/uniqueids").then()
                .statusCode(200).body("status", equalTo("COMPLETED")).body("deleted.uniqueId", hasItem("10"))
                .body("errors[0].uniqueId", equalTo("ghost")).body("disconnectedUniqueIds", hasItem("3"));

        String source = post("/idnamespaces", "{\"idNamespaceName\":\"src-" + id + "\",\"type\":\"SOURCE\","
                + "\"inputSourceConfig\":[{\"inputSourceARN\":\"" + tableArn + "\",\"schemaName\":\"" + schema + "\"}],"
                + "\"idMappingWorkflowProperties\":[{\"idMappingType\":\"RULE_BASED\",\"ruleBasedProperties\":"
                + "{\"ruleDefinitionTypes\":[\"TARGET\"],\"attributeMatchingModel\":\"ONE_TO_ONE\","
                + "\"recordMatchingModels\":[\"ONE_SOURCE_TO_ONE_TARGET\"]}}],\"roleArn\":\"" + role + "\"}")
                .statusCode(200).extract().path("idNamespaceArn");
        String target = post("/idnamespaces", "{\"idNamespaceName\":\"tgt-" + id + "\",\"type\":\"TARGET\","
                + "\"inputSourceConfig\":[{\"inputSourceARN\":\"arn:aws:entityresolution:us-east-1:" + ACCOUNT
                + ":matchingworkflow/" + workflow + "\"}],\"idMappingWorkflowProperties\":[{\"idMappingType\":"
                + "\"RULE_BASED\",\"ruleBasedProperties\":{\"rules\":[{\"ruleName\":\"ByEmail\",\"matchingKeys\":"
                + "[\"email\"]}],\"ruleDefinitionTypes\":[\"TARGET\"],\"attributeMatchingModel\":\"ONE_TO_ONE\","
                + "\"recordMatchingModels\":[\"ONE_SOURCE_TO_ONE_TARGET\"]}}],\"roleArn\":\"" + role + "\"}")
                .statusCode(200).extract().path("idNamespaceArn");
        String mapping = "map-" + id;
        post("/idmappingworkflows", "{\"workflowName\":\"" + mapping + "\",\"inputSourceConfig\":[{\"inputSourceARN\":\""
                + source + "\",\"type\":\"SOURCE\"},{\"inputSourceARN\":\"" + target + "\",\"type\":\"TARGET\"}],"
                + "\"idMappingTechniques\":{\"idMappingType\":\"RULE_BASED\",\"ruleBasedProperties\":"
                + "{\"ruleDefinitionType\":\"TARGET\",\"attributeMatchingModel\":\"ONE_TO_ONE\","
                + "\"recordMatchingModel\":\"ONE_SOURCE_TO_ONE_TARGET\"}},\"outputSourceConfig\":[{\"outputS3Path\":"
                + "\"s3://" + bucket + "/idmapping/\"}],\"roleArn\":\"" + role + "\"}").statusCode(200);
        er().delete("/idnamespaces/src-" + id).then().statusCode(400).body("__type", containsString("Conflict"));
        String mappingJob = post("/idmappingworkflows/" + mapping + "/jobs", "{}").statusCode(200)
                .body("jobType", equalTo("BATCH")).extract().path("jobId");
        assertEquals("SUCCEEDED", awaitJob("/idmappingworkflows/" + mapping + "/jobs/" + mappingJob));
        get("/idmappingworkflows/" + mapping + "/jobs/" + mappingJob).statusCode(200)
                .body("metrics.totalMappedRecords", equalTo(4));
        String mapped = given().header("Authorization", auth("s3")).get("/" + bucket + "/idmapping/" + mapping + "/"
                + mappingJob + "/success/run-0-part-00000.csv").then().statusCode(200).extract().asString();
        assertTrue(mapped.startsWith("SourceInputSourceARN,SourceRecordId,TargetInputSourceARN,TargetRecordId"), mapped);

        er().delete("/idmappingworkflows/" + mapping).then().statusCode(200);
        er().delete("/idnamespaces/src-" + id).then().statusCode(200);
        er().delete("/idnamespaces/tgt-" + id).then().statusCode(200);
        er().delete("/matchingworkflows/" + workflow).then().statusCode(200);
        er().delete("/schemas/" + schema).then().statusCode(200);
        get("/matchingworkflows/" + workflow).statusCode(404).body("__type", containsString("ResourceNotFound"));
        er().delete("/matchingworkflows/" + workflow).then().statusCode(200);
    }

    @Test
    void rolesTagsPoliciesAndErrors() {
        String id = suffix();
        String bucket = "er-" + id;
        String tableArn = table(bucket, "er_" + id);
        String schema = "s-" + id;
        post("/schemas", "{\"schemaName\":\"" + schema + "\",\"mappedInputFields\":[{\"fieldName\":\"id\",\"type\":"
                + "\"UNIQUE_ID\"},{\"fieldName\":\"email\",\"type\":\"EMAIL_ADDRESS\",\"matchKey\":\"email\"},"
                + "{\"fieldName\":\"name\",\"type\":\"NAME\"}]}").statusCode(200);
        post("/schemas", "{\"schemaName\":\"two-ids-" + id + "\",\"mappedInputFields\":[{\"fieldName\":\"a\",\"type\":"
                + "\"UNIQUE_ID\"},{\"fieldName\":\"b\",\"type\":\"UNIQUE_ID\"}]}").statusCode(400);
        String untrusted = role("er-untrusted-" + id, "glue.amazonaws.com");
        post("/matchingworkflows", workflowBody("w-" + id, tableArn, schema, bucket, untrusted,
                "[{\"ruleName\":\"ByEmail\",\"matchingKeys\":[\"email\"]}]")).statusCode(403)
                .body("message", containsString("assuming the passed role"));
        String role = role("er-role-" + id, "entityresolution.amazonaws.com");
        post("/matchingworkflows", workflowBody("w-" + id, tableArn, schema, bucket, role,
                "[{\"ruleName\":\"ByName\",\"matchingKeys\":[\"name\"]}]")).statusCode(400)
                .body("message", containsString("match key name"));
        String arn = post("/matchingworkflows", workflowBody("w-" + id, tableArn, schema, bucket, role,
                "[{\"ruleName\":\"ByEmail\",\"matchingKeys\":[\"email\"]}]")).statusCode(200).extract()
                .path("workflowArn");
        post("/matchingworkflows", workflowBody("w-" + id, tableArn, schema, bucket, role,
                "[{\"ruleName\":\"ByEmail\",\"matchingKeys\":[\"email\"]}]")).statusCode(400)
                .body("__type", containsString("ConflictException"));

        get("/tags/" + arn).statusCode(200).body("tags.team", equalTo("crm"));
        post("/tags/" + arn, "{\"tags\":{\"env\":\"dev\"}}").statusCode(200);
        er().delete("/tags/" + arn + "?tagKeys=team").then().statusCode(200);
        get("/tags/" + arn).statusCode(200).body("tags.env", equalTo("dev")).body("tags.team", equalTo(null));
        get("/tags/arn:aws:entityresolution:us-east-1:" + ACCOUNT + ":matchingworkflow/missing").statusCode(404);

        String token = get("/policies/" + arn).statusCode(200).extract().path("token");
        post("/policies/" + arn + "/share", "{\"effect\":\"Allow\",\"action\":[\"entityresolution:GetMatchId\"],"
                + "\"principal\":[\"111111111111\"]}").statusCode(200).body("policy", containsString("\"share\""));
        er().body("{\"token\":\"" + token + "\",\"policy\":\"{}\"}").put("/policies/" + arn).then().statusCode(400)
                .body("__type", containsString("ConflictException"));
        er().delete("/policies/" + arn + "/share").then().statusCode(200);

        get("/matchingworkflows/does-not-exist").statusCode(404).body("__type", containsString("ResourceNotFound"));
        get("/matchingworkflows/does-not-exist/jobs/0123456789abcdef0123456789abcdef").statusCode(404);
        get("/matchingworkflows/w-" + id + "/jobs/0123456789abcdef0123456789abcdef").statusCode(404);
        get("/providerservices").statusCode(200).body("providerServiceSummaries", hasSize(0));
        er().delete("/schemas/never-" + id).then().statusCode(200);
        er().delete("/matchingworkflows/w-" + id).then().statusCode(200);
    }
}
