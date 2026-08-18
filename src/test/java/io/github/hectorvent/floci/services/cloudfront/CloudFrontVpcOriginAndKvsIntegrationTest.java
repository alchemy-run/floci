package io.github.hectorvent.floci.services.cloudfront;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudFrontVpcOriginAndKvsIntegrationTest {

    private static final String KVS_NAME = "alchemy-parity-kvs";
    private static String kvsEtag;
    private static String functionEtag;

    @Test
    @Order(1)
    void createVpcOriginRejectsMissingLoadBalancer() {
        given()
            .contentType("application/xml")
            .body("""
                    <CreateVpcOriginRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <VpcOriginEndpointConfig>
                        <Name>alchemy-vpc-origin-probe</Name>
                        <Arn>arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/does-not-exist/0000000000000000</Arn>
                        <HTTPPort>80</HTTPPort>
                        <HTTPSPort>443</HTTPSPort>
                        <OriginProtocolPolicy>https-only</OriginProtocolPolicy>
                      </VpcOriginEndpointConfig>
                    </CreateVpcOriginRequest>
                    """)
        .when()
            .post("/2020-05-31/vpc-origin")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));
    }

    @Test
    @Order(2)
    void listVpcOriginsIsEmpty() {
        given()
        .when()
            .get("/2020-05-31/vpc-origin")
        .then()
            .statusCode(200)
            .body(containsString("VpcOriginList"))
            .body(containsString("<Quantity>0</Quantity>"));
    }

    @Test
    @Order(3)
    void createDescribeListUpdateDeleteKeyValueStore() {
        String etag = given()
            .contentType("application/xml")
            .body("""
                    <CreateKeyValueStoreRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Name>%s</Name>
                      <Comment>list</Comment>
                    </CreateKeyValueStoreRequest>
                    """.formatted(KVS_NAME))
        .when()
            .post("/2020-05-31/key-value-store")
        .then()
            .statusCode(201)
            .header("ETag", org.hamcrest.Matchers.notNullValue())
            .body(containsString("<Name>" + KVS_NAME + "</Name>"))
            .body(containsString("<Status>READY</Status>"))
            .extract()
            .header("ETag");
        kvsEtag = etag;

        given()
        .when()
            .get("/2020-05-31/key-value-store/" + KVS_NAME)
        .then()
            .statusCode(200)
            .header("ETag", etag)
            .body(containsString("<Name>" + KVS_NAME + "</Name>"));

        given()
        .when()
            .get("/2020-05-31/key-value-store")
        .then()
            .statusCode(200)
            .body(containsString("<Name>" + KVS_NAME + "</Name>"));

        String updated = given()
            .contentType("application/xml")
            .header("If-Match", etag)
            .body("<Comment>updated</Comment>")
        .when()
            .put("/2020-05-31/key-value-store/" + KVS_NAME)
        .then()
            .statusCode(200)
            .body(containsString("<Comment>updated</Comment>"))
            .extract()
            .header("ETag");
        kvsEtag = updated;
    }

    @Test
    @Order(4)
    void createFunctionWithKeyValueStoreAssociation() {
        functionEtag = given()
            .contentType("application/xml")
            .body("""
                    <CreateFunctionRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Name>alchemy-parity-fn</Name>
                      <FunctionConfig>
                        <Comment>request handler</Comment>
                        <Runtime>cloudfront-js-2.0</Runtime>
                        <KeyValueStoreAssociations>
                          <Quantity>1</Quantity>
                          <Items>
                            <KeyValueStoreAssociation>
                              <KeyValueStoreARN>arn:aws:cloudfront::000000000000:key-value-store/abc</KeyValueStoreARN>
                            </KeyValueStoreAssociation>
                          </Items>
                        </KeyValueStoreAssociations>
                      </FunctionConfig>
                      <FunctionCode>async function handler(event) { return event.request; }</FunctionCode>
                    </CreateFunctionRequest>
                    """)
        .when()
            .post("/2020-05-31/function")
        .then()
            .statusCode(201)
            .body(containsString("KeyValueStoreARN"))
            .body(containsString("arn:aws:cloudfront::000000000000:key-value-store/abc"))
            .extract()
            .header("ETag");

        given()
        .when()
            .get("/2020-05-31/function/alchemy-parity-fn/describe")
        .then()
            .statusCode(200)
            .body(containsString("<Name>alchemy-parity-fn</Name>"))
            .body(containsString("arn:aws:cloudfront::000000000000:key-value-store/abc"));
    }

    @Test
    @Order(5)
    void cleanup() {
        given()
            .header("If-Match", functionEtag)
        .when()
            .delete("/2020-05-31/function/alchemy-parity-fn")
        .then()
            .statusCode(204);

        given()
            .header("If-Match", kvsEtag)
        .when()
            .delete("/2020-05-31/key-value-store/" + KVS_NAME)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/2020-05-31/key-value-store/" + KVS_NAME)
        .then()
            .statusCode(404)
            .body(containsString("EntityNotFound"));
    }
}
