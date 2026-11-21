package io.floci.aws.services.keyspaces;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon Keyspaces: keyspaces, tables and types over JSON 1.0, auto scaling through Application Auto Scaling, CQL
 * over the native protocol (a minimal client here speaks it frame by frame), CDC streams of the writes, and
 * point-in-time restore.
 */
@QuarkusTest
class KeyspacesIntegrationTest {

    static final int CQL_PORT = 19142;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static ValidatableResponse keyspaces(String action, String body) {
        return given().header("Authorization", auth("cassandra")).header("X-Amz-Target", "KeyspacesService." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }

    private static ValidatableResponse streams(String action, String body) {
        return given().header("Authorization", auth("cassandra")).header("X-Amz-Target", "KeyspacesStreams." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }

    private static void awaitTable(String keyspace, String table) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                keyspaces("GetTable", "{\"keyspaceName\":\"" + keyspace + "\",\"tableName\":\"" + table + "\"}")
                        .statusCode(200).body("status", equalTo("ACTIVE")));
    }

    @Test
    void managesKeyspacesTablesAndTypes() {
        keyspaces("ListKeyspaces", "{}").statusCode(200).body("keyspaces.keyspaceName", hasItem("system_schema"));
        keyspaces("CreateKeyspace", "{\"keyspaceName\":\"inventory\",\"tags\":[{\"key\":\"team\",\"value\":\"ops\"}]}")
                .statusCode(200).body("resourceArn", equalTo("arn:aws:cassandra:us-east-1:000000000000:/keyspace/"
                        + "inventory/"));
        keyspaces("CreateKeyspace", "{\"keyspaceName\":\"inventory\"}").statusCode(409)
                .body("__type", containsString("ConflictException"));
        keyspaces("CreateKeyspace", "{\"keyspaceName\":\"multi\",\"replicationSpecification\":{"
                + "\"replicationStrategy\":\"MULTI_REGION\",\"regionList\":[\"us-west-2\",\"eu-west-1\"]}}").statusCode(400)
                .body("message", containsString("us-east-1"));
        keyspaces("GetKeyspace", "{\"keyspaceName\":\"missing\"}").statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));

        keyspaces("CreateType", "{\"keyspaceName\":\"inventory\",\"typeName\":\"address\",\"fieldDefinitions\":["
                + "{\"name\":\"street\",\"type\":\"text\"},{\"name\":\"zip\",\"type\":\"int\"}]}").statusCode(200);
        keyspaces("CreateTable", "{\"keyspaceName\":\"inventory\",\"tableName\":\"bad\",\"schemaDefinition\":{"
                + "\"allColumns\":[{\"name\":\"id\",\"type\":\"list<int>\"}],\"partitionKeys\":[{\"name\":\"id\"}]}}")
                .statusCode(400).body("message", containsString("frozen"));
        keyspaces("CreateTable", "{\"keyspaceName\":\"inventory\",\"tableName\":\"bad\",\"schemaDefinition\":{"
                + "\"allColumns\":[{\"name\":\"id\",\"type\":\"int\"},{\"name\":\"n\",\"type\":\"counter\"},{\"name\":"
                + "\"x\",\"type\":\"text\"}],\"partitionKeys\":[{\"name\":\"id\"}]}}").statusCode(400)
                .body("message", containsString("counter"));
        keyspaces("CreateTable", "{\"keyspaceName\":\"inventory\",\"tableName\":\"items\",\"schemaDefinition\":{"
                + "\"allColumns\":[{\"name\":\"sku\",\"type\":\"text\"},{\"name\":\"added\",\"type\":\"timestamp\"},"
                + "{\"name\":\"ship_to\",\"type\":\"frozen<address>\"}],\"partitionKeys\":[{\"name\":\"sku\"}],"
                + "\"clusteringKeys\":[{\"name\":\"added\",\"orderBy\":\"DESC\"}]},\"capacitySpecification\":{"
                + "\"throughputMode\":\"PROVISIONED\",\"readCapacityUnits\":5,\"writeCapacityUnits\":5},"
                + "\"pointInTimeRecovery\":{\"status\":\"ENABLED\"},\"autoScalingSpecification\":{"
                + "\"readCapacityAutoScaling\":{\"minimumUnits\":5,\"maximumUnits\":50,\"scalingPolicy\":{"
                + "\"targetTrackingScalingPolicyConfiguration\":{\"targetValue\":70}}}}}").statusCode(200);
        keyspaces("GetTable", "{\"keyspaceName\":\"inventory\",\"tableName\":\"items\"}").statusCode(200)
                .body("status", equalTo("CREATING"));
        awaitTable("inventory", "items");
        keyspaces("GetTable", "{\"keyspaceName\":\"inventory\",\"tableName\":\"items\"}").statusCode(200)
                .body("pointInTimeRecovery.earliestRestorableTimestamp", notNullValue())
                .body("capacitySpecification.readCapacityUnits", equalTo(5));
        keyspaces("GetTableAutoScalingSettings", "{\"keyspaceName\":\"inventory\",\"tableName\":\"items\"}")
                .statusCode(200).body("autoScalingSpecification.readCapacityAutoScaling.maximumUnits", equalTo(50))
                .body("autoScalingSpecification.writeCapacityAutoScaling.autoScalingDisabled", equalTo(true));
        given().header("Authorization", auth("application-autoscaling"))
                .header("X-Amz-Target", "AnyScaleFrontendService.DescribeScalableTargets")
                .contentType("application/x-amz-json-1.1").body("{\"ServiceNamespace\":\"cassandra\"}").post("/")
                .then().statusCode(200).body("ScalableTargets.ResourceId", hasItem("keyspace/inventory/table/items"));
        keyspaces("GetType", "{\"keyspaceName\":\"inventory\",\"typeName\":\"address\"}").statusCode(200)
                .body("directReferringTables", hasItem("items"));
        keyspaces("DeleteType", "{\"keyspaceName\":\"inventory\",\"typeName\":\"address\"}").statusCode(400)
                .body("message", containsString("items"));

        keyspaces("UpdateTable", "{\"keyspaceName\":\"inventory\",\"tableName\":\"items\",\"addColumns\":[{\"name\":"
                + "\"qty\",\"type\":\"int\"}],\"ttl\":{\"status\":\"ENABLED\"},\"defaultTimeToLive\":3600}")
                .statusCode(200);
        keyspaces("UpdateTable", "{\"keyspaceName\":\"inventory\",\"tableName\":\"items\",\"addColumns\":[{\"name\":"
                + "\"more\",\"type\":\"int\"}]}").statusCode(409);
        awaitTable("inventory", "items");
        keyspaces("GetTable", "{\"keyspaceName\":\"inventory\",\"tableName\":\"items\"}").statusCode(200)
                .body("schemaDefinition.allColumns.name", hasItem("qty")).body("defaultTimeToLive", equalTo(3600));

        String arn = "arn:aws:cassandra:us-east-1:000000000000:/keyspace/inventory/table/items";
        keyspaces("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"env\",\"value\":\"test\"}]}")
                .statusCode(200);
        keyspaces("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}").statusCode(200)
                .body("tags.key", hasItem("env"));
        keyspaces("ListTables", "{\"keyspaceName\":\"inventory\"}").statusCode(200)
                .body("tables.tableName", hasItem("items"));
        keyspaces("DeleteTable", "{\"keyspaceName\":\"inventory\",\"tableName\":\"items\"}").statusCode(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> keyspaces("GetTable", "{\"keyspaceName\":"
                + "\"inventory\",\"tableName\":\"items\"}").statusCode(404));
        keyspaces("DeleteType", "{\"keyspaceName\":\"inventory\",\"typeName\":\"address\"}").statusCode(200);
        keyspaces("DeleteKeyspace", "{\"keyspaceName\":\"inventory\"}").statusCode(200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> keyspaces("ListKeyspaces", "{}").statusCode(200)
                .body("keyspaces.keyspaceName", not(hasItem("inventory"))));
    }

    @Test
    void streamsWritesAndRestoresThroughCql() throws IOException {
        given().header("Authorization", auth("iam")).formParam("Action", "CreateUser").formParam("UserName", "cql")
                .post("/").then().statusCode(200);
        XmlPath created = given().header("Authorization", auth("iam"))
                .formParam("Action", "CreateServiceSpecificCredential").formParam("UserName", "cql")
                .formParam("ServiceName", "cassandra.amazonaws.com").post("/").then().statusCode(200).extract()
                .xmlPath();
        String prefix = "CreateServiceSpecificCredentialResponse.CreateServiceSpecificCredentialResult"
                + ".ServiceSpecificCredential.";
        String user = created.getString(prefix + "ServiceUserName");
        String password = created.getString(prefix + "ServicePassword");

        try (Cql bad = new Cql()) {
            assertEquals(CqlError.BAD_CREDENTIALS, bad.login(user, "wrong").errorCode());
        }
        try (Cql cql = new Cql()) {
            assertEquals(0x10, cql.login(user, password).opcode());
            Cql.Frame local = cql.query("SELECT cluster_name FROM system.local", 0x0001);
            assertEquals(0x08, local.opcode());
            assertEquals(List.of("Amazon Keyspaces"), local.texts());

            assertEquals(0x08, cql.query("CREATE KEYSPACE shop WITH replication = {'class': 'SingleRegionStrategy'}",
                    0x0006).opcode());
            cql.query("CREATE TABLE shop.orders (id int PRIMARY KEY, note text) WITH custom_properties = "
                    + "{'point_in_time_recovery': {'status': 'enabled'}}", 0x0006);
            awaitTable("shop", "orders");
            keyspaces("UpdateTable", "{\"keyspaceName\":\"shop\",\"tableName\":\"orders\",\"cdcSpecification\":{"
                    + "\"status\":\"ENABLED\",\"viewType\":\"NEW_AND_OLD_IMAGES\"}}").statusCode(200);
            awaitTable("shop", "orders");
            assertEquals(CqlError.INVALID, cql.query("INSERT INTO shop.orders (id, note) VALUES (1, 'x')", 0x0001)
                    .errorCode());
            cql.query("INSERT INTO shop.orders (id, note) VALUES (1, 'first')", 0x0006);
            cql.query("UPDATE shop.orders SET note = 'second' WHERE id = 1", 0x0006);
            long between = System.currentTimeMillis();
            await().pollDelay(Duration.ofMillis(1100)).until(() -> true);
            cql.query("DELETE FROM shop.orders WHERE id = 1", 0x0006);
            assertEquals(List.of(), cql.query("SELECT note FROM shop.orders WHERE id = 1", 0x0006).texts());
            assertEquals(CqlError.INVALID, cql.query("SELECT * FROM shop.orders WHERE note = 'x'", 0x0006)
                    .errorCode());

            String streamArn = keyspaces("GetTable", "{\"keyspaceName\":\"shop\",\"tableName\":\"orders\"}")
                    .statusCode(200).extract().path("latestStreamArn");
            streams("ListStreams", "{\"keyspaceName\":\"shop\"}").statusCode(200)
                    .body("streams.streamArn", hasItem(streamArn));
            String shard = streams("GetStream", "{\"streamArn\":\"" + streamArn + "\"}").statusCode(200)
                    .body("streamViewType", equalTo("NEW_AND_OLD_IMAGES")).extract().path("shards[0].shardId");
            String iterator = streams("GetShardIterator", "{\"streamArn\":\"" + streamArn + "\",\"shardId\":\""
                    + shard + "\",\"shardIteratorType\":\"TRIM_HORIZON\"}").statusCode(200).extract()
                    .path("shardIterator");
            streams("GetRecords", "{\"shardIterator\":\"" + iterator + "\"}").statusCode(200)
                    .body("changeRecords", hasSize(3))
                    .body("changeRecords[0].partitionKeys.id.intT", equalTo("1"))
                    .body("changeRecords[0].newImage.valueCells.note.value.textT", equalTo("first"))
                    .body("changeRecords[1].oldImage.valueCells.note.value.textT", equalTo("first"))
                    .body("changeRecords[2].newImage", equalTo(null));

            keyspaces("RestoreTable", "{\"sourceKeyspaceName\":\"shop\",\"sourceTableName\":\"orders\","
                    + "\"targetKeyspaceName\":\"shop\",\"targetTableName\":\"orders_then\",\"restoreTimestamp\":"
                    + (between / 1000.0) + "}").statusCode(200)
                    .body("restoredTableARN", containsString("/table/orders_then"));
            keyspaces("GetTable", "{\"keyspaceName\":\"shop\",\"tableName\":\"orders_then\"}").statusCode(200)
                    .body("status", equalTo("RESTORING"));
            awaitTable("shop", "orders_then");
            assertEquals(List.of("second"), cql.query("SELECT note FROM shop.orders_then WHERE id = 1", 0x0006)
                    .texts());
            keyspaces("RestoreTable", "{\"sourceKeyspaceName\":\"shop\",\"sourceTableName\":\"orders_then\","
                    + "\"targetKeyspaceName\":\"shop\",\"targetTableName\":\"again\"}").statusCode(400)
                    .body("message", containsString("Point-in-time recovery"));
        }
    }

    /** A minimal CQL native protocol v4 client. */
    static final class Cql implements AutoCloseable {
        record Frame(int opcode, byte[] body) {
            int errorCode() {
                return opcode == 0 ? new CqlCodec.In(body).i() : -1;
            }

            /** The text values of a rows result's first column. */
            List<String> texts() {
                CqlCodec.In in = new CqlCodec.In(body);
                assertEquals(2, in.i());
                int flags = in.i();
                int columns = in.i();
                if ((flags & 0x0002) != 0) {
                    in.bytes();
                }
                if ((flags & 0x0001) != 0) {
                    in.string();
                    in.string();
                }
                for (int c = 0; c < columns; c++) {
                    if ((flags & 0x0001) == 0) {
                        in.string();
                        in.string();
                    }
                    in.string();
                    int type = in.s();
                    assertTrue(type < 0x20, "a simple type");
                }
                int rows = in.i();
                List<String> out = new ArrayList<>();
                for (int r = 0; r < rows; r++) {
                    out.add(new String(in.bytes(), StandardCharsets.UTF_8));
                    for (int c = 1; c < columns; c++) {
                        in.bytes();
                    }
                }
                return out;
            }
        }

        private final Socket socket = new Socket("127.0.0.1", CQL_PORT);
        private final DataInputStream input = new DataInputStream(socket.getInputStream());
        private short stream;

        Cql() throws IOException {
        }

        Frame send(int opcode, byte[] body) throws IOException {
            CqlCodec.Out frame = new CqlCodec.Out().b(0x04).b(0).s(++stream).b(opcode).i(body.length).raw(body);
            OutputStream out = socket.getOutputStream();
            out.write(frame.toBytes());
            out.flush();
            int version = input.readUnsignedByte();
            assertEquals(0x84, version);
            input.readUnsignedByte();
            input.readShort();
            int reply = input.readUnsignedByte();
            byte[] data = new byte[input.readInt()];
            input.readFully(data);
            return new Frame(reply, data);
        }

        Frame login(String user, String password) throws IOException {
            CqlCodec.Out startup = new CqlCodec.Out().s(1).string("CQL_VERSION").string("3.4.4");
            assertEquals(0x03, send(0x01, startup.toBytes()).opcode());
            byte[] token = ("\u0000" + user + "\u0000" + password).getBytes(StandardCharsets.UTF_8);
            return send(0x0F, new CqlCodec.Out().bytes(token).toBytes());
        }

        Frame query(String cql, int consistency) throws IOException {
            return send(0x07, new CqlCodec.Out().longString(cql).s(consistency).b(0).toBytes());
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
