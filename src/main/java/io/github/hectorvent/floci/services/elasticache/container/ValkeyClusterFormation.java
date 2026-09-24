package io.github.hectorvent.floci.services.elasticache.container;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Forms a Valkey cluster out of freshly started cluster-enabled nodes, the way
 * {@code valkey-cli --cluster create} does: distinct config epochs, MEET, slot
 * assignment on the primaries, then REPLICATE for the replicas.
 *
 * <p>Commands are issued from the JVM over each node's Floci-reachable endpoint;
 * the addresses handed to MEET are the nodes' Docker-network IPs, because that is
 * where the nodes reach <em>each other</em>.
 */
@ApplicationScoped
public class ValkeyClusterFormation {

    private static final Logger LOG = Logger.getLogger(ValkeyClusterFormation.class);

    private static final int BACKEND_PORT = 6379;
    private static final int TOTAL_SLOTS = 16384;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final long FORMATION_DEADLINE_MS = 60_000;
    private static final long RETRY_SLEEP_MS = 200;
    private static final long RESHARD_DEADLINE_MS = 120_000;
    private static final int SLOT_BATCH = 512;
    private static final int MIGRATE_KEY_BATCH = 100;
    private static final int MIGRATE_TIMEOUT_MS = 5_000;

    /**
     * One node to join into the cluster.
     *
     * @param endpointHost host Floci uses to reach the node
     * @param endpointPort port Floci uses to reach the node
     * @param networkIp    the node's Docker-network IP, dialled by its peers
     * @param nodeGroup    zero-based shard index
     * @param primary      whether this node holds the shard's slots
     */
    public record Node(String endpointHost, int endpointPort, String networkIp,
                       int nodeGroup, boolean primary) {}

    public void form(String groupId, List<Node> nodes, int numNodeGroups) {
        long deadline = System.currentTimeMillis() + FORMATION_DEADLINE_MS;
        List<RespClient> clients = new ArrayList<>(nodes.size());
        try {
            for (Node node : nodes) {
                clients.add(new RespClient(node.endpointHost(), node.endpointPort()));
            }

            List<String> nodeIds = new ArrayList<>(nodes.size());
            for (RespClient client : clients) {
                nodeIds.add(client.callString("CLUSTER", "MYID"));
            }

            for (int i = 0; i < clients.size(); i++) {
                try {
                    clients.get(i).callString("CLUSTER", "SET-CONFIG-EPOCH", String.valueOf(i + 1));
                } catch (RespError e) {
                    LOG.debugv("SET-CONFIG-EPOCH on node {0} of group {1}: {2}",
                            String.valueOf(i), groupId, e.getMessage());
                }
            }

            for (int i = 1; i < nodes.size(); i++) {
                clients.getFirst().callString("CLUSTER", "MEET",
                        nodes.get(i).networkIp(), String.valueOf(BACKEND_PORT));
            }

            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                if (node.primary()) {
                    int[] range = slotRange(node.nodeGroup(), numNodeGroups);
                    clients.get(i).callString("CLUSTER", "ADDSLOTSRANGE",
                            String.valueOf(range[0]), String.valueOf(range[1]));
                }
            }

            awaitKnownNodes(groupId, clients, nodes.size(), deadline);

            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                if (!node.primary()) {
                    String primaryId = nodeIds.get(primaryIndex(nodes, node.nodeGroup()));
                    replicateWithRetry(groupId, clients.get(i), primaryId, deadline);
                }
            }

