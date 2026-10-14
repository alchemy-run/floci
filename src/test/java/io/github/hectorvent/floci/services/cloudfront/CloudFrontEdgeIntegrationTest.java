package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emulated CloudFront edge: a distribution's viewer-request function runs
 * for real, reads the distribution's key value store, and its response is
 * served to the viewer.
 *
 * <p>Covers function responses and KVS-selected local HTTP origins through the
 * gateway and per-distribution edge ports.
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
                .body(Map.of("Value", value))
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

    @Test
    void responseHeadersPolicyAppliesToFunctionResponses() {
        String kvsArn = createKeyValueStore("edge-policy-kvs", "greeting", "policy");
        Function function = createFunction("edge-policy-fn", """
                async function handler(event) {
                  return { statusCode: 200, body: { encoding: "text", data: "function response" } };
                }
                """, kvsArn);
        String id = createDistribution("edge-policy", function.arn());
        String policyXml = given().contentType("application/xml").body("""
                <ResponseHeadersPolicyConfig xmlns="%s">
                  <Name>edge-policy-headers</Name>
                  <CustomHeadersConfig><Quantity>1</Quantity><Items><ResponseHeadersPolicyCustomHeader>
                    <Header>X-Combined-Policy</Header><Value>applied</Value><Override>true</Override>
                  </ResponseHeadersPolicyCustomHeader></Items></CustomHeadersConfig>
                </ResponseHeadersPolicyConfig>
                """.formatted(NS)).post("/2020-05-31/response-headers-policy")
                .then().statusCode(201).extract().body().asString();
        String policyId = firstElement(policyXml, "Id");
        ExtractableResponse<Response> config = given().get("/2020-05-31/distribution/" + id + "/config")
                .then().statusCode(200).extract();
        given().contentType("application/xml").header("If-Match", config.header("ETag"))
                .body(config.body().asString().replace("</DefaultCacheBehavior>",
                        "<ResponseHeadersPolicyId>" + policyId + "</ResponseHeadersPolicyId></DefaultCacheBehavior>"))
                .put("/2020-05-31/distribution/" + id + "/config").then().statusCode(200);
        given().header("Host", id + ".cloudfront.net").get("/policy").then().statusCode(200)
                .header("X-Combined-Policy", equalTo("applied"))
                .body(equalTo("function response"));
    }

    @Test
    void disabledDistributionRejectsFunctionExecutionOnBothRoutes() {
        String kvsArn = createKeyValueStore("edge-disabled-kvs", "greeting", "disabled");
        Function function = createFunction("edge-disabled-fn", """
                async function handler(event) {
                  return { statusCode: 200, body: { encoding: "text", data: "must not run" } };
                }
                """, kvsArn);
        String id = createDistribution("edge-disabled", function.arn());
        ExtractableResponse<Response> config = given().get("/2020-05-31/distribution/" + id + "/config")
                .then().statusCode(200).extract();
        given().contentType("application/xml").header("If-Match", config.header("ETag"))
                .body(config.body().asString().replace("<Enabled>true</Enabled>", "<Enabled>false</Enabled>"))
                .put("/2020-05-31/distribution/" + id + "/config").then().statusCode(200);

        given().get("/_floci/cloudfront/" + id + "/blocked").then().statusCode(404);
        given().header("Host", id + ".cloudfront.net").get("/blocked").then().statusCode(404);
    }

    @Test
    @Timeout(90)
    void routesLocalDevOriginsThroughKvsAcrossEdgePortReuse() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        Map<String, String> env = Map.of("SITE_ENV_MARKER", "local-dev-env-marker");
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            hits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if ("/redirect".equals(path)) {
                exchange.getResponseHeaders().add("Location", "/redirect-target");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            String content = switch (path) {
                case "/robots.txt" -> "local-dev-robots-marker";
                case "/api/hello" -> exchange.getRequestURI().getRawQuery();
                default -> "LOCAL_DEV_PAGE_MARKER env:" + env.get("SITE_ENV_MARKER");
            };
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            for (String header : List.of("Host", "X-Forwarded-Host", "Cookie", "X-Viewer-Test", "X-Distribution")) {
                String value = exchange.getRequestHeaders().getFirst(header);
                if (value != null) {
                    exchange.getResponseHeaders().add("X-Origin-" + header, value);
                }
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        String kvsName = "edge-local-dev-kvs";
        String kvsArn = null;
        Function function = null;
        try {
            String authority = "localhost:" + origin.getAddress().getPort();
            kvsArn = createKeyValueStore(kvsName, "router:routes", "site,site,,/");
            putKey(kvsArn, "site:metadata", """
                    {"servers":[["%s"]],"origin":{"protocol":"http"}}
                    """.formatted(authority));
            function = createFunction("edge-local-dev-fn", """
                    import cf from "cloudfront";
                    async function handler(event) {
                      var route = (await cf.kvs().get("router:routes")).split(",");
                      var metadata = JSON.parse(await cf.kvs().get(route[1] + ":metadata"));
                      event.request.headers["x-forwarded-host"] = event.request.headers.host;
                      event.request.headers["x-distribution"] = { value: event.context.distributionId };
                      var origin = { domainName: metadata.servers[0][0],
                        customOriginConfig: { protocol: "https", port: 443 } };
                      if (metadata.origin.protocol === "http") delete origin.customOriginConfig;
                      cf.updateRequestOrigin(origin);
                      return event.request;
                    }
                    """, kvsArn);
            Integer reusedPort = null;
            for (int generation = 0; generation < 3; generation++) {
                String id = createDistribution("edge-local-dev-" + generation, function.arn());
                try {
                    int port = assignedPort(id);
                    if (reusedPort == null) {
                        reusedPort = port;
                    } else {
                        assertEquals(reusedPort.intValue(), port, "deleted distributions release their edge port");
                    }
                    given().get("/_floci/cloudfront/" + id + "/").then().statusCode(200)
                            .body(containsString("LOCAL_DEV_PAGE_MARKER"));
                    HttpResponse<String> page = requestEdge(port, "/");
                    assertEquals(200, page.statusCode(), page.body());
                    assertTrue(page.body().contains("LOCAL_DEV_PAGE_MARKER"));
                    assertTrue(page.body().contains("env:local-dev-env-marker"));
                    assertEquals(authority, page.headers().firstValue("x-origin-host").orElseThrow());
                    assertEquals("localhost:" + port,
                            page.headers().firstValue("x-origin-x-forwarded-host").orElseThrow());
                    assertEquals("session=viewer-cookie", page.headers().firstValue("x-origin-cookie").orElseThrow());
                    assertEquals("viewer-value", page.headers().firstValue("x-origin-x-viewer-test").orElseThrow());
                    assertEquals(id, page.headers().firstValue("x-origin-x-distribution").orElseThrow());
                    for (String value : List.of("router-one", "router-two")) {
                        HttpResponse<String> api = requestEdge(port, "/api/hello?echo=" + value);
                        assertEquals(200, api.statusCode(), api.body());
                        assertEquals("echo=" + value, api.body());
                    }
                    HttpResponse<String> asset = requestEdge(port, "/robots.txt");
                    assertEquals(200, asset.statusCode(), asset.body());
                    assertEquals("local-dev-robots-marker", asset.body());
                    HttpResponse<String> redirect = requestEdge(port, "/redirect");
                    assertEquals(302, redirect.statusCode());
                    assertEquals("/redirect-target", redirect.headers().firstValue("location").orElseThrow());
                } finally {
                    deleteDistribution(id);
                }
            }
            assertEquals(18, hits.get(), "each request reaches the origin once; redirects are not followed");
        } finally {
            origin.stop(0);
            try {
                if (function != null) {
                    deleteFunction(function);
                }
            } finally {
                if (kvsArn != null) {
                    deleteKeyValueStore(kvsName);
                }
            }
        }
    }

    @Test
    @Timeout(90)
    void rejectsOrdinaryPrivateAndMetadataOriginsSelectedByAFunction() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        origin.start();
        String kvsName = "edge-blocked-origin-kvs";
        String kvsArn = null;
        Function function = null;
        try {
            kvsArn = createKeyValueStore(kvsName, "origin", "{}");
            function = createFunction("edge-blocked-origin-fn", """
                    import cf from "cloudfront";
                    async function handler(event) {
                      cf.updateRequestOrigin(JSON.parse(await cf.kvs().get("origin")));
                      return event.request;
                    }
                    """, kvsArn);
            String id = createDistribution("edge-blocked-origin", function.arn());
            try {
                int port = assignedPort(id);
                for (String selected : List.of(
                        """
                        {"domainName":"127.0.0.1","customOriginConfig":{"protocol":"http","port":%d}}
                        """.formatted(origin.getAddress().getPort()),
                        """
                        {"domainName":"169.254.169.254:80","customOriginConfig":{"protocol":"http","port":80}}
                        """,
                        """
                        {"domainName":"10.0.0.1:8080","customOriginConfig":{"protocol":"http","port":8080}}
                        """)) {
                    putKey(kvsArn, "origin", selected);
                    HttpResponse<String> response = requestEdge(port, "/");
                    assertEquals(502, response.statusCode(), response.body());
                    assertTrue(response.body().contains("blocked address"), response.body());
                }
                assertEquals(0, hits.get());
            } finally {
                deleteDistribution(id);
            }
        } finally {
            origin.stop(0);
            try {
                if (function != null) {
                    deleteFunction(function);
                }
            } finally {
                if (kvsArn != null) {
                    deleteKeyValueStore(kvsName);
                }
            }
        }
    }

    private void putKey(String arn, String key, String value) {
        String etag = given().get("/key-value-stores/" + arn).then().statusCode(200).extract().header("ETag");
        given().contentType("application/json").header("If-Match", etag).body(Map.of("Value", value))
                .put("/key-value-stores/" + arn + "/keys/" + key).then().statusCode(200);
    }

    private int assignedPort(String id) {
        return given().get("/_floci/cloudfront-edge/" + id).then().statusCode(200).extract().path("Port");
    }

    private HttpResponse<String> requestEdge(int port, String path) throws Exception {
        // A new client exercises fresh accepted sockets rather than a pooled viewer connection.
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .timeout(Duration.ofSeconds(10)).header("Cookie", "session=viewer-cookie")
                            .header("X-Viewer-Test", "viewer-value").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private void deleteDistribution(String id) {
        String path = "/2020-05-31/distribution/" + id;
        ExtractableResponse<Response> config = given().get(path + "/config").then().statusCode(200).extract();
        String etag = given().contentType("application/xml").header("If-Match", config.header("ETag"))
                .body(config.body().asString().replace("<Enabled>true</Enabled>", "<Enabled>false</Enabled>"))
                .put(path + "/config").then().statusCode(200).extract().header("ETag");
        given().header("If-Match", etag).delete(path).then().statusCode(204);
        given().get("/_floci/cloudfront-edge/" + id).then().statusCode(404);
    }

    private void deleteFunction(Function function) {
        String path = "/2020-05-31/function/" + function.name();
        String etag = given().queryParam("Stage", "DEVELOPMENT").get(path + "/describe")
                .then().statusCode(200).extract().header("ETag");
        given().header("If-Match", etag).delete(path).then().statusCode(204);
    }

    private void deleteKeyValueStore(String name) {
        String path = "/2020-05-31/key-value-store/" + name;
        String etag = given().get(path).then().statusCode(200).extract().header("ETag");
        given().header("If-Match", etag).delete(path).then().statusCode(204);
    }

    /**
     * Every distribution also gets a plain-HTTP port of its own. That port is
     * the only address of the emulated edge that actually works on a developer
     * machine: {@code *.cloudfront.net} resolves to nothing without a hosts
     * entry, and the emulator's TLS certificate is self-signed. The emulator
     * assigns the port, so it reports it back.
     */
    @Test
    void servesEachDistributionOnItsOwnPort() {
        String kvsArn = createKeyValueStore("edge-port-kvs", "greeting", "by-port");
        Function function = createFunction("edge-port-fn", """
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
        String distributionId = createDistribution("edge-port", function.arn());

        ExtractableResponse<Response> assigned = given()
                .when()
                .get("/_floci/cloudfront-edge/" + distributionId)
                .then()
                .statusCode(200)
                .body("DistributionId", equalTo(distributionId))
                .extract();
        int port = assigned.path("Port");
        assertEquals("http://localhost:" + port, assigned.path("Url"));

        // Plain HTTP, no Host header games, no TLS: what a browser does.
        given()
                .baseUri("http://localhost")
                .port(port)
                .when()
                .get("/greet/me")
                .then()
                .statusCode(200)
                // The viewer's own Host reaches the function untouched, so edge
                // code that reads it sees the address the request arrived on.
                .body(equalTo("by-port localhost:" + port));

        // The listing reports the same assignment.
        given()
                .when()
                .get("/_floci/cloudfront-edge")
                .then()
                .statusCode(200)
                .body("Enabled", equalTo(true))
                .body("Distributions.find { it.DistributionId == '%s' }.Port".formatted(distributionId),
                        equalTo(port));
    }
}
