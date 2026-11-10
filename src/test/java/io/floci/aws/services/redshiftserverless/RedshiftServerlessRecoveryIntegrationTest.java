package io.floci.aws.services.redshiftserverless;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Recovery points and table restores against a real workgroup: the sweep dumps the namespace, a
 * point restores the whole database or one table, converts to a snapshot, and a snapshot restores
 * one table. Needs Docker.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedshiftServerlessRecoveryIntegrationTest {

    private static final String NAMESPACE = "rp-it-ns";
    private static final String WORKGROUP = "rp-it-wg";
    private static final String PASSWORD = "Secret123";
    private static final String REGION = "us-east-1";

    @Inject
    RedshiftServerlessService service;

    @Inject
    RedshiftServerlessRecoveryService recovery;

    private int port;
    private boolean created;

    @BeforeEach
    void ensureWorkgroup() throws SQLException {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for Redshift Serverless runtime tests");
        RestAssuredJsonUtils.configureAwsContentTypes();
        if (created) {
            return;
        }
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateNamespace", """
                {"namespaceName":"%s","adminUsername":"admin","adminUserPassword":"%s","dbName":"dev"}
                """.formatted(NAMESPACE, PASSWORD)).then().statusCode(200);
        created = true;
        ExtractableResponse<Response> workgroup = RestAssuredJsonUtils.awsAction("RedshiftServerless",
                "CreateWorkgroup", """
                {"workgroupName":"%s","namespaceName":"%s"}
                """.formatted(WORKGROUP, NAMESPACE)).then().statusCode(200).extract();
        port = workgroup.path("workgroup.endpoint.port");
        sql("CREATE SCHEMA sales; CREATE TABLE sales.orders (id int, item text); "
                + "INSERT INTO sales.orders VALUES (1, 'first'), (2, 'second')");
    }

    @AfterAll
    void deleteWorkgroup() {
        if (created) {
            service.deleteWorkgroup(WORKGROUP, REGION);
            service.deleteNamespace(NAMESPACE, REGION);
        }
    }

    @Test
    @Order(1)
    void aRecoveryPointRestoresTablesTheDatabaseAndBecomesASnapshot() throws SQLException {
        recovery.sweep(Instant.now());
        String pointId = RestAssuredJsonUtils.awsAction("RedshiftServerless", "ListRecoveryPoints",
                "{\"namespaceName\":\"" + NAMESPACE + "\"}").then().statusCode(200)
                .body("recoveryPoints[0].workgroupName", equalTo(WORKGROUP))
                .body("recoveryPoints[0].namespaceArn", containsString(":namespace/"))
                .extract().path("recoveryPoints[0].recoveryPointId");
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "GetRecoveryPoint",
                "{\"recoveryPointId\":\"" + pointId + "\"}").then().statusCode(200)
                .body("recoveryPoint.namespaceName", equalTo(NAMESPACE));

        // A second sweep within the interval takes no new point.
        recovery.sweep(Instant.now());
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "ListRecoveryPoints",
                "{\"namespaceName\":\"" + NAMESPACE + "\"}").then().statusCode(200)
                .body("recoveryPoints.size()", equalTo(1));

        sql("DELETE FROM sales.orders WHERE id = 1");

        // One table back under a new name, in a new schema.
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "RestoreTableFromRecoveryPoint", """
                {"namespaceName":"%s","workgroupName":"%s","recoveryPointId":"%s","sourceDatabaseName":"dev",
                 "sourceSchemaName":"sales","sourceTableName":"orders","targetSchemaName":"restored",
                 "newTableName":"orders_before"}""".formatted(NAMESPACE, WORKGROUP, pointId))
                .then().statusCode(200)
                .body("tableRestoreStatus.status", equalTo("SUCCEEDED"))
                .body("tableRestoreStatus.recoveryPointId", equalTo(pointId));
        assertEquals(List.of("1:first", "2:second"), rows("SELECT id, item FROM restored.orders_before ORDER BY id"));
        assertEquals(List.of("2:second"), rows("SELECT id, item FROM sales.orders ORDER BY id"));

        // The same target again fails the restore, not the request.
        String failedId = RestAssuredJsonUtils.awsAction("RedshiftServerless", "RestoreTableFromRecoveryPoint", """
                {"namespaceName":"%s","workgroupName":"%s","recoveryPointId":"%s","sourceDatabaseName":"dev",
                 "sourceSchemaName":"sales","sourceTableName":"orders","targetSchemaName":"restored",
                 "newTableName":"orders_before"}""".formatted(NAMESPACE, WORKGROUP, pointId))
                .then().statusCode(200)
                .body("tableRestoreStatus.status", equalTo("FAILED"))
                .body("tableRestoreStatus.message", containsString("already exists"))
                .extract().path("tableRestoreStatus.tableRestoreRequestId");
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "GetTableRestoreStatus",
                "{\"tableRestoreRequestId\":\"" + failedId + "\"}").then().statusCode(200)
                .body("tableRestoreStatus.status", equalTo("FAILED"));

        // The point becomes a snapshot that restores a table too.
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "ConvertRecoveryPointToSnapshot",
                "{\"recoveryPointId\":\"" + pointId + "\",\"snapshotName\":\"rp-it-snap\",\"retentionPeriod\":1}")
                .then().statusCode(200)
                .body("snapshot.snapshotName", equalTo("rp-it-snap"))
                .body("snapshot.namespaceName", equalTo(NAMESPACE));
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "RestoreTableFromSnapshot", """
                {"namespaceName":"%s","workgroupName":"%s","snapshotName":"rp-it-snap","sourceDatabaseName":"dev",
                 "sourceSchemaName":"sales","sourceTableName":"ORDERS","newTableName":"Orders_Snap"}"""
                .formatted(NAMESPACE, WORKGROUP))
                .then().statusCode(200)
                .body("tableRestoreStatus.status", equalTo("SUCCEEDED"))
                .body("tableRestoreStatus.newTableName", equalTo("orders_snap"))
                .body("tableRestoreStatus.targetSchemaName", equalTo("sales"));
        assertEquals(List.of("1:first", "2:second"), rows("SELECT id, item FROM sales.orders_snap ORDER BY id"));

        // A table the point does not hold fails the restore.
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "RestoreTableFromSnapshot", """
                {"namespaceName":"%s","workgroupName":"%s","snapshotName":"rp-it-snap","sourceDatabaseName":"dev",
                 "sourceTableName":"missing","newTableName":"missing_copy"}""".formatted(NAMESPACE, WORKGROUP))
                .then().statusCode(200)
                .body("tableRestoreStatus.status", equalTo("FAILED"))
                .body("tableRestoreStatus.message", containsString("not in the snapshot"));

        RestAssuredJsonUtils.awsAction("RedshiftServerless", "ListTableRestoreStatus",
                "{\"workgroupName\":\"" + WORKGROUP + "\"}").then().statusCode(200)
                .body("tableRestoreStatuses.size()", equalTo(4))
                .body("tableRestoreStatuses.tableRestoreRequestId", hasItem(failedId));

        // The whole database back as the point had it.
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "RestoreFromRecoveryPoint", """
                {"namespaceName":"%s","workgroupName":"%s","recoveryPointId":"%s"}"""
                .formatted(NAMESPACE, WORKGROUP, pointId))
                .then().statusCode(200)
                .body("recoveryPointId", equalTo(pointId))
                .body("namespace.status", equalTo("AVAILABLE"));
        assertEquals(List.of("1:first", "2:second"), rows("SELECT id, item FROM sales.orders ORDER BY id"));

        RestAssuredJsonUtils.awsAction("RedshiftServerless", "DeleteSnapshot",
                "{\"snapshotName\":\"rp-it-snap\"}").then().statusCode(200);
    }

    @Test
    @Order(2)
    void anUnknownRecoveryPointOrRestoreRequestIsNotFound() {
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "GetRecoveryPoint",
                "{\"recoveryPointId\":\"00000000-0000-0000-0000-000000000000\"}").then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "GetTableRestoreStatus",
                "{\"tableRestoreRequestId\":\"00000000-0000-0000-0000-000000000000\"}").then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "RestoreFromRecoveryPoint", """
                {"namespaceName":"%s","workgroupName":"%s","recoveryPointId":"00000000-0000-0000-0000-000000000000"}"""
                .formatted(NAMESPACE, WORKGROUP)).then().statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    @Order(3)
    void pointsExpireAfterADay() {
        Instant later = Instant.now().plus(Duration.ofHours(25));
        recovery.sweep(Instant.now());
        recovery.sweep(later);
        // The later sweep dropped the day-old point and took a fresh one stamped "later".
        RestAssuredJsonUtils.awsAction("RedshiftServerless", "ListRecoveryPoints",
                "{\"namespaceName\":\"" + NAMESPACE + "\",\"startTime\":" + Instant.now().plus(Duration.ofHours(1))
                        .getEpochSecond() + "}").then().statusCode(200)
                .body("recoveryPoints.size()", equalTo(1));
    }

    private void sql(String statements) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(statements);
        }
    }

    private List<String> rows(String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                rows.add(result.getInt(1) + ":" + result.getString(2));
            }
        }
        return rows;
    }

    private Connection connect() {
        return Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .until(() -> DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/dev", "admin",
                        PASSWORD), Objects::nonNull);
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
