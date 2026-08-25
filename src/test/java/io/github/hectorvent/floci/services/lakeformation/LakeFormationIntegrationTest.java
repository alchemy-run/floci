package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Verifies Lake Formation restJson1 settings, grants, LF-tags, filters,
 * opt-ins, and S3 location registration — the operations Alchemy
 * {@code LakeFormation/*.test.ts} drives.
 */
@QuarkusTest
class LakeFormationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE =
            "arn:aws:iam::000000000000:role/alchemy-lf-analyst";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDataLakeSettingsOnAFreshCatalogReturnsDefaultsThenPutAddsAdmins() {
        String authorization = auth(EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetDataLakeSettings")
                .then()
                .statusCode(200)
                .body("DataLakeSettings.DataLakeAdmins", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "DataLakeSettings": {
                            "DataLakeAdmins": [
                              {"DataLakePrincipalIdentifier":"%s"}
                            ]
                          }
                        }
                        """.formatted(ROLE))
                .when()
                .post("/PutDataLakeSettings")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetDataLakeSettings")
                .then()
                .statusCode(200)
                .body("DataLakeSettings.DataLakeAdmins[0].DataLakePrincipalIdentifier", equalTo(ROLE));
    }

    @Test
    void grantListUpdateAndRevokeDatabasePermissions() {
        String authorization = auth(EAST);
        String database = "lf-db-permissions";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Principal":{"DataLakePrincipalIdentifier":"%s"},
                          "Resource":{"Database":{"Name":"%s"}},
                          "Permissions":["CREATE_TABLE","DESCRIBE"]
                        }
                        """.formatted(ROLE, database))
                .when()
                .post("/GrantPermissions")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Resource":{"Database":{"Name":"%s"}}}
                        """.formatted(database))
                .when()
                .post("/ListPermissions")
                .then()
                .statusCode(200)
                .body("PrincipalResourcePermissions", hasSize(1))
                .body("PrincipalResourcePermissions[0].Permissions", hasItems("CREATE_TABLE", "DESCRIBE"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Principal":{"DataLakePrincipalIdentifier":"%s"},
                          "Resource":{"Database":{"Name":"%s"}},
                          "Permissions":["CREATE_TABLE"]
                        }
                        """.formatted(ROLE, database))
                .when()
                .post("/RevokePermissions")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Principal":{"DataLakePrincipalIdentifier":"%s"},
                          "Resource":{"Database":{"Name":"%s"}},
                          "Permissions":["ALTER","DESCRIBE"]
                        }
                        """.formatted(ROLE, database))
                .when()
                .post("/GrantPermissions")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Resource":{"Database":{"Name":"%s"}}}
                        """.formatted(database))
                .when()
                .post("/ListPermissions")
                .then()
                .statusCode(200)
                .body("PrincipalResourcePermissions[0].Permissions", hasItems("ALTER", "DESCRIBE"))
                .body("PrincipalResourcePermissions[0].Permissions", not(hasItems("CREATE_TABLE")));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Principal":{"DataLakePrincipalIdentifier":"%s"},
                          "Resource":{"Database":{"Name":"%s"}},
                          "Permissions":["ALTER","DESCRIBE"]
                        }
                        """.formatted(ROLE, database))
                .when()
                .post("/RevokePermissions")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Resource":{"Database":{"Name":"%s"}}}
                        """.formatted(database))
                .when()
                .post("/ListPermissions")
                .then()
                .statusCode(200)
                .body("PrincipalResourcePermissions", hasSize(0));
    }

    @Test
    void lfTagLifecycleAndDatabaseAssociation() {
        String authorization = auth(EAST);
        String database = "lf-tag-db";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"TagKey":"alchemy-lf-env","TagValues":["dev","prod"]}
                        """)
                .when()
                .post("/CreateLFTag")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TagKey\":\"alchemy-lf-env\"}")
                .when()
                .post("/GetLFTag")
                .then()
                .statusCode(200)
                .body("TagKey", equalTo("alchemy-lf-env"))
                .body("TagValues", hasItems("dev", "prod"))
                .body("CatalogId", equalTo(ACCOUNT));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "TagKey":"alchemy-lf-env",
                          "TagValuesToAdd":["staging"]
                        }
                        """)
                .when()
                .post("/UpdateLFTag")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Resource":{"Database":{"Name":"%s"}},
                          "LFTags":[{"TagKey":"alchemy-lf-env","TagValues":["dev"]}]
                        }
                        """.formatted(database))
                .when()
                .post("/AddLFTagsToResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Resource":{"Database":{"Name":"%s"}}}
                        """.formatted(database))
                .when()
                .post("/GetResourceLFTags")
                .then()
                .statusCode(200)
                .body("LFTagOnDatabase[0].TagKey", equalTo("alchemy-lf-env"))
                .body("LFTagOnDatabase[0].TagValues", equalTo(java.util.List.of("dev")));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Resource":{"Database":{"Name":"%s"}},
                          "LFTags":[{"TagKey":"alchemy-lf-env","TagValues":["staging"]}]
                        }
                        """.formatted(database))
                .when()
                .post("/AddLFTagsToResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Resource":{"Database":{"Name":"%s"}},
                          "LFTags":[{"TagKey":"alchemy-lf-env"}]
                        }
                        """.formatted(database))
                .when()
                .post("/RemoveLFTagsFromResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TagKey\":\"alchemy-lf-env\"}")
                .when()
                .post("/DeleteLFTag")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TagKey\":\"alchemy-lf-env\"}")
                .when()
                .post("/GetLFTag")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("EntityNotFoundException"))
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void expressionFilterAndOptInLifecycle() {
        String authorization = auth(EAST);
        String database = "lf-filter-db";
        String table = "lf-filter-table";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"TagKey":"alchemy-lf-expr","TagValues":["a","b"]}
                        """)
                .when()
                .post("/CreateLFTag")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"alchemy-lf-expression",
                          "Description":"alchemy test expression",
                          "Expression":[{"TagKey":"alchemy-lf-expr","TagValues":["a"]}]
                        }
                        """)
                .when()
                .post("/CreateLFTagExpression")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"alchemy-lf-expression\"}")
                .when()
                .post("/GetLFTagExpression")
                .then()
                .statusCode(200)
                .body("Expression[0].TagKey", equalTo("alchemy-lf-expr"))
                .body("Expression[0].TagValues", equalTo(java.util.List.of("a")));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"alchemy-lf-expression",
                          "Expression":[{"TagKey":"alchemy-lf-expr","TagValues":["b"]}]
                        }
                        """)
                .when()
                .post("/UpdateLFTagExpression")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "TableData":{
                            "TableCatalogId":"%s",
                            "DatabaseName":"%s",
                            "TableName":"%s",
                            "Name":"alchemy-no-email",
                            "ColumnWildcard":{"ExcludedColumnNames":["email"]}
                          }
                        }
                        """.formatted(ACCOUNT, database, table))
                .when()
                .post("/CreateDataCellsFilter")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "TableCatalogId":"%s",
                          "DatabaseName":"%s",
                          "TableName":"%s",
                          "Name":"alchemy-no-email"
                        }
                        """.formatted(ACCOUNT, database, table))
                .when()
                .post("/GetDataCellsFilter")
                .then()
                .statusCode(200)
                .body("DataCellsFilter.ColumnWildcard.ExcludedColumnNames", equalTo(java.util.List.of("email")));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "TableData":{
                            "TableCatalogId":"%s",
                            "DatabaseName":"%s",
                            "TableName":"%s",
                            "Name":"alchemy-no-email",
                            "ColumnWildcard":{"ExcludedColumnNames":["email"]},
                            "RowFilter":{"FilterExpression":"id='x'"}
                          }
                        }
                        """.formatted(ACCOUNT, database, table))
                .when()
                .post("/UpdateDataCellsFilter")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Principal":{"DataLakePrincipalIdentifier":"%s"},
                          "Resource":{"Database":{"CatalogId":"%s","Name":"%s"}}
                        }
                        """.formatted(ROLE, ACCOUNT, database))
                .when()
                .post("/CreateLakeFormationOptIn")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Principal":{"DataLakePrincipalIdentifier":"%s"},
                          "Resource":{"Database":{"Name":"%s"}}
                        }
                        """.formatted(ROLE, database))
                .when()
                .post("/ListLakeFormationOptIns")
                .then()
                .statusCode(200)
                .body("LakeFormationOptInsInfoList", hasSize(1));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"alchemy-lf-expression\"}")
                .when()
                .post("/DeleteLFTagExpression")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"alchemy-lf-expression\"}")
                .when()
                .post("/GetLFTagExpression")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("EntityNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Principal":{"DataLakePrincipalIdentifier":"%s"},
                          "Resource":{"Database":{"CatalogId":"%s","Name":"%s"}}
                        }
                        """.formatted(ROLE, ACCOUNT, database))
                .when()
                .post("/DeleteLakeFormationOptIn")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Principal":{"DataLakePrincipalIdentifier":"%s"}}
                        """.formatted(ROLE))
                .when()
                .post("/ListLakeFormationOptIns")
                .then()
                .statusCode(200)
                .body("LakeFormationOptInsInfoList", hasSize(0));
    }

    @Test
    void describeResourceOnAMissingArnFailsWithEntityNotFoundException() {
        String arn = "arn:aws:s3:::lf-missing-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DescribeResource")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("EntityNotFoundException"))
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void registerDescribeUpdateListAndDeregisterS3Location() {
        String arn = "arn:aws:s3:::lf-it-" + UUID.randomUUID().toString().substring(0, 8);
        String authorization = auth(EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\",\"UseServiceLinkedRole\":true}")
                .when()
                .post("/RegisterResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DescribeResource")
                .then()
                .statusCode(200)
                .body("ResourceInfo.ResourceArn", equalTo(arn))
                .body("ResourceInfo.RoleArn", containsString("AWSServiceRoleForLakeFormationDataAccess"))
                .body("ResourceInfo.HybridAccessEnabled", equalTo(false));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DeregisterResource")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidInputException"))
                .body("message", containsString("Must manually delete service-linked role"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn
                        + "\",\"UseServiceLinkedRole\":true,\"HybridAccessEnabled\":true}")
                .when()
                .post("/RegisterResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DescribeResource")
                .then()
                .statusCode(200)
                .body("ResourceInfo.HybridAccessEnabled", equalTo(true));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/ListResources")
                .then()
                .statusCode(200)
                .body("ResourceInfoList.ResourceArn", hasItem(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DeregisterResource")
                .then()
                .statusCode(400);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DescribeResource")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void updateResourceOnCustomRoleSyncsHybridAccess() {
        String arn = "arn:aws:s3:::lf-it-role-" + UUID.randomUUID().toString().substring(0, 8);
        String role = "arn:aws:iam::000000000000:role/lf-data-access";
        String authorization = auth(EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"" + role + "\"}")
                .when()
                .post("/RegisterResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"" + role
                        + "\",\"HybridAccessEnabled\":true}")
                .when()
                .post("/UpdateResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DescribeResource")
                .then()
                .statusCode(200)
                .body("ResourceInfo.RoleArn", equalTo(role))
                .body("ResourceInfo.HybridAccessEnabled", equalTo(true));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DeregisterResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DescribeResource")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void updateResourceOnServiceLinkedRoleFails() {
        String arn = "arn:aws:s3:::lf-it-slr-" + UUID.randomUUID().toString().substring(0, 8);
        String authorization = auth(EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\",\"UseServiceLinkedRole\":true}")
                .when()
                .post("/RegisterResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn
                        + "\",\"RoleArn\":\"arn:aws:iam::000000000000:role/other\",\"HybridAccessEnabled\":true}")
                .when()
                .post("/UpdateResource")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("InvalidInputException"))
                .body("message", containsString("Service Linked Role"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + arn + "\"}")
                .when()
                .post("/DeregisterResource")
                .then()
                .statusCode(400);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260205/" + region
                + "/lakeformation/aws4_request";
    }
}
