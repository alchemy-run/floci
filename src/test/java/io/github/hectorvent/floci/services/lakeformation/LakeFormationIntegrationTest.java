package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestMethodOrder(MethodOrderer.MethodName.class)
class LakeFormationIntegrationTest {

    @Inject
    GlueService glueService;
    @Inject
    IamService iamService;
    @Inject
    RegionResolver regionResolver;
    @Inject
    AccountResolver accountResolver;
    @Inject
    ObjectMapper mapper;
    @TempDir
    Path persistenceDirectory;

    @Test
    void catalogStateSurvivesStorageFactoryRestart() throws Exception {
        String database = "lf_persistent_database";
        String account = regionResolver.getAccountId();
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        glue("CreateTable", "{\"DatabaseName\":\"" + database
                + "\",\"TableInput\":{\"Name\":\"records\",\"StorageDescriptor\":{\"Columns\":[{\"Name\":\"id\",\"Type\":\"string\"}]}}}");
        JsonNode expression = mapper.readTree("{\"Name\":\"persistent-expression\","
                + "\"Expression\":[{\"TagKey\":\"persist-env\",\"TagValues\":[\"dev\"]}]}");
        JsonNode assignment = mapper.readTree("{\"Resource\":{\"Database\":{\"Name\":\"" + database
                + "\"}},\"LFTags\":[{\"TagKey\":\"persist-env\",\"TagValues\":[\"dev\"]}]}");
        JsonNode filter = mapper.readTree("{\"TableCatalogId\":\"" + account + "\",\"DatabaseName\":\"" + database
                + "\",\"TableName\":\"records\",\"Name\":\"visible\",\"ColumnWildcard\":{},\"RowFilter\":{\"AllRowsWildcard\":{}}}");
        JsonNode optIn = mapper.readTree("{\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::" + account
                + ":root\"},\"Resource\":{\"Database\":{\"Name\":\"" + database + "\"}}}");
        StorageFactory firstFactory = persistentFactory();
        try {
            MemoryLakeFormationStorage storage = new MemoryLakeFormationStorage(firstFactory);
            LakeFormationCatalogService catalog = new LakeFormationCatalogService(storage, firstFactory, glueService,
                    iamService, accountResolver, regionResolver, mapper);
            storage.createLFTag("us-east-1", account, "persist-env", List.of("dev"));
            catalog.changeTags("us-east-1", assignment, false);
            catalog.expression("us-east-1", "CreateLFTagExpression", expression);
            catalog.dataCellsFilter("us-east-1", "CreateDataCellsFilter", mapper.createObjectNode().set("TableData", filter));
            catalog.optIn("us-east-1", "CreateLakeFormationOptIn", optIn, AUTH_HEADER);
        } finally {
            firstFactory.shutdownAll();
        }
        StorageFactory secondFactory = persistentFactory();
        try {
            MemoryLakeFormationStorage storage = new MemoryLakeFormationStorage(secondFactory);
            LakeFormationCatalogService catalog = new LakeFormationCatalogService(storage, secondFactory, glueService,
                    iamService, accountResolver, regionResolver, mapper);
            assertEquals("dev", catalog.getResourceLFTags("us-east-1", assignment)
                    .path("LFTagOnDatabase").get(0).path("TagValues").get(0).asText());
            assertEquals("persistent-expression", catalog.expression("us-east-1", "GetLFTagExpression", expression)
                    .path("Name").asText());
            assertEquals("visible", catalog.dataCellsFilter("us-east-1", "GetDataCellsFilter", filter)
                    .path("DataCellsFilter").path("Name").asText());
            assertEquals(1, catalog.optIn("us-east-1", "ListLakeFormationOptIns", optIn, AUTH_HEADER)
                    .path("LakeFormationOptInsInfoList").size());
            assertTrue(catalog.expression("us-west-2", "ListLFTagExpressions", mapper.createObjectNode())
                    .path("LFTagExpressions").isEmpty());
        } finally {
            secondFactory.shutdownAll();
            glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}");
        }
    }

    private StorageFactory persistentFactory() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(regionResolver.getAccountId());
        when(config.storage().persistentPath()).thenReturn(persistenceDirectory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("lakeformation")).thenReturn("persistent");
        return new StorageFactory(config, access);
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/json";
    private static final String AUTH_HEADER = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20201022/us-east-1/lakeformation/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy";




    @Test
    void catalogTagsSearchAndLifecycleRoutes() {
        String database = "lf_catalog_routes";
        String account = post("GetDataLakePrincipal", "{}", 200).extract().path("Identity");
        account = account.split(":")[4];
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        glue("CreateTable", "{\"DatabaseName\":\"" + database + "\",\"TableInput\":{\"Name\":\"records\","
                + "\"StorageDescriptor\":{\"Columns\":[{\"Name\":\"id\",\"Type\":\"string\"},"
                + "{\"Name\":\"email\",\"Type\":\"string\"}]}}}");
        String resource = "{\"Database\":{\"Name\":\"" + database + "\"}}";
        String expression = "[{\"TagKey\":\"lf-route-env\",\"TagValues\":[\"dev\"]}]";
        post("CreateLFTag", "{\"TagKey\":\"lf-route-env\",\"TagValues\":[\"dev\",\"prod\"]}", 200);
        post("AddLFTagsToResource", "{\"Resource\":" + resource + ",\"LFTags\":" + expression + "}", 200)
                .body("Failures", empty());
        post("GetResourceLFTags", "{\"Resource\":{\"Database\":{\"CatalogId\":\"" + account
                + "\",\"Name\":\"" + database + "\"}}}", 200)
                .body("LFTagOnDatabase[0].TagValues", contains("dev"));
        post("SearchDatabasesByLFTags", "{\"Expression\":" + expression + "}", 200)
                .body("DatabaseList.Database.Name", hasItem(database));
        post("SearchTablesByLFTags", "{\"Expression\":" + expression + "}", 200)
                .body("TableList.Table.Name", hasItem("records"));
        post("SearchTablesByLFTags", "{\"Expression\":[{\"TagKey\":\"absent-lf-tag\",\"TagValues\":[\"x\"]}]}", 400)
                .body("__type", equalTo("EntityNotFoundException"));
        post("AddLFTagsToResource", "{\"Resource\":" + resource
                + ",\"LFTags\":[{\"TagKey\":\"lf-route-env\",\"TagValues\":[\"unknown\"]}]}", 200)
                .body("Failures[0].Error.ErrorCode", equalTo("InvalidInputException"));
        post("GetResourceLFTags", "{\"Resource\":" + resource + "}", 200)
                .body("LFTagOnDatabase[0].TagValues", contains("dev"));

        post("CreateLFTagExpression", "{\"Name\":\"lf-route-expression\",\"Expression\":" + expression + "}", 200);
        post("UpdateLFTagExpression", "{\"Name\":\"lf-route-expression\",\"Description\":\"updated\","
                + "\"Expression\":[{\"TagKey\":\"lf-route-env\",\"TagValues\":[\"prod\"]}]}", 200);
        post("GetLFTagExpression", "{\"Name\":\"lf-route-expression\"}", 200)
                .body("Expression[0].TagValues", contains("prod"));
        post("ListLFTagExpressions", "{}", 200).body("LFTagExpressions.Name", hasItem("lf-route-expression"));

        String filterKey = "\"TableCatalogId\":\"" + account + "\",\"DatabaseName\":\"" + database
                + "\",\"TableName\":\"records\",\"Name\":\"no-email\"";
        post("CreateDataCellsFilter", "{\"TableData\":{" + filterKey
                + ",\"ColumnWildcard\":{\"ExcludedColumnNames\":[\"email\"]},\"RowFilter\":{\"AllRowsWildcard\":{}}}}", 200);
        String version = post("GetDataCellsFilter", "{" + filterKey + "}", 200)
                .body("DataCellsFilter.ColumnWildcard.ExcludedColumnNames", contains("email"))
                .extract().path("DataCellsFilter.VersionId");
        post("UpdateDataCellsFilter", "{\"TableData\":{" + filterKey + ",\"VersionId\":\"" + version
                + "\",\"ColumnWildcard\":{},\"RowFilter\":{\"FilterExpression\":\"id='x'\"}}}", 200);
        post("GetDataCellsFilter", "{" + filterKey + "}", 200)
                .body("DataCellsFilter.RowFilter.FilterExpression", equalTo("id='x'"));
        post("ListDataCellsFilter", "{\"Table\":{\"DatabaseName\":\"" + database + "\",\"Name\":\"records\"}}", 200)
                .body("DataCellsFilters.Name", contains("no-email"));

        String principal = "{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::" + account + ":root\"}";
        String optIn = "{\"Principal\":" + principal + ",\"Resource\":" + resource + "}";
        post("CreateLakeFormationOptIn", optIn, 200);
        post("ListLakeFormationOptIns", optIn, 200).body("LakeFormationOptInsInfoList", hasSize(1));
        post("DeleteLakeFormationOptIn", optIn, 200);
        post("ListLakeFormationOptIns", optIn, 200).body("LakeFormationOptInsInfoList", empty());
        post("DeleteDataCellsFilter", "{" + filterKey + "}", 200);
        post("GetDataCellsFilter", "{" + filterKey + "}", 400).body("__type", equalTo("EntityNotFoundException"));
        post("DeleteLFTagExpression", "{\"Name\":\"lf-route-expression\"}", 200);
        post("GetLFTagExpression", "{\"Name\":\"lf-route-expression\"}", 400).body("__type", equalTo("EntityNotFoundException"));
        post("RemoveLFTagsFromResource", "{\"Resource\":" + resource + ",\"LFTags\":" + expression + "}", 200);
        post("GetResourceLFTags", "{\"Resource\":" + resource + "}", 200).body("LFTagOnDatabase", empty());
        post("DeleteLFTag", "{\"TagKey\":\"lf-route-env\"}", 200);
        glue("DeleteTable", "{\"DatabaseName\":\"" + database + "\",\"Name\":\"records\"}");
        glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}");
        post("GetResourceLFTags", "{\"Resource\":" + resource + "}", 400).body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void glueMetadataIsAccountAndRegionScoped() {
        String database = "lf_metadata_isolation";
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        String resource = "{\"Resource\":{\"Database\":{\"Name\":\"" + database + "\"}}}";
        try {
            post("GetResourceLFTags", resource, 200).body("LFTagOnDatabase", empty());
            given().contentType(CONTENT_TYPE)
                    .header("Authorization", AUTH_HEADER.replace("AKIAIOSFODNN7EXAMPLE", "444455556666"))
                    .body(resource).post("/GetResourceLFTags").then().statusCode(400)
                    .body("__type", equalTo("EntityNotFoundException"));
            given().contentType(CONTENT_TYPE)
                    .header("Authorization", AUTH_HEADER.replace("us-east-1", "us-west-2"))
                    .body(resource).post("/GetResourceLFTags").then().statusCode(400)
                    .body("__type", equalTo("EntityNotFoundException"));
        } finally {
            glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}");
        }
    }

    @Test
    void columnTagsOverrideInheritanceAndRejectMissingMetadata() {
        String database = "lf_columns_database";
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        glue("CreateTable", "{\"DatabaseName\":\"" + database + "\",\"TableInput\":{\"Name\":\"records\","
                + "\"StorageDescriptor\":{\"Columns\":[{\"Name\":\"id\",\"Type\":\"string\"},"
                + "{\"Name\":\"email\",\"Type\":\"string\"}]}}}");
        String databaseResource = "{\"Database\":{\"Name\":\"" + database + "\"}}";
        String tableResource = "{\"Table\":{\"DatabaseName\":\"" + database + "\",\"Name\":\"records\"}}";
        String columnResource = "{\"TableWithColumns\":{\"DatabaseName\":\"" + database
                + "\",\"Name\":\"records\",\"ColumnNames\":[\"email\"]}}";
        post("CreateLFTag", "{\"TagKey\":\"lf-column-visibility\",\"TagValues\":[\"public\",\"private\"]}", 200);
        String publicTag = "[{\"TagKey\":\"lf-column-visibility\",\"TagValues\":[\"public\"]}]";
        String privateTag = "[{\"TagKey\":\"lf-column-visibility\",\"TagValues\":[\"private\"]}]";
        post("AddLFTagsToResource", "{\"Resource\":" + databaseResource + ",\"LFTags\":" + publicTag + "}", 200);
        post("AddLFTagsToResource", "{\"Resource\":" + columnResource + ",\"LFTags\":" + privateTag + "}", 200);
        post("GetResourceLFTags", "{\"Resource\":" + tableResource + "}", 200)
                .body("LFTagsOnTable[0].TagValues", contains("public"))
                .body("LFTagsOnColumns.find { it.Name == 'email' }.LFTags[0].TagValues", contains("private"))
                .body("LFTagsOnColumns.find { it.Name == 'id' }.LFTags[0].TagValues", contains("public"));
        post("GetResourceLFTags", "{\"Resource\":" + tableResource + ",\"ShowAssignedLFTags\":true}", 200)
                .body("LFTagOnDatabase", empty()).body("LFTagsOnTable", empty()).body("LFTagsOnColumns", hasSize(1));
        post("SearchTablesByLFTags", "{\"Expression\":" + privateTag + "}", 200)
                .body("TableList[0].Table.Name", equalTo("records"))
                .body("TableList[0].LFTagsOnColumns.Name", contains("email"));
        post("AddLFTagsToResource", "{\"Resource\":" + databaseResource + ",\"LFTags\":" + privateTag + "}", 200);
        post("GetResourceLFTags", "{\"Resource\":" + databaseResource + "}", 200)
                .body("LFTagOnDatabase[0].TagValues", contains("private"));
        post("AddLFTagsToResource", "{\"Resource\":" + columnResource.replace("email", "absent")
                + ",\"LFTags\":" + privateTag + "}", 400).body("__type", equalTo("InvalidInputException"));
        post("DeleteLFTag", "{\"TagKey\":\"lf-column-visibility\"}", 200);
        post("CreateLFTag", "{\"TagKey\":\"lf-column-visibility\",\"TagValues\":[\"public\",\"private\"]}", 200);
        post("GetResourceLFTags", "{\"Resource\":" + tableResource + "}", 200)
                .body("LFTagOnDatabase", empty()).body("LFTagsOnTable", empty()).body("LFTagsOnColumns", empty());
        post("DeleteLFTag", "{\"TagKey\":\"lf-column-visibility\"}", 200);
        glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}");
        post("AddLFTagsToResource", "{\"Resource\":" + databaseResource + ",\"LFTags\":" + publicTag + "}", 400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void serviceLinkedRegistrationReturnsAnActualIamRoleAndFlags() {
        String arn = "arn:aws:s3:::lf-service-linked-registration";
        post("RegisterResource", "{\"ResourceArn\":\"" + arn + "\",\"UseServiceLinkedRole\":true}", 200);
        String roleArn = post("DescribeResource", "{\"ResourceArn\":\"" + arn + "\"}", 200)
                .body("ResourceInfo.HybridAccessEnabled", equalTo(false))
                .body("ResourceInfo.WithFederation", equalTo(false))
                .body("ResourceInfo.RoleArn", endsWith("/AWSServiceRoleForLakeFormationDataAccess"))
                .extract().path("ResourceInfo.RoleArn");
        given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH_HEADER.replace("/lakeformation/", "/iam/"))
                .body("Action=GetRole&Version=2010-05-08&RoleName=AWSServiceRoleForLakeFormationDataAccess")
                .post("/").then().statusCode(200).body("GetRoleResponse.GetRoleResult.Role.Arn", equalTo(roleArn));
        post("UpdateResource", "{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"" + roleArn
                + "\",\"HybridAccessEnabled\":true}", 400).body("__type", equalTo("InvalidInputException"));
        post("DeregisterResource", "{\"ResourceArn\":\"" + arn + "\"}", 200);
        post("RegisterResource", "{\"ResourceArn\":\"" + arn
                + "\",\"UseServiceLinkedRole\":true,\"HybridAccessEnabled\":true}", 200);
        post("DescribeResource", "{\"ResourceArn\":\"" + arn + "\"}", 200)
                .body("ResourceInfo.RoleArn", equalTo(roleArn)).body("ResourceInfo.HybridAccessEnabled", equalTo(true));
        post("DeregisterResource", "{\"ResourceArn\":\"" + arn + "\"}", 200);
    }

    private ValidatableResponse post(String operation, String body, int status) {
        return given().contentType(CONTENT_TYPE).header("Authorization", AUTH_HEADER).body(body)
                .post("/" + operation).then().statusCode(status);
    }

    private void glue(String operation, String body) {
        given().contentType("application/x-amz-json-1.1")
                .header("Authorization", AUTH_HEADER.replace("/lakeformation/", "/glue/"))
                .header("X-Amz-Target", "AWSGlue." + operation).body(body)
                .post("/").then().statusCode(200);
    }

    @Test
    void putAndGetDataLakeSettings() {
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"DataLakeSettings\":{\"DataLakeAdmins\":[{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:user/admin\"}]}}")
        .when()
            .post("/PutDataLakeSettings")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/GetDataLakeSettings")
        .then()
            .statusCode(200)
            .body("DataLakeSettings.DataLakeAdmins[0].DataLakePrincipalIdentifier", equalTo("arn:aws:iam::111122223333:user/admin"));
    }

    @Test
    void createAndGetLFTag() {
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department\",\"TagValues\":[\"sales\",\"engineering\"]}")
        .when()
            .post("/CreateLFTag")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department\"}")
        .when()
            .post("/GetLFTag")
        .then()
            .statusCode(200)
            .body("TagKey", equalTo("department"))
            .body("TagValues", containsInAnyOrder("sales", "engineering"));
    }

    @Test
    void updateLFTag() {
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department2\",\"TagValues\":[\"sales\"]}")
        .when()
            .post("/CreateLFTag")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department2\",\"TagValuesToAdd\":[\"marketing\"],\"TagValuesToDelete\":[\"sales\"]}")
        .when()
            .post("/UpdateLFTag")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department2\"}")
        .when()
            .post("/GetLFTag")
        .then()
            .statusCode(200)
            .body("TagKey", equalTo("department2"))
            .body("TagValues", contains("marketing"));
    }

    @Test
    void grantAndListPermissions() {
        String grantBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/my-role\"},"
            + "\"Resource\":{\"Table\":{\"DatabaseName\":\"default\",\"Name\":\"my-table\"}},"
            + "\"Permissions\":[\"SELECT\",\"INSERT\"]"
            + "}";

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body(grantBody)
        .when()
            .post("/GrantPermissions")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/ListPermissions")
        .then()
            .statusCode(200)
            .body("PrincipalResourcePermissions[0].Principal.DataLakePrincipalIdentifier", equalTo("arn:aws:iam::111122223333:role/my-role"))
            .body("PrincipalResourcePermissions[0].Resource.Table.DatabaseName", equalTo("default"))
            .body("PrincipalResourcePermissions[0].Resource.Table.Name", equalTo("my-table"))
            .body("PrincipalResourcePermissions[0].Permissions", containsInAnyOrder("SELECT", "INSERT"));
    }

    @Test
    void revokePermissions() {
        String grantBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/my-role\"},"
            + "\"Resource\":{\"Table\":{\"DatabaseName\":\"default\",\"Name\":\"my-table\"}},"
            + "\"Permissions\":[\"SELECT\",\"INSERT\"]"
            + "}";

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body(grantBody)
        .when()
            .post("/GrantPermissions")
        .then()
            .statusCode(200);

        String revokeBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/my-role\"},"
            + "\"Resource\":{\"Table\":{\"DatabaseName\":\"default\",\"Name\":\"my-table\"}},"
            + "\"Permissions\":[\"INSERT\"]"
            + "}";

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body(revokeBody)
        .when()
            .post("/RevokePermissions")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/ListPermissions")
        .then()
            .statusCode(200)
            .body("PrincipalResourcePermissions[0].Permissions", contains("SELECT"));
    }

    @Test
    void grantAndListPermissionsTBAC() {
        String grantBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/tbac\"},"
            + "\"Resource\":{\"LFTag\":{\"CatalogId\":\"111122223333\",\"TagKey\":\"env\",\"TagValues\":[\"dev\"]}},"
            + "\"Permissions\":[\"ASSOCIATE\"]"
            + "}";

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body(grantBody)
        .when()
            .post("/GrantPermissions")
        .then()
            .statusCode(200);

        String listBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/tbac\"}"
            + "}";
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body(listBody)
        .when()
            .post("/ListPermissions")
        .then()
            .statusCode(200)
            .body("PrincipalResourcePermissions[0].Resource.LFTag.TagKey", equalTo("env"))
            .body("PrincipalResourcePermissions[0].Resource.LFTag.TagValues", contains("dev"))
            .body("PrincipalResourcePermissions[0].Permissions", contains("ASSOCIATE"));
    }

    @Test
    void resourceLifecycle() {
        String arn = "arn:aws:s3:::my-lake-bucket";
        String arn2 = "arn:aws:s3:::my-other-lake-bucket";
        
        // Register first
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"arn:aws:iam::111122223333:role/s3-role\",\"UseServiceLinkedRole\":true}")
        .when()
            .post("/RegisterResource")
        .then()
            .statusCode(200);

        // Register second
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn2 + "\",\"RoleArn\":\"arn:aws:iam::111122223333:role/s3-role\",\"UseServiceLinkedRole\":true}")
        .when()
            .post("/RegisterResource")
        .then()
            .statusCode(200);

        // Describe first
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DescribeResource")
        .then()
            .statusCode(200)
            .body("ResourceInfo.ResourceArn", equalTo(arn));

        // List with filter (should only return the first resource)
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"FilterConditionList\":[{\"Field\":\"RESOURCE_ARN\",\"ComparisonOperator\":\"EQ\",\"StringValueList\":[\"" + arn + "\"]}]}")
        .when()
            .post("/ListResources")
        .then()
            .statusCode(200)
            .body("ResourceInfoList", hasSize(1))
            .body("ResourceInfoList[0].ResourceArn", equalTo(arn));

        // Deregister
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DeregisterResource")
        .then()
            .statusCode(200);
            
        // Describe should fail now
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DescribeResource")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void updateResourcePreservesOmittedValuesAndIsRegionScoped() {
        String arn = "arn:aws:s3:::update-resource-bucket";
        String initialRole = "arn:aws:iam::111122223333:role/initial";
        String updatedRole = "arn:aws:iam::111122223333:role/updated";
        String secondRegionHeader = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20201022/us-west-2/lakeformation/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy";

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"" + initialRole + "\",\"WithFederation\":true}")
        .when()
            .post("/RegisterResource")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", secondRegionHeader)
            .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"" + initialRole + "\",\"WithFederation\":false}")
        .when()
            .post("/RegisterResource")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"" + updatedRole + "\"}")
        .when()
            .post("/UpdateResource")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DescribeResource")
        .then()
            .statusCode(200)
            .body("ResourceInfo.RoleArn", equalTo(updatedRole))
            .body("ResourceInfo.WithFederation", equalTo(true));

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", secondRegionHeader)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DescribeResource")
        .then()
            .statusCode(200)
            .body("ResourceInfo.RoleArn", equalTo(initialRole))
            .body("ResourceInfo.WithFederation", equalTo(false));
    }

    @Test
    void listAndDeleteLFTag() {
        // Create tag
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"project\",\"TagValues\":[\"apollo\"]}")
        .when()
            .post("/CreateLFTag")
        .then()
            .statusCode(200);

        // List
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/ListLFTags")
        .then()
            .statusCode(200)
            .body("LFTags.TagKey", hasItem("project"));

        // Delete
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"project\"}")
        .when()
            .post("/DeleteLFTag")
        .then()
            .statusCode(200);
            
        // Get should fail
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"project\"}")
        .when()
            .post("/GetLFTag")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void addAndRemoveLFTagsFromResource() {
        String database = "lf_classification_database";
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        // Create tag
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"classification\",\"TagValues\":[\"confidential\"]}")
        .when()
            .post("/CreateLFTag")
        .then()
            .statusCode(200);

        // Add to resource
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Resource\":{\"Database\":{\"Name\":\"" + database + "\"}},\"LFTags\":[{\"TagKey\":\"classification\",\"TagValues\":[\"confidential\"]}]}")
        .when()
            .post("/AddLFTagsToResource")
        .then()
            .statusCode(200);

        // Remove from resource
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Resource\":{\"Database\":{\"Name\":\"" + database + "\"}},\"LFTags\":[{\"TagKey\":\"classification\",\"TagValues\":[\"confidential\"]}]}")
        .when()
            .post("/RemoveLFTagsFromResource")
        .then()
            .statusCode(200)
            .body("Failures", empty());
        post("GetResourceLFTags", "{\"Resource\":{\"Database\":{\"Name\":\"" + database + "\"}}}", 200)
                .body("LFTagOnDatabase", empty());
        post("DeleteLFTag", "{\"TagKey\":\"classification\"}", 200);
        glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}");
    }
}
