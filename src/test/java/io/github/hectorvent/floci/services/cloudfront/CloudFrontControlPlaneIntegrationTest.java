package io.github.hectorvent.floci.services.cloudfront;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudFrontControlPlaneIntegrationTest {

    @Test
    void listCachePoliciesUsesCachePolicyListRoot() {
        given()
            .contentType("application/xml")
            .body("""
                    <CachePolicyConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Name>list-envelope-cache-policy</Name>
                      <Comment>list</Comment>
                      <MinTTL>0</MinTTL>
                      <DefaultTTL>60</DefaultTTL>
                      <MaxTTL>3600</MaxTTL>
                    </CachePolicyConfig>
                    """)
        .when()
            .post("/2020-05-31/cache-policy")
        .then()
            .statusCode(201);

        given()
        .when()
            .get("/2020-05-31/cache-policy?Type=custom")
        .then()
            .statusCode(200)
            .body(containsString("<CachePolicyList"))
            .body(containsString("<CachePolicySummary>"))
            .body(containsString("<Type>custom</Type>"))
            .body(containsString("<Name>list-envelope-cache-policy</Name>"))
            .body(not(containsString("ListCachePoliciesResult")));
    }

    @Test
    void keyGroupItemsAreFlatPublicKeyElements() {
        String publicKeyBody = given()
            .contentType("application/xml")
            .body("""
                    <PublicKeyConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <CallerReference>list-envelope-pk</CallerReference>
                      <Name>list-envelope-pk</Name>
                      <EncodedKey>-----BEGIN PUBLIC KEY-----MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A-----END PUBLIC KEY-----</EncodedKey>
                    </PublicKeyConfig>
                    """)
        .when()
            .post("/2020-05-31/public-key")
        .then()
            .statusCode(201)
            .extract()
            .body()
            .asString();
        String publicKeyId = publicKeyBody.replaceAll("(?s).*<Id>([^<]+)</Id>.*", "$1");

        String groupBody = given()
            .contentType("application/xml")
            .body("""
                    <KeyGroupConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Name>list-envelope-key-group</Name>
                      <Comment>initial</Comment>
                      <Items>
                        <PublicKey>%s</PublicKey>
                      </Items>
                    </KeyGroupConfig>
                    """.formatted(publicKeyId))
        .when()
            .post("/2020-05-31/key-group")
        .then()
            .statusCode(201)
            .body(containsString("<PublicKey>" + publicKeyId + "</PublicKey>"))
            .body(not(containsString("<Items><Quantity>")))
            .extract()
            .body()
            .asString();

        String groupId = groupBody.replaceAll("(?s).*<Id>([^<]+)</Id>.*", "$1");

        given()
        .when()
            .get("/2020-05-31/key-group/" + groupId)
        .then()
            .statusCode(200)
            .body(containsString("<PublicKey>" + publicKeyId + "</PublicKey>"))
            .body(not(containsString("<Items><Quantity>")));

        given()
        .when()
            .get("/2020-05-31/key-group")
        .then()
            .statusCode(200)
            .body(containsString("<KeyGroupList"))
            .body(containsString("<KeyGroupSummary>"))
            .body(not(containsString("ListKeyGroupsResult")));
    }

    @Test
    void realtimeLogConfigFieldsAndEndpointsAreFlatLists() {
        given()
            .contentType("application/xml")
            .body("""
                    <CreateRealtimeLogConfigRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Name>list-envelope-rtlc</Name>
                      <SamplingRate>100</SamplingRate>
                      <Fields>
                        <Field>timestamp</Field>
                        <Field>c-ip</Field>
                      </Fields>
                      <EndPoints>
                        <EndPoint>
                          <StreamType>Kinesis</StreamType>
                          <KinesisStreamConfig>
                            <RoleARN>arn:aws:iam::000000000000:role/log</RoleARN>
                            <StreamARN>arn:aws:kinesis:us-east-1:000000000000:stream/edge</StreamARN>
                          </KinesisStreamConfig>
                        </EndPoint>
                      </EndPoints>
                    </CreateRealtimeLogConfigRequest>
                    """)
        .when()
            .post("/2020-05-31/realtime-log-config")
        .then()
            .statusCode(201)
            .body(containsString("<Field>timestamp</Field>"))
            .body(containsString("<Field>c-ip</Field>"))
            .body(containsString("<StreamARN>arn:aws:kinesis:us-east-1:000000000000:stream/edge</StreamARN>"))
            .body(not(containsString("<Fields><Quantity>")));

        given()
            .contentType("application/xml")
            .body("<GetRealtimeLogConfigRequest><Name>list-envelope-rtlc</Name></GetRealtimeLogConfigRequest>")
        .when()
            .post("/2020-05-31/get-realtime-log-config")
        .then()
            .statusCode(200)
            .body(containsString("<Field>timestamp</Field>"))
            .body(containsString("<RoleARN>arn:aws:iam::000000000000:role/log</RoleARN>"));
    }

    @Test
    void publishedFunctionRemainsDescribableInDevelopmentAndDeletesWithThatEtag() {
        String etag = given()
            .contentType("application/xml")
            .body("""
                    <CreateFunctionRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                      <Name>list-envelope-fn</Name>
                      <FunctionConfig>
                        <Comment>list handler</Comment>
                        <Runtime>cloudfront-js-2.0</Runtime>
                      </FunctionConfig>
                      <FunctionCode>async function handler(event) { return event.request; }</FunctionCode>
                    </CreateFunctionRequest>
                    """)
        .when()
            .post("/2020-05-31/function")
        .then()
            .statusCode(201)
            .extract()
            .header("ETag");

        given()
            .header("If-Match", etag)
        .when()
            .post("/2020-05-31/function/list-envelope-fn/publish")
        .then()
            .statusCode(200)
            .body(containsString("<Stage>LIVE</Stage>"));

        given()
        .when()
            .get("/2020-05-31/function/list-envelope-fn/describe?Stage=DEVELOPMENT")
        .then()
            .statusCode(200)
            .body(containsString("<Stage>DEVELOPMENT</Stage>"));

        given()
        .when()
            .get("/2020-05-31/function")
        .then()
            .statusCode(200)
            .body(containsString("<FunctionList"))
            .body(containsString("<Name>list-envelope-fn</Name>"))
            .body(not(containsString("ListFunctionsResult")));

        given()
            .header("If-Match", etag)
        .when()
            .delete("/2020-05-31/function/list-envelope-fn")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/2020-05-31/function/list-envelope-fn/describe?Stage=LIVE")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchFunctionExists"));
    }
}
