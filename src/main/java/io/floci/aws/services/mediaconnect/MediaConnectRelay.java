package io.floci.aws.services.mediaconnect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.kinesisvideo.KvsTranscoder;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Carries an active flow's content. Each source of a datagram protocol ({@code rtp}, {@code rtp-fec}, {@code rist},
 * {@code udp}) listens on a UDP port: its own {@code IngestPort} when that port is free, otherwise one from the
 * configured range, which the API reports as {@code LocalIngestPort}. Packets from outside the source's
 * {@code WhitelistCidr} are dropped. What a source receives goes to the flow's enabled outputs of the same protocols
 * (an RTP header added or removed between {@code udp} and the RTP protocols), and to the flows its entitlements are
 * the sources of. With two sources, {@code FAILOVER} carries the primary until it falls silent and {@code MERGE}
 * carries both, dropping RTP packets already sent. The MPEG transport stream inside is read for source metadata
 * (programs, streams, codecs, H.264 resolution, AAC channels and sample rate) and kept briefly for thumbnails, which
 * ffmpeg makes. A source starting or stopping sends an alert event.
 */
@ApplicationScoped
public class MediaConnectRelay {

    private static final Logger LOG = Logger.getLogger(MediaConnectRelay.class);
    static final Set<String> DATAGRAM = Set.of("rtp", "rtp-fec", "rist", "udp");
    private static final int TS = 188;
    private static final long SILENCE_MILLIS = 3000;
    private static final int KEEP_BYTES = 4 * 1024 * 1024;

    private final Vertx vertx;
    private final MediaConnectStore store;
    private final ObjectMapper mapper;
    private final Provider<MediaConnectFlows> flows;
    private final Provider<KvsTranscoder> ffmpeg;
    private final Map<String, FlowRelay> relays = new ConcurrentHashMap<>();
    private final Set<Integer> taken = ConcurrentHashMap.newKeySet();

    /** A thumbnail: a base64 JPEG and when its frame was received, or why there is none. */
    record Thumbnail(String image, String at, String timecode, String message) {
    }

    @Inject
    public MediaConnectRelay(Vertx vertx, MediaConnectStore store, Provider<MediaConnectFlows> flows,
                             Provider<KvsTranscoder> ffmpeg) {
        this.vertx = vertx;
        this.store = store;
        this.mapper = store.mapper();
        this.flows = flows;
        this.ffmpeg = ffmpeg;
    }

    // ================================================================ lifecycle

    synchronized void start(String account, String region, ObjectNode flow) {
        String arn = flow.path("FlowArn").asText();
        stop(arn);
        FlowRelay relay = new FlowRelay(account, region, arn);
        relays.put(arn, relay);
        relay.configure(flow);
        relay.checker = vertx.setPeriodic(1000, timer -> relay.checkSilence());
    }

    synchronized void update(String account, String region, ObjectNode flow) {
        FlowRelay relay = relays.get(flow.path("FlowArn").asText());
        if (relay == null) {
            start(account, region, flow);
        } else {
            relay.configure(flow);
        }
    }

    synchronized void stop(String flowArn) {
        FlowRelay relay = relays.remove(flowArn);
        if (relay != null) {
            relay.close();
        }
    }

    @PreDestroy
    synchronized void shutdown() {
        List.copyOf(relays.keySet()).forEach(this::stop);
    }

    int localPort(String flowArn, String sourceArn) {
        FlowRelay relay = relays.get(flowArn);
        if (relay == null) {
            return 0;
        }
        Endpoint endpoint = relay.endpoints.get(sourceArn);
        return endpoint == null ? 0 : endpoint.port;
    }

    List<String> errors(String flowArn) {
        FlowRelay relay = relays.get(flowArn);
        return relay == null ? List.of() : List.copyOf(relay.errors);
    }

    // ================================================================ metadata and thumbnails

