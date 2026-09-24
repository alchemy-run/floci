package io.github.hectorvent.floci.services.elasticache.container;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValkeyClusterFormationTest {

    private final List<FakeNode> nodes = new ArrayList<>();

    @AfterEach
    void closeNodes() throws IOException {
        for (FakeNode node : nodes) {
            node.close();
        }
    }

    @Test
    void slotOwnersExpandsRangesAndSingleSlots() {
        String[] owners = ValkeyClusterFormation.slotOwners(Map.of("0001", "0-9,12", "0002", "13-16383"));

        assertEquals("0001", owners[0]);
        assertEquals("0001", owners[9]);
        assertNull(owners[10]);
        assertEquals("0001", owners[12]);
        assertEquals("0002", owners[16383]);
    }

    @Test
    void slotOwnerIdsReadsAClusterSlotsReply() {
        List<Object> reply = List.of(
                List.of(0L, 8191L, List.of("10.0.0.1", 6379L, "node-a")),
                List.of(8192L, 16383L, List.of("10.0.0.2", 6379L, "node-b"), List.of("10.0.0.3", 6379L, "node-c")));

        String[] owners = ValkeyClusterFormation.slotOwnerIds(reply);

        assertEquals("node-a", owners[0]);
        assertEquals("node-a", owners[8191]);
        assertEquals("node-b", owners[8192]);
        assertEquals("node-b", owners[16383]);
    }

    @Test
    void pipelineReturnsErrorsInPlaceAndKeepsReading() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    OutputStream out = socket.getOutputStream();
                    out.write("+OK\r\n-ERR nope\r\n:5\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    socket.getInputStream().readNBytes(1);
                } catch (IOException ignored) {
                    // the client closing first ends the exchange; nothing to assert here
                }
            });
            ValkeyClusterFormation.RespClient client =
                    new ValkeyClusterFormation.RespClient("127.0.0.1", server.getLocalPort());
            try {
                List<Object> replies = client.pipeline(List.of(
                        new String[] {"PING"}, new String[] {"BAD"}, new String[] {"DBSIZE"}));

                assertEquals("OK", replies.get(0));
                assertInstanceOf(ValkeyClusterFormation.RespError.class, replies.get(1));
                assertEquals(5L, replies.get(2));
            } finally {
                client.closeQuietly();
            }
        }
    }

    @Test
    void addingAShardMovesSlotsAndTheirKeysToTheJoiningNode() throws IOException {
        FakeCluster cluster = new FakeCluster();
        FakeNode first = start(cluster, "10.0.0.1", true);
        FakeNode second = start(cluster, "10.0.0.2", true);
        FakeNode joining = start(cluster, "10.0.0.3", false);
        cluster.assign(0, 8191, first.id);
        cluster.assign(8192, 16383, second.id);
        cluster.keySlots.put("moved-key", 12000);
        cluster.keyOwners.put("moved-key", second.id);
        cluster.keySlots.put("kept-key", 100);
        cluster.keyOwners.put("kept-key", first.id);

        new ValkeyClusterFormation().reshard("grp", List.of(
                        member(first, "0001", false, false),
                        member(second, "0002", false, false),
                        member(joining, "0003", true, false)),
                Map.of("0001", "0-8191", "0002", "8192-16383"),
                Map.of("0001", "0-5460", "0002", "5461-10921", "0003", "10922-16383"));

        assertEquals(first.id, cluster.owners[5460]);
        assertEquals(second.id, cluster.owners[5461]);
        assertEquals(second.id, cluster.owners[10921]);
        assertEquals(joining.id, cluster.owners[10922]);
        assertEquals(joining.id, cluster.owners[16383]);
        assertEquals(joining.id, cluster.keyOwners.get("moved-key"));
        assertEquals(first.id, cluster.keyOwners.get("kept-key"));
        assertTrue(cluster.known.contains(joining.id));
    }

    @Test
    void removingAShardEmptiesItAndForgetsItsNodes() throws IOException {
        FakeCluster cluster = new FakeCluster();
        FakeNode first = start(cluster, "10.0.0.1", true);
        FakeNode leaving = start(cluster, "10.0.0.2", true);
        FakeNode third = start(cluster, "10.0.0.3", true);
        cluster.assign(0, 5460, first.id);
        cluster.assign(5461, 10921, leaving.id);
        cluster.assign(10922, 16383, third.id);
        cluster.keySlots.put("orphan-key", 7000);
        cluster.keyOwners.put("orphan-key", leaving.id);

        new ValkeyClusterFormation().reshard("grp", List.of(
                        member(first, "0001", false, false),
                        member(leaving, "0002", false, true),
                        member(third, "0003", false, false)),
                Map.of("0001", "0-5460", "0002", "5461-10921", "0003", "10922-16383"),
                Map.of("0001", "0-8191", "0003", "8192-16383"));

        for (int slot = 0; slot < 16384; slot++) {
            assertFalse(leaving.id.equals(cluster.owners[slot]), "slot " + slot + " still on the removed shard");
        }
        assertEquals(first.id, cluster.owners[8191]);
        assertEquals(third.id, cluster.owners[8192]);
        assertEquals(first.id, cluster.keyOwners.get("orphan-key"));
        assertFalse(cluster.known.contains(leaving.id));
        assertArrayEquals(new String[] {first.id, third.id}, cluster.known.toArray(new String[0]));
    }

    private FakeNode start(FakeCluster cluster, String networkIp, boolean known) throws IOException {
        FakeNode node = new FakeNode(cluster, networkIp, "node-" + networkIp.replace('.', '-'));
        cluster.byIp.put(networkIp, node.id);
        if (known) {
            cluster.known.add(node.id);
        }
        nodes.add(node);
        return node;
    }

    private static ValkeyClusterFormation.ShardMember member(FakeNode node, String nodeGroupId,
                                                             boolean joining, boolean leaving) {
        return new ValkeyClusterFormation.ShardMember("127.0.0.1", node.server.getLocalPort(),
                node.networkIp, nodeGroupId, true, joining, leaving);
    }

    /** Cluster-wide state the fake nodes share, standing in for gossip. */
    private static final class FakeCluster {
        final String[] owners = new String[16384];
        final Set<String> known = new LinkedHashSet<>();
        final Map<String, String> byIp = new ConcurrentHashMap<>();
        final Map<String, Integer> keySlots = new ConcurrentHashMap<>();
        final Map<String, String> keyOwners = new ConcurrentHashMap<>();
        final Map<String, String> migrating = new ConcurrentHashMap<>();
        final Map<String, String> importing = new ConcurrentHashMap<>();

        void assign(int start, int end, String id) {
            for (int slot = start; slot <= end; slot++) {
                owners[slot] = id;
            }
        }

        synchronized List<String> keysIn(String nodeId, int slot) {
            List<String> keys = new ArrayList<>();
            keySlots.forEach((key, keySlot) -> {
                if (keySlot == slot && nodeId.equals(keyOwners.get(key))) {
                    keys.add(key);
                }
            });
            return keys;
        }
    }

    /** A single-connection RESP server answering the cluster commands a reshard issues. */
    private static final class FakeNode {
        final FakeCluster cluster;
        final String networkIp;
        final String id;
        final ServerSocket server;

        FakeNode(FakeCluster cluster, String networkIp, String id) throws IOException {
            this.cluster = cluster;
            this.networkIp = networkIp;
            this.id = id;
            this.server = new ServerSocket(0);
            Thread.ofVirtual().start(this::serve);
        }

        void close() throws IOException {
            server.close();
        }

        private void serve() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread.ofVirtual().start(() -> handle(socket));
                } catch (IOException expected) {
                    // closing the server socket ends the accept loop
                    return;
                }
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                while (true) {
                    List<String> command = readCommand(in);
                    if (command == null) {
                        return;
                    }
                    out.write(answer(command).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException expected) {
                // the client disconnecting ends the session
            }
        }

        private String answer(List<String> command) {
            String name = command.get(0).toUpperCase();
            if ("MIGRATE".equals(name)) {
                String target = cluster.byIp.get(command.get(1));
                int keysAt = command.indexOf("KEYS");
                for (String key : command.subList(keysAt + 1, command.size())) {
                    cluster.keyOwners.put(key, target);
                }
                return "+OK\r\n";
            }
            String sub = command.get(1).toUpperCase();
            synchronized (cluster) {
                return switch (sub) {
                    case "MYID" -> bulk(id);
                    case "MEET" -> {
                        cluster.known.add(cluster.byIp.get(command.get(2)));
                        yield "+OK\r\n";
                    }
                    case "INFO" -> bulk("cluster_state:ok\r\ncluster_known_nodes:" + cluster.known.size() + "\r\n");
                    case "REPLICATE", "SET-CONFIG-EPOCH" -> "+OK\r\n";
                    case "FORGET" -> {
                        cluster.known.remove(command.get(2));
                        yield "+OK\r\n";
                    }
                    case "ADDSLOTS" -> {
                        cluster.owners[Integer.parseInt(command.get(2))] = id;
                        yield "+OK\r\n";
                    }
                    case "COUNTKEYSINSLOT" -> ":" + cluster.keysIn(id, Integer.parseInt(command.get(2))).size() + "\r\n";
                    case "GETKEYSINSLOT" -> array(cluster.keysIn(id, Integer.parseInt(command.get(2))));
                    case "SETSLOT" -> setSlot(Integer.parseInt(command.get(2)), command.get(3).toUpperCase(), command.get(4));
                    case "SLOTS" -> slots();
                    default -> "-ERR unknown subcommand " + sub + "\r\n";
                };
            }
        }

        private String setSlot(int slot, String mode, String nodeId) {
            String key = slot + "";
            switch (mode) {
                case "IMPORTING" -> {
                    if (id.equals(cluster.owners[slot])) {
                        return "-ERR I'm already the owner of hash slot " + slot + "\r\n";
                    }
                    cluster.importing.put(key, nodeId);
                }
                case "MIGRATING" -> {
                    if (!id.equals(cluster.owners[slot])) {
                        return "-ERR I'm not the owner of hash slot " + slot + "\r\n";
                    }
                    cluster.migrating.put(key, nodeId);
                }
                case "NODE" -> {
                    if (id.equals(cluster.owners[slot]) && !nodeId.equals(id) && !cluster.keysIn(id, slot).isEmpty()) {
                        return "-ERR I still hold keys in slot " + slot + "\r\n";
                    }
                    cluster.owners[slot] = nodeId;
                    cluster.migrating.remove(key);
                    cluster.importing.remove(key);
                }
                default -> {
                    return "-ERR bad SETSLOT mode\r\n";
                }
            }
            return "+OK\r\n";
        }

        private String slots() {
            StringBuilder reply = new StringBuilder();
            int count = 0;
            int start = 0;
            for (int slot = 1; slot <= 16384; slot++) {
                if (slot == 16384 || !String.valueOf(cluster.owners[slot]).equals(String.valueOf(cluster.owners[start]))) {
                    if (cluster.owners[start] != null) {
                        reply.append("*3\r\n:").append(start).append("\r\n:").append(slot - 1).append("\r\n")
                                .append("*3\r\n").append(bulk("127.0.0.1")).append(":6379\r\n")
                                .append(bulk(cluster.owners[start]));
                        count++;
                    }
                    start = slot;
                }
            }
            return "*" + count + "\r\n" + reply;
        }

        private static String bulk(String value) {
            return "$" + value.getBytes(StandardCharsets.UTF_8).length + "\r\n" + value + "\r\n";
        }

        private static String array(List<String> values) {
            StringBuilder reply = new StringBuilder("*").append(values.size()).append("\r\n");
            values.forEach(value -> reply.append(bulk(value)));
            return reply.toString();
        }

        private static List<String> readCommand(InputStream in) throws IOException {
            String header = readLine(in);
            if (header == null) {
                return null;
            }
            int count = Integer.parseInt(header.substring(1));
            List<String> args = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = Integer.parseInt(readLine(in).substring(1));
                byte[] data = in.readNBytes(length);
                in.readNBytes(2);
                args.add(new String(data, StandardCharsets.UTF_8));
            }
            return args;
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder line = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\r') {
                    in.read();
                    return line.toString();
                }
                line.append((char) b);
            }
            return null;
        }
    }
}
