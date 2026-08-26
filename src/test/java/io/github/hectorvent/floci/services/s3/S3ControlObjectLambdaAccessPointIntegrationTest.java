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
 * S3 Control Object Lambda Access Point REST-XML: Get/Create/Delete
 * AccessPointForObjectLambda and Get/Put configuration.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ControlObjectLambdaAccessPointIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "s3control-olap-it-bucket";
    private static final String SUPPORTING = "s3control-olap-it-ap";
    private static final String NAME = "s3control-olap-it";
    private static final String SUPPORTING_ARN =
            "arn:aws:s3:" + REGION + ":" + ACCOUNT + ":accesspoint/" + SUPPORTING;
    private static final String LAMBDA_ARN =
            "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:olap-transform";

    private static String configurationXml(boolean metricsEnabled) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateAccessPointForObjectLambdaRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                  <Configuration>
                    <SupportingAccessPoint>%s</SupportingAccessPoint>
                    <CloudWatchMetricsEnabled>%s</CloudWatchMetricsEnabled>
                    <TransformationConfigurations>
                      <TransformationConfiguration>
                        <Actions>
                          <Action>GetObject</Action>
                        </Actions>
                        <ContentTransformation>
                          <AwsLambda>
                            <FunctionArn>%s</FunctionArn>
                          </AwsLambda>
                        </ContentTransformation>
                      </TransformationConfiguration>
                    </TransformationConfigurations>
                  </Configuration>
                </CreateAccessPointForObjectLambdaRequest>
                """.formatted(SUPPORTING_ARN, metricsEnabled, LAMBDA_ARN);
    }

    @Test
    @Order(1)
    @DisplayName("GetAccessPointForObjectLambda on a missing name returns NoSuchAccessPoint")
    void getObjectLambdaAccessPointMissingReturnsNoSuchAccessPoint() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspointforobjectlambda/alchemy-does-not-exist-xyz")
        .then()
            .statusCode(404)
            .contentType(containsString("xml"))
            .body(containsString("<Code>NoSuchAccessPoint</Code>"));
    }

    @Test
    @Order(2)
    @DisplayName("CreateAccessPointForObjectLambda without a supporting AP returns NoSuchAccessPoint")
    void createObjectLambdaMissingSupportingAccessPoint() {
        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <CreateAccessPointForObjectLambdaRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                      <Configuration>
                        <SupportingAccessPoint>arn:aws:s3:us-west-2:391965393224:accesspoint/alchemy-does-not-exist-xyz</SupportingAccessPoint>
                        <TransformationConfigurations>
                          <TransformationConfiguration>
                            <Actions><Action>GetObject</Action></Actions>
                            <ContentTransformation>
                              <AwsLambda>
                                <FunctionArn>arn:aws:lambda:us-west-2:391965393224:function:does-not-exist</FunctionArn>
                              </AwsLambda>
                            </ContentTransformation>
                          </TransformationConfiguration>
                        </TransformationConfigurations>
                      </Configuration>
                    </CreateAccessPointForObjectLambdaRequest>
                    """)
        .when()
            .put("/v20180820/accesspointforobjectlambda/alchemy-olap-entitlement-probe")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchAccessPoint</Code>"));
    }

    @Test
    @Order(3)
    @DisplayName("create, get, update configuration, list, delete object lambda access point")
    void createGetUpdateListDeleteObjectLambdaAccessPoint() {
        given().when().put("/" + BUCKET).then().statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <CreateAccessPointRequest xmlns="http://awss3control.amazonaws.com/doc/2018-08-20/">
                      <Bucket>%s</Bucket>
                    </CreateAccessPointRequest>
                    """.formatted(BUCKET))
        .when()
            .put("/v20180820/accesspoint/" + SUPPORTING)
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(configurationXml(false))
        .when()
            .put("/v20180820/accesspointforobjectlambda/" + NAME)
        .then()
            .statusCode(200)
            .body(containsString("<ObjectLambdaAccessPointArn>"))
            .body(containsString(":s3-object-lambda:"))
            .body(containsString(":accesspoint/" + NAME))
            .body(containsString("<Alias>"))
            .body(containsString("<Status>READY</Status>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspointforobjectlambda/" + NAME)
        .then()
            .statusCode(200)
            .body(containsString("<Name>" + NAME + "</Name>"))
            .body(containsString("<BlockPublicAcls>true</BlockPublicAcls>"))
            .body(containsString("<Alias>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspointforobjectlambda/" + NAME + "/configuration")
        .then()
            .statusCode(200)
            .body(containsString("<SupportingAccessPoint>" + SUPPORTING_ARN + "</SupportingAccessPoint>"))
            .body(containsString("<CloudWatchMetricsEnabled>false</CloudWatchMetricsEnabled>"))
            .body(containsString("<Action>GetObject</Action>"))
            .body(containsString("<FunctionArn>" + LAMBDA_ARN + "</FunctionArn>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(configurationXml(true))
        .when()
            .put("/v20180820/accesspointforobjectlambda/" + NAME + "/configuration")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspointforobjectlambda/" + NAME + "/configuration")
        .then()
            .statusCode(200)
            .body(containsString("<CloudWatchMetricsEnabled>true</CloudWatchMetricsEnabled>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspointforobjectlambda")
        .then()
            .statusCode(200)
            .body(containsString("<Name>" + NAME + "</Name>"))
            .body(containsString(":s3-object-lambda:"));

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(configurationXml(false))
        .when()
            .put("/v20180820/accesspointforobjectlambda/" + NAME)
        .then()
            .statusCode(409)
            .body(containsString("<Code>AccessPointAlreadyOwnedByYou</Code>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .delete("/v20180820/accesspointforobjectlambda/" + NAME)
        .then()
            .statusCode(200);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspointforobjectlambda/" + NAME)
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchAccessPoint</Code>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/accesspointforobjectlambda")
        .then()
            .statusCode(200)
            .body(not(containsString("<Name>" + NAME + "</Name>")));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .delete("/v20180820/accesspoint/" + SUPPORTING)
        .then()
            .statusCode(200);
    }
}