    ObjectNode metadata(String flowArn) {
        FlowRelay relay = relays.get(flowArn);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode messages = out.putArray("Messages");
        out.put("Timestamp", Instant.now().toEpochMilli() / 1000.0);
        if (relay == null || relay.received == 0) {
            messages.addObject().put("Code", "NO_SOURCE_DATA").put("Message",
                    "The flow isn't receiving content from its source.");
            return out;
        }
        synchronized (relay.analyzer) {
            if (relay.analyzer.programs.isEmpty()) {
                messages.addObject().put("Code", "NO_TRANSPORT_STREAM").put("Message",
                        "The source's content isn't an MPEG transport stream with a program association table.");
                return out;
            }
            out.set("TransportMediaInfo", relay.analyzer.info(mapper));
            out.put("Timestamp", relay.analyzer.at / 1000.0);
        }
        return out;
    }

    Thumbnail thumbnail(String flowArn) {
        FlowRelay relay = relays.get(flowArn);
        if (relay == null || relay.received == 0) {
            return new Thumbnail(null, null, null, "The flow isn't receiving content from its source.");
        }
        byte[] recent;
        long at;
        synchronized (relay.recent) {
            recent = relay.recent.toByteArray();
            at = relay.lastAt;
        }
        try {
            Map<String, byte[]> files = ffmpeg.get().run(new LinkedHashMap<>(Map.of("in.ts", recent)),
                    "ffmpeg -hide_banner -loglevel error -sseof -1 -i in.ts -frames:v 1 -vf scale=-2:360 -q:v 4 "
                            + "thumbnail.jpg");
            byte[] image = files.get("thumbnail.jpg");
            if (image == null || image.length == 0) {
                return new Thumbnail(null, null, null, "No video frame could be decoded from the source.");
            }
            return new Thumbnail(Base64.getEncoder().encodeToString(image), Instant.ofEpochMilli(at).toString(),
                    null, null);
        } catch (RuntimeException e) {
            return new Thumbnail(null, null, null, "No video frame could be decoded from the source: "
                    + e.getMessage());
        }
    }

    // ================================================================ one flow

    /** A source's listener. */
    private static final class Endpoint {
        final String sourceArn;
        final String name;
        final String protocol;
        final String cidr;
        final DatagramSocket socket;
        final int port;
        volatile long lastAt;
        volatile boolean connected;

        Endpoint(String sourceArn, String name, String protocol, String cidr, DatagramSocket socket, int port) {
            this.sourceArn = sourceArn;
            this.name = name;
            this.protocol = protocol;
            this.cidr = cidr;
            this.socket = socket;
            this.port = port;
        }
    }

    /** Where an output sends. */
    private record Target(String name, String protocol, String host, int port) {
    }

