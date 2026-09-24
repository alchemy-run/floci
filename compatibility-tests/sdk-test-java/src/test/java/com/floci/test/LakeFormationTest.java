package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Lake Formation signed REST and Glue metadata compatibility")
class LakeFormationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = TestFixtures.emulatorHttpClient();
    private static final AwsCredentials DEFAULT = AwsBasicCredentials.create("test", "test");

    @Test
    @DisplayName("Signed LF-tag associations, inherited search, filters, expressions and opt-ins converge")
    void signedCatalogLifecycle() throws Exception {
        String database = TestFixtures.uniqueName("lf_sdk_db");
        String tag = TestFixtures.uniqueName("lf-sdk-tag");
        String expressionName = TestFixtures.uniqueName("lf-sdk-expression");
        String account = call("GetDataLakePrincipal", Map.of()).path("Identity").asText().split(":")[4];
        Map<String, Object> resource = Map.of("Database", Map.of("Name", database));
        Map<String, Object> pair = Map.of("TagKey", tag, "TagValues", List.of("dev"));
        Map<String, Object> filterKey = Map.of("TableCatalogId", account, "DatabaseName", database,
                "TableName", "records", "Name", "no-email");
        Map<String, Object> principal = Map.of("DataLakePrincipalIdentifier", "arn:aws:iam::" + account + ":root");
        Map<String, Object> optIn = Map.of("Principal", principal, "Resource", resource);
        try (GlueClient glue = TestFixtures.glueClient()) {
            glue.createDatabase(request -> request.databaseInput(input -> input.name(database)));
            try {
                glue.createTable(request -> request.databaseName(database).tableInput(input -> input.name("records")
                        .storageDescriptor(descriptor -> descriptor.columns(
                                Column.builder().name("id").type("string").build(),
                                Column.builder().name("email").type("string").build()))));
                call("CreateLFTag", Map.of("TagKey", tag, "TagValues", List.of("dev", "prod")));
                call("AddLFTagsToResource", Map.of("Resource", resource, "LFTags", List.of(pair)));
                assertThat(call("GetResourceLFTags", Map.of("Resource", resource))
                        .path("LFTagOnDatabase").get(0).path("TagValues").get(0).asText()).isEqualTo("dev");
                assertThat(call("SearchDatabasesByLFTags", Map.of("Expression", List.of(pair)))
                        .path("DatabaseList").findValuesAsText("Name")).contains(database);
                assertThat(call("SearchTablesByLFTags", Map.of("Expression", List.of(pair)))
                        .path("TableList").findValuesAsText("Name")).contains("records");
                call("CreateLFTagExpression", Map.of("Name", expressionName, "Expression", List.of(pair)));
                Map<String, Object> prod = Map.of("TagKey", tag, "TagValues", List.of("prod"));
                call("UpdateLFTagExpression", Map.of("Name", expressionName, "Expression", List.of(prod)));
                assertThat(call("GetLFTagExpression", Map.of("Name", expressionName))
                        .path("Expression").get(0).path("TagValues").get(0).asText()).isEqualTo("prod");
                JsonNode filter = JSON.valueToTree(filterKey);
                ((ObjectNode) filter).set("ColumnWildcard",
                        JSON.valueToTree(Map.of("ExcludedColumnNames", List.of("email"))));
                ((ObjectNode) filter).set("RowFilter",
                        JSON.valueToTree(Map.of("AllRowsWildcard", Map.of())));
                call("CreateDataCellsFilter", Map.of("TableData", filter));
                JsonNode observed = call("GetDataCellsFilter", filterKey).get("DataCellsFilter");
                assertThat(observed.path("ColumnWildcard").path("ExcludedColumnNames").get(0).asText()).isEqualTo("email");
                ((ObjectNode) observed).set("RowFilter",
                        JSON.valueToTree(Map.of("FilterExpression", "id='x'")));
                call("UpdateDataCellsFilter", Map.of("TableData", observed));
                assertThat(call("GetDataCellsFilter", filterKey).path("DataCellsFilter").path("RowFilter")
                        .path("FilterExpression").asText()).isEqualTo("id='x'");
                call("CreateLakeFormationOptIn", optIn);
                assertThat(call("ListLakeFormationOptIns", optIn).path("LakeFormationOptInsInfoList").size()).isEqualTo(1);
                call("DeleteLakeFormationOptIn", optIn);
                assertThat(call("ListLakeFormationOptIns", optIn).path("LakeFormationOptInsInfoList").size()).isZero();
                call("RemoveLFTagsFromResource", Map.of("Resource", resource, "LFTags", List.of(pair)));
                assertThat(call("GetResourceLFTags", Map.of("Resource", resource)).path("LFTagOnDatabase").size()).isZero();
            } finally {
                cleanup("DeleteLakeFormationOptIn", optIn);
                cleanup("DeleteDataCellsFilter", filterKey);
                cleanup("DeleteLFTagExpression", Map.of("Name", expressionName));
                cleanup("DeleteLFTag", Map.of("TagKey", tag));
                glue.deleteDatabase(request -> request.name(database));
            }
        }
    }

    @Test
    @DisplayName("GetDataLakePrincipal resolves real temporary credentials")
    void principalUsesTheAssumedRole() throws Exception {
        String name = TestFixtures.uniqueName("lf-sdk-caller");
        try (IamClient iam = TestFixtures.iamClient(); StsClient sts = TestFixtures.stsClient()) {
            String role = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument(
                    "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                            + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sts:AssumeRole\"}]}"))
                    .role().arn();
            try {
                Credentials session = sts.assumeRole(request -> request.roleArn(role).roleSessionName("lf-sdk")).credentials();
                AwsSessionCredentials credentials = AwsSessionCredentials.create(session.accessKeyId(),
                        session.secretAccessKey(), session.sessionToken());
                try (StsClient caller = StsClient.builder().endpointOverride(TestFixtures.endpoint())
                        .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(credentials)).build()) {
                    String identity = caller.getCallerIdentity().arn();
                    assertThat(call("GetDataLakePrincipal", Map.of(), credentials, Region.US_EAST_1, 200)
                            .path("Identity").asText()).isEqualTo(identity).contains("assumed-role/" + name + "/");
                }
            } finally {
                iam.deleteRole(request -> request.roleName(name));
            }
        }
    }

    @Test
    @DisplayName("Signed requests preserve account and region isolation and reject foreign pagination tokens")
    void catalogIsolationAndPagination() throws Exception {
        AwsCredentials account = AwsBasicCredentials.create("111122223333", "test");
        AwsCredentials other = AwsBasicCredentials.create("444455556666", "test");
        String tag = TestFixtures.uniqueName("lf-page-tag");
        String first = TestFixtures.uniqueName("lf-page-first");
        String second = TestFixtures.uniqueName("lf-page-second");
        call("CreateLFTag", Map.of("TagKey", tag, "TagValues", List.of("dev")), account, Region.US_EAST_1, 200);
        try {
            for (String name : List.of(first, second)) {
                call("CreateLFTagExpression", Map.of("Name", name,
                        "Expression", List.of(Map.of("TagKey", tag, "TagValues", List.of("dev")))), account, Region.US_EAST_1, 200);
            }
            JsonNode page = call("ListLFTagExpressions", Map.of("MaxResults", 1), account, Region.US_EAST_1, 200);
            assertThat(page.path("LFTagExpressions").size()).isEqualTo(1);
            String token = page.path("NextToken").asText();
            assertThat(token).isNotEmpty();
            JsonNode next = call("ListLFTagExpressions", Map.of("MaxResults", 1, "NextToken", token), account, Region.US_EAST_1, 200);
            assertThat(next.path("LFTagExpressions").get(0).path("Name").asText())
                    .isNotEqualTo(page.path("LFTagExpressions").get(0).path("Name").asText());
            assertThat(call("GetLFTagExpression", Map.of("Name", first), other, Region.US_EAST_1, 400)
                    .path("__type").asText()).isEqualTo("EntityNotFoundException");
            assertThat(call("GetLFTagExpression", Map.of("Name", first), account, Region.US_WEST_2, 400)
                    .path("__type").asText()).isEqualTo("EntityNotFoundException");
            assertThat(call("ListLFTagExpressions", Map.of("NextToken", token), other, Region.US_EAST_1, 400)
                    .path("__type").asText()).isEqualTo("InvalidInputException");
        } finally {
            for (String name : List.of(first, second)) {
                call("DeleteLFTagExpression", Map.of("Name", name), account, Region.US_EAST_1, 200);
            }
            call("DeleteLFTag", Map.of("TagKey", tag), account, Region.US_EAST_1, 200);
        }
    }

    private JsonNode call(String operation, Object body) throws Exception {
        return call(operation, body, DEFAULT, Region.US_EAST_1, 200);
    }

    private void cleanup(String operation, Object body) throws Exception {
        HttpResponse<String> response = send(operation, body, DEFAULT, Region.US_EAST_1);
        if (response.statusCode() != 200) {
            assertThat(JSON.readTree(response.body()).path("__type").asText()).isEqualTo("EntityNotFoundException");
        }
    }

    private JsonNode call(String operation, Object body, AwsCredentials credentials, Region region, int status) throws Exception {
        HttpResponse<String> response = send(operation, body, credentials, region);
        assertThat(response.statusCode()).as("%s: %s", operation, response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String operation, Object body, AwsCredentials credentials, Region region) throws Exception {
        URI endpoint = TestFixtures.endpoint().resolve("/" + operation);
        byte[] bytes = JSON.writeValueAsBytes(body);
        SdkHttpFullRequest signed = Aws4Signer.create().sign(SdkHttpFullRequest.builder().uri(endpoint)
                        .method(SdkHttpMethod.POST).putHeader("Content-Type", "application/json")
                        .contentStreamProvider(() -> new ByteArrayInputStream(bytes)).build(),
                Aws4SignerParams.builder().awsCredentials(credentials).signingRegion(region).signingName("lakeformation").build());
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        signed.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Length")) {
                values.forEach(value -> request.header(name, value));
            }
        });
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
