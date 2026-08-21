package io.github.hectorvent.floci.services.cloudfront;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The emulated CloudFront edge: a distribution's viewer-request function runs
 * for real, reads the distribution's key value store, and its response is
 * served to the viewer.
 *
 * <p>The functions here return a response rather than falling through to an
 * origin, so the whole pipeline is covered without standing up an origin
 * server.
 */
@QuarkusTest
class CloudFrontEdgeIntegrationTest {

    private static final String NS = "http://cloudfront.amazonaws.com/doc/2020-05-31/";

    private record Function(String name, String arn, String developmentEtag) {}

    /** First occurrence — a distribution body repeats {@code <Id>} for each origin. */
    private static String firstElement(String xml, String element) {
        Matcher matcher = Pattern.compile("<" + element + ">([^<]*)</" + element + ">").matcher(xml);
        if (!matcher.find()) {
            throw new AssertionError("no <" + element + "> in: " + xml);
        }
        return matcher.group(1);
    }

    private String createKeyValueStore(String name, String key, String value) {
        ExtractableResponse<Response> created = given()
                .contentType("application/xml")
                .body("""
                        <KeyValueStoreConfig xmlns="%s">
                          <Name>%s</Name>
                          <Comment>edge test</Comment>
                        </KeyValueStoreConfig>
                        """.formatted(NS, name))
                .when()
                .post("/2020-05-31/key-value-store")
                .then()
                .statusCode(201)
                .extract();
        String arn = firstElement(created.body().asString(), "ARN");
        String etag = created.header("ETag");

        given()
                .contentType("application/json")
                .header("If-Match", etag)
                .body("{\"Value\":\"%s\"}".formatted(value))
                .when()
                .put("/key-value-stores/" + arn + "/keys/" + key)
                .then()
                .statusCode(200);
        return arn;
    }