            awaitClusterOk(groupId, clients, deadline);
            LOG.infov("Valkey cluster for group {0} formed: {1} shard(s), {2} node(s)",
                    groupId, String.valueOf(numNodeGroups), String.valueOf(nodes.size()));
        } catch (IOException e) {
            throw new RuntimeException("Cluster formation for group " + groupId + " failed: " + e.getMessage(), e);
        } finally {
            clients.forEach(RespClient::closeQuietly);
        }
    }

    /** The contiguous slot range served by the given shard, covering all 16384 slots overall. */
    public static int[] slotRange(int nodeGroup, int numNodeGroups) {
        int start = nodeGroup * TOTAL_SLOTS / numNodeGroups;
        int end = (nodeGroup + 1) * TOTAL_SLOTS / numNodeGroups - 1;
        return new int[] {start, end};
    }

    /**
     * One node of a running cluster taking part in a reshard.
     *
     * @param endpointHost host Floci uses to reach the node
     * @param endpointPort port Floci uses to reach the node
     * @param networkIp    the node's Docker-network IP, dialled by its peers and by MIGRATE
     * @param nodeGroupId  the shard the node belongs to
     * @param primary      whether this node holds the shard's slots
     * @param joining      a freshly started node that is not yet part of the cluster
     * @param leaving      a node whose shard is being removed; it ends up owning no slots and is
     *                     forgotten by every node that stays
     */
    public record ShardMember(String endpointHost, int endpointPort, String networkIp,
                              String nodeGroupId, boolean primary, boolean joining, boolean leaving) {}

    /**
     * Reshards a running cluster online, the way {@code valkey-cli --cluster add-node},
     * {@code reshard} and {@code del-node} do: joining nodes are introduced with MEET and their
     * replicas attached, every slot whose owner changes is moved with the IMPORTING/MIGRATING
     * handshake so its keys travel with it, and leaving nodes are forgotten once they own nothing.
     *
     * @param currentSlots the slot ranges each shard owns now, keyed by node group id
     * @param desiredSlots the slot ranges each remaining shard must own afterwards
     */
    public void reshard(String groupId, List<ShardMember> members,
                        Map<String, String> currentSlots, Map<String, String> desiredSlots) {
        long deadline = System.currentTimeMillis() + RESHARD_DEADLINE_MS;
        List<RespClient> clients = new ArrayList<>(members.size());
        try {
            for (ShardMember member : members) {
                clients.add(new RespClient(member.endpointHost(), member.endpointPort()));
            }
            List<String> nodeIds = new ArrayList<>(members.size());
            for (RespClient client : clients) {
                nodeIds.add(client.callString("CLUSTER", "MYID"));
            }

            int seed = -1;
            for (int i = 0; i < members.size(); i++) {
                if (!members.get(i).joining()) {
                    seed = i;
                    break;
                }
            }
            if (seed < 0) {
                throw new IllegalArgumentException("Reshard of group " + groupId + " has no existing node");
            }
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).joining()) {
                    clients.get(seed).callString("CLUSTER", "MEET",
                            members.get(i).networkIp(), String.valueOf(BACKEND_PORT));
                }
            }
            awaitKnownNodes(groupId, clients, members.size(), deadline);

            Map<String, Integer> primaryByShard = new LinkedHashMap<>();
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).primary()) {
                    primaryByShard.put(members.get(i).nodeGroupId(), i);
                }
            }
            for (int i = 0; i < members.size(); i++) {
                ShardMember member = members.get(i);
                if (member.joining() && !member.primary()) {
                    Integer primary = primaryByShard.get(member.nodeGroupId());
                    if (primary == null) {
                        throw new IllegalArgumentException("No primary for node group " + member.nodeGroupId());
                    }
                    replicateWithRetry(groupId, clients.get(i), nodeIds.get(primary), deadline);
                }
            }

            String[] from = slotOwners(currentSlots);
            String[] to = slotOwners(desiredSlots);
            Map<SlotMove, List<Integer>> moves = new LinkedHashMap<>();
            for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
                if (to[slot] == null) {
                    throw new IllegalArgumentException("Slot " + slot + " has no owner in the target layout");
                }
                if (!to[slot].equals(from[slot])) {
                    moves.computeIfAbsent(new SlotMove(from[slot], to[slot]), key -> new ArrayList<>()).add(slot);
                }
            }

            List<Integer> primaries = new ArrayList<>(primaryByShard.values());
            for (Map.Entry<SlotMove, List<Integer>> move : moves.entrySet()) {
                Integer target = primaryByShard.get(move.getKey().to());
                if (target == null) {
                    throw new IllegalArgumentException("No primary for node group " + move.getKey().to());
                }
                Integer source = move.getKey().from() == null ? null : primaryByShard.get(move.getKey().from());
                migrateSlots(groupId, clients, nodeIds, primaries, source, target,
                        members.get(target).networkIp(), move.getValue());
            }

            List<RespClient> remaining = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                if (!members.get(i).leaving()) {
                    remaining.add(clients.get(i));
                }
            }
            for (int i = 0; i < members.size(); i++) {
                if (!members.get(i).leaving()) {
                    continue;
                }
                for (RespClient client : remaining) {
                    try {
                        client.callString("CLUSTER", "FORGET", nodeIds.get(i));
                    } catch (RespError e) {
                        LOG.debugv("FORGET {0} in group {1}: {2}", nodeIds.get(i), groupId, e.getMessage());
                    }
                }
            }

            String[] desiredOwnerIds = new String[TOTAL_SLOTS];
            for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
                desiredOwnerIds[slot] = nodeIds.get(primaryByShard.get(to[slot]));
            }
            awaitClusterOk(groupId, remaining, deadline);
            awaitSlotOwnership(groupId, remaining, desiredOwnerIds, deadline);
            LOG.infov("Valkey cluster for group {0} resharded: {1} slot move(s) across {2} shard(s)",
                    groupId, String.valueOf(moves.values().stream().mapToInt(List::size).sum()),
                    String.valueOf(desiredSlots.size()));
        } catch (IOException e) {
            throw new RuntimeException("Reshard of group " + groupId + " failed: " + e.getMessage(), e);
        } finally {
            clients.forEach(RespClient::closeQuietly);
        }
    }

    /**
     * Moves {@code slots} to {@code target}, a batch at a time with pipelined SETSLOT calls. A slot
     * that holds keys has them carried across with MIGRATE while it is in the migrating state, so
     * no key is lost and clients are redirected with ASK for the duration.
     */
    private static void migrateSlots(String groupId, List<RespClient> clients, List<String> nodeIds,
                                     List<Integer> primaries, Integer source, int target,
                                     String targetIp, List<Integer> slots) throws IOException {
        RespClient targetClient = clients.get(target);
        String targetId = nodeIds.get(target);
        for (int start = 0; start < slots.size(); start += SLOT_BATCH) {
            List<Integer> batch = slots.subList(start, Math.min(slots.size(), start + SLOT_BATCH));
            if (source == null) {
                expectOk(targetClient.pipeline(slotCommands(batch, "ADDSLOTS")));
                continue;
            }
            RespClient sourceClient = clients.get(source);
            String sourceId = nodeIds.get(source);
            expectOk(targetClient.pipeline(slotCommands(batch, "SETSLOT", "IMPORTING", sourceId)));
            expectOk(sourceClient.pipeline(slotCommands(batch, "SETSLOT", "MIGRATING", targetId)));
            List<Object> counts = sourceClient.pipeline(slotCommands(batch, "COUNTKEYSINSLOT"));
            expectOk(counts);
            for (int i = 0; i < batch.size(); i++) {
                if (counts.get(i) instanceof Long count && count > 0) {
                    moveKeys(groupId, sourceClient, targetIp, batch.get(i));
                }
            }
            expectOk(targetClient.pipeline(slotCommands(batch, "SETSLOT", "NODE", targetId)));
            for (Object reply : sourceClient.pipeline(slotCommands(batch, "SETSLOT", "NODE", targetId))) {
                // A primary that gossip has already stripped of its last slot demotes itself to a
                // replica of the new owner and refuses SETSLOT; the slot has moved regardless.
                if (reply instanceof RespError e && !e.getMessage().contains("SETSLOT only with")) {
                    throw e;
                }
            }
            for (int primary : primaries) {
                if (primary == target || primary == source) {
                    continue;
                }
                // Only speeds up convergence: a node that has not learned the target yet refuses,
                // and gossip of the target's bumped epoch settles it regardless.
                for (Object reply : clients.get(primary).pipeline(slotCommands(batch, "SETSLOT", "NODE", targetId))) {
                    if (reply instanceof RespError e) {
                        LOG.debugv("SETSLOT NODE broadcast in group {0}: {1}", groupId, e.getMessage());
                    }
                }
            }
        }
    }

    private static void moveKeys(String groupId, RespClient source, String targetIp, int slot) throws IOException {
        while (true) {
            Object reply = source.callBinary(List.of(bytes("CLUSTER"), bytes("GETKEYSINSLOT"),
                    bytes(String.valueOf(slot)), bytes(String.valueOf(MIGRATE_KEY_BATCH))));
            if (!(reply instanceof List<?> keys) || keys.isEmpty()) {
                return;
            }
            List<byte[]> command = new ArrayList<>();
            command.add(bytes("MIGRATE"));
            command.add(bytes(targetIp));
            command.add(bytes(String.valueOf(BACKEND_PORT)));
            command.add(new byte[0]);
            command.add(bytes("0"));
            command.add(bytes(String.valueOf(MIGRATE_TIMEOUT_MS)));
            command.add(bytes("KEYS"));
            for (Object key : keys) {
                command.add((byte[]) key);
            }
            source.callBinary(command);
            LOG.debugv("Migrated {0} key(s) of slot {1} in group {2}",
                    String.valueOf(keys.size()), String.valueOf(slot), groupId);
        }
    }

    private static List<String[]> slotCommands(List<Integer> slots, String subcommand, String... suffix) {
        List<String[]> commands = new ArrayList<>(slots.size());
        for (int slot : slots) {
            String[] command = new String[3 + suffix.length];
            command[0] = "CLUSTER";
            command[1] = subcommand;
            command[2] = String.valueOf(slot);
            System.arraycopy(suffix, 0, command, 3, suffix.length);
            commands.add(command);
        }
        return commands;
    }

    private static void expectOk(List<Object> replies) throws RespError {
        for (Object reply : replies) {
            if (reply instanceof RespError e) {
                throw e;
            }
        }
    }

    /**
     * Expands a node-group-to-ranges map (each value like {@code 0-5460} or {@code 0-99,200-299})
     * into the owning node group of every slot.
     */
    static String[] slotOwners(Map<String, String> slotsByNodeGroup) {
        String[] owners = new String[TOTAL_SLOTS];
        for (Map.Entry<String, String> entry : slotsByNodeGroup.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            for (String range : entry.getValue().split(",")) {
                String trimmed = range.trim();
                int dash = trimmed.indexOf('-');
                int start = Integer.parseInt(dash < 0 ? trimmed : trimmed.substring(0, dash));
                int end = dash < 0 ? start : Integer.parseInt(trimmed.substring(dash + 1));
                for (int slot = start; slot <= end; slot++) {
                    owners[slot] = entry.getKey();
                }
            }
        }
        return owners;
    }

    private static void awaitSlotOwnership(String groupId, List<RespClient> clients,
                                           String[] desiredOwnerIds, long deadline) throws IOException {
        while (true) {
            boolean agreed = true;
            for (RespClient client : clients) {
                if (!Arrays.equals(slotOwnerIds(client.call("CLUSTER", "SLOTS")), desiredOwnerIds)) {
                    agreed = false;
                    break;
                }
            }
            if (agreed) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new RuntimeException("Reshard of group " + groupId
                        + " timed out waiting for every node to agree on slot ownership");
            }
            sleep(groupId);
        }
    }

    /** The primary node id owning each slot, read from a CLUSTER SLOTS reply. */
    static String[] slotOwnerIds(Object clusterSlotsReply) {
        String[] owners = new String[TOTAL_SLOTS];
        if (!(clusterSlotsReply instanceof List<?> ranges)) {
            return owners;
        }
        for (Object range : ranges) {
            if (!(range instanceof List<?> entry) || entry.size() < 3
                    || !(entry.get(0) instanceof Long start) || !(entry.get(1) instanceof Long end)
                    || !(entry.get(2) instanceof List<?> primary) || primary.size() < 3) {
                continue;
            }
            String id = String.valueOf(primary.get(2));
            for (long slot = start; slot <= end && slot < TOTAL_SLOTS; slot++) {
                owners[(int) slot] = id;
            }
        }
        return owners;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Slots moving from one node group to another; {@code from} is null for an unassigned slot. */
    private record SlotMove(String from, String to) {}

    private static int primaryIndex(List<Node> nodes, int nodeGroup) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).primary() && nodes.get(i).nodeGroup() == nodeGroup) {
                return i;
            }
        }
        throw new IllegalStateException("No primary for node group " + nodeGroup);
    }

    private static void replicateWithRetry(String groupId, RespClient replica,
                                           String primaryId, long deadline) throws IOException {
        while (true) {
            try {
                replica.callString("CLUSTER", "REPLICATE", primaryId);
                return;
            } catch (RespError e) {
                // The replica may not have gossiped the primary yet — retry until the deadline.
                if (System.currentTimeMillis() >= deadline) {
                    throw new RuntimeException("Cluster formation for group " + groupId
                            + " timed out waiting to replicate " + primaryId + ": " + e.getMessage(), e);
                }
                sleep(groupId);
            }
        }
    }

    private static void awaitKnownNodes(String groupId, List<RespClient> clients,
                                        int expected, long deadline) throws IOException {
        while (!allMatch(clients, info -> parseInfoField(info, "cluster_known_nodes") >= expected)) {
            if (System.currentTimeMillis() >= deadline) {
                throw new RuntimeException("Cluster formation for group " + groupId
                        + " timed out waiting for all " + expected + " nodes to meet");
            }
            sleep(groupId);
        }
    }

    private static void awaitClusterOk(String groupId, List<RespClient> clients,
                                       long deadline) throws IOException {
        while (!allMatch(clients, info -> info.contains("cluster_state:ok"))) {
            if (System.currentTimeMillis() >= deadline) {
                throw new RuntimeException("Cluster formation for group " + groupId
                        + " timed out waiting for cluster_state:ok");
            }
            sleep(groupId);
        }
    }

    private static boolean allMatch(List<RespClient> clients,
                                    Predicate<String> infoPredicate) throws IOException {
        for (RespClient client : clients) {
            if (!infoPredicate.test(client.callString("CLUSTER", "INFO"))) {
                return false;
            }
        }
        return true;
    }

    private static long parseInfoField(String info, String field) {
        for (String line : info.split("\r?\n")) {
            if (line.startsWith(field + ":")) {
                try {
                    return Long.parseLong(line.substring(field.length() + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static void sleep(String groupId) {
        try {
            Thread.sleep(RETRY_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while forming cluster for group " + groupId, e);
        }
    }

    /** An {@code -ERR} reply from the server, distinct from transport failures. */
    static final class RespError extends IOException {
        RespError(String message) {
            super(message);
        }
    }

    /** Minimal RESP2 client: sends one command array, reads one reply. */
    static final class RespClient implements Closeable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        RespClient(String host, int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        String callString(String... args) throws IOException {
            Object reply = call(args);
            return reply == null ? null : reply.toString();
        }

        Object call(String... args) throws IOException {
            writeCommand(toBytes(args));
            out.flush();
            return readReply(false);
        }

        /** As {@link #call}, with byte-exact arguments and bulk replies returned as {@code byte[]}. */
        Object callBinary(List<byte[]> args) throws IOException {
            writeCommand(args);
            out.flush();
            return readReply(true);
        }

        /**
         * Sends every command before reading any reply. An error reply is returned in place as a
         * {@link RespError} rather than thrown, so the remaining replies are still consumed.
         */
        List<Object> pipeline(List<String[]> commands) throws IOException {
            for (String[] command : commands) {
                writeCommand(toBytes(command));
            }
            out.flush();
            List<Object> replies = new ArrayList<>(commands.size());
            for (int i = 0; i < commands.size(); i++) {
                try {
                    replies.add(readReply(false));
                } catch (RespError e) {
                    replies.add(e);
                }
            }
            return replies;
        }

        private static List<byte[]> toBytes(String[] args) {
            List<byte[]> encoded = new ArrayList<>(args.length);
            for (String arg : args) {
                encoded.add(arg.getBytes(StandardCharsets.UTF_8));
            }
            return encoded;
        }

        private void writeCommand(List<byte[]> args) throws IOException {
            out.write(("*" + args.size() + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (byte[] arg : args) {
                out.write(("$" + arg.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(arg);
                out.write('\r');
                out.write('\n');
            }
        }

        private Object readReply(boolean binary) throws IOException {
            int type = in.read();
            if (type == -1) {
                throw new IOException("Connection closed while awaiting reply");
            }
            String line = readLine();
            return switch (type) {
                case '+' -> line;
                case '-' -> throw new RespError(line);
                case ':' -> Long.parseLong(line);
                case '$' -> readBulk(Integer.parseInt(line), binary);
                case '*' -> readArray(Integer.parseInt(line), binary);
                default -> throw new IOException("Unexpected RESP type: " + (char) type);
            };
        }

        private Object readBulk(int length, boolean binary) throws IOException {
            if (length < 0) {
                return null;
            }
            byte[] data = in.readNBytes(length);
            if (data.length != length) {
                throw new IOException("Truncated bulk reply");
            }
            expectCrLf();
            return binary ? data : new String(data, StandardCharsets.UTF_8);
        }

        private List<Object> readArray(int count, boolean binary) throws IOException {
            if (count < 0) {
                return null;
            }
            List<Object> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                items.add(readReply(binary));
            }
            return items;
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\r') {
                    int next = in.read();
                    if (next != '\n') {
                        throw new IOException("Malformed RESP line terminator");
                    }
                    return sb.toString();
                }
                sb.append((char) b);
            }
            throw new IOException("Connection closed mid-line");
        }

        private void expectCrLf() throws IOException {
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("Missing CRLF after bulk reply");
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        void closeQuietly() {
            try {
                close();
            } catch (IOException e) {
                LOG.debugv("Ignoring close failure for formation connection to {0}: {1}",
                        socket.getRemoteSocketAddress(), e.getMessage());
            }
        }
    }
}
