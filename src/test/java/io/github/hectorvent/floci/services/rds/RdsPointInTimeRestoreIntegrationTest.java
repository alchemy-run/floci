package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RestoreDBInstanceToPointInTime and ModifyDBInstance NewDBInstanceIdentifier over the Query
 * protocol against real PostgreSQL backends, in the order a blue/green style swap uses them:
 * restore the source to its latest restorable time under a new name, rename the source away and
 * the restored copy into its place.
 */
@QuarkusTest
class RdsPointInTimeRestoreIntegrationTest {

    private static final String SOURCE = "pitr-it-source";
    private static final String RESTORED = "pitr-it-source-restored";
    private static final String DISPLACED = "pitr-it-source-old";

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH)
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @AfterEach
    void cleanUp() {
        for (String id : new String[] {SOURCE, RESTORED, DISPLACED}) {
            query("DeleteDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("SkipFinalSnapshot", "true").when().post("/");
        }
    }

    @Test
    void restoresTheSourceDataUnderANewIdentityAndSwapsItIntoPlace() throws Exception {
        String created = query("CreateDBInstance")
                .formParam("DBInstanceIdentifier", SOURCE)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBName", "appdb")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "20")
                .formParam("BackupRetentionPeriod", "1")
        .when().post("/").then().statusCode(200).extract().asString();
        String sourceResourceId = element(created, "DbiResourceId");

        // A client whose first poll comes late still sees the creation, then availability.
        Thread.sleep(1500);
        String firstPoll = describe(SOURCE);
        assertEquals("creating", element(firstPoll, "DBInstanceStatus"));
        String source = describe(SOURCE);
        assertEquals("available", element(source, "DBInstanceStatus"));
        assertTrue(source.contains("<LatestRestorableTime>"), source);

        execute(source, "CREATE TABLE marker (v text); INSERT INTO marker VALUES ('from-source')");

        String restoredXml = query("RestoreDBInstanceToPointInTime")
                .formParam("SourceDBInstanceIdentifier", SOURCE)
                .formParam("TargetDBInstanceIdentifier", RESTORED)
                .formParam("UseLatestRestorableTime", "true")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("StorageType", element(source, "StorageType"))
                .formParam("AllocatedStorage", "20")
                .formParam("Iops", element(source, "Iops"))
                .formParam("StorageThroughput", element(source, "StorageThroughput"))
                .formParam("Port", "5432")
                .formParam("BackupRetentionPeriod", "1")
                .formParam("DeletionProtection", "false")
        .when().post("/").then().statusCode(200)
                .body(containsString("<RestoreDBInstanceToPointInTimeResult>"))
                .extract().asString();
        String restoredResourceId = element(restoredXml, "DbiResourceId");
        assertNotEquals(sourceResourceId, restoredResourceId);
        assertEquals("marker:from-source", firstMarker(describe(RESTORED)));

        query("ModifyDBInstance").formParam("DBInstanceIdentifier", SOURCE)
                .formParam("NewDBInstanceIdentifier", DISPLACED).formParam("ApplyImmediately", "true")
        .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceIdentifier>" + DISPLACED + "</DBInstanceIdentifier>"));
        query("ModifyDBInstance").formParam("DBInstanceIdentifier", RESTORED)
                .formParam("NewDBInstanceIdentifier", SOURCE).formParam("ApplyImmediately", "true")
        .when().post("/").then().statusCode(200);

        String swapped = describe(SOURCE);
        assertEquals(restoredResourceId, element(swapped, "DbiResourceId"));
        assertTrue(swapped.contains(":db:" + SOURCE + "</DBInstanceArn>"), swapped);
        assertEquals(sourceResourceId, element(describe(DISPLACED), "DbiResourceId"));
        query("DescribeDBInstances").formParam("DBInstanceIdentifier", RESTORED)
        .when().post("/").then().statusCode(404).body(containsString("DBInstanceNotFound"));
        // The swapped-in instance serves its data on the original endpoint name.
        assertEquals("marker:from-source", firstMarker(swapped));
    }

    private static String describe(String id) {
        return query("DescribeDBInstances").formParam("DBInstanceIdentifier", id)
                .when().post("/").then().statusCode(200).extract().asString();
    }

    private static String element(String xml, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        assertTrue(matcher.find(), name + " missing from " + xml);
        return matcher.group(1);
    }

    private static String jdbcUrl(String instanceXml) {
        Matcher endpoint = Pattern.compile("<Endpoint>\\s*<Address>([^<]+)</Address>\\s*<Port>(\\d+)</Port>")
                .matcher(instanceXml);
        assertTrue(endpoint.find(), instanceXml);
        return "jdbc:postgresql://" + endpoint.group(1) + ":" + endpoint.group(2)
                + "/appdb?sslmode=require&sslfactory=org.postgresql.ssl.NonValidatingFactory";
    }

    private static void execute(String instanceXml, String sql) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .ignoreExceptions().untilAsserted(() -> {
                    try (Connection connection = DriverManager.getConnection(jdbcUrl(instanceXml), "admin", "password123");
                         Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                });
    }

    private static String firstMarker(String instanceXml) {
        String[] value = new String[1];
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .ignoreExceptions().untilAsserted(() -> {
                    try (Connection connection = DriverManager.getConnection(jdbcUrl(instanceXml), "admin", "password123");
                         Statement statement = connection.createStatement();
                         ResultSet rows = statement.executeQuery("SELECT v FROM marker")) {
                        assertTrue(rows.next());
                        value[0] = "marker:" + rows.getString(1);
                    }
                });
        return value[0];
    }
}
