package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GetEffectivePermissionsForPath and the GetDataAccess credential-vending operations,
 * driven against real registrations, grants, data lake settings and Glue metadata. Runs in
 * its own region so its data lake settings never leak into other Lake Formation tests.
 */
@QuarkusTest
class LakeFormationDataAccessIntegrationTest {

    private static final String REGION = "eu-west-3";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20260922/" + REGION
            + "/lakeformation/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy";

    @Inject
    IamService iamService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void effectivePermissionsForPathReflectRegisteredLocations() {
        String account = account();
        String bucket = "lf-effective-bucket";
        String database = "lf_effective_db";
        post("GetEffectivePermissionsForPath", "{\"ResourceArn\":\"arn:aws:s3:::lf-effective-unregistered\"}", 200)
                .body("Permissions", empty());
        post("GetEffectivePermissionsForPath", "{\"ResourceArn\":\"not-an-arn\"}", 400)
                .body("__type", equalTo("InvalidInputException"));
        post("GetEffectivePermissionsForPath", "{}", 400).body("__type", equalTo("InvalidInputException"));

        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        createTable(database, "orders", "s3://" + bucket + "/orders", false);
        createTable(database, "other", "s3://" + bucket + "/other", false);
        register(bucket, account);
        try {
            grant(account, "{\"Table\":{\"DatabaseName\":\"" + database + "\",\"Name\":\"orders\"}}", "SELECT");
            grant(account, "{\"Table\":{\"DatabaseName\":\"" + database + "\",\"Name\":\"other\"}}", "SELECT");
            grant(account, "{\"DataLocation\":{\"ResourceArn\":\"arn:aws:s3:::" + bucket + "\"}}",
                    "DATA_LOCATION_ACCESS");

            post("GetEffectivePermissionsForPath", "{\"ResourceArn\":\"arn:aws:s3:::" + bucket + "/orders\"}", 200)
                    .body("Permissions", hasSize(2))
                    .body("Permissions.Resource.Table.Name", hasItem("orders"))
                    .body("Permissions.Resource.DataLocation.ResourceArn", hasItem("arn:aws:s3:::" + bucket));
            String token = post("GetEffectivePermissionsForPath", "{\"ResourceArn\":\"arn:aws:s3:::" + bucket
                    + "\",\"MaxResults\":2}", 200)
                    .body("Permissions", hasSize(2))
                    .extract().path("NextToken");
            post("GetEffectivePermissionsForPath", "{\"ResourceArn\":\"arn:aws:s3:::" + bucket
                    + "\",\"MaxResults\":2,\"NextToken\":\"" + token + "\"}", 200)
                    .body("Permissions", hasSize(1));
        } finally {
            post("DeregisterResource", "{\"ResourceArn\":\"arn:aws:s3:::" + bucket + "\"}", 200);
            glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}");
        }
    }

    @Test
    void tableCredentialsRequireRegistrationGrantsAndSettings() {
        String account = account();
        String bucket = "lf-table-cred-bucket";
        String database = "lf_table_cred_db";
        String tableArn = "arn:aws:glue:" + REGION + ":" + account + ":table/" + database + "/sales";
        String request = "{\"TableArn\":\"" + tableArn + "\",\"Permissions\":[\"SELECT\"],"
                + "\"SupportedPermissionTypes\":[\"COLUMN_PERMISSION\"]}";

        post("GetTemporaryGlueTableCredentials", "{\"TableArn\":\"arn:aws:glue:" + REGION
                + ":123456789013:table/missing_db/missing_table\",\"Permissions\":[\"SELECT\"]}", 400)
                .body("__type", equalTo(account.equals("123456789013")
                        ? "EntityNotFoundException" : "AccessDeniedException"));
        post("GetTemporaryGlueTableCredentials", "{\"TableArn\":\"arn:aws:glue:" + REGION + ":" + account
                + ":table/missing_db/missing_table\"}", 400).body("__type", equalTo("EntityNotFoundException"));
        post("GetTemporaryGlueTableCredentials", "{\"TableArn\":\"garbage\"}", 400)
                .body("__type", equalTo("InvalidInputException"));

        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        createTable(database, "sales", "s3://" + bucket + "/sales", false);
        createTable(database, "events", "s3://" + bucket + "/events", false);
        try {
            post("GetTemporaryGlueTableCredentials", request, 400).body("__type", equalTo("EntityNotFoundException"));
            register(bucket, account);
            post("GetTemporaryGlueTableCredentials", request, 400).body("__type", equalTo("AccessDeniedException"));
            grant(account, "{\"Table\":{\"DatabaseName\":\"" + database + "\",\"Name\":\"sales\"}}", "SELECT");
            // Full-table vending is off until the data lake settings allow it.
            post("GetTemporaryGlueTableCredentials", request, 400).body("__type", equalTo("AccessDeniedException"));
            post("PutDataLakeSettings", "{\"DataLakeSettings\":{\"AllowFullTableExternalDataAccess\":true}}", 200);
            post("GetTemporaryGlueTableCredentials", "{\"TableArn\":\"" + tableArn
                    + "\",\"DurationSeconds\":60}", 400).body("__type", equalTo("InvalidInputException"));

            String accessKeyId = post("GetTemporaryGlueTableCredentials", request, 200)
                    .body("AccessKeyId", startsWith("ASIA"))
                    .body("SecretAccessKey", notNullValue())
                    .body("SessionToken", notNullValue())
                    .body("Expiration", notNullValue())
                    .body("VendedS3Path", contains("s3://" + bucket + "/sales"))
                    .extract().path("AccessKeyId");
            // The vended credentials are a live session for the registration role.
            assertEquals("arn:aws:sts::" + account + ":assumed-role/lf-data-access/floci-session",
                    iamService.resolveCallerArn(accessKeyId).orElseThrow());
            post("GetTemporaryGlueTableCredentials", "{\"TableArn\":\"" + tableArn
                    + "\",\"S3Path\":\"s3://" + bucket + "/events\"}", 400)
                    .body("__type", equalTo("InvalidInputException"));
            post("GetTemporaryGlueTableCredentials", "{\"TableArn\":\"" + tableArn
                    + "\",\"Permissions\":[\"INSERT\"]}", 400).body("__type", equalTo("AccessDeniedException"));

            // A column-restricted grant needs COLUMN_PERMISSION in SupportedPermissionTypes.
            grant(account, "{\"TableWithColumns\":{\"DatabaseName\":\"" + database
                    + "\",\"Name\":\"events\",\"ColumnNames\":[\"id\"]}}", "SELECT");
            String eventsArn = "arn:aws:glue:" + REGION + ":" + account + ":table/" + database + "/events";
            post("GetTemporaryGlueTableCredentials", "{\"TableArn\":\"" + eventsArn
                    + "\",\"SupportedPermissionTypes\":[\"CELL_FILTER_PERMISSION\"]}", 400)
                    .body("__type", equalTo("PermissionTypeMismatchException"));
            post("GetTemporaryGlueTableCredentials", "{\"TableArn\":\"" + eventsArn
                    + "\",\"SupportedPermissionTypes\":[\"COLUMN_PERMISSION\"]}", 400)
                    .body("__type", equalTo("AccessDeniedException"));
        } finally {
            post("PutDataLakeSettings", "{\"DataLakeSettings\":{}}", 200);
            post("DeregisterResource", "{\"ResourceArn\":\"arn:aws:s3:::" + bucket + "\"}", 200);
            glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}");
        }
    }

    @Test
    void partitionCredentialsResolveThePartitionLocation() {
        String account = account();
        String bucket = "lf-partition-cred-bucket";
        String database = "lf_partition_cred_db";
        String tableArn = "arn:aws:glue:" + REGION + ":" + account + ":table/" + database + "/logs";
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        createTable(database, "logs", "s3://" + bucket + "/logs", true);
        glue("CreatePartition", "{\"DatabaseName\":\"" + database + "\",\"TableName\":\"logs\","
                + "\"PartitionInput\":{\"Values\":[\"2026\"],\"StorageDescriptor\":{\"Location\":\"s3://"
                + bucket + "/logs/year=2026\"}}}");
        register(bucket, account);
        try {
            grant(account, "{\"Table\":{\"DatabaseName\":\"" + database + "\",\"Name\":\"logs\"}}", "SELECT");
            post("PutDataLakeSettings", "{\"DataLakeSettings\":{\"AllowFullTableExternalDataAccess\":true}}", 200);
            post("GetTemporaryGluePartitionCredentials", "{\"TableArn\":\"" + tableArn
                    + "\",\"Partition\":{\"Values\":[\"2026\"]},\"Permissions\":[\"SELECT\"]}", 200)
                    .body("AccessKeyId", startsWith("ASIA"));
            post("GetTemporaryGluePartitionCredentials", "{\"TableArn\":\"" + tableArn
                    + "\",\"Partition\":{\"Values\":[\"1999\"]}}", 400)
                    .body("__type", equalTo("EntityNotFoundException"));
            post("GetTemporaryGluePartitionCredentials", "{\"TableArn\":\"" + tableArn
                    + "\",\"Partition\":{\"Values\":[\"2026\",\"01\"]}}", 400)
                    .body("__type", equalTo("InvalidInputException"));
            post("GetTemporaryGluePartitionCredentials", "{\"TableArn\":\"" + tableArn + "\"}", 400)
                    .body("__type", equalTo("InvalidInputException"));
        } finally {
            post("PutDataLakeSettings", "{\"DataLakeSettings\":{}}", 200);
            post("DeregisterResource", "{\"ResourceArn\":\"arn:aws:s3:::" + bucket + "\"}", 200);
            glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}");
        }
    }

    @Test
    void dataLocationCredentialsRequireRegisteredTableAccess() {
        String account = account();
        String bucket = "lf-location-cred-bucket";
        String database = "lf_location_cred_db";
        String location = "arn:aws:s3:::" + bucket + "/clicks";
        String read = "{\"DataLocations\":[\"" + location + "\"],\"CredentialsScope\":\"READ\"}";
        post("GetTemporaryDataLocationCredentials", read, 400).body("__type", equalTo("EntityNotFoundException"));
        post("GetTemporaryDataLocationCredentials", "{}", 400).body("__type", equalTo("InvalidInputException"));
        post("GetTemporaryDataLocationCredentials", "{\"DataLocations\":[\"" + location
                + "\"],\"CredentialsScope\":\"ADMIN\"}", 400).body("__type", equalTo("InvalidInputException"));

        register(bucket, account);
        glue("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + database + "\"}}");
        try {
            post("GetTemporaryDataLocationCredentials", read, 400).body("__type", equalTo("EntityNotFoundException"));
            createTable(database, "clicks", "s3://" + bucket + "/clicks", false);
            post("GetTemporaryDataLocationCredentials", read, 400).body("__type", equalTo("AccessDeniedException"));
            grant(account, "{\"Table\":{\"DatabaseName\":\"" + database + "\",\"Name\":\"clicks\"}}", "SELECT");
            post("GetTemporaryDataLocationCredentials", read, 400).body("__type", equalTo("AccessDeniedException"));
            post("PutDataLakeSettings", "{\"DataLakeSettings\":{\"AllowFullTableExternalDataAccess\":true}}", 200);
            post("GetTemporaryDataLocationCredentials", read, 200)
                    .body("Credentials.AccessKeyId", startsWith("ASIA"))
                    .body("AccessibleDataLocations", contains(location))
                    .body("CredentialsScope", equalTo("READ"));
            post("GetTemporaryDataLocationCredentials", "{\"DataLocations\":[\"" + location
                    + "\"],\"CredentialsScope\":\"READWRITE\"}", 400)
                    .body("__type", equalTo("AccessDeniedException"));
        } finally {
            post("PutDataLakeSettings", "{\"DataLakeSettings\":{}}", 200);
            post("DeregisterResource", "{\"ResourceArn\":\"arn:aws:s3:::" + bucket + "\"}", 200);
            glue("DeleteDatabase", "{\"Name\":\"" + database + "\"}");
        }
    }

    private String account() {
        String identity = post("GetDataLakePrincipal", "{}", 200).extract().path("Identity");
        return identity.split(":")[4];
    }

    private void register(String bucket, String account) {
        post("RegisterResource", "{\"ResourceArn\":\"arn:aws:s3:::" + bucket + "\",\"RoleArn\":\"arn:aws:iam::"
                + account + ":role/lf-data-access\"}", 200);
    }

    private void grant(String account, String resource, String permission) {
        post("GrantPermissions", "{\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::" + account
                + ":root\"},\"Resource\":" + resource + ",\"Permissions\":[\"" + permission + "\"]}", 200);
    }

    private void createTable(String database, String name, String location, boolean partitioned) {
        glue("CreateTable", "{\"DatabaseName\":\"" + database + "\",\"TableInput\":{\"Name\":\"" + name + "\","
                + "\"StorageDescriptor\":{\"Location\":\"" + location + "\",\"Columns\":[{\"Name\":\"id\","
                + "\"Type\":\"string\"},{\"Name\":\"value\",\"Type\":\"string\"}]}"
                + (partitioned ? ",\"PartitionKeys\":[{\"Name\":\"year\",\"Type\":\"string\"}]" : "") + "}}");
    }

    private ValidatableResponse post(String operation, String body, int status) {
        return given().contentType("application/json").header("Authorization", AUTH).body(body)
                .post("/" + operation).then().statusCode(status);
    }

    private void glue(String operation, String body) {
        given().contentType("application/x-amz-json-1.1")
                .header("Authorization", AUTH.replace("/lakeformation/", "/glue/"))
                .header("X-Amz-Target", "AWSGlue." + operation).body(body)
                .post("/").then().statusCode(200);
    }
}
