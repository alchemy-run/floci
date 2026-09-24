package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The RDS model declares the {@code Parameters} list with {@code locationName: Parameter}, so the
 * CLI and every SDK send {@code Parameters.Parameter.N.*}. A modify sent that way must land in
 * the group; the plain {@code Parameters.member.N.*} encoding stays accepted for callers that
 * build the query by hand.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RdsParameterGroupWireEncodingIntegrationTest {

    private static final String PG = "wire-encoding-pg";
    private static final String CLUSTER_PG = "wire-encoding-cluster-pg";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @Test
    @Order(1)
    void modifyDbParameterGroupStoresTheParametersTheCliSends() {
        query("CreateDBParameterGroup")
                .formParam("DBParameterGroupName", PG)
                .formParam("DBParameterGroupFamily", "postgres16")
                .formParam("Description", "wire encoding")
        .when().post("/").then().statusCode(200);

        List<Map<String, String>> defaults = parameters(PG, null);
        assertEquals("LEAST({DBInstanceClassMemory/9531392},5000)", parameter(defaults, "max_connections").get("ParameterValue"));
        assertEquals("static", parameter(defaults, "max_connections").get("ApplyType"));
        assertEquals("pending-reboot", parameter(defaults, "max_connections").get("ApplyMethod"));
        assertEquals("4096", parameter(defaults, "work_mem").get("ParameterValue"));
        assertEquals("dynamic", parameter(defaults, "work_mem").get("ApplyType"));
        assertEquals("immediate", parameter(defaults, "work_mem").get("ApplyMethod"));
        assertEquals("engine-default", parameter(defaults, "log_autovacuum_min_duration").get("Source"));
        assertEquals("600000", parameter(defaults, "log_autovacuum_min_duration").get("ParameterValue"));
        assertTrue(parameters(PG, "user").isEmpty());

        // Exactly what `aws rds modify-db-parameter-group --parameters ...` puts on the wire.
        query("ModifyDBParameterGroup")
                .formParam("DBParameterGroupName", PG)
                .formParam("Parameters.Parameter.1.ParameterName", "max_connections")
                .formParam("Parameters.Parameter.1.ParameterValue", "250")
                .formParam("Parameters.Parameter.1.ApplyMethod", "pending-reboot")
                .formParam("Parameters.Parameter.2.ParameterName", "work_mem")
                .formParam("Parameters.Parameter.2.ParameterValue", "65536")
                .formParam("Parameters.Parameter.2.ApplyMethod", "pending-reboot")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBParameterGroupName>" + PG + "</DBParameterGroupName>"));

        query("DescribeDBParameters")
                .formParam("DBParameterGroupName", PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>max_connections</ParameterName>"))
            .body(containsString("<ParameterValue>250</ParameterValue>"))
            .body(containsString("<ParameterName>work_mem</ParameterName>"))
            .body(containsString("<ParameterValue>65536</ParameterValue>"));

        List<Map<String, String>> overrides = parameters(PG, "user");
        assertEquals(2, overrides.size());
        assertEquals("static", parameter(overrides, "max_connections").get("ApplyType"));
        assertEquals("pending-reboot", parameter(overrides, "max_connections").get("ApplyMethod"));
        assertEquals("dynamic", parameter(overrides, "work_mem").get("ApplyType"));
        assertEquals("pending-reboot", parameter(overrides, "work_mem").get("ApplyMethod"));

        // The plain Query encoding keeps working alongside it.
        query("ModifyDBParameterGroup")
                .formParam("DBParameterGroupName", PG)
                .formParam("Parameters.member.1.ParameterName", "shared_buffers")
                .formParam("Parameters.member.1.ParameterValue", "4096")
        .when().post("/").then().statusCode(200);

        query("DescribeDBParameters")
                .formParam("DBParameterGroupName", PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>shared_buffers</ParameterName>"))
            .body(containsString("<ParameterName>max_connections</ParameterName>"));
    }

    @Test
    @Order(2)
    void modifyDbClusterParameterGroupStoresTheParametersTheCliSends() {
        query("CreateDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", CLUSTER_PG)
                .formParam("DBParameterGroupFamily", "aurora-postgresql15")
                .formParam("Description", "wire encoding")
        .when().post("/").then().statusCode(200);

        query("ModifyDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", CLUSTER_PG)
                .formParam("Parameters.Parameter.1.ParameterName", "rds.force_ssl")
                .formParam("Parameters.Parameter.1.ParameterValue", "1")
                .formParam("Parameters.Parameter.1.ApplyMethod", "immediate")
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DBClusterParameterGroupName>" + CLUSTER_PG + "</DBClusterParameterGroupName>"));

        query("DescribeDBClusterParameters")
                .formParam("DBClusterParameterGroupName", CLUSTER_PG)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>rds.force_ssl</ParameterName>"))
            .body(containsString("<ParameterValue>1</ParameterValue>"));
    }

    @Test
    @Order(3)
    void mysqlDefaultsAndOverridesRemainFamilySpecific() {
        String name = "wire-mysql-pg";
        query("CreateDBParameterGroup").formParam("DBParameterGroupName", name)
                .formParam("DBParameterGroupFamily", "mysql8.4").formParam("Description", "mysql defaults")
        .when().post("/").then().statusCode(200);
        try {
            List<Map<String, String>> defaults = parameters(name, null);
            assertEquals("UTC", parameter(defaults, "time_zone").get("ParameterValue"));
            assertEquals("{DBInstanceClassMemory/12582880}", parameter(defaults, "max_connections").get("ParameterValue"));
            assertEquals("dynamic", parameter(defaults, "max_connections").get("ApplyType"));
            query("ModifyDBParameterGroup").formParam("DBParameterGroupName", name)
                    .formParam("Parameters.Parameter.1.ParameterName", "time_zone")
                    .formParam("Parameters.Parameter.1.ParameterValue", "Australia/Sydney")
                    .formParam("Parameters.Parameter.1.ApplyMethod", "immediate")
                    .formParam("Parameters.Parameter.2.ParameterName", "max_connections")
                    .formParam("Parameters.Parameter.2.ParameterValue", "150")
                    .formParam("Parameters.Parameter.2.ApplyMethod", "pending-reboot")
            .when().post("/").then().statusCode(200);
            assertEquals("pending-reboot", parameter(parameters(name, "user"), "max_connections").get("ApplyMethod"));
            query("ResetDBParameterGroup").formParam("DBParameterGroupName", name)
                    .formParam("ResetAllParameters", "true")
            .when().post("/").then().statusCode(200);
            assertEquals(defaults, parameters(name, null));
            assertTrue(parameters(name, "user").isEmpty());
        } finally {
            query("DeleteDBParameterGroup").formParam("DBParameterGroupName", name)
            .when().post("/").then().statusCode(200);
        }
    }

    @Test
    @Order(4)
    void engineCatalogUsesTheRdsQueryEnvelope() {
        String body = query("DescribeDBEngineVersions").formParam("Engine", "postgres")
                .formParam("EngineVersion", "16.3")
        .when().post("/").then().statusCode(200).extract().asString();
        assertEquals("DescribeDBEngineVersionsResponse", XmlParser.rootElementName(body));
        assertEquals(List.of("DBEngineVersion"), XmlParser.childElementNames(body, "DBEngineVersions"));
        assertEquals(List.of("postgres16"), XmlParser.extractAll(body, "DBParameterGroupFamily"));
        given().contentType(URLENC).formParam("Action", "DescribeDBEngineVersions")
                .formParam("Version", "2014-10-31").formParam("Engine", "postgres")
                .formParam("EngineVersion", "16.3")
        .when().post("/").then().statusCode(200)
            .body(containsString("<DBParameterGroupFamily>postgres16</DBParameterGroupFamily>"));
        query("DescribeDBEngineVersions").formParam("Engine", "postgres")
                .formParam("DefaultOnly", "true").formParam("IncludeAll", "true")
        .when().post("/").then().statusCode(400)
            .body(containsString("<Code>InvalidParameterCombination</Code>"));
    }

    private static List<Map<String, String>> parameters(String name, String source) {
        RequestSpecification request = query("DescribeDBParameters").formParam("DBParameterGroupName", name);
        if (source != null) {
            request.formParam("Source", source);
        }
        String body = request.when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractGroups(body, "Parameter");
    }

    private static Map<String, String> parameter(List<Map<String, String>> parameters, String name) {
        return parameters.stream().filter(parameter -> name.equals(parameter.get("ParameterName")))
                .findFirst().orElseThrow();
    }

    @Test
    @Order(9)
    void cleanUp() {
        query("DeleteDBParameterGroup").formParam("DBParameterGroupName", PG).when().post("/");
        query("DeleteDBClusterParameterGroup").formParam("DBClusterParameterGroupName", CLUSTER_PG).when().post("/");
    }
}