    private Function createFunction(String name, String code, String kvsArn) {
        String encoded = Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8));
        ExtractableResponse<Response> created = given()
                .contentType("application/xml")
                .body("""
                        <CreateFunctionRequest xmlns="%s">
                          <Name>%s</Name>
                          <FunctionConfig>
                            <Comment>edge test</Comment>
                            <Runtime>cloudfront-js-2.0</Runtime>
                            <KeyValueStoreAssociations>
                              <Items>
                                <KeyValueStoreAssociation>
                                  <KeyValueStoreARN>%s</KeyValueStoreARN>
                                </KeyValueStoreAssociation>
                              </Items>
                            </KeyValueStoreAssociations>
                          </FunctionConfig>
                          <FunctionCode>%s</FunctionCode>
                        </CreateFunctionRequest>
                        """.formatted(NS, name, kvsArn, encoded))
                .when()
                .post("/2020-05-31/function")
                .then()
                .statusCode(201)
                .extract();
        String etag = created.header("ETag");
        String arn = firstElement(created.body().asString(), "FunctionARN");

        given()
                .header("If-Match", etag)
                .when()
                .post("/2020-05-31/function/" + name + "/publish")
                .then()
                .statusCode(200);
        return new Function(name, arn, etag);
    }

    private String createDistribution(String callerReference, String functionArn) {
        String body = given()
                .contentType("application/xml")
                .body("""
                        <DistributionConfig xmlns="%s">
                          <CallerReference>%s</CallerReference>
                          <Enabled>true</Enabled>
                          <Comment>edge test</Comment>
                          <Origins>
                            <Quantity>1</Quantity>
                            <Items>
                              <Origin>
                                <Id>default</Id>
                                <DomainName>placeholder.invalid</DomainName>
                                <CustomOriginConfig>
                                  <HTTPPort>80</HTTPPort>
                                  <HTTPSPort>443</HTTPSPort>
                                  <OriginProtocolPolicy>https-only</OriginProtocolPolicy>
                                </CustomOriginConfig>
                              </Origin>
                            </Items>
                          </Origins>
                          <DefaultCacheBehavior>
                            <TargetOriginId>default</TargetOriginId>
                            <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                            <FunctionAssociations>
                              <Quantity>1</Quantity>
                              <Items>
                                <FunctionAssociation>
                                  <FunctionARN>%s</FunctionARN>
                                  <EventType>viewer-request</EventType>
                                </FunctionAssociation>
                              </Items>
                            </FunctionAssociations>
                          </DefaultCacheBehavior>
                        </DistributionConfig>
                        """.formatted(NS, callerReference, functionArn))
                .when()
                .post("/2020-05-31/distribution")
                .then()
                .statusCode(201)
                // The association round-trips: it used to be dropped on parse
                // and always serialized as Quantity 0.
                .body(containsString("<EventType>viewer-request</EventType>"))
                .extract().body().asString();
        return firstElement(body, "Id");
    }

    @Test
    void servesTheViewerRequestFunctionResponse() {
        String kvsArn = createKeyValueStore("edge-response-kvs", "greeting", "hello");
        Function function = createFunction("edge-response-fn", """
                import cf from "cloudfront";
                async function handler(event) {
                  var greeting = await cf.kvs().get("greeting");
                  console.log("serving", event.request.uri);
                  return {
                    statusCode: 200,
                    statusDescription: "OK",
                    headers: { "content-type": { value: "text/plain" } },
                    body: { encoding: "text", data: greeting + " " + event.request.uri }
                  };
                }
                """, kvsArn);
        String distributionId = createDistribution("edge-response", function.arn());

        given()
                .when()
                .get("/_floci/cloudfront/" + distributionId + "/greet/me")
                .then()
                .statusCode(200)
                .body(equalTo("hello /greet/me"));
    }

    /**
     * A client addressing the distribution's own {@code *.cloudfront.net}
     * hostname reaches the edge, the same way the S3-website and execute-api
     * virtual-host filters work.
     */
    @Test
    void routesTheDistributionDomainName() {
        String kvsArn = createKeyValueStore("edge-host-kvs", "greeting", "by-host");
        Function function = createFunction("edge-host-fn", """
                import cf from "cloudfront";
                async function handler(event) {
                  var greeting = await cf.kvs().get("greeting");
                  return {
                    statusCode: 200,
                    headers: { "content-type": { value: "text/plain" } },
                    body: { encoding: "text", data: greeting + " " + event.request.headers.host.value }
                  };
                }
                """, kvsArn);
        String distributionId = createDistribution("edge-host", function.arn());
        String domainName = distributionId + ".cloudfront.net";

        given()
                .header("Host", domainName)
                .when()
                .get("/anything")
                .then()
                .statusCode(200)
                .body(equalTo("by-host " + domainName));
    }

    /**
     * The trailing slash and percent-encoding of the viewer's URI reach the
     * function untouched — a static site's index resolution depends on it.
     */
    @Test
    void preservesTheViewerUri() {
        String kvsArn = createKeyValueStore("edge-uri-kvs", "greeting", "uri");
        Function function = createFunction("edge-uri-fn", """
                import cf from "cloudfront";
                async function handler(event) {
                  return {
                    statusCode: 200,
                    headers: { "content-type": { value: "text/plain" } },
                    body: { encoding: "text", data: event.request.uri + "|" + (event.request.querystring.q ? event.request.querystring.q.value : "") }
                  };
                }
                """, kvsArn);
        String distributionId = createDistribution("edge-uri", function.arn());

        given()
                // Send the percent-encoding through verbatim instead of letting
                // the client re-encode it.
                .urlEncodingEnabled(false)
                .when()
                .get("/_floci/cloudfront/" + distributionId + "/docs/a%20b/?q=1")
                .then()
                .statusCode(200)
                .body(equalTo("/docs/a%20b/|1"));
    }

    /**
     * CloudFront Functions do not run on Node. The emulator must be at least as
     * restrictive, or code that reaches for a Node global passes locally and
     * fails on deploy.
     */
    @Test
    void rejectsCodeThatEscapesTheCloudFrontRuntime() {
        String kvsArn = createKeyValueStore("edge-sandbox-kvs", "greeting", "nope");
        Function function = createFunction("edge-sandbox-fn", """
                async function handler(event) {
                  await fetch("https://example.com/");
                  return event.request;
                }
                """, kvsArn);
        String distributionId = createDistribution("edge-sandbox", function.arn());

        given()
                .when()
                .get("/_floci/cloudfront/" + distributionId + "/anything")
                .then()
                .statusCode(502)
                .body(containsString("fetch is not defined"))
                .body(containsString("not Node.js"));
    }

    /**
     * TestFunction runs the same code in the same runtime as the edge, so a
     * local result is directly comparable to the same call against AWS.
     */
    @Test
    void testFunctionRunsTheSameRuntime() {
        String kvsArn = createKeyValueStore("edge-test-kvs", "greeting", "tested");
        Function function = createFunction("edge-test-fn", """
                import cf from "cloudfront";
                async function handler(event) {
                  console.log("hello from the function");
                  event.request.uri = await cf.kvs().get("greeting");
                  return event.request;
                }
                """, kvsArn);

        String event = """
                {"version":"1.0","context":{"eventType":"viewer-request"},
                 "request":{"method":"GET","uri":"/original","querystring":{},
                 "headers":{"host":{"value":"example.cloudfront.net"}},"cookies":{}}}
                """;
        given()
                .contentType("application/xml")
                .header("If-Match", function.developmentEtag())
                .body("""
                        <TestFunctionRequest xmlns="%s">
                          <Stage>DEVELOPMENT</Stage>
                          <EventObject>%s</EventObject>
                        </TestFunctionRequest>
                        """.formatted(NS, Base64.getEncoder()
                        .encodeToString(event.getBytes(StandardCharsets.UTF_8))))
                .when()
                .post("/2020-05-31/function/" + function.name() + "/test")
                .then()
                .statusCode(200)
                .body(containsString("<TestResult"))
                .body(containsString("<member>INFO: hello from the function</member>"))
                .body(containsString("&quot;uri&quot;:&quot;tested&quot;"))
                .body(not(containsString("<FunctionErrorMessage>")));
    }

    @Test
    void testFunctionRejectsAStaleEtag() {
        String kvsArn = createKeyValueStore("edge-etag-kvs", "greeting", "x");
        Function function = createFunction("edge-etag-fn", """
                async function handler(event) { return event.request; }
                """, kvsArn);

        given()
                .contentType("application/xml")
                .header("If-Match", "not-the-etag")
                .body("""
                        <TestFunctionRequest xmlns="%s">
                          <Stage>DEVELOPMENT</Stage>
                          <EventObject>e30=</EventObject>
                        </TestFunctionRequest>
                        """.formatted(NS))
                .when()
                .post("/2020-05-31/function/" + function.name() + "/test")
                .then()
                .statusCode(400)
                .body(containsString("InvalidIfMatchVersion"));
    }
}
