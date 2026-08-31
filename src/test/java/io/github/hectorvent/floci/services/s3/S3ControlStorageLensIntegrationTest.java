package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * S3 Control Storage Lens REST-XML: Put/Get/DeleteStorageLensConfiguration
 * and configuration tagging.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ControlStorageLensIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CONFIG_ID = "s3control-lens-it";

    private static final String DEFAULT_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PutStorageLensConfigurationRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
              <StorageLensConfiguration>
                <Id>%s</Id>
                <AccountLevel>
                  <BucketLevel></BucketLevel>
                </AccountLevel>
                <IsEnabled>true</IsEnabled>
              </StorageLensConfiguration>
            </PutStorageLensConfigurationRequest>
            """.formatted(CONFIG_ID);

    private static final String UPDATED_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PutStorageLensConfigurationRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
              <StorageLensConfiguration>
                <Id>%s</Id>
                <AccountLevel>
                  <ActivityMetrics>
                    <IsEnabled>true</IsEnabled>
                  </ActivityMetrics>
                  <BucketLevel>
                    <ActivityMetrics>
                      <IsEnabled>true</IsEnabled>
                    </ActivityMetrics>
                  </BucketLevel>
                </AccountLevel>
                <IsEnabled>false</IsEnabled>
              </StorageLensConfiguration>
            </PutStorageLensConfigurationRequest>
            """.formatted(CONFIG_ID);

    @Test
    @Order(1)
    @DisplayName("GetStorageLensConfiguration on a missing id returns NoSuchConfiguration")
    void getMissingReturnsNoSuchConfiguration() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/storagelens/alchemy-does-not-exist-xyz")
        .then()
            .statusCode(404)
            .contentType(containsString("xml"))
            .body(containsString("<Code>NoSuchConfiguration</Code>"));
    }

    @Test
    @Order(2)
    @DisplayName("PutStorageLensConfiguration rejects account activity metrics without bucket-level")
    void putMissingBucketLevelActivityMetrics() {
        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <PutStorageLensConfigurationRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                      <StorageLensConfiguration>
                        <Id>lens-missing-bucket-activity</Id>
                        <AccountLevel>
                          <ActivityMetrics>
                            <IsEnabled>true</IsEnabled>
                          </ActivityMetrics>
                          <BucketLevel></BucketLevel>
                        </AccountLevel>
                        <IsEnabled>true</IsEnabled>
                      </StorageLensConfiguration>
                    </PutStorageLensConfigurationRequest>
                    """)
        .when()
            .put("/v20180820/storagelens/lens-missing-bucket-activity")
        .then()
            .statusCode(400)
            .body(containsString("<Code>MissingBucketLevelActivityMetrics</Code>"));
    }

    @Test
    @Order(3)
    @DisplayName("create, get, tag, update, list, delete storage lens configuration")
    void createGetTagUpdateDelete() {
        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(DEFAULT_BODY)
        .when()
            .put("/v20180820/storagelens/" + CONFIG_ID)
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/storagelens/" + CONFIG_ID)
        .then()
            .statusCode(200)
            .body(containsString("<Id>" + CONFIG_ID + "</Id>"))
            .body(containsString("<IsEnabled>true</IsEnabled>"))
            .body(containsString("<AccountLevel>"))
            .body(containsString(":storage-lens/" + CONFIG_ID));

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <PutStorageLensConfigurationTaggingRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                      <Tags>
                        <Tag><Key>Environment</Key><Value>test</Value></Tag>
                        <Tag><Key>alchemy::id</Key><Value>TestLens</Value></Tag>
                      </Tags>
                    </PutStorageLensConfigurationTaggingRequest>
                    """)
        .when()
            .put("/v20180820/storagelens/" + CONFIG_ID + "/tagging")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/storagelens/" + CONFIG_ID + "/tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>Environment</Key>"))
            .body(containsString("<Value>test</Value>"))
            .body(containsString("<Key>alchemy::id</Key>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(UPDATED_BODY)
        .when()
            .put("/v20180820/storagelens/" + CONFIG_ID)
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/storagelens/" + CONFIG_ID)
        .then()
            .statusCode(200)
            .body(containsString("<IsEnabled>false</IsEnabled>"))
            .body(containsString("<ActivityMetrics>"))
            .body(containsString("<IsEnabled>true</IsEnabled>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <PutStorageLensConfigurationTaggingRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                      <Tags>
                        <Tag><Key>Environment</Key><Value>production</Value></Tag>
                        <Tag><Key>alchemy::id</Key><Value>TestLens</Value></Tag>
                      </Tags>
                    </PutStorageLensConfigurationTaggingRequest>
                    """)
        .when()
            .put("/v20180820/storagelens/" + CONFIG_ID + "/tagging")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/storagelens/" + CONFIG_ID + "/tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Value>production</Value>"))
            .body(not(containsString("<Value>test</Value>")));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/storagelens")
        .then()
            .statusCode(200)
            .body(containsString("<Id>" + CONFIG_ID + "</Id>"))
            .body(containsString(":storage-lens/" + CONFIG_ID));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .delete("/v20180820/storagelens/" + CONFIG_ID)
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/storagelens/" + CONFIG_ID)
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchConfiguration</Code>"));
    }

    @Test
    @Order(4)
    @DisplayName("GetStorageLensConfigurationTagging on a missing id returns NoSuchConfiguration")
    void getTaggingMissingReturnsNoSuchConfiguration() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/storagelens/alchemy-does-not-exist-xyz/tagging")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchConfiguration</Code>"));
    }
}