    private final class FlowRelay {
        final String account;
        final String region;
        final String flowArn;
        final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
        final List<String> errors = new ArrayList<>();
        final TsAnalyzer analyzer = new TsAnalyzer();
        final ByteArrayOutputStream recent = new ByteArrayOutputStream();
        volatile List<Target> targets = List.of();
        volatile List<String> entitlements = List.of();
        volatile String failoverMode;
        volatile String primary;
        volatile long received;
        volatile long lastAt;
        long checker;
        DatagramSocket sender;
        int sequence;
        final Map<String, Long> seen = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > 4096;
            }
        };

        FlowRelay(String account, String region, String flowArn) {
            this.account = account;
            this.region = region;
            this.flowArn = flowArn;
            this.sender = vertx.createDatagramSocket(new DatagramSocketOptions());
        }

        synchronized void configure(ObjectNode flow) {
            errors.clear();
            List<String> wanted = new ArrayList<>();
            for (JsonNode source : flow.path("Sources")) {
                String arn = source.path("SourceArn").asText();
                wanted.add(arn);
                if (source.hasNonNull("EntitlementArn")) {
                    continue;
                }
                String protocol = source.path("Transport").path("Protocol").asText("");
                if (!DATAGRAM.contains(protocol)) {
                    errors.add("Floci carries rtp, rtp-fec, rist and udp sources; the " + protocol + " source "
                            + source.path("Name").asText() + " receives nothing.");
                    continue;
                }
                Endpoint current = endpoints.get(arn);
                String cidr = source.path("WhitelistCidr").asText(null);
                if (current != null && current.protocol.equals(protocol) && Objects.equals(current.cidr,
                        cidr)) {
                    continue;
                }
                if (current != null) {
                    close(current);
                }
                Endpoint endpoint = listen(arn, source.path("Name").asText(), protocol, cidr,
                        source.path("IngestPort").asInt(0));
                if (endpoint != null) {
                    endpoints.put(arn, endpoint);
                }
            }
            for (String arn : List.copyOf(endpoints.keySet())) {
                if (!wanted.contains(arn)) {
                    close(endpoints.remove(arn));
                }
            }
            List<Target> outputs = new ArrayList<>();
            for (JsonNode output : flow.path("Outputs")) {
                if (output.hasNonNull("EntitlementArn") || !"ENABLED".equals(output.path("OutputStatus")
                        .asText("ENABLED"))) {
                    continue;
                }
                String protocol = output.path("Transport").path("Protocol").asText("");
                if (!DATAGRAM.contains(protocol)) {
                    if (!protocol.isEmpty()) {
                        errors.add("Floci carries rtp, rtp-fec, rist and udp outputs; the " + protocol + " output "
                                + output.path("Name").asText() + " sends nothing.");
                    }
                    continue;
                }
                outputs.add(new Target(output.path("Name").asText(), protocol, output.path("Destination").asText(),
                        output.path("Port").asInt()));
            }
            targets = List.copyOf(outputs);
            List<String> granted = new ArrayList<>();
            for (JsonNode entitlement : flow.path("Entitlements")) {
                if ("ENABLED".equals(entitlement.path("EntitlementStatus").asText("ENABLED"))) {
                    granted.add(entitlement.path("EntitlementArn").asText());
                }
            }
            entitlements = List.copyOf(granted);
            JsonNode failover = flow.path("SourceFailoverConfig");
            failoverMode = "ENABLED".equals(failover.path("State").asText()) ? failover.path("FailoverMode")
                    .asText("MERGE") : null;
            primary = flow.path("Source").path("SourceArn").asText(null);
        }

        private Endpoint listen(String arn, String name, String protocol, String cidr, int preferred) {
            DatagramSocket socket = vertx.createDatagramSocket(new DatagramSocketOptions().setReceiveBufferSize(
                    4 * 1024 * 1024));
            List<Integer> candidates = new ArrayList<>();
            if (preferred > 0) {
                candidates.add(preferred);
            }
            int start = store.config().udpPortStart();
            for (int port = start; port < start + store.config().udpPortCount(); port++) {
                candidates.add(port);
            }
            for (int port : candidates) {
                if (!taken.add(port)) {
                    continue;
                }
                try {
                    socket.listen(port, store.config().bindHost()).toCompletionStage().toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    Endpoint endpoint = new Endpoint(arn, name, protocol, cidr, socket, port);
                    socket.handler(packet -> receive(endpoint, packet));
                    return endpoint;
                } catch (Exception e) {
                    taken.remove(port);
                }
            }
            errors.add("No UDP port was free for the source " + name + ".");
            socket.close();
            return null;
        }

        private void close(Endpoint endpoint) {
            if (endpoint != null) {
                endpoint.socket.close();
                taken.remove(endpoint.port);
            }
        }

        void close() {
            vertx.cancelTimer(checker);
            endpoints.values().forEach(this::close);
            endpoints.clear();
            sender.close();
        }

        private void receive(Endpoint endpoint, DatagramPacket packet) {
            String sender = packet.sender().host();
            if (endpoint.cidr != null && !inCidr(sender, endpoint.cidr)) {
                return;
            }
            byte[] data = packet.data().getBytes();
            boolean rtp = !"udp".equals(endpoint.protocol) && isRtp(data);
            if (!endpoint.connected) {
                endpoint.connected = true;
                alert(endpoint, false, "Source " + endpoint.name + " connected from " + sender + ".");
            }
            endpoint.lastAt = System.currentTimeMillis();
            if (!carries(endpoint, data, rtp)) {
                return;
            }
            byte[] payload = rtp ? rtpPayload(data) : data;
            deliver(payload, rtp ? data : null);
        }

        /** Whether this source's packet is carried: the primary under failover, and RTP packets not seen yet. */
        private boolean carries(Endpoint endpoint, byte[] data, boolean rtp) {
            if (failoverMode == null || endpoints.size() < 2) {
                return endpoint.sourceArn.equals(primary) || endpoints.size() < 2;
            }
            if ("FAILOVER".equals(failoverMode)) {
                Endpoint first = endpoints.get(primary);
                boolean primaryAlive = first != null && System.currentTimeMillis() - first.lastAt < 1000;
                return endpoint.sourceArn.equals(primary) || !primaryAlive;
            }
            if (!rtp) {
                return true;
            }
            String key = ((data[2] & 0xff) << 8 | (data[3] & 0xff)) + "/" + ((data[4] & 0xff) << 24
                    | (data[5] & 0xff) << 16 | (data[6] & 0xff) << 8 | (data[7] & 0xff));
            synchronized (seen) {
                return seen.put(key, System.currentTimeMillis()) == null;
            }
        }

        /** Takes a transport stream payload in: analyzes it, keeps it, and sends it on. */
        void deliver(byte[] payload, byte[] rtpPacket) {
            received += payload.length;
            lastAt = System.currentTimeMillis();
            synchronized (analyzer) {
                analyzer.feed(payload);
            }
            synchronized (recent) {
                if (recent.size() + payload.length > KEEP_BYTES) {
                    byte[] kept = recent.toByteArray();
                    recent.reset();
                    int from = Math.max(0, kept.length - KEEP_BYTES / 2);
                    from -= from % TS;
                    recent.write(kept, from, kept.length - from);
                }
                recent.writeBytes(payload);
            }
            for (Target target : targets) {
                boolean wantsRtp = !"udp".equals(target.protocol);
                byte[] out = wantsRtp ? (rtpPacket != null ? rtpPacket : wrap(payload)) : payload;
                sender.send(Buffer.buffer(out), target.port, target.host).onFailure(e -> LOG.tracev(
                        "MediaConnect output {0} couldn't send: {1}", target.name, e.getMessage()));
            }
            for (String entitlement : entitlements) {
                for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : flows.get().entitledFlows(
                        entitlement)) {
                    FlowRelay downstream = relays.get(entry.value().path("FlowArn").asText());
                    if (downstream != null && downstream != this) {
                        downstream.deliver(payload, null);
                    }
                }
            }
        }

        private byte[] wrap(byte[] payload) {
            byte[] packet = new byte[12 + payload.length];
            int seq;
            synchronized (this) {
                seq = sequence++ & 0xffff;
            }
            long timestamp = (System.nanoTime() / 1000 * 90 / 1000) & 0xffffffffL;
            packet[0] = (byte) 0x80;
            packet[1] = 33;
            packet[2] = (byte) (seq >> 8);
            packet[3] = (byte) seq;
            packet[4] = (byte) (timestamp >> 24);
            packet[5] = (byte) (timestamp >> 16);
            packet[6] = (byte) (timestamp >> 8);
            packet[7] = (byte) timestamp;
            int ssrc = flowArn.hashCode();
            packet[8] = (byte) (ssrc >> 24);
            packet[9] = (byte) (ssrc >> 16);
            packet[10] = (byte) (ssrc >> 8);
            packet[11] = (byte) ssrc;
            System.arraycopy(payload, 0, packet, 12, payload.length);
            return packet;
        }

        void checkSilence() {
            long now = System.currentTimeMillis();
            for (Endpoint endpoint : endpoints.values()) {
                if (endpoint.connected && now - endpoint.lastAt > SILENCE_MILLIS) {
                    endpoint.connected = false;
                    alert(endpoint, true, "Source " + endpoint.name + " stopped sending content.");
                }
            }
        }

        private void alert(Endpoint endpoint, boolean errored, String message) {
            ObjectNode detail = mapper.createObjectNode();
            detail.put("error-code", "SourceDisconnected");
            detail.put("errored", errored);
            detail.put("message", message);
            detail.put("source-arn", endpoint.sourceArn);
            vertx.executeBlocking(() -> {
                RequestScopes.runAs(account, region, () -> flows.get().publish(account, region, "MediaConnect Alert",
                        detail, flowArn));
                return null;
            }, false);
        }
    }

    // ================================================================ RTP

    static boolean isRtp(byte[] data) {
        return data.length >= 12 && (data[0] & 0xc0) == 0x80 && data[0] != 0x47;
    }

    static byte[] rtpPayload(byte[] data) {
        int offset = 12 + (data[0] & 0x0f) * 4;
        if ((data[0] & 0x10) != 0 && data.length >= offset + 4) {
            offset += 4 + (((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff)) * 4;
        }
        int end = data.length;
        if ((data[0] & 0x20) != 0 && end > offset) {
            end -= data[end - 1] & 0xff;
        }
        if (offset >= end) {
            return new byte[0];
        }
        byte[] payload = new byte[end - offset];
        System.arraycopy(data, offset, payload, 0, payload.length);
        return payload;
    }

    // ================================================================ CIDR

    static boolean validCidr(String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2 || !parts[0].matches("[0-9.]+|[0-9a-fA-F:]+")) {
            return false;
        }
        try {
            byte[] address = InetAddress.getByName(parts[0]).getAddress();
            int bits = Integer.parseInt(parts[1]);
            return bits >= 0 && bits <= address.length * 8;
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }

    static boolean inCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] address = InetAddress.getByName(ip).getAddress();
            if (network.length != address.length) {
                return false;
            }
            int bits = Integer.parseInt(parts[1]);
            int length = network.length * 8;
            BigInteger mask = bits == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(length).subtract(BigInteger.ONE)
                    .shiftRight(length - bits).shiftLeft(length - bits);
            return new BigInteger(1, network).and(mask).equals(new BigInteger(1, address).and(mask));
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    // ================================================================ MPEG transport stream

    /** Reads PAT and PMT sections and the first parameters of each elementary stream. */
    static final class TsAnalyzer {
        final Map<Integer, Integer> programs = new TreeMap<>();
        final Map<Integer, ObjectNode> pmts = new TreeMap<>();
        final Map<Integer, int[]> details = new ConcurrentHashMap<>();
        int tsId = -1;
        long at;

        void feed(byte[] data) {
            for (int offset = 0; offset + TS <= data.length; offset += TS) {
                if (data[offset] != 0x47) {
                    continue;
                }
                packet(data, offset);
            }
        }

        private void packet(byte[] data, int offset) {
            int pid = ((data[offset + 1] & 0x1f) << 8) | (data[offset + 2] & 0xff);
            boolean start = (data[offset + 1] & 0x40) != 0;
            int control = (data[offset + 3] >> 4) & 0x3;
            int payload = offset + 4;
            if (control == 2) {
                return;
            }
            if (control == 3) {
                payload += 1 + (data[offset + 4] & 0xff);
            }
            int end = offset + TS;
            if (payload >= end) {
                return;
            }
            at = System.currentTimeMillis();
            if (pid == 0 && start) {
                pat(data, payload + 1 + (data[payload] & 0xff), end);
            } else if (start && programs.containsValue(pid)) {
                pmt(data, payload + 1 + (data[payload] & 0xff), end, pid);
            } else if (start) {
                elementary(pid, data, payload, end);
            }
        }

        private void pat(byte[] data, int at, int end) {
            if (at + 8 > end || data[at] != 0) {
                return;
            }
            int length = ((data[at + 1] & 0x0f) << 8) | (data[at + 2] & 0xff);
            tsId = ((data[at + 3] & 0xff) << 8) | (data[at + 4] & 0xff);
            int last = Math.min(end, at + 3 + length - 4);
            for (int entry = at + 8; entry + 4 <= last; entry += 4) {
                int number = ((data[entry] & 0xff) << 8) | (data[entry + 1] & 0xff);
                int pid = ((data[entry + 2] & 0x1f) << 8) | (data[entry + 3] & 0xff);
                if (number != 0) {
                    programs.put(number, pid);
                }
            }
        }

        private void pmt(byte[] data, int at, int end, int pmtPid) {
            if (at + 12 > end || data[at] != 2) {
                return;
            }
            int length = ((data[at + 1] & 0x0f) << 8) | (data[at + 2] & 0xff);
            int number = ((data[at + 3] & 0xff) << 8) | (data[at + 4] & 0xff);
            int pcr = ((data[at + 8] & 0x1f) << 8) | (data[at + 9] & 0xff);
            int infoLength = ((data[at + 10] & 0x0f) << 8) | (data[at + 11] & 0xff);
            int last = Math.min(end, at + 3 + length - 4);
            ObjectNode program = new ObjectMapper().createObjectNode();
            program.put("ProgramNumber", number);
            program.put("ProgramPid", pmtPid);
            program.put("PcrPid", pcr);
            ArrayNode streams = program.putArray("Streams");
            int entry = at + 12 + infoLength;
            while (entry + 5 <= last) {
                int type = data[entry] & 0xff;
                int pid = ((data[entry + 1] & 0x1f) << 8) | (data[entry + 2] & 0xff);
                int esLength = ((data[entry + 3] & 0x0f) << 8) | (data[entry + 4] & 0xff);
                String codec = codec(type, data, entry + 5, Math.min(last, entry + 5 + esLength));
                ObjectNode stream = streams.addObject();
                stream.put("Pid", pid);
                stream.put("StreamType", kind(codec));
                stream.put("Codec", codec);
                details.putIfAbsent(pid, new int[] {type, 0, 0, 0, 0});
                entry += 5 + esLength;
            }
            pmts.put(number, program);
        }

        private static String codec(int type, byte[] data, int from, int to) {
            switch (type) {
                case 0x01, 0x02:
                    return "mpeg2";
                case 0x1b:
                    return "h264";
                case 0x24:
                    return "h265";
                case 0x03, 0x04:
                    return "mp2";
                case 0x0f, 0x11:
                    return "aac";
                case 0x81:
                    return "ac3";
                case 0x87:
                    return "eac3";
                case 0x86:
                    return "scte35";
                case 0x15:
                    return "id3";
                default:
                    break;
            }
            for (int at = from; at + 2 <= to; at += 2 + (data[at + 1] & 0xff)) {
                switch (data[at] & 0xff) {
                    case 0x6a:
                        return "ac3";
                    case 0x7a:
                        return "eac3";
                    case 0x59:
                        return "dvb-sub";
                    case 0x56:
                        return "dvb-teletext";
                    case 0x05:
                        return "private";
                    default:
                        break;
                }
            }
            return "private";
        }

        private static String kind(String codec) {
            return switch (codec) {
                case "mpeg2", "h264", "h265" -> "Video";
                case "mp2", "aac", "ac3", "eac3" -> "Audio";
                default -> "Data";
            };
        }

        /** Reads a PES start for the stream's parameters: an H.264 SPS's size, an ADTS header's audio settings. */
        private void elementary(int pid, byte[] data, int from, int to) {
            int[] detail = details.get(pid);
            if (detail == null || detail[1] != 0 || from + 9 > to || data[from] != 0 || data[from + 1] != 0
                    || data[from + 2] != 1) {
                return;
            }
            int start = from + 9 + (data[from + 8] & 0xff);
            if (detail[0] == 0x1b) {
                for (int at = start; at + 4 < to; at++) {
                    if (data[at] == 0 && data[at + 1] == 0 && data[at + 2] == 1 && (data[at + 3] & 0x1f) == 7) {
                        int[] size = H264.size(data, at + 4, to);
                        if (size != null) {
                            detail[1] = size[0];
                            detail[2] = size[1];
                        }
                        return;
                    }
                }
            } else if (detail[0] == 0x0f && start + 7 <= to && (data[start] & 0xff) == 0xff
                    && (data[start + 1] & 0xf0) == 0xf0) {
                int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000,
                    7350};
                int index = (data[start + 2] >> 2) & 0x0f;
                detail[3] = index < rates.length ? rates[index] : 0;
                detail[4] = ((data[start + 2] & 0x01) << 2) | ((data[start + 3] >> 6) & 0x03);
                detail[1] = -1;
            }
        }

        ObjectNode info(ObjectMapper mapper) {
            ObjectNode info = mapper.createObjectNode();
            if (tsId >= 0) {
                info.put("TsId", tsId);
            }
            ArrayNode list = info.putArray("Programs");
            for (Map.Entry<Integer, Integer> program : programs.entrySet()) {
                ObjectNode described = pmts.get(program.getKey());
                if (described == null) {
                    described = mapper.createObjectNode().put("ProgramNumber", program.getKey())
                            .put("ProgramPid", program.getValue()).put("PcrPid", 0);
                    described.putArray("Streams");
                } else {
                    described = described.deepCopy();
                }
                for (JsonNode stream : described.path("Streams")) {
                    int[] detail = details.get(stream.path("Pid").asInt());
                    if (detail == null) {
                        continue;
                    }
                    if (detail[1] > 0) {
                        ((ObjectNode) stream).putObject("FrameResolution").put("FrameWidth", detail[1])
                                .put("FrameHeight", detail[2]);
                    }
                    if (detail[3] > 0) {
                        ((ObjectNode) stream).put("SampleRate", detail[3]);
                        ((ObjectNode) stream).put("Channels", detail[4]);
                    }
                }
                list.add(described);
            }
            return info;
        }
    }

    /** Reads the picture size from an H.264 sequence parameter set. */
    static final class H264 {
        private final byte[] data;
        private final int end;
        private int bit;

        private H264(byte[] data, int from, int end) {
            byte[] unescaped = new byte[end - from];
            int length = 0;
            for (int at = from; at < end; at++) {
                if (at + 2 < end && data[at] == 0 && data[at + 1] == 0 && data[at + 2] == 3) {
                    unescaped[length++] = 0;
                    unescaped[length++] = 0;
                    at += 2;
                    continue;
                }
                unescaped[length++] = data[at];
            }
            this.data = unescaped;
            this.end = length;
        }

        static int[] size(byte[] data, int from, int to) {
            try {
                return new H264(data, from, to).read();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private int bits(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                if (bit >= end * 8) {
                    throw new IllegalStateException("short SPS");
                }
                value = (value << 1) | ((data[bit / 8] >> (7 - bit % 8)) & 1);
                bit++;
            }
            return value;
        }

        private int ue() {
            int zeros = 0;
            while (bits(1) == 0) {
                zeros++;
                if (zeros > 31) {
                    throw new IllegalStateException("bad exp-Golomb code");
                }
            }
            return (1 << zeros) - 1 + bits(zeros);
        }

        private int se() {
            int value = ue();
            return (value & 1) == 1 ? (value + 1) / 2 : -(value / 2);
        }

        private int[] read() {
            int profile = bits(8);
            bits(16);
            ue();
            int chroma = 1;
            if (Set.of(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135).contains(profile)) {
                chroma = ue();
                if (chroma == 3) {
                    bits(1);
                }
                ue();
                ue();
                bits(1);
                if (bits(1) == 1) {
                    for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) {
                        if (bits(1) == 1) {
                            int last = 8;
                            int next = 8;
                            for (int j = 0; j < (i < 6 ? 16 : 64); j++) {
                                if (next != 0) {
                                    next = (last + se() + 256) % 256;
                                }
                                last = next == 0 ? last : next;
                            }
                        }
                    }
                }
            }
            ue();
            int pocType = ue();
            if (pocType == 0) {
                ue();
            } else if (pocType == 1) {
                bits(1);
                se();
                se();
                int cycle = ue();
                for (int i = 0; i < cycle; i++) {
                    se();
                }
            }
            ue();
            bits(1);
            int width = (ue() + 1) * 16;
            int mapUnits = ue() + 1;
            int frameMbsOnly = bits(1);
            int height = (2 - frameMbsOnly) * mapUnits * 16;
            if (frameMbsOnly == 0) {
                bits(1);
            }
            bits(1);
            if (bits(1) == 1) {
                int left = ue();
                int right = ue();
                int top = ue();
                int bottom = ue();
                int cropX = chroma == 0 || chroma == 3 ? 1 : 2;
                int cropY = (chroma == 1 ? 2 : 1) * (2 - frameMbsOnly);
                width -= (left + right) * cropX;
                height -= (top + bottom) * cropY;
            }
            return new int[] {width, height};
        }
    }

}
