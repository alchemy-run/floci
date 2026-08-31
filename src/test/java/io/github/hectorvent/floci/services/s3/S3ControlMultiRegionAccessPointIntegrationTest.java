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
 * S3 Control Multi-Region Access Point REST-XML: Get, Create, List, Delete.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ControlMultiRegionAccessPointIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String BUCKET = "s3control-mrap-it-bucket";
    private static final String NAME = "s3control-mrap-it";

    @Test
    @Order(1)
    @DisplayName("GetMultiRegionAccessPoint on a missing name returns NoSuchMultiRegionAccessPoint")
    void getMissingReturnsNoSuchMultiRegionAccessPoint() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/mrap/instances/alchemy-does-not-exist-xyz")
        .then()
            .statusCode(404)
            .contentType(containsString("xml"))
            .body(containsString("<Code>NoSuchMultiRegionAccessPoint</Code>"));
    }

    @Test
    @Order(2)
    @DisplayName("create, get READY, list, delete multi-region access point")
    void createGetListDeleteMultiRegionAccessPoint() {
        given().when().put("/" + BUCKET).then().statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <CreateMultiRegionAccessPointRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                      <ClientToken>mrap-create-token</ClientToken>
                      <Details>
                        <Name>%s</Name>
                        <Regions>
                          <Region>
                            <Bucket>%s</Bucket>
                          </Region>
                        </Regions>
                      </Details>
                    </CreateMultiRegionAccessPointRequest>
                    """.formatted(NAME, BUCKET))
        .when()
            .post("/v20180820/async-requests/mrap/create")
        .then()
            .statusCode(200)
            .body(containsString("<RequestTokenARN>"))
            .body(containsString(":async-request/mrap/create/"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/mrap/instances/" + NAME)
        .then()
            .statusCode(200)
            .body(containsString("<Name>" + NAME + "</Name>"))
            .body(containsString("<Status>READY</Status>"))
            .body(containsString("<Alias>"))
            .body(containsString(".mrap</Alias>"))
            .body(containsString("<Bucket>" + BUCKET + "</Bucket>"))
            .body(containsString("<BlockPublicAcls>true</BlockPublicAcls>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/mrap/instances")
        .then()
            .statusCode(200)
            .body(containsString("<Name>" + NAME + "</Name>"))
            .body(containsString("<Bucket>" + BUCKET + "</Bucket>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <DeleteMultiRegionAccessPointRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                      <ClientToken>mrap-delete-token</ClientToken>
                      <Details>
                        <Name>%s</Name>
                      </Details>
                    </DeleteMultiRegionAccessPointRequest>
                    """.formatted(NAME))
        .when()
            .post("/v20180820/async-requests/mrap/delete")
        .then()
            .statusCode(200)
            .body(containsString("<RequestTokenARN>"))
            .body(containsString(":async-request/mrap/delete/"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/mrap/instances/" + NAME)
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchMultiRegionAccessPoint</Code>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/mrap/instances")
        .then()
            .statusCode(200)
            .body(not(containsString("<Name>" + NAME + "</Name>")));
    }

    @Test
    @Order(3)
    @DisplayName("DeleteMultiRegionAccessPoint on a missing name returns NoSuchMultiRegionAccessPoint")
    void deleteMissingReturnsNoSuchMultiRegionAccessPoint() {
        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <DeleteMultiRegionAccessPointRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                      <ClientToken>mrap-delete-missing</ClientToken>
                      <Details>
                        <Name>alchemy-does-not-exist-xyz</Name>
                      </Details>
                    </DeleteMultiRegionAccessPointRequest>
                    """)
        .when()
            .post("/v20180820/async-requests/mrap/delete")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchMultiRegionAccessPoint</Code>"));
    }
}
