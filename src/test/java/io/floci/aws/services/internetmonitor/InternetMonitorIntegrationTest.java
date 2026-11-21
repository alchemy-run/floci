package io.floci.aws.services.internetmonitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.cloudfront.CloudFrontService;
import io.floci.aws.services.cloudfront.model.DefaultCacheBehavior;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.cloudfront.model.DistributionConfig;
import io.floci.aws.services.cloudfront.model.Origin;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static io.floci.aws.testing.AlbFixtures.createListener;
import static io.floci.aws.testing.AlbFixtures.createLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.createTargetGroup;
import static io.floci.aws.testing.AlbFixtures.deleteListener;
import static io.floci.aws.testing.AlbFixtures.deleteLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.deleteTargetGroup;
import static io.floci.aws.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Internet Monitor over REST: monitors and their transitions, tags, and the measurements, health events, internet
 * events, logs and queries Floci derives from the traffic a monitored CloudFront distribution and a VPC's load
 * balancer really serve.
 */
@QuarkusTest
@TestProfile(InternetMonitorIntegrationTest.ShortWindows.class)
class InternetMonitorIntegrationTest {

    /** Three-second windows, a fast transition, and a low performance threshold to cross with a slow origin. */
    public static class ShortWindows implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.internetmonitor.measurement-seconds", "3",
                    "floci.services.internetmonitor.pending-millis", "200",
                    "floci.services.internetmonitor.performance-threshold-millis", "150",
                    "floci.services.elbv2.mock", "false",
                    "floci.services.cloudfront.allowed-private-origin-hosts", "127.0.0.1");
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CREDENTIAL = "test/20260101/us-east-1/";
    private static final String MONITORS = "/v20210603/Monitors";

    @Inject
    Vertx vertx;

    @Inject
    CloudFrontService cloudFront;

    @TestHTTPResource
    URI endpoint;

    private HttpServer origin;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void startOrigin() throws Exception {
        origin = vertx.createHttpServer().requestHandler(request -> {
            if (request.path().startsWith("/fail")) {
                request.response().setStatusCode(500).end("broken");
            } else if (request.path().startsWith("/slow")) {
                vertx.setTimer(300, ignored -> request.response().end("slow"));
            } else {
                request.response().end("ok");
            }
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @AfterEach
    void stopOrigin() throws Exception {
        origin.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    static RequestSpecification signed(String service) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + CREDENTIAL + service
                + "/aws4_request").contentType("application/json");
    }

    static RequestSpecification im() {
        return signed("internetmonitor");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode json(ValidatableResponse response) {
        try {
            return MAPPER.readTree(response.extract().asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode monitor(String name) {
        return json(im().get(MONITORS + "/" + name).then().statusCode(200));
    }

    private static JsonNode await(String what, Supplier<JsonNode> read, Predicate<JsonNode> done) {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = read.get();
            if (done.test(last)) {
                return last;
            }
            sleep(250);
        }
        fail(what + " did not happen: " + last);
        return last;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Waits for the start of the next three-second window, so a burst of requests lands in one window. */
    private static void nextWindow() {
        long now = System.currentTimeMillis();
        sleep(3000 - now % 3000 + 100);
    }

    private static void createMonitor(String name, String body) {
        im().body(body).post(MONITORS).then().statusCode(200).body("Status", equalTo("PENDING"));
        await(name + " to become ACTIVE", () -> monitor(name), m -> "ACTIVE".equals(m.path("Status").asText()));
    }

    private static void deleteMonitor(String name) {
        im().body("{\"Status\":\"INACTIVE\"}").patch(MONITORS + "/" + name).then().statusCode(200);
        await(name + " to become INACTIVE", () -> monitor(name), m -> "INACTIVE".equals(m.path("Status").asText()));
        im().delete(MONITORS + "/" + name).then().statusCode(200);
    }

    // ---------------------------------------------------------------- control plane

    @Test
    void monitorsAreValidatedTransitionAndAreTagged() {
        im().body("{\"MonitorName\":\"Bare\"}").post(MONITORS).then().statusCode(400)
                .header("x-amzn-ErrorType", "ValidationException")
                .body("message", containsString("MaxCityNetworksToMonitor"));
        im().body("{\"MonitorName\":\"Alb\",\"MaxCityNetworksToMonitor\":1,\"Resources\":[\"arn:aws:"
                + "elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/web/0123456789abcdef\"]}")
                .post(MONITORS).then().statusCode(400).body("message", containsString("not a VPC"));
        im().body("{\"MonitorName\":\"Elsewhere\",\"MaxCityNetworksToMonitor\":1,\"Resources\":[\"arn:aws:ec2:"
                + "us-west-2:000000000000:vpc/vpc-0123456789abcdef0\"]}").post(MONITORS).then().statusCode(400)
                .body("message", containsString("not in us-east-1"));
        im().body("{\"MonitorName\":\"bad name!\",\"MaxCityNetworksToMonitor\":1}").post(MONITORS).then()
                .statusCode(400).header("x-amzn-ErrorType", "ValidationException");
        im().body("{\"MonitorName\":\"Logged\",\"MaxCityNetworksToMonitor\":1,\"InternetMeasurementsLogDelivery\":"
                + "{\"S3Config\":{\"BucketName\":\"no-such-bucket-im\",\"LogDeliveryStatus\":\"ENABLED\"}}}")
                .post(MONITORS).then().statusCode(400).body("message", containsString("does not exist"));

        String create = "{\"MonitorName\":\"Shop\",\"MaxCityNetworksToMonitor\":5,\"ClientToken\":\"t1\","
                + "\"Tags\":{\"team\":\"web\"}}";
        String arn = im().body(create).post(MONITORS).then().statusCode(200).body("Status", equalTo("PENDING"))
                .extract().path("Arn");
        assertEquals("arn:aws:internetmonitor:us-east-1:000000000000:monitor/Shop", arn);
        im().body(create).post(MONITORS).then().statusCode(200).body("Arn", equalTo(arn));
        im().body(create.replace("t1", "t2")).post(MONITORS).then().statusCode(409)
                .header("x-amzn-ErrorType", "ConflictException");

        JsonNode pending = monitor("Shop");
        assertEquals("PENDING", pending.path("Status").asText());
        JsonNode active = await("Shop to activate", () -> monitor("Shop"),
                m -> "ACTIVE".equals(m.path("Status").asText()));
        assertEquals("COLLECTING_DATA", active.path("ProcessingStatus").asText());
        assertEquals(5, active.path("MaxCityNetworksToMonitor").asInt());
        assertEquals(0, active.path("Resources").size());
        assertEquals("web", active.path("Tags").path("team").asText());

        String vpc = "arn:aws:ec2:us-east-1:000000000000:vpc/vpc-0123456789abcdef0";
        im().body("{\"ResourcesToAdd\":[\"" + vpc + "\"],\"TrafficPercentageToMonitor\":50}")
                .patch(MONITORS + "/Shop").then().statusCode(200).body("Status", equalTo("PENDING"));
        JsonNode updated = await("Shop to settle", () -> monitor("Shop"),
                m -> "ACTIVE".equals(m.path("Status").asText()));
        assertEquals(vpc, updated.path("Resources").get(0).asText());
        assertEquals(50, updated.path("TrafficPercentageToMonitor").asInt());
        im().body("{\"ResourcesToRemove\":[\"" + vpc.replace("0123", "9999") + "\"]}").patch(MONITORS + "/Shop")
                .then().statusCode(400).body("message", containsString("not in the monitor"));
        im().body("{\"Status\":\"PENDING\"}").patch(MONITORS + "/Shop").then().statusCode(400);
        im().body("{}").patch(MONITORS + "/Missing").then().statusCode(404)
                .header("x-amzn-ErrorType", "ResourceNotFoundException");

        im().body("{\"Tags\":{\"env\":\"prod\"}}").post("/tags/" + encode(arn)).then().statusCode(200);
        im().body("{\"Tags\":{\"aws:x\":\"y\"}}").post("/tags/" + encode(arn)).then().statusCode(400)
                .header("x-amzn-ErrorType", "BadRequestException");
        im().queryParam("tagKeys", "team").delete("/tags/" + encode(arn)).then().statusCode(200);
        im().get("/tags/" + encode(arn)).then().statusCode(200).body("Tags.env", equalTo("prod"))
                .body("Tags.team", equalTo(null));
        im().get("/tags/" + encode(arn.replace("Shop", "Gone"))).then().statusCode(404)
                .header("x-amzn-ErrorType", "NotFoundException");

        im().body(create.replace("Shop", "Shop2").replace("t1", "t3")).post(MONITORS).then().statusCode(200);
        JsonNode first = json(im().queryParam("MaxResults", 1).get(MONITORS).then().statusCode(200));
        assertEquals(1, first.path("Monitors").size());
        JsonNode second = json(im().queryParam("MaxResults", 1).queryParam("NextToken",
                first.path("NextToken").asText()).get(MONITORS).then().statusCode(200));
        assertEquals("Shop2", second.path("Monitors").get(0).path("MonitorName").asText());
        im().queryParam("MaxResults", 26).get(MONITORS).then().statusCode(400);
        im().queryParam("LinkedAccountId", "111122223333").get(MONITORS + "/Shop").then().statusCode(403)
                .header("x-amzn-ErrorType", "AccessDeniedException");

        im().delete(MONITORS + "/Shop").then().statusCode(400)
                .body("message", containsString("inactive state before deletion"));
        deleteMonitor("Shop");
        im().get(MONITORS + "/Shop").then().statusCode(404).header("x-amzn-ErrorType", "ResourceNotFoundException");
        deleteMonitor("Shop2");

        im().get("/v20210603/InternetEvents/00000000-0000-0000-0000-000000000000").then().statusCode(404);
        im().queryParam("EventType", "OTHER").get("/v20210603/InternetEvents").then().statusCode(400);
    }

    // ---------------------------------------------------------------- traffic

    @Test
    void aDistributionsTrafficIsMeasuredAndRaisesHealthEvents() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "im-measurements-" + suffix;
        signed("s3").put("/" + bucket).then().statusCode(200);
        String queue = createQueue("im-events-" + suffix);
        String rule = "im-rule-" + suffix;
        signed("events").header("X-Amz-Target", "AWSEvents.PutRule").contentType("application/x-amz-json-1.1")
                .body("{\"Name\":\"" + rule + "\",\"EventPattern\":\"{\\\"source\\\":[\\\"aws.internetmonitor\\\"]}\"}")
                .post("/").then().statusCode(200);
        signed("events").header("X-Amz-Target", "AWSEvents.PutTargets").contentType("application/x-amz-json-1.1")
                .body("{\"Rule\":\"" + rule + "\",\"Targets\":[{\"Id\":\"q\",\"Arn\":\"" + queueArn(queue) + "\"}]}")
                .post("/").then().statusCode(200);

        Distribution distribution = cloudFront.createDistribution(distribution(), Map.of());
        String name = "Edge" + suffix;
        createMonitor(name, "{\"MonitorName\":\"" + name + "\",\"MaxCityNetworksToMonitor\":10,\"Resources\":[\""
                + distribution.getArn() + "\"],\"InternetMeasurementsLogDelivery\":{\"S3Config\":{\"BucketName\":\""
                + bucket + "\",\"BucketPrefix\":\"im\",\"LogDeliveryStatus\":\"ENABLED\"}}}");
        try {
            String host = distribution.getDomainName();

            // A healthy window.
            nextWindow();
            IntStream.range(0, 20).forEach(i -> given().header("Host", host).get("/ok").then().statusCode(200));
            JsonNode measured = await("the first window", () -> monitor(name),
                    m -> "OK".equals(m.path("ProcessingStatus").asText()));
            assertEquals("The monitor is processing data.", measured.path("ProcessingStatusInfo").asText());

            // Half the requests fail: an availability event opens.
            nextWindow();
            IntStream.range(0, 10).forEach(i -> {
                given().header("Host", host).get("/ok").then().statusCode(200);
                given().header("Host", host).get("/fail").then().statusCode(500);
            });
            JsonNode events = await("an availability event", () -> healthEvents(name),
                    e -> e.path("HealthEvents").size() > 0);
            JsonNode event = events.path("HealthEvents").get(0);
            assertEquals("AVAILABILITY", event.path("ImpactType").asText());
            assertEquals("ACTIVE", event.path("Status").asText());
            assertEquals(50.0, event.path("PercentOfTotalTrafficImpacted").asDouble());
            assertEquals(95.0, event.path("HealthScoreThreshold").asDouble());
            assertTrue(event.path("EventId").asText().endsWith("/availability"), event.toString());
            JsonNode location = event.path("ImpactedLocations").get(0);
            assertEquals("CloudFront", location.path("ServiceLocation").asText());
            assertEquals("Floci local network", location.path("ASName").asText());
            assertEquals(64512, location.path("ASNumber").asLong());
            assertEquals("AWS", location.path("CausedBy").path("NetworkEventType").asText());
            assertEquals(50.0, location.path("InternetHealth").path("Availability").path("ExperienceScore")
                    .asDouble());
            String eventId = event.path("EventId").asText();
            im().urlEncodingEnabled(false).get(MONITORS + "/" + name + "/HealthEvents/" + encode(eventId)).then()
                    .statusCode(200).body("EventArn", equalTo("arn:aws:internetmonitor:us-east-1:000000000000:"
                            + "monitor/" + name + "/health-event/" + eventId));
            im().get(MONITORS + "/" + name + "/HealthEvents/nope").then().statusCode(404);

            JsonNode internet = await("an internet event", () -> json(im().queryParam("EventType", "AVAILABILITY")
                    .get("/v20210603/InternetEvents").then().statusCode(200)),
                    e -> e.path("InternetEvents").size() > 0);
            String internetId = internet.path("InternetEvents").get(0).path("EventId").asText();
            im().get("/v20210603/InternetEvents/" + internetId).then().statusCode(200)
                    .body("ClientLocation.City", equalTo("Local"))
                    .body("EventArn", equalTo("arn:aws:internetmonitor:::internet-event/" + internetId));

            // Healthy again: the event resolves.
            nextWindow();
            IntStream.range(0, 20).forEach(i -> given().header("Host", host).get("/ok").then().statusCode(200));
            await("the event to resolve", () -> healthEvents(name),
                    e -> "RESOLVED".equals(e.path("HealthEvents").get(0).path("Status").asText()));
            await("the internet event to resolve", () -> json(im().get("/v20210603/InternetEvents/" + internetId)
                    .then().statusCode(200)), e -> "RESOLVED".equals(e.path("EventStatus").asText()));

            // Slow answers: a performance event opens.
            nextWindow();
            IntStream.range(0, 8).parallel().forEach(i -> given().header("Host", host).get("/slow").then()
                    .statusCode(200));
            JsonNode slow = await("a performance event", () -> healthEvents(name),
                    e -> "PERFORMANCE".equals(e.path("HealthEvents").get(0).path("ImpactType").asText()));
            assertTrue(slow.path("HealthEvents").get(0).path("ImpactedLocations").get(0).path("InternetHealth")
                    .path("Performance").path("RoundTripTime").path("P50").asDouble() >= 150);

            List<String> detailTypes = new ArrayList<>();
            await("the EventBridge events", () -> {
                detailTypes.addAll(receive(queue));
                return MAPPER.valueToTree(detailTypes);
            }, types -> types.toString().contains("Health Event Created")
                    && types.toString().contains("Health Event Closed"));

            // Measurements reach CloudWatch Logs and S3.
            String groups = signed("logs").header("X-Amz-Target", "Logs_20140328.DescribeLogGroups")
                    .contentType("application/x-amz-json-1.1")
                    .body("{\"logGroupNamePrefix\":\"/aws/internet-monitor/" + name + "/\"}").post("/").then()
                    .statusCode(200).extract().asString();
            for (String granularity : List.of("byCity", "byMetro", "bySubdivision", "byCountry")) {
                assertTrue(groups.contains("/aws/internet-monitor/" + name + "/" + granularity), groups);
            }
            String logged = signed("logs").header("X-Amz-Target", "Logs_20140328.GetLogEvents")
                    .contentType("application/x-amz-json-1.1").body("{\"logGroupName\":\"/aws/internet-monitor/"
                            + name + "/byCity\",\"logStreamName\":\"CloudFront\"}").post("/").then()
                    .statusCode(200).extract().asString();
            assertTrue(logged.contains("\\\"serviceLocation\\\":\\\"CloudFront\\\""), logged);
            assertTrue(logged.contains("\\\"city\\\":\\\"Local\\\""), logged);
            String objects = signed("s3").queryParam("prefix", "im/AWSLogs/000000000000/internetmonitor/us-east-1/")
                    .get("/" + bucket).then().statusCode(200).extract().asString();
            assertTrue(objects.contains("_internetmonitor_us-east-1_" + name + "_"), objects);

            // Queries over the stored measurements.
            Instant now = Instant.now();
            List<List<String>> rows = query(name, "MEASUREMENTS", now.minusSeconds(600), now.plusSeconds(60),
                    "[]", List.of("timestamp", "availability", "performance", "bytes_in", "bytes_out", "rtt_p50",
                            "rtt_p90", "rtt_p95"));
            assertTrue(rows.size() >= 4, rows.toString());
            assertTrue(rows.stream().anyMatch(row -> row.get(1).equals("50.0")), rows.toString());
            assertTrue(rows.stream().anyMatch(row -> row.get(1).equals("100.0") && row.get(2).equals("100.0")),
                    rows.toString());
            List<List<String>> top = query(name, "TOP_LOCATIONS", now.minusSeconds(600), now.plusSeconds(60),
                    "[{\"Field\":\"geo\",\"Operator\":\"EQUALS\",\"Values\":[\"city\"]}]",
                    List.of("aws_location", "city", "metro", "subdivision", "country", "asn", "availability",
                            "performance", "bytes_in", "bytes_out", "current_fbl", "best_ec2", "best_ec2_region",
                            "best_cf_fbl"));
            assertEquals(List.of("CloudFront", "Local", "Local", "Local", "Local", "64512"), top.get(0).subList(0, 6));
            List<List<String>> filtered = query(name, "MEASUREMENTS", now.minusSeconds(600), now.plusSeconds(60),
                    "[{\"Field\":\"aws_location\",\"Operator\":\"NOT_EQUALS\",\"Values\":[\"CloudFront\"]}]", null);
            assertEquals(0, filtered.size());
            assertEquals(0, query(name, "ROUTING_SUGGESTIONS", now.minusSeconds(600), now.plusSeconds(60), "[]",
                    null).size());
            im().body("{\"StartTime\":\"" + now + "\",\"EndTime\":\"" + now.minusSeconds(60)
                    + "\",\"QueryType\":\"MEASUREMENTS\"}").post(MONITORS + "/" + name + "/Queries").then()
                    .statusCode(400);
            im().body("{\"StartTime\":\"" + now.minusSeconds(60) + "\",\"EndTime\":\"" + now
                    + "\",\"QueryType\":\"TOP_LOCATIONS\"}").post(MONITORS + "/" + name + "/Queries").then()
                    .statusCode(400).body("message", containsString("geo"));
            im().body("{\"StartTime\":\"" + now.minusSeconds(60) + "\",\"EndTime\":\"" + now
                    + "\",\"QueryType\":\"MEASUREMENTS\",\"FilterParameters\":[{\"Field\":\"geo\",\"Values\":"
                    + "[\"city\"]}]}").post(MONITORS + "/" + name + "/Queries").then().statusCode(400);
            im().get(MONITORS + "/" + name + "/Queries/nope/Status").then().statusCode(400);
        } finally {
            deleteMonitor(name);
            cloudFront.removeDistribution(distribution.getId());
        }
    }

    @Test
    void aVpcsLoadBalancerTrafficIsMeasured() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        int port = FreePorts.anyInterfacePort();
        String loadBalancer = createLoadBalancer("im-lb-" + suffix);
        String targetGroup = createTargetGroup("im-tg-" + suffix, "ip", origin.actualPort());
        registerTarget(targetGroup, "127.0.0.1", origin.actualPort());
        String listener = createListener(loadBalancer, targetGroup, port);
        String vpcId = signed("elasticloadbalancing").contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeLoadBalancers").formParam("LoadBalancerArns.member.1", loadBalancer)
                .post("/").then().statusCode(200).extract()
                .path("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.VpcId");
        String name = "Vpc" + suffix;
        createMonitor(name, "{\"MonitorName\":\"" + name + "\",\"TrafficPercentageToMonitor\":50,\"Resources\":"
                + "[\"arn:aws:ec2:us-east-1:000000000000:vpc/" + vpcId + "\"]}");
        try {
            await("the listener", () -> MAPPER.valueToTree(given().baseUri("http://127.0.0.1").port(port)
                    .get("/ok").statusCode()), status -> status.asInt() == 200);
            nextWindow();
            IntStream.range(0, 20).forEach(i -> given().baseUri("http://127.0.0.1").port(port).get("/ok").then()
                    .statusCode(200));
            await("a measured window", () -> monitor(name), m -> "OK".equals(m.path("ProcessingStatus").asText()));
            Instant now = Instant.now();
            List<List<String>> top = query(name, "TOP_LOCATIONS", now.minusSeconds(600), now.plusSeconds(60),
                    "[{\"Field\":\"geo\",\"Operator\":\"EQUALS\",\"Values\":[\"country\"]}]", null);
            assertEquals("us-east-1", top.get(0).get(0));
            assertEquals("Local", top.get(0).get(1));
            String logged = signed("logs").header("X-Amz-Target", "Logs_20140328.GetLogEvents")
                    .contentType("application/x-amz-json-1.1").body("{\"logGroupName\":\"/aws/internet-monitor/"
                            + name + "/byCountry\",\"logStreamName\":\"us-east-1\"}").post("/").then()
                    .statusCode(200).extract().asString();
            // Half of the requests are sampled; the burst may straddle a boundary, so count across windows.
            assertTrue(logged.contains("clientConnectionCount"), logged);
            assertFalse(logged.contains("\\\"city\\\""), logged);
        } finally {
            deleteMonitor(name);
            deleteListener(listener);
            deleteTargetGroup(targetGroup);
            deleteLoadBalancer(loadBalancer);
        }
    }

    @Test
    void aNetworkLoadBalancersConnectionsAreMeasured() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        try (ElasticLoadBalancingV2Client elb = ElasticLoadBalancingV2Client.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String loadBalancer = elb.createLoadBalancer(request -> request.name("im-nlb-" + suffix)
                    .type(LoadBalancerTypeEnum.NETWORK).subnets(Ec2Service.defaultSubnetId("us-east-1", "a")))
                    .loadBalancers().getFirst().loadBalancerArn();
            String targetGroup = elb.createTargetGroup(request -> request.name("im-nlb-tg-" + suffix)
                    .protocol(ProtocolEnum.TCP).port(origin.actualPort())
                    .vpcId(Ec2Service.defaultVpcId("us-east-1")).targetType(TargetTypeEnum.IP)
                    .healthCheckProtocol(ProtocolEnum.HTTP).healthCheckPort(Integer.toString(origin.actualPort()))
                    .healthCheckIntervalSeconds(5).healthyThresholdCount(2))
                    .targetGroups().getFirst().targetGroupArn();
            elb.registerTargets(request -> request.targetGroupArn(targetGroup)
                    .targets(TargetDescription.builder().id("127.0.0.1").port(origin.actualPort()).build()));
            await("a healthy target", () -> MAPPER.valueToTree(elb.describeTargetHealth(request -> request
                    .targetGroupArn(targetGroup)).targetHealthDescriptions().getFirst().targetHealth().stateAsString()),
                    state -> "healthy".equals(state.asText()));
            int port = FreePorts.anyInterfacePort();
            String listener = elb.createListener(request -> request.loadBalancerArn(loadBalancer)
                    .protocol(ProtocolEnum.TCP).port(port).defaultActions(Action.builder()
                            .type(ActionTypeEnum.FORWARD).targetGroupArn(targetGroup).build()))
                    .listeners().getFirst().listenerArn();
            String name = "Nlb" + suffix;
            createMonitor(name, "{\"MonitorName\":\"" + name + "\",\"MaxCityNetworksToMonitor\":1,"
                    + "\"Resources\":[\"" + loadBalancer + "\"]}");
            try {
                await("the listener", () -> MAPPER.valueToTree(exchange(port)), answer -> answer.asText()
                        .contains("ok"));
                nextWindow();
                for (int i = 0; i < 10; i++) {
                    assertTrue(exchange(port).contains("ok"));
                }
                await("a measured window", () -> monitor(name),
                        m -> "OK".equals(m.path("ProcessingStatus").asText()));
                Instant now = Instant.now();
                List<List<String>> rows = query(name, "MEASUREMENTS", now.minusSeconds(600), now.plusSeconds(60),
                        "[{\"Field\":\"aws_location\",\"Operator\":\"EQUALS\",\"Values\":[\"us-east-1\"]}]",
                        null);
                assertFalse(rows.isEmpty());
                assertEquals("100.0", rows.get(rows.size() - 1).get(1));
            } finally {
                deleteMonitor(name);
                elb.deleteListener(request -> request.listenerArn(listener));
                elb.deleteLoadBalancer(request -> request.loadBalancerArn(loadBalancer));
                elb.deleteTargetGroup(request -> request.targetGroupArn(targetGroup));
            }
        }
    }

    /** One HTTP request over a raw connection to a TCP listener, and the answer. */
    private static String exchange(int port) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /ok HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            return e.toString();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static JsonNode healthEvents(String name) {
        return json(im().get(MONITORS + "/" + name + "/HealthEvents").then().statusCode(200));
    }

    /** Runs a query to completion and returns its rows, after checking its fields when {@code fields} is given. */
    private static List<List<String>> query(String name, String type, Instant start, Instant end, String filters,
                                            List<String> fields) {
        String id = im().body("{\"StartTime\":\"" + start + "\",\"EndTime\":\"" + end + "\",\"QueryType\":\"" + type
                + "\",\"FilterParameters\":" + filters + "}").post(MONITORS + "/" + name + "/Queries").then()
                .statusCode(200).extract().path("QueryId");
        await("the query", () -> json(im().get(MONITORS + "/" + name + "/Queries/" + id + "/Status").then()
                .statusCode(200)), s -> "SUCCEEDED".equals(s.path("Status").asText()));
        JsonNode results = json(im().get(MONITORS + "/" + name + "/Queries/" + id + "/Results").then()
                .statusCode(200));
        if (fields != null) {
            List<String> names = new ArrayList<>();
            results.path("Fields").forEach(field -> names.add(field.path("Name").asText()));
            assertEquals(fields, names);
        }
        im().delete(MONITORS + "/" + name + "/Queries/" + id).then().statusCode(200);
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : results.path("Data")) {
            List<String> values = new ArrayList<>();
            row.forEach(value -> values.add(value.asText()));
            rows.add(values);
        }
        return rows;
    }

    private Distribution distribution() {
        Map<String, Object> originConfig = new LinkedHashMap<>();
        originConfig.put("HTTPPort", String.valueOf(origin.actualPort()));
        originConfig.put("HTTPSPort", "443");
        originConfig.put("OriginProtocolPolicy", "http-only");
        Origin custom = new Origin();
        custom.setId("origin");
        custom.setDomainName("127.0.0.1");
        custom.setCustomOriginConfig(originConfig);
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("origin");
        behavior.setViewerProtocolPolicy("allow-all");
        // CachingDisabled: every viewer request reaches the origin.
        behavior.setCachePolicyId("4135ea2d-6df8-44a3-9df3-4b5a84be39ad");
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(custom));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    private static String createQueue(String name) {
        return signed("sqs").header("X-Amz-Target", "AmazonSQS.CreateQueue").contentType("application/x-amz-json-1.0")
                .body("{\"QueueName\":\"" + name + "\"}").post("/").then().statusCode(200).extract().path("QueueUrl");
    }

    private static String queueArn(String url) {
        return signed("sqs").header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .contentType("application/x-amz-json-1.0").body("{\"QueueUrl\":\"" + url
                        + "\",\"AttributeNames\":[\"QueueArn\"]}").post("/").then().statusCode(200).extract()
                .path("Attributes.QueueArn");
    }

    private static List<String> receive(String url) {
        JsonNode messages = json(signed("sqs").header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .contentType("application/x-amz-json-1.0").body("{\"QueueUrl\":\"" + url
                        + "\",\"MaxNumberOfMessages\":10}").post("/").then().statusCode(200));
        List<String> types = new ArrayList<>();
        for (JsonNode message : messages.path("Messages")) {
            try {
                JsonNode body = MAPPER.readTree(message.path("Body").asText());
                types.add(body.path("detail-type").asText());
                assertEquals("aws.internetmonitor", body.path("source").asText());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return types;
    }
}
