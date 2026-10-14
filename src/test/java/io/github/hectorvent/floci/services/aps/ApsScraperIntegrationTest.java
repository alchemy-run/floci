package io.github.hectorvent.floci.services.aps;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * AMP scraper lifecycle against an EKS cluster source in the same emulator: the source cluster,
 * its subnets and the destination workspace are validated, and the scraper, its tags and its
 * logging configuration round-trip through the restJson1 routes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApsScraperIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "aps-scraper-it-cluster";
    private static final String CONFIG = Base64.getEncoder().encodeToString(
            "scrape_configs:\n  - job_name: apiserver\n".getBytes(StandardCharsets.UTF_8));

    private static String subnetId;
    private static String clusterArn;
    private static String workspaceArn;
    private static String scraperId;
    private static String scraperArn;

    @Test
    @Order(1)
    void setupClusterAndWorkspace() {
        String vpcId = given().formParam("Action", "CreateVpc").formParam("CidrBlock", "10.20.0.0/16")
                .when().post("/").then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
        subnetId = given().formParam("Action", "CreateSubnet").formParam("VpcId", vpcId)
                .formParam("CidrBlock", "10.20.1.0/24")
                .when().post("/").then().statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");
        clusterArn = given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"resourcesVpcConfig\":{\"subnetIds\":[\"" + subnetId + "\"]}}")
                .when().post("/clusters").then().statusCode(200)
                .extract().path("cluster.arn");
        workspaceArn = given().contentType(JSON).body("{\"alias\":\"scraper-it\"}")
                .when().post("/workspaces").then().statusCode(202)
                .extract().path("arn");
    }

    private String scraperBody(String alias, String cluster) {
        return """
                {"alias": "%s",
                 "scrapeConfiguration": {"configurationBlob": "%s"},
                 "source": {"eksConfiguration": {"clusterArn": "%s", "subnetIds": ["%s"]}},
                 "destination": {"ampConfiguration": {"workspaceArn": "%s"}},
                 "tags": {"Environment": "test"}}
                """.formatted(alias, CONFIG, cluster, subnetId, workspaceArn);
    }

    @Test
    @Order(2)
    void createRejectsAMissingCluster() {
        given().contentType(JSON)
                .body(scraperBody("missing", clusterArn.replace(CLUSTER, "no-such-cluster")))
                .when().post("/scrapers")
                .then().statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(3)
    void createDescribeAndListScraper() {
        scraperId = given().contentType(JSON).body(scraperBody("first", clusterArn))
                .when().post("/scrapers")
                .then().statusCode(202)
                .body("scraperId", startsWith("s-"))
                .body("status.statusCode", equalTo("CREATING"))
                .body("tags.Environment", equalTo("test"))
                .extract().path("scraperId");
        scraperArn = given().when().get("/scrapers/" + scraperId)
                .then().statusCode(200)
                .body("scraper.status.statusCode", equalTo("ACTIVE"))
                .body("scraper.scrapeConfiguration.configurationBlob", equalTo(CONFIG))
                .body("scraper.source.eksConfiguration.clusterArn", equalTo(clusterArn))
                .body("scraper.destination.ampConfiguration.workspaceArn", equalTo(workspaceArn))
                .body("scraper.roleArn", startsWith("arn:aws:iam::000000000000:role/aws-service-role/"))
                .body("scraper.createdAt", notNullValue())
                .extract().path("scraper.arn");
        given().when().get("/scrapers?alias=first&status=ACTIVE")
                .then().statusCode(200)
                .body("scrapers.scraperId", hasItem(scraperId));
        given().when().get("/scrapers?alias=nobody")
                .then().statusCode(200).body("scrapers", hasSize(0));
    }

    @Test
    @Order(4)
    void updateAliasAndTags() {
        given().contentType(JSON).body("{\"alias\":\"second\"}")
                .when().put("/scrapers/" + scraperId)
                .then().statusCode(202).body("status.statusCode", equalTo("UPDATING"));
        given().when().get("/scrapers/" + scraperId)
                .then().statusCode(200).body("scraper.alias", equalTo("second"));

        given().contentType(JSON).body("{\"tags\":{\"team\":\"metrics\"}}")
                .when().post("/tags/{arn}", scraperArn).then().statusCode(200);
        given().when().get("/tags/{arn}", scraperArn)
                .then().statusCode(200)
                .body("tags.team", equalTo("metrics"))
                .body("tags.Environment", equalTo("test"));
    }

    @Test
    @Order(5)
    void loggingConfigurationRoundTrips() {
        String path = "/scrapers/" + scraperId + "/logging-configuration";
        given().when().get(path).then().statusCode(404);
        given().contentType(JSON)
                .body("{\"loggingDestination\":{\"cloudWatchLogs\":{\"logGroupArn\":"
                        + "\"arn:aws:logs:us-east-1:000000000000:log-group:/aws/vendedlogs/prometheus/it:*\"}}}")
                .when().put(path)
                .then().statusCode(202).body("status.statusCode", equalTo("CREATING"));
        given().when().get(path)
                .then().statusCode(200)
                .body("scraperId", equalTo(scraperId))
                .body("status.statusCode", equalTo("ACTIVE"))
                .body("scraperComponents", hasSize(3));
        given().when().delete(path).then().statusCode(202);
        given().when().get(path).then().statusCode(404);
    }

    @Test
    @Order(6)
    void deleteScraperAndCluster() {
        given().when().delete("/scrapers/" + scraperId)
                .then().statusCode(202).body("status.statusCode", equalTo("DELETING"));
        given().when().get("/scrapers/" + scraperId).then().statusCode(404);
        given().when().delete("/clusters/" + CLUSTER).then().statusCode(200);
    }
}
