package io.floci.aws.services.iotwireless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.TextWebSocket;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.iot.IotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.floci.aws.services.iotwireless.WirelessStore.DESTINATION;
import static io.floci.aws.services.iotwireless.WirelessStore.DEVICE;
import static io.floci.aws.services.iotwireless.WirelessStore.DEVICE_PROFILE;
import static io.floci.aws.services.iotwireless.WirelessStore.GATEWAY;
import static io.floci.aws.services.iotwireless.WirelessStore.SERVICE_PROFILE;
import static io.floci.aws.services.iotwireless.WirelessStore.SETTINGS;
import static io.floci.aws.services.iotwireless.WirelessStore.meta;
import static io.floci.aws.services.iotwireless.WirelessStore.notFound;
import static io.floci.aws.services.iotwireless.WirelessStore.validation;

/**
 * The LoRaWAN network server behind IoT Wireless: gateways connect as LoRa Basics Station gateways (see
 * {@link WirelessStation}); join requests are checked and answered with join accepts; uplinks are checked, counted,
 * decrypted and delivered to the device's destination (an IoT rule invoked by name, or an MQTT topic), with gateway
 * metadata when the service profile asks for it; confirmed uplinks are acknowledged; LinkCheckReq and DeviceTimeReq
 * MAC commands are answered; queued downlinks go out in the device's RX1 window (or at once on RX2 for Class C), and
 * confirmed ones stay queued until acknowledged. Join and connection status events, statistics, metrics, positions,
 * event and log configuration live here too.
 */
@ApplicationScoped
public class WirelessNetwork {

    private static final Logger LOG = Logger.getLogger(WirelessNetwork.class);
    static final int NET_ID = 0x000024;
    static final String LOG_GROUP = "AWSIoTWirelessLogs";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> EVENT_NAMES = Set.of("DeviceRegistrationState", "Proximity", "Join",
            "ConnectionStatus", "MessageDeliveryStatus");

    private final WirelessStore store;
    private final ObjectMapper mapper;
    private final Instance<IotService> iot;
    private final Instance<CloudWatchLogsService> logs;
    private final Instance<FlociCertificateAuthority> authority;
    private final Instance<WirelessService> service;
    private final Instance<WirelessFleet> fleet;
    /** Connected gateways by {@code account|region|gatewayId}. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong diids = new AtomicLong(1);

    @Inject
    public WirelessNetwork(WirelessStore store, Instance<IotService> iot, Instance<CloudWatchLogsService> logs,
                           Instance<FlociCertificateAuthority> authority, Instance<WirelessService> service,
                           Instance<WirelessFleet> fleet) {
        this.store = store;
        this.mapper = store.mapper();
        this.iot = iot;
        this.logs = logs;
        this.authority = authority;
        this.service = service;
        this.fleet = fleet;
    }

    /** A connected gateway. */
    static final class Session {
        final String account;
        final String region;
        final String gatewayId;
        final String gatewayEui;
        final TextWebSocket socket;
        volatile LoRaRegions.Region radio;

        Session(String account, String region, String gatewayId, String gatewayEui, TextWebSocket socket,
                LoRaRegions.Region radio) {
            this.account = account;
            this.region = region;
            this.gatewayId = gatewayId;
            this.gatewayEui = gatewayEui;
            this.socket = socket;
            this.radio = radio;
        }
    }

    private static String sessionKey(String account, String region, String gatewayId) {
        return account + "|" + region + "|" + gatewayId;
    }

    // ---------------------------------------------------------------- provisioning

    /** Sets up a new device's network state: an ABP device has its session at once, an OTAA device after a join. */
    void provision(ObjectNode device) {
        ObjectNode net = (ObjectNode) meta(device).with("net");
        net.putArray("queue");
        JsonNode lorawan = device.path("LoRaWAN");
        JsonNode abp = lorawan.has("AbpV1_1") ? lorawan.get("AbpV1_1") : lorawan.get("AbpV1_0_x");
        if (abp != null) {
            net.put("devAddr", abp.get("DevAddr").asText().toLowerCase());
            JsonNode keys = abp.get("SessionKeys");
            if (lorawan.has("AbpV1_1")) {
                net.put("fNwkSIntKey", keys.get("FNwkSIntKey").asText()).put("sNwkSIntKey",
                        keys.get("SNwkSIntKey").asText()).put("nwkSEncKey", keys.get("NwkSEncKey").asText());
                net.put("v11", true);
            } else {
                String nwkSKey = keys.get("NwkSKey").asText();
                net.put("fNwkSIntKey", nwkSKey).put("sNwkSIntKey", nwkSKey).put("nwkSEncKey", nwkSKey);
                net.put("v11", false);
            }
            net.put("appSKey", keys.get("AppSKey").asText());
            net.put("fCntUp", abp.path("FCntStart").asLong(0) - 1);
            net.put("nFCntDown", 0).put("aFCntDown", 0);
            net.put("activated", true);
        } else {
            net.put("activated", false);
        }
    }

    void resetCounters(ObjectNode device, int start) {
        ObjectNode net = (ObjectNode) meta(device).with("net");
        net.put("fCntUp", start - 1L);
    }

    void forgetSession(ObjectNode device) {
        ObjectNode net = (ObjectNode) meta(device).with("net");
        if (device.path("LoRaWAN").has("OtaaV1_1") || device.path("LoRaWAN").has("OtaaV1_0_x")) {
            for (String field : List.of("devAddr", "fNwkSIntKey", "sNwkSIntKey", "nwkSEncKey", "appSKey")) {
                net.remove(field);
            }
            net.put("activated", false);
        }
    }

    private static LoRaWan.SessionKeys keys(JsonNode net) {
        return new LoRaWan.SessionKeys(LoRaWan.hex(net.get("fNwkSIntKey").asText()),
                LoRaWan.hex(net.get("sNwkSIntKey").asText()), LoRaWan.hex(net.get("nwkSEncKey").asText()),
                LoRaWan.hex(net.get("appSKey").asText()));
    }

    // ---------------------------------------------------------------- gateways

    /** The gateway a Basics Station router EUI names, in any account of the Region. */
    AccountAwareStorageBackend.AccountEntry<ObjectNode> gatewayByEui(String region, String eui) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : store.everywhere(key -> key.startsWith(region + "/" + GATEWAY + "/"))) {
            if (eui.equalsIgnoreCase(entry.value().path("LoRaWAN").path("GatewayEui").asText())) {
                return entry;
            }
        }
        return null;
    }

    Session connect(String account, String region, ObjectNode gateway, TextWebSocket socket) {
        String id = gateway.get("Id").asText();
        Session previous = sessions.get(sessionKey(account, region, id));
        if (previous != null && previous.socket.isOpen()) {
            previous.socket.close(1000, "Replaced by a new connection.");
        }
        LoRaRegions.Region radio = LoRaRegions.region(gateway.path("LoRaWAN").path("RfRegion").asText("US915"));
        Session session = new Session(account, region, id, gateway.get("LoRaWAN").get("GatewayEui").asText(), socket,
                radio);
        sessions.put(sessionKey(account, region, id), session);
        updateGateway(account, region, id, net -> net.put("connected", true).put("connectedAt", WirelessStore.now()));
        connectionEvent(account, region, gateway, "connected");
        log(account, region, "WirelessGateway", id, "INFO", "Gateway connected", null);
        return session;
    }

    void closed(Session session) {
        sessions.remove(sessionKey(session.account, session.region, session.gatewayId), session);
        RequestScopes.runAs(session.account, session.region, () -> {
            updateGateway(session.account, session.region, session.gatewayId,
                    net -> net.put("connected", false).put("disconnectedAt", WirelessStore.now()));
            store.get(session.account, session.region, GATEWAY, session.gatewayId)
                    .ifPresent(gateway -> connectionEvent(session.account, session.region, gateway, "disconnected"));
        });
    }

    void reconfigure(String account, String region, ObjectNode gateway) {
        Session session = sessions.get(sessionKey(account, region, gateway.get("Id").asText()));
        if (session != null && session.socket.isOpen()) {
            session.socket.send(routerConfig(gateway).toString());
        }
    }

    void disconnect(String account, String region, ObjectNode gateway) {
        Session session = sessions.remove(sessionKey(account, region, gateway.get("Id").asText()));
        if (session != null) {
            session.socket.close(1000, "The gateway was deleted.");
        }
    }

    ObjectNode routerConfig(ObjectNode gateway) {
        JsonNode lorawan = gateway.get("LoRaWAN");
        LoRaRegions.Region radio = LoRaRegions.region(lorawan.path("RfRegion").asText("US915"));
        ArrayNode joinEuis = null;
        if (lorawan.path("JoinEuiFilters").isArray()) {
            joinEuis = mapper.createArrayNode();
            for (JsonNode range : lorawan.get("JoinEuiFilters")) {
                ArrayNode pair = joinEuis.addArray();
                range.forEach(eui -> pair.add(Long.parseUnsignedLong(eui.asText(), 16)));
            }
        }
        ArrayNode netIds = null;
        if (lorawan.path("NetIdFilters").isArray()) {
            netIds = mapper.createArrayNode();
            for (JsonNode netId : lorawan.get("NetIdFilters")) {
                netIds.add(Integer.parseInt(netId.asText(), 16));
            }
        }
        return LoRaRegions.routerConfig(mapper, radio, joinEuis, netIds);
    }

    private void updateGateway(String account, String region, String id, Consumer<ObjectNode> f) {
        synchronized (this) {
            store.get(account, region, GATEWAY, id).ifPresent(gateway -> {
                f.accept((ObjectNode) meta(gateway).with("net"));
                store.put(account, region, GATEWAY, id, gateway);
            });
        }
    }

    // ---------------------------------------------------------------- station messages

    /** A message from a connected gateway. */
    void message(Session session, String text) {
        JsonNode message;
        try {
            message = mapper.readTree(text);
        } catch (Exception e) {
            return;
        }
        RequestScopes.runAs(session.account, session.region, () -> {
            try {
                switch (message.path("msgtype").asText()) {
                    case "version" -> version(session, message);
                    case "jreq" -> joinRequest(session, message);
                    case "updf" -> uplink(session, message);
                    case "dntxed" -> transmitted(session, message);
                    case "timesync" -> session.socket.send(mapper.createObjectNode().put("msgtype", "timesync")
                            .put("txtime", message.path("txtime").asDouble())
                            .put("gpstime", gpsTime(Instant.now())).toString());
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                LOG.debugv(e, "IoT Wireless couldn't handle a message from gateway {0}", session.gatewayEui);
            }
        });
    }

    private static long gpsTime(Instant at) {
        return (at.toEpochMilli() - 315_964_800_000L + 18_000L) * 1000;
    }

    private void version(Session session, JsonNode message) {
        ObjectNode gateway = store.get(session.account, session.region, GATEWAY, session.gatewayId).orElse(null);
        if (gateway == null) {
            session.socket.close(1008, "The gateway doesn't exist.");
            return;
        }
        ObjectNode version = mapper.createObjectNode();
        version.put("PackageVersion", message.path("package").asText(""));
        version.put("Model", message.path("model").asText(""));
        version.put("Station", message.path("station").asText(""));
        updateGateway(session.account, session.region, session.gatewayId, net -> net.set("version", version));
        session.socket.send(routerConfig(gateway).toString());
    }

    private void transmitted(Session session, JsonNode message) {
        String devEui = message.path("DevEui").asText(null);
        if (devEui != null) {
            metric(session.account, session.region, "gateway", session.gatewayId, "GatewayDownlinkCount", 1);
        }
    }

    /** The radio of an uplink: data rate, frequency and the gateway's RSSI and SNR. */
    private record Radio(int dr, long freq, double rssi, double snr, JsonNode upinfo) {
    }

    private static Radio radio(JsonNode message) {
        JsonNode upinfo = message.path("upinfo");
        return new Radio(message.path("DR").asInt(), message.path("Freq").asLong(), upinfo.path("rssi").asDouble(),
                upinfo.path("snr").asDouble(), upinfo);
    }

    private static String eui(JsonNode value) {
        if (value.isNumber()) {
            return String.format("%016x", value.asLong());
        }
        return value.asText().replace("-", "").replace(":", "").toLowerCase();
    }

    // ---------------------------------------------------------------- joins

    private void joinRequest(Session session, JsonNode message) {
        String account = session.account;
        String region = session.region;
        String devEui = eui(message.get("DevEui"));
        String joinEui = eui(message.get("JoinEui"));
        int devNonce = message.path("DevNonce").asInt();
        int mic = message.path("MIC").asInt();
        Radio radio = radio(message);
        metric(account, region, "gateway", session.gatewayId, "GatewayJoinRequestCount", 1);
        lastUplink(account, region, session.gatewayId);
        ObjectNode device = null;
        for (ObjectNode candidate : store.list(account, region, DEVICE)) {
            if (devEui.equalsIgnoreCase(candidate.path("LoRaWAN").path("DevEui").asText())) {
                device = candidate;
            }
        }
        if (device == null) {
            log(account, region, "WirelessGateway", session.gatewayId, "ERROR", "Join request from unknown device",
                    devEui);
            return;
        }
        JsonNode lorawan = device.get("LoRaWAN");
        boolean v11 = lorawan.has("OtaaV1_1");
        JsonNode otaa = v11 ? lorawan.get("OtaaV1_1") : lorawan.get("OtaaV1_0_x");
        String deviceId = device.get("Id").asText();
        if (otaa == null) {
            log(account, region, "WirelessDevice", deviceId, "ERROR", "Join request from an ABP device", devEui);
            return;
        }
        String expectedJoinEui = otaa.path("JoinEui").asText(otaa.path("AppEui").asText(""));
        if (!expectedJoinEui.equalsIgnoreCase(joinEui)) {
            log(account, region, "WirelessDevice", deviceId, "ERROR", "Join request with the wrong JoinEui", joinEui);
            return;
        }
        byte[] appKey = LoRaWan.hex(otaa.get("AppKey").asText());
        byte[] rootKey = v11 ? LoRaWan.hex(otaa.get("NwkKey").asText()) : appKey;
        byte[] frame = joinFrame(joinEui, devEui, devNonce, mic);
        if (!LoRaWan.joinRequestValid(LoRaWan.joinRequest(frame), rootKey)) {
            log(account, region, "WirelessDevice", deviceId, "ERROR", "Join request MIC mismatch", devEui);
            return;
        }
        joinEvent(account, region, device, "join_req_received", null);
        metric(account, region, "device", deviceId, "DeviceJoinRequestCount", 1);
        synchronized (this) {
            device = store.get(account, region, DEVICE, deviceId).orElse(device);
            ObjectNode net = (ObjectNode) meta(device).with("net");
            ArrayNode nonces = (ArrayNode) net.withArray("devNonces");
            for (JsonNode used : nonces) {
                if (used.asInt() == devNonce) {
                    log(account, region, "WirelessDevice", deviceId, "ERROR", "Join request DevNonce replayed",
                            String.valueOf(devNonce));
                    return;
                }
            }
            nonces.add(devNonce);
            while (nonces.size() > 100) {
                nonces.remove(0);
            }
            int joinNonce = net.path("joinNonce").asInt(0) + 1;
            net.put("joinNonce", joinNonce);
            byte[] devAddr = new byte[4];
            RANDOM.nextBytes(devAddr);
            devAddr[0] = (byte) ((NET_ID & 0x7F) << 1 | (devAddr[0] & 0x01));
            LoRaWan.SessionKeys keys = v11 ? LoRaWan.deriveV11(rootKey, appKey, joinNonce, joinEui, devNonce)
                    : LoRaWan.deriveV10(appKey, joinNonce, NET_ID, devNonce);
            ObjectNode profile = store.get(account, region, DEVICE_PROFILE, lorawan.get("DeviceProfileId").asText())
                    .orElse(null);
            JsonNode settings = profile == null ? mapper.createObjectNode() : profile.path("LoRaWAN");
            int rx1Offset = settings.path("RxDrOffset1").asInt(0);
            LoRaRegions.Region region0 = session.radio;
            int rx2Dr = settings.has("RxDataRate2") ? settings.get("RxDataRate2").asInt() : region0.rx2Dr();
            int rxDelay = Math.max(1, settings.path("RxDelay1").asInt(1));
            byte[] accept = LoRaWan.joinAccept(joinNonce, NET_ID, devAddr, (rx1Offset & 0x07) << 4 | (rx2Dr & 0x0F),
                    rxDelay == 1 ? 0 : rxDelay, null, rootKey, v11, v11 ? LoRaWan.jsIntKey(rootKey, devEui) : null,
                    joinEui, devNonce);
            String address = LoRaWan.hex(devAddr);
            net.put("devAddr", address);
            net.put("fNwkSIntKey", LoRaWan.hex(keys.fNwkSIntKey())).put("sNwkSIntKey", LoRaWan.hex(keys.sNwkSIntKey()))
                    .put("nwkSEncKey", LoRaWan.hex(keys.nwkSEncKey())).put("appSKey", LoRaWan.hex(keys.appSKey()));
            net.put("v11", v11);
            net.put("fCntUp", -1).put("nFCntDown", 0).put("aFCntDown", 0);
            net.put("activated", true);
            net.put("joinedAt", WirelessStore.now());
            net.put("rx1Offset", rx1Offset).put("rx2Dr", rx2Dr).put("rxDelay", rxDelay);
            store.put(account, region, DEVICE, deviceId, device);
            send(session, devEui, accept, radio, 5, rx1Offset, rx2Dr);
            metric(account, region, "device", deviceId, "DeviceJoinAcceptCount", 1);
            metric(account, region, "gateway", session.gatewayId, "GatewayJoinAcceptCount", 1);
            joinEvent(account, region, device, "join_accepted", address);
            log(account, region, "WirelessDevice", deviceId, "INFO", "Join accepted", address);
        }
    }

    /** The join request a station's {@code jreq} fields stand for, rebuilt to check its MIC. */
    private static byte[] joinFrame(String joinEui, String devEui, int devNonce, int mic) {
        byte[] frame = new byte[23];
        System.arraycopy(LoRaWan.reverse(LoRaWan.hex(joinEui)), 0, frame, 1, 8);
        System.arraycopy(LoRaWan.reverse(LoRaWan.hex(devEui)), 0, frame, 9, 8);
        frame[17] = (byte) devNonce;
        frame[18] = (byte) (devNonce >> 8);
        System.arraycopy(LoRaWan.micBytes(mic), 0, frame, 19, 4);
        return frame;
    }

    // ---------------------------------------------------------------- uplinks

    private void uplink(Session session, JsonNode message) {
        String account = session.account;
        String region = session.region;
        int mhdr = message.path("MHdr").asInt();
        String devAddr = String.format("%08x", message.path("DevAddr").asInt());
        int fCtrl = message.path("FCtrl").asInt();
        int fCnt16 = message.path("FCnt").asInt();
        byte[] fOpts = LoRaWan.hex(message.path("FOpts").asText(""));
        int fPort = message.path("FPort").asInt(-1);
        byte[] payload = LoRaWan.hex(message.path("FRMPayload").asText(""));
        int mic = message.path("MIC").asInt();
        Radio radio = radio(message);
        metric(account, region, "gateway", session.gatewayId, "GatewayUplinkCount", 1);
        metric(account, region, "gateway", session.gatewayId, "GatewayRSSI", radio.rssi());
        metric(account, region, "gateway", session.gatewayId, "GatewaySNR", radio.snr());
        lastUplink(account, region, session.gatewayId);
        byte[] frame = frame(mhdr, devAddr, fCtrl, fCnt16, fOpts, fPort, payload, mic);
        synchronized (this) {
            for (ObjectNode device : store.list(account, region, DEVICE)) {
                JsonNode net = meta(device).path("net");
                if (!net.path("activated").asBoolean(false) || !devAddr.equalsIgnoreCase(net.path("devAddr").asText())) {
                    continue;
                }
                LoRaWan.SessionKeys keys = keys(net);
                boolean v11 = net.path("v11").asBoolean(false);
                long fCnt = LoRaWan.fullCounter(Math.max(0, net.path("fCntUp").asLong(-1)), fCnt16);
                int expected = LoRaWan.uplinkMic(frame, devAddr, fCnt, keys, v11, 0, radio.dr(), 0);
                if (expected != mic) {
                    continue;
                }
                received(session, device, frame, fCnt, radio);
                return;
            }
        }
        log(account, region, "WirelessGateway", session.gatewayId, "ERROR", "Uplink from an unknown device", devAddr);
    }

    private static byte[] frame(int mhdr, String devAddr, int fCtrl, int fCnt16, byte[] fOpts, int fPort,
                                byte[] payload, int mic) {
        int length = 8 + fOpts.length + (fPort >= 0 ? 1 + payload.length : 0) + 4;
        byte[] frame = new byte[length];
        frame[0] = (byte) mhdr;
        System.arraycopy(LoRaWan.reverse(LoRaWan.hex(devAddr)), 0, frame, 1, 4);
        frame[5] = (byte) fCtrl;
        frame[6] = (byte) fCnt16;
        frame[7] = (byte) (fCnt16 >> 8);
        System.arraycopy(fOpts, 0, frame, 8, fOpts.length);
        if (fPort >= 0) {
            frame[8 + fOpts.length] = (byte) fPort;
            System.arraycopy(payload, 0, frame, 9 + fOpts.length, payload.length);
        }
        System.arraycopy(LoRaWan.micBytes(mic), 0, frame, length - 4, 4);
        return frame;
    }

    /** An uplink whose MIC checked out: counts, decrypts, routes and answers it. */
    private void received(Session session, ObjectNode device, byte[] frame, long fCnt, Radio radio) {
        String account = session.account;
        String region = session.region;
        String deviceId = device.get("Id").asText();
        ObjectNode net = (ObjectNode) meta(device).with("net");
        long last = net.path("fCntUp").asLong(-1);
        LoRaWan.DataFrame data = LoRaWan.dataFrame(frame);
        if (fCnt <= last) {
            log(account, region, "WirelessDevice", deviceId, "ERROR", "Uplink frame counter replayed",
                    String.valueOf(fCnt));
            return;
        }
        if (last >= 0 && fCnt > last + 1) {
            metric(account, region, "device", deviceId, "DeviceUplinkLostCount", fCnt - last - 1);
        }
        net.put("fCntUp", fCnt);
        LoRaWan.SessionKeys keys = keys(net);
        boolean v11 = net.path("v11").asBoolean(false);
        byte[] fOpts = data.fOpts();
        if (v11 && fOpts.length > 0) {
            fOpts = LoRaWan.crypt(keys.nwkSEncKey(), 0, data.devAddr(), fCnt, fOpts);
        }
        byte[] payload = data.fPort() >= 0 ? LoRaWan.crypt(data.fPort() == 0 ? keys.nwkSEncKey() : keys.appSKey(), 0,
                data.devAddr(), fCnt, data.payload()) : new byte[0];
        byte[] macCommands = data.fPort() == 0 ? payload : fOpts;
        List<byte[]> answers = macAnswers(macCommands, radio, session);
        if (data.ack()) {
            ArrayNode queue = (ArrayNode) net.withArray("queue");
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).path("_sent").asBoolean(false) && queue.get(i).path("TransmitMode").asInt() == 1) {
                    queue.remove(i);
                    break;
                }
            }
        }
        ObjectNode lastUplink = mapper.createObjectNode();
        lastUplink.put("DevEui", device.path("LoRaWAN").path("DevEui").asText());
        if (data.fPort() >= 0) {
            lastUplink.put("FPort", data.fPort());
        }
        lastUplink.put("DataRate", radio.dr());
        lastUplink.put("Frequency", radio.freq());
        lastUplink.put("Timestamp", WirelessStore.now());
        lastUplink.putArray("Gateways").addObject().put("GatewayEui", session.gatewayEui).put("Snr", radio.snr())
                .put("Rssi", radio.rssi());
        net.set("lastUplink", lastUplink);
        net.put("lastGateway", session.gatewayId);
        net.put("lastDr", radio.dr());
        net.put("lastFreq", radio.freq());
        net.set("lastUpinfo", radio.upinfo().deepCopy());
        store.put(account, region, DEVICE, deviceId, device);
        metric(account, region, "device", deviceId, "DeviceUplinkCount", 1);
        metric(account, region, "device", deviceId, "DeviceRSSI", radio.rssi());
        metric(account, region, "device", deviceId, "DeviceSNR", radio.snr());
        JsonNode ports = device.path("LoRaWAN").path("FPorts");
        if (data.fPort() > 0 && (data.fPort() == ports.path("Multicast").asInt(200)
                || data.fPort() == ports.path("Fuota").asInt(201))) {
            fleet.get().protocolAnswer(account, region, device, data.fPort(), payload);
        } else if (data.fPort() > 0) {
            ObjectNode metadata = metadata(account, region, device, data, fCnt, radio, session);
            deliver(account, region, device, payload, metadata);
        }
        answer(session, device, data, radio, answers);
    }

    /** LinkCheckReq and DeviceTimeReq answered; other MAC commands acknowledged by being read. */
    private static List<byte[]> macAnswers(byte[] commands, Radio radio, Session session) {
        List<byte[]> answers = new ArrayList<>();
        int i = 0;
        while (i < commands.length) {
            int cid = commands[i] & 0xFF;
            switch (cid) {
                case 0x02 -> {
                    int margin = (int) Math.max(0, Math.min(254, radio.snr() + 20));
                    answers.add(new byte[] {0x02, (byte) margin, 1});
                    i += 1;
                }
                case 0x0D -> {
                    long gps = gpsTime(Instant.now()) / 1000;
                    long seconds = gps / 1000;
                    int fraction = (int) ((gps % 1000) * 256 / 1000);
                    answers.add(new byte[] {0x0D, (byte) seconds, (byte) (seconds >> 8), (byte) (seconds >> 16),
                        (byte) (seconds >> 24), (byte) fraction});
                    i += 1;
                }
                case 0x03 -> i += 2;
                case 0x04, 0x05 -> i += 2;
                case 0x06 -> i += 3;
                case 0x07 -> i += 2;
                case 0x08, 0x09, 0x0A -> i += 1;
                case 0x01 -> i += 2;
                case 0x0B -> i += 2;
                default -> i = commands.length;
            }
        }
        return answers;
    }

    private ObjectNode metadata(String account, String region, ObjectNode device, LoRaWan.DataFrame data, long fCnt,
                                Radio radio, Session session) {
        LoRaRegions.DataRate dr = session.radio.dr(radio.dr());
        ObjectNode lorawan = mapper.createObjectNode();
        lorawan.put("ADR", data.adr());
        lorawan.put("Bandwidth", dr == null ? 125 : dr.bw());
        lorawan.put("ClassB", (data.fCtrl() & 0x10) != 0);
        lorawan.put("CodeRate", "4/5");
        lorawan.put("DataRate", String.valueOf(radio.dr()));
        lorawan.put("DevAddr", data.devAddr());
        lorawan.put("DevEui", device.path("LoRaWAN").path("DevEui").asText());
        lorawan.put("FCnt", fCnt);
        lorawan.put("FOptLen", data.fOpts().length);
        lorawan.put("FPort", data.fPort());
        lorawan.put("Frequency", String.valueOf(radio.freq()));
        ObjectNode serviceProfile = store.get(account, region, SERVICE_PROFILE,
                device.path("LoRaWAN").path("ServiceProfileId").asText("")).orElse(null);
        if (serviceProfile != null && serviceProfile.path("LoRaWAN").path("AddGwMetadata").asBoolean(false)) {
            lorawan.putArray("Gateways").addObject().put("GatewayEui", session.gatewayEui)
                    .put("Rssi", radio.rssi()).put("Snr", radio.snr());
        }
        lorawan.put("MIC", String.format("%08x", data.mic()));
        lorawan.put("MType", data.confirmed() ? "ConfirmedDataUp" : "UnconfirmedDataUp");
        lorawan.put("Major", "LoRaWANR1");
        lorawan.put("Modulation", "LORA");
        lorawan.put("PolarizationInversion", false);
        lorawan.put("SpreadingFactor", dr == null ? 7 : dr.sf());
        lorawan.put("Timestamp", WirelessStore.now());
        return lorawan;
    }

    /** Delivers an uplink to the device's destination: its IoT rule, invoked by name, or its MQTT topic. */
    void deliver(String account, String region, ObjectNode device, byte[] payload, ObjectNode lorawan) {
        ObjectNode message = mapper.createObjectNode();
        message.put("WirelessDeviceId", device.get("Id").asText());
        message.put("PayloadData", Base64.getEncoder().encodeToString(payload));
        ObjectNode metadata = message.putObject("WirelessMetadata");
        if (lorawan != null) {
            metadata.set("LoRaWAN", lorawan);
        }
        route(account, region, device, device.get("DestinationName").asText(), message);
    }

    void route(String account, String region, ObjectNode device, String destinationName, ObjectNode message) {
        ObjectNode destination = store.get(account, region, DESTINATION, destinationName).orElse(null);
        String deviceId = device.get("Id").asText();
        if (destination == null) {
            log(account, region, "WirelessDevice", deviceId, "ERROR", "Uplink destination doesn't exist",
                    destinationName);
            return;
        }
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if ("RuleName".equals(destination.get("ExpressionType").asText())) {
                iot.get().invokeTopicRule(destination.get("Expression").asText(), bytes, region);
            } else {
                iot.get().publish(destination.get("Expression").asText(), bytes, false, 0, region, null);
            }
            log(account, region, "WirelessDevice", deviceId, "INFO", "Uplink data delivered", destinationName);
        } catch (AwsException e) {
            log(account, region, "WirelessDevice", deviceId, "ERROR", "Uplink data couldn't be delivered: "
                    + e.getMessage(), destinationName);
        }
    }

    // ---------------------------------------------------------------- downlinks

    /** After an uplink: one downlink carrying MAC answers, an ACK and the next queued message, if any are due. */
    private void answer(Session session, ObjectNode device, LoRaWan.DataFrame data, Radio radio,
                        List<byte[]> answers) {
        String account = session.account;
        String region = session.region;
        ObjectNode net = (ObjectNode) meta(device).with("net");
        ArrayNode queue = (ArrayNode) net.withArray("queue");
        ObjectNode next = queue.isEmpty() ? null : (ObjectNode) queue.get(0);
        if (answers.isEmpty() && !data.confirmed() && next == null) {
            return;
        }
        byte[] fOpts = new byte[0];
        for (byte[] answer : answers) {
            fOpts = concat(fOpts, answer);
        }
        boolean v11 = net.path("v11").asBoolean(false);
        LoRaWan.SessionKeys keys = keys(net);
        int fPort = -1;
        byte[] payload = new byte[0];
        boolean confirmed = false;
        if (next != null) {
            fPort = next.path("LoRaWAN").path("FPort").asInt(1);
            payload = Base64.getDecoder().decode(next.path("_payload").asText());
            confirmed = next.path("TransmitMode").asInt() == 1;
        } else if (fOpts.length > 15) {
            fPort = 0;
            payload = fOpts;
            fOpts = new byte[0];
        }
        long fCnt = v11 && fPort > 0 ? net.path("aFCntDown").asLong(0) : net.path("nFCntDown").asLong(0);
        if (v11 && fOpts.length > 0) {
            fOpts = LoRaWan.crypt(keys.nwkSEncKey(), 1, data.devAddr(), fCnt, fOpts);
        }
        byte[] frame = LoRaWan.buildDownlink(confirmed, data.devAddr(), fCnt, data.confirmed(), queue.size() > 1,
                fOpts, fPort, payload, keys, v11, data.confirmed() ? (int) (net.path("fCntUp").asLong() & 0xFFFF) : 0);
        if (v11 && fPort > 0) {
            net.put("aFCntDown", fCnt + 1);
        } else {
            net.put("nFCntDown", fCnt + 1);
        }
        if (next != null) {
            if (confirmed) {
                next.put("_sent", true);
            } else {
                queue.remove(0);
            }
        }
        store.put(account, region, DEVICE, device.get("Id").asText(), device);
        int offset = net.path("rx1Offset").asInt(0);
        int rx2Dr = net.has("rx2Dr") ? net.get("rx2Dr").asInt() : session.radio.rx2Dr();
        send(session, device.path("LoRaWAN").path("DevEui").asText(), frame, radio, net.path("rxDelay").asInt(1),
                offset, rx2Dr);
        metric(account, region, "device", device.get("Id").asText(), "DeviceDownlinkCount", 1);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** A Class A downlink in the RX1 and RX2 windows of the uplink it answers. */
    private void send(Session session, String devEui, byte[] frame, Radio radio, int rxDelay, int rx1Offset,
                      int rx2Dr) {
        LoRaRegions.Region region = session.radio;
        ObjectNode message = mapper.createObjectNode();
        message.put("msgtype", "dnmsg");
        message.put("DevEui", formatEui(devEui));
        message.put("dC", 0);
        message.put("diid", diids.getAndIncrement());
        message.put("pdu", LoRaWan.hex(frame));
        message.put("RxDelay", rxDelay);
        message.put("RX1DR", region.rx1Dr(radio.dr(), rx1Offset));
        message.put("RX1Freq", region.rx1Freq(radio.freq()));
        message.put("RX2DR", rx2Dr);
        message.put("RX2Freq", region.rx2Freq());
        message.put("priority", 0);
        message.put("xtime", radio.upinfo().path("xtime").asLong());
        message.put("rctx", radio.upinfo().path("rctx").asLong());
        message.put("MuxTime", System.currentTimeMillis() / 1000.0);
        session.socket.send(message.toString());
    }

    /** A Class C downlink: sent at once on RX2 through the gateway the device was last heard on. */
    private boolean sendClassC(String account, String region, ObjectNode device, byte[] frame) {
        JsonNode net = meta(device).path("net");
        Session session = sessions.get(sessionKey(account, region, net.path("lastGateway").asText("")));
        if (session == null || !session.socket.isOpen()) {
            return false;
        }
        ObjectNode message = mapper.createObjectNode();
        message.put("msgtype", "dnmsg");
        message.put("DevEui", formatEui(device.path("LoRaWAN").path("DevEui").asText()));
        message.put("dC", 2);
        message.put("diid", diids.getAndIncrement());
        message.put("pdu", LoRaWan.hex(frame));
        message.put("RX2DR", net.has("rx2Dr") ? net.get("rx2Dr").asInt() : session.radio.rx2Dr());
        message.put("RX2Freq", session.radio.rx2Freq());
        message.put("priority", 0);
        message.put("rctx", net.path("lastUpinfo").path("rctx").asLong());
        message.put("MuxTime", System.currentTimeMillis() / 1000.0);
        session.socket.send(message.toString());
        return true;
    }

    private static String formatEui(String eui) {
        String hex = eui.toLowerCase();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                out.append('-');
            }
            out.append(hex, i, i + 2);
        }
        return out.toString();
    }

    ObjectNode sendDataToWirelessDevice(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode device = service.get().device(account, region, in.get("Id").asText());
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(in.get("PayloadData").asText());
        } catch (IllegalArgumentException e) {
            throw validation("PayloadData is base64.");
        }
        String messageId = WirelessStore.uuid();
        ObjectNode queued = mapper.createObjectNode();
        queued.put("MessageId", messageId);
        queued.put("TransmitMode", in.get("TransmitMode").asInt());
        queued.put("ReceivedAt", WirelessStore.now());
        if (device.has("Sidewalk")) {
            ObjectNode sidewalk = in.path("WirelessMetadata").path("Sidewalk").isObject()
                    ? in.path("WirelessMetadata").path("Sidewalk").deepCopy() : mapper.createObjectNode();
            queued.set("Sidewalk", sidewalk);
            queued.put("_payload", Base64.getEncoder().encodeToString(payload));
            synchronized (this) {
                ObjectNode current = service.get().device(account, region, device.get("Id").asText());
                ((ArrayNode) meta(current).with("net").withArray("queue")).add(queued);
                store.put(account, region, DEVICE, current.get("Id").asText(), current);
            }
            return mapper.createObjectNode().put("MessageId", messageId);
        }
        JsonNode lorawan = in.path("WirelessMetadata").path("LoRaWAN");
        int fPort = lorawan.path("FPort").asInt(1);
        if (fPort < 1 || fPort > 223) {
            throw validation("FPort is between 1 and 223.");
        }
        int max = maxPayload(account, region, device);
        if (payload.length > max) {
            throw validation("The payload is " + payload.length + " bytes; this device takes at most " + max + ".");
        }
        ObjectNode metadata = queued.putObject("LoRaWAN").put("FPort", fPort);
        if (lorawan.has("ParticipatingGateways")) {
            JsonNode gateways = lorawan.get("ParticipatingGateways");
            for (JsonNode item : gateways.path("GatewayList")) {
                service.get().gateway(account, region, item.get("GatewayId").asText());
            }
            metadata.set("ParticipatingGateways", gateways.deepCopy());
        }
        queued.put("_payload", Base64.getEncoder().encodeToString(payload));
        synchronized (this) {
            ObjectNode current = service.get().device(account, region, device.get("Id").asText());
            ObjectNode net = (ObjectNode) meta(current).with("net");
            ArrayNode queue = (ArrayNode) net.withArray("queue");
            if (queue.size() >= store.config().maxQueuedMessages()) {
                throw validation("The device's downlink queue is full.");
            }
            queue.add(queued);
            store.put(account, region, DEVICE, current.get("Id").asText(), current);
            if (classC(account, region, current) && net.path("activated").asBoolean(false)) {
                flushClassC(account, region, current);
            }
        }
        return mapper.createObjectNode().put("MessageId", messageId);
    }

    private boolean classC(String account, String region, ObjectNode device) {
        return store.get(account, region, DEVICE_PROFILE, device.path("LoRaWAN").path("DeviceProfileId").asText(""))
                .map(p -> p.path("LoRaWAN").path("SupportsClassC").asBoolean(false)).orElse(false);
    }

    /** Sends a Class C device's queued unconfirmed downlinks now, through its last gateway. */
    private void flushClassC(String account, String region, ObjectNode device) {
        ObjectNode net = (ObjectNode) meta(device).with("net");
        ArrayNode queue = (ArrayNode) net.withArray("queue");
        LoRaWan.SessionKeys keys = keys(net);
        boolean v11 = net.path("v11").asBoolean(false);
        while (!queue.isEmpty()) {
            JsonNode next = queue.get(0);
            int fPort = next.path("LoRaWAN").path("FPort").asInt(1);
            long fCnt = v11 ? net.path("aFCntDown").asLong(0) : net.path("nFCntDown").asLong(0);
            boolean confirmed = next.path("TransmitMode").asInt() == 1;
            byte[] frame = LoRaWan.buildDownlink(confirmed, net.get("devAddr").asText(), fCnt, false,
                    queue.size() > 1, null, fPort, Base64.getDecoder().decode(next.path("_payload").asText()), keys,
                    v11, 0);
            if (!sendClassC(account, region, device, frame)) {
                return;
            }
            net.put(v11 ? "aFCntDown" : "nFCntDown", fCnt + 1);
            if (confirmed) {
                ((ObjectNode) next).put("_sent", true);
                break;
            }
            queue.remove(0);
        }
        store.put(account, region, DEVICE, device.get("Id").asText(), device);
    }

    /** The largest application payload at the device's last data rate, or the lowest data rate's. */
    private int maxPayload(String account, String region, ObjectNode device) {
        String rfRegion = store.get(account, region, DEVICE_PROFILE,
                device.path("LoRaWAN").path("DeviceProfileId").asText("")).map(p -> p.path("LoRaWAN")
                .path("RfRegion").asText("US915")).orElse("US915");
        LoRaRegions.Region radio = LoRaRegions.region(rfRegion);
        int dr = meta(device).path("net").path("lastDr").asInt(0);
        LoRaRegions.DataRate rate = radio.dr(dr);
        if (rate == null) {
            return 11;
        }
        if (radio.fixed()) {
            return switch (dr) {
                case 0 -> 11;
                case 1 -> 53;
                case 2 -> 125;
                default -> 242;
            };
        }
        return switch (rate.sf()) {
            case 12, 11, 10 -> 51;
            case 9 -> 115;
            default -> 222;
        };
    }

    ObjectNode listQueuedMessages(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode device = service.get().device(account, region, in.get("Id").asText());
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode queued : meta(device).path("net").path("queue")) {
            ObjectNode item = queued.deepCopy();
            List<String> hidden = new ArrayList<>();
            item.fieldNames().forEachRemaining(name -> {
                if (name.startsWith("_")) {
                    hidden.add(name);
                }
            });
            item.remove(hidden);
            items.add(item);
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "DownlinkQueueMessagesList");
        return out;
    }

    ObjectNode deleteQueuedMessages(ObjectNode in, String region) {
        String account = store.account();
        String messageId = in.get("MessageId").asText();
        synchronized (this) {
            ObjectNode device = service.get().device(account, region, in.get("Id").asText());
            ArrayNode queue = (ArrayNode) meta(device).with("net").withArray("queue");
            if ("*".equals(messageId)) {
                queue.removeAll();
            } else {
                boolean removed = false;
                for (int i = queue.size() - 1; i >= 0; i--) {
                    if (messageId.equals(queue.get(i).path("MessageId").asText())) {
                        queue.remove(i);
                        removed = true;
                    }
                }
                if (!removed) {
                    throw notFound("DownlinkQueueMessage", messageId);
                }
            }
            store.put(account, region, DEVICE, device.get("Id").asText(), device);
        }
        return mapper.createObjectNode();
    }

    // ---------------------------------------------------------------- tests and statistics

    /**
     * TestWirelessDevice: sends the uplink {@code Hello} from the device to its destination, as a device would, so the
     * route can be checked without radio hardware.
     */
    ObjectNode testWirelessDevice(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode device = service.get().device(account, region, in.get("Id").asText());
        byte[] payload = "Hello".getBytes(StandardCharsets.UTF_8);
        if (device.has("LoRaWAN")) {
            ObjectNode lorawan = mapper.createObjectNode();
            lorawan.put("DevEui", device.get("LoRaWAN").get("DevEui").asText());
            lorawan.put("FPort", 1);
            lorawan.put("Timestamp", WirelessStore.now());
            deliver(account, region, device, payload, lorawan);
        } else {
            ObjectNode message = mapper.createObjectNode();
            message.put("WirelessDeviceId", device.get("Id").asText());
            message.put("PayloadData", Base64.getEncoder().encodeToString(payload));
            message.putObject("WirelessMetadata").putObject("Sidewalk").put("Seq", 0)
                    .put("Timestamp", WirelessStore.now());
            route(account, region, device, device.get("DestinationName").asText(), message);
        }
        return mapper.createObjectNode().put("Result", "Test succeeded. One message is sent with payload: hello.");
    }

    ObjectNode getWirelessDeviceStatistics(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode device = service.get().device(account, region, in.get("WirelessDeviceId").asText());
        ObjectNode out = mapper.createObjectNode().put("WirelessDeviceId", device.get("Id").asText());
        JsonNode last = meta(device).path("net").path("lastUplink");
        if (last.has("Timestamp")) {
            out.put("LastUplinkReceivedAt", last.get("Timestamp").asText());
            if (device.has("LoRaWAN")) {
                out.set("LoRaWAN", last.deepCopy());
            }
        }
        if (device.has("Sidewalk")) {
            String status = device.get("Sidewalk").path("Status").asText();
            out.putObject("Sidewalk").put("DeviceState", "ACTIVATED".equals(status) ? "RegisteredReachable"
                    : "REGISTERED".equals(status) ? "RegisteredNotSeen" : "Provisioned");
        }
        return out;
    }

    ObjectNode getWirelessGatewayStatistics(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode gateway = service.get().gateway(account, region, in.get("WirelessGatewayId").asText());
        ObjectNode out = mapper.createObjectNode().put("WirelessGatewayId", gateway.get("Id").asText());
        String last = meta(gateway).path("net").path("lastUplinkReceivedAt").asText(null);
        if (last != null) {
            out.put("LastUplinkReceivedAt", last);
        }
        Session session = sessions.get(sessionKey(account, region, gateway.get("Id").asText()));
        out.put("ConnectionStatus", session != null && session.socket.isOpen() ? "Connected" : "Disconnected");
        return out;
    }

    private void lastUplink(String account, String region, String gatewayId) {
        updateGateway(account, region, gatewayId, net -> net.put("lastUplinkReceivedAt", WirelessStore.now()));
    }

    ObjectNode getServiceEndpoint(ObjectNode in, String region) {
        String type = in.path("ServiceType").asText("LNS");
        String base = store.emulator().effectiveBaseUrl().replaceAll("/+$", "");
        String endpoint = "LNS".equals(type)
                ? base.replaceFirst("^http", "ws") + WirelessStation.PATH + region + "/lns"
                : base + WirelessStation.PATH + region + "/cups";
        ObjectNode out = mapper.createObjectNode().put("ServiceType", type).put("ServiceEndpoint", endpoint);
        out.put("ServerTrust", authority.get().caPem());
        return out;
    }

    // ---------------------------------------------------------------- settings: events, logs, metrics

    ObjectNode settings(String account, String region) {
        return store.get(account, region, SETTINGS, "account").orElseGet(store::record);
    }

    void saveSettings(String account, String region, ObjectNode settings) {
        store.put(account, region, SETTINGS, "account", settings);
    }

    ObjectNode getEventConfigurationByResourceTypes(ObjectNode in, String region) {
        JsonNode events = settings(store.account(), region).path("events");
        ObjectNode out = mapper.createObjectNode();
        out.putObject("DeviceRegistrationState").putObject("Sidewalk").put("WirelessDeviceEventTopic",
                events.at("/DeviceRegistrationState/Sidewalk/WirelessDeviceEventTopic").asText("Disabled"));
        out.putObject("Proximity").putObject("Sidewalk").put("WirelessDeviceEventTopic",
                events.at("/Proximity/Sidewalk/WirelessDeviceEventTopic").asText("Disabled"));
        out.putObject("Join").putObject("LoRaWAN").put("WirelessDeviceEventTopic",
                events.at("/Join/LoRaWAN/WirelessDeviceEventTopic").asText("Disabled"));
        out.putObject("ConnectionStatus").putObject("LoRaWAN").put("WirelessGatewayEventTopic",
                events.at("/ConnectionStatus/LoRaWAN/WirelessGatewayEventTopic").asText("Disabled"));
        out.putObject("MessageDeliveryStatus").putObject("Sidewalk").put("WirelessDeviceEventTopic",
                events.at("/MessageDeliveryStatus/Sidewalk/WirelessDeviceEventTopic").asText("Disabled"));
        return out;
    }

    ObjectNode updateEventConfigurationByResourceTypes(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode settings = settings(account, region);
        ObjectNode events = (ObjectNode) settings.with("events");
        for (String name : EVENT_NAMES) {
            if (in.has(name)) {
                ObjectNode target = (ObjectNode) events.with(name);
                in.get(name).fields().forEachRemaining(e -> ((ObjectNode) target.with(e.getKey()))
                        .setAll((ObjectNode) e.getValue()));
            }
        }
        saveSettings(account, region, settings);
        return mapper.createObjectNode();
    }

    private String resourceKey(String identifierType, String identifier) {
        return identifierType + ":" + ("DevEui".equals(identifierType) || "GatewayEui".equals(identifierType)
                ? identifier.toLowerCase() : identifier);
    }

    ObjectNode getResourceEventConfiguration(ObjectNode in, String region) {
        String account = store.account();
        checkResource(account, region, in.get("IdentifierType").asText(), in.get("Identifier").asText());
        JsonNode configuration = settings(account, region).path("resourceEvents")
                .path(resourceKey(in.get("IdentifierType").asText(), in.get("Identifier").asText()));
        ObjectNode out = configuration.isObject() ? configuration.deepCopy() : mapper.createObjectNode();
        return out;
    }

    ObjectNode updateResourceEventConfiguration(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("IdentifierType").asText();
        String identifier = in.get("Identifier").asText();
        checkResource(account, region, type, identifier);
        ObjectNode settings = settings(account, region);
        ObjectNode configuration = (ObjectNode) settings.with("resourceEvents").with(resourceKey(type, identifier));
        for (String name : EVENT_NAMES) {
            if (in.has(name)) {
                ObjectNode target = (ObjectNode) configuration.with(name);
                in.get(name).fields().forEachRemaining(e -> target.set(e.getKey(), e.getValue().deepCopy()));
            }
        }
        if (in.hasNonNull("PartnerType")) {
            configuration.put("_partnerType", in.get("PartnerType").asText());
        }
        saveSettings(account, region, settings);
        return mapper.createObjectNode();
    }

    private void checkResource(String account, String region, String type, String identifier) {
        switch (type) {
            case "WirelessDeviceId" -> service.get().device(account, region, identifier);
            case "DevEui" -> service.get().deviceBy(account, region, identifier, "DevEui");
            case "WirelessGatewayId" -> service.get().gateway(account, region, identifier);
            case "GatewayEui" -> service.get().gatewayBy(account, region, identifier, "GatewayEui");
            case "PartnerAccountId" -> {
                if (store.get(account, region, WirelessStore.PARTNER_ACCOUNT, identifier).isEmpty()) {
                    throw notFound(WirelessStore.PARTNER_ACCOUNT, identifier);
                }
            }
            default -> throw validation("The identifier type " + type + " isn't supported.");
        }
    }

    ObjectNode listEventConfigurations(ObjectNode in, String region) {
        String account = store.account();
        String resourceType = in.get("ResourceType").asText();
        List<ObjectNode> items = new ArrayList<>();
        settings(account, region).path("resourceEvents").fields().forEachRemaining(entry -> {
            String type = entry.getKey().substring(0, entry.getKey().indexOf(':'));
            String identifier = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            boolean match = switch (resourceType) {
                case "WirelessDevice" -> Set.of("WirelessDeviceId", "DevEui").contains(type);
                case "WirelessGateway" -> Set.of("WirelessGatewayId", "GatewayEui").contains(type);
                default -> "PartnerAccountId".equals(type);
            };
            if (match) {
                ObjectNode item = mapper.createObjectNode().put("Identifier", identifier).put("IdentifierType", type);
                if (entry.getValue().has("_partnerType")) {
                    item.put("PartnerType", entry.getValue().get("_partnerType").asText());
                }
                ObjectNode events = entry.getValue().deepCopy();
                events.remove("_partnerType");
                item.set("Events", events);
                items.add(item);
            }
        });
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "EventConfigurationsList");
        return out;
    }

    /** Publishes an event to the resource-type topic and to the identifier topics its configuration enables. */
    private void publishEvent(String account, String region, String eventName, String eventType, String resource,
                              boolean typeEnabled, Map<String, String> identifiers, String configName,
                              Map<String, String> identifierFields, ObjectNode payload) {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        String base = "$aws/iotwireless/events/" + eventName + "/" + eventType + "/lorawan/" + resource;
        try {
            if (typeEnabled) {
                iot.get().publish(base, bytes, false, 0, region, null);
            }
            JsonNode resourceEvents = settings(account, region).path("resourceEvents");
            for (Map.Entry<String, String> identifier : identifiers.entrySet()) {
                JsonNode configuration = resourceEvents.path(resourceKey(identifier.getKey(), identifier.getValue()))
                        .path(configName);
                boolean enabled = "Enabled".equals(configuration.path(identifierFields.get("id")).asText())
                        || "Enabled".equals(configuration.path("LoRaWAN").path(identifierFields.get("eui")).asText());
                if (enabled) {
                    iot.get().publish(base + "/" + identifierFields.get("idPath") + "/"
                            + identifiers.get(identifierFields.get("idType")), bytes, false, 0, region, null);
                    iot.get().publish(base + "/" + identifierFields.get("euiPath") + "/"
                            + identifiers.get(identifierFields.get("euiType")).toLowerCase(), bytes, false, 0, region,
                            null);
                    break;
                }
            }
        } catch (RuntimeException e) {
            LOG.debugv(e, "IoT Wireless couldn't publish the {0} event", eventName);
        }
    }

    private void joinEvent(String account, String region, ObjectNode device, String type, String devAddr) {
        boolean enabled = "Enabled".equals(settings(account, region).path("events")
                .at("/Join/LoRaWAN/WirelessDeviceEventTopic").asText());
        ObjectNode payload = mapper.createObjectNode();
        payload.put("eventId", WirelessStore.uuid());
        payload.put("eventType", type);
        payload.put("WirelessDeviceId", device.get("Id").asText());
        payload.put("timestamp", System.currentTimeMillis());
        ObjectNode lorawan = payload.putObject("LoRaWAN");
        String devEui = device.path("LoRaWAN").path("DevEui").asText();
        lorawan.put("DevEui", devEui);
        JsonNode otaa = device.path("LoRaWAN").has("OtaaV1_1") ? device.path("LoRaWAN").get("OtaaV1_1")
                : device.path("LoRaWAN").path("OtaaV1_0_x");
        if ("join_req_received".equals(type)) {
            if (otaa.hasNonNull("JoinEui")) {
                lorawan.put("JoinEui", otaa.get("JoinEui").asText());
            }
            if (otaa.hasNonNull("AppEui")) {
                lorawan.put("AppEui", otaa.get("AppEui").asText());
            }
        } else {
            lorawan.put("DevAddr", devAddr);
            lorawan.put("NetId", String.format("%06x", NET_ID));
        }
        publishEvent(account, region, "join", type, "wireless_devices", enabled,
                Map.of("WirelessDeviceId", device.get("Id").asText(), "DevEui", devEui), "Join",
                Map.of("id", "WirelessDeviceIdEventTopic", "eui", "DevEuiEventTopic", "idPath", "wireless_device_id",
                        "euiPath", "dev_eui", "idType", "WirelessDeviceId", "euiType", "DevEui"), payload);
    }

    private void connectionEvent(String account, String region, ObjectNode gateway, String type) {
        boolean enabled = "Enabled".equals(settings(account, region).path("events")
                .at("/ConnectionStatus/LoRaWAN/WirelessGatewayEventTopic").asText());
        ObjectNode payload = mapper.createObjectNode();
        payload.put("eventId", WirelessStore.uuid());
        payload.put("eventType", type);
        payload.put("WirelessGatewayId", gateway.get("Id").asText());
        payload.put("timestamp", System.currentTimeMillis());
        String eui = gateway.path("LoRaWAN").path("GatewayEui").asText();
        payload.putObject("LoRaWAN").put("GatewayEui", eui);
        publishEvent(account, region, "connection_status", type, "wireless_gateways", enabled,
                Map.of("WirelessGatewayId", gateway.get("Id").asText(), "GatewayEui", eui), "ConnectionStatus",
                Map.of("id", "WirelessGatewayIdEventTopic", "eui", "GatewayEuiEventTopic", "idPath",
                        "wireless_gateway_id", "euiPath", "gateway_eui", "idType", "WirelessGatewayId", "euiType",
                        "GatewayEui"), payload);
    }

    // ---------------------------------------------------------------- log levels

    ObjectNode getLogLevelsByResourceTypes(ObjectNode in, String region) {
        JsonNode levels = settings(store.account(), region).path("logLevels");
        ObjectNode out = mapper.createObjectNode();
        out.put("DefaultLogLevel", levels.path("DefaultLogLevel").asText("ERROR"));
        for (String field : List.of("WirelessGatewayLogOptions", "WirelessDeviceLogOptions", "FuotaTaskLogOptions")) {
            out.set(field, levels.has(field) ? levels.get(field).deepCopy() : mapper.createArrayNode());
        }
        return out;
    }

    ObjectNode updateLogLevelsByResourceTypes(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode settings = settings(account, region);
        ObjectNode levels = (ObjectNode) settings.with("logLevels");
        for (String field : List.of("DefaultLogLevel", "WirelessGatewayLogOptions", "WirelessDeviceLogOptions",
                "FuotaTaskLogOptions")) {
            if (in.has(field)) {
                levels.set(field, in.get(field).deepCopy());
            }
        }
        saveSettings(account, region, settings);
        return mapper.createObjectNode();
    }

    ObjectNode getResourceLogLevel(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("ResourceType").asText();
        String id = in.get("ResourceIdentifier").asText();
        checkLogResource(account, region, type, id);
        JsonNode level = settings(account, region).path("resourceLogLevels").path(type + ":" + id);
        ObjectNode out = mapper.createObjectNode();
        if (!level.isMissingNode()) {
            out.put("LogLevel", level.asText());
        }
        return out;
    }

    private void checkLogResource(String account, String region, String type, String id) {
        switch (type) {
            case "WirelessDevice" -> service.get().device(account, region, id);
            case "WirelessGateway" -> service.get().gateway(account, region, id);
            case "FuotaTask" -> {
                if (store.get(account, region, WirelessStore.FUOTA_TASK, id).isEmpty()) {
                    throw notFound(WirelessStore.FUOTA_TASK, id);
                }
            }
            default -> throw validation("The resource type " + type + " is WirelessDevice, WirelessGateway or "
                    + "FuotaTask.");
        }
    }

    ObjectNode putResourceLogLevel(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("ResourceType").asText();
        String id = in.get("ResourceIdentifier").asText();
        checkLogResource(account, region, type, id);
        ObjectNode settings = settings(account, region);
        ((ObjectNode) settings.with("resourceLogLevels")).put(type + ":" + id, in.get("LogLevel").asText());
        saveSettings(account, region, settings);
        return mapper.createObjectNode();
    }

    ObjectNode resetResourceLogLevel(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode settings = settings(account, region);
        ((ObjectNode) settings.with("resourceLogLevels")).remove(in.get("ResourceType").asText() + ":"
                + in.get("ResourceIdentifier").asText());
        saveSettings(account, region, settings);
        return mapper.createObjectNode();
    }

    ObjectNode resetAllResourceLogLevels(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode settings = settings(account, region);
        settings.remove("resourceLogLevels");
        saveSettings(account, region, settings);
        return mapper.createObjectNode();
    }

    /**
     * Writes an event to the {@code AWSIoTWirelessLogs} log group when the resource's log level, or its resource
     * type's, or the default (ERROR), lets it through.
     */
    void log(String account, String region, String resourceType, String id, String level, String message,
             String detail) {
        JsonNode settings = settings(account, region);
        String configured = settings.path("resourceLogLevels").path(resourceType + ":" + id).asText(null);
        if (configured == null) {
            String field = "WirelessDevice".equals(resourceType) ? "WirelessDeviceLogOptions"
                    : "WirelessGateway".equals(resourceType) ? "WirelessGatewayLogOptions" : "FuotaTaskLogOptions";
            for (JsonNode option : settings.path("logLevels").path(field)) {
                configured = option.path("LogLevel").asText(null);
            }
        }
        if (configured == null) {
            configured = settings.path("logLevels").path("DefaultLogLevel").asText("ERROR");
        }
        if ("DISABLED".equals(configured) || "INFO".equals(level) && "ERROR".equals(configured)) {
            return;
        }
        try {
            if (!logs.get().logGroupExists(LOG_GROUP, region)) {
                logs.get().createLogGroupForAccount(account, LOG_GROUP, null, Map.of(), region);
            }
            String stream = resourceType + "-" + id;
            try {
                logs.get().createLogStreamForAccount(account, LOG_GROUP, stream, region);
            } catch (AwsException e) {
                if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                    throw e;
                }
            }
            ObjectNode event = mapper.createObjectNode();
            event.put("timestamp", Instant.now().atOffset(ZoneOffset.UTC).toString());
            event.put("resource", resourceType);
            event.put(resourceType.substring(0, 1).toLowerCase() + resourceType.substring(1) + "Id", id);
            event.put("logLevel", level);
            event.put("event", message);
            if (detail != null) {
                event.put("detail", detail);
            }
            logs.get().putLogEventsForAccount(account, LOG_GROUP, stream, List.of(Map.of(
                    "timestamp", System.currentTimeMillis(), "message", event.toString())), region);
        } catch (RuntimeException e) {
            LOG.debugv(e, "IoT Wireless couldn't write its log in {0}", region);
        }
    }

    // ---------------------------------------------------------------- metrics

    ObjectNode getMetricConfiguration(ObjectNode in, String region) {
        String status = settings(store.account(), region).path("metrics").path("Status").asText("Disabled");
        ObjectNode out = mapper.createObjectNode();
        out.putObject("SummaryMetric").put("Status", status);
        return out;
    }

    ObjectNode updateMetricConfiguration(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode settings = settings(account, region);
        if (in.has("SummaryMetric")) {
            settings.set("metrics", in.get("SummaryMetric").deepCopy());
        }
        saveSettings(account, region, settings);
        return mapper.createObjectNode();
    }

    /** Records a sample of a metric of a device, a gateway, and the account. */
    private void metric(String account, String region, String scope, String id, String name, double value) {
        if (!"Enabled".equals(settings(account, region).path("metrics").path("Status").asText("Disabled"))) {
            return;
        }
        synchronized (this) {
            ObjectNode settings = settings(account, region);
            ObjectNode samples = (ObjectNode) settings.with("samples");
            ArrayNode series = (ArrayNode) samples.withArray(name + "|" + id);
            series.addArray().add(System.currentTimeMillis() / 1000).add(value);
            while (series.size() > 10_000) {
                series.remove(0);
            }
            String accountName = name.replaceFirst("^(Device|Gateway)", "AwsAccount");
            if (name.endsWith("Count")) {
                ArrayNode total = (ArrayNode) samples.withArray(accountName + "|");
                total.addArray().add(System.currentTimeMillis() / 1000).add(value);
                while (total.size() > 10_000) {
                    total.remove(0);
                }
            }
            saveSettings(account, region, settings);
        }
    }

    ObjectNode getMetrics(ObjectNode in, String region) {
        String account = store.account();
        JsonNode settings = settings(account, region);
        if (!"Enabled".equals(settings.path("metrics").path("Status").asText("Disabled"))) {
            throw validation("Summary metrics are disabled; enable them with UpdateMetricConfiguration.");
        }
        ArrayNode results = mapper.createArrayNode();
        for (JsonNode query : in.path("SummaryMetricQueries")) {
            ObjectNode result = results.addObject();
            for (String field : List.of("QueryId", "MetricName", "Dimensions", "AggregationPeriod", "StartTimestamp",
                    "EndTimestamp")) {
                if (query.has(field)) {
                    result.set(field, query.get(field).deepCopy());
                }
            }
            String name = query.path("MetricName").asText();
            String id = "";
            for (JsonNode dimension : query.path("Dimensions")) {
                id = dimension.path("value").asText();
            }
            long period = switch (query.path("AggregationPeriod").asText("OneHour")) {
                case "OneDay" -> 86_400;
                case "OneWeek" -> 604_800;
                default -> 3600;
            };
            double start = date(query.get("StartTimestamp"), Instant.now().getEpochSecond() - period);
            double end = date(query.get("EndTimestamp"), Instant.now().getEpochSecond());
            TreeMap<Long, List<Double>> buckets = new TreeMap<>();
            String seriesKey = name.startsWith("AwsAccount") ? name + "|" : name + "|" + id;
            for (JsonNode sample : settings.path("samples").path(seriesKey)) {
                long at = sample.get(0).asLong();
                if (at >= start && at <= end) {
                    buckets.computeIfAbsent(at / period * period, k -> new ArrayList<>()).add(sample.get(1).asDouble());
                }
            }
            ArrayNode timestamps = result.putArray("Timestamps");
            ArrayNode values = result.putArray("Values");
            for (Map.Entry<Long, List<Double>> bucket : buckets.entrySet()) {
                List<Double> vs = new ArrayList<>(bucket.getValue());
                vs.sort(Double::compareTo);
                double sum = vs.stream().mapToDouble(Double::doubleValue).sum();
                double avg = sum / vs.size();
                double variance = vs.stream().mapToDouble(v -> (v - avg) * (v - avg)).sum() / vs.size();
                timestamps.add((double) bucket.getKey());
                values.addObject().put("Min", vs.get(0)).put("Max", vs.get(vs.size() - 1)).put("Sum", sum)
                        .put("Avg", avg).put("Std", Math.sqrt(variance))
                        .put("P90", vs.get(Math.min(vs.size() - 1, (int) Math.ceil(0.9 * vs.size()) - 1)));
            }
            result.put("QueryStatus", "Succeeded");
            result.put("Unit", name.contains("RSSI") ? "dBm" : name.contains("SNR") ? "dB"
                    : name.contains("Rate") ? "Percent" : name.contains("Time") ? "Seconds" : "Count");
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("SummaryMetricQueryResults", results);
        return out;
    }

    private static double date(JsonNode value, double fallback) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return fallback;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Instant.parse(value.asText()).getEpochSecond();
        } catch (RuntimeException e) {
            return Double.parseDouble(value.asText());
        }
    }

    // ---------------------------------------------------------------- positions

    private ObjectNode positioned(String account, String region, String type, String id) {
        return "WirelessGateway".equals(type) ? service.get().gateway(account, region, id)
                : service.get().device(account, region, id);
    }

    private void savePositioned(String account, String region, String type, ObjectNode record) {
        store.put(account, region, "WirelessGateway".equals(type) ? GATEWAY : DEVICE, record.get("Id").asText(),
                record);
    }

    /** UpdateResourcePosition: a GeoJSON point, or with no payload, no position. */
    ObjectNode updateResourcePosition(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("ResourceType").asText();
        ObjectNode record = positioned(account, region, type, in.get("ResourceIdentifier").asText());
        String payload = in.path("GeoJsonPayload").asText(null);
        if (payload == null || payload.isBlank()) {
            meta(record).remove("geoJson");
        } else {
            JsonNode geoJson;
            try {
                geoJson = mapper.readTree(payload);
            } catch (Exception e) {
                throw validation("The GeoJSON payload isn't JSON.");
            }
            if (!"Point".equals(geoJson.path("type").asText()) || !geoJson.path("coordinates").isArray()
                    || geoJson.get("coordinates").size() < 2) {
                throw validation("The GeoJSON payload is a Point with [longitude, latitude] coordinates.");
            }
            double longitude = geoJson.get("coordinates").get(0).asDouble();
            double latitude = geoJson.get("coordinates").get(1).asDouble();
            if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
                throw validation("The coordinates are outside [-180, 180] and [-90, 90].");
            }
            meta(record).put("geoJson", payload);
        }
        savePositioned(account, region, type, record);
        return mapper.createObjectNode();
    }

    /** GetResourcePosition: the GeoJSON point last set, or 404 when there is none. */
    byte[] getResourcePosition(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode record = positioned(account, region, in.get("ResourceType").asText(),
                in.get("ResourceIdentifier").asText());
        String geoJson = meta(record).path("geoJson").asText(null);
        if (geoJson == null) {
            throw notFound("ResourcePosition", in.get("ResourceIdentifier").asText());
        }
        return geoJson.getBytes(StandardCharsets.UTF_8);
    }

    ObjectNode updatePosition(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("ResourceType").asText();
        ObjectNode record = positioned(account, region, type, in.get("ResourceIdentifier").asText());
        ObjectNode position = mapper.createObjectNode();
        position.set("Position", in.get("Position").deepCopy());
        position.put("Timestamp", WirelessStore.now());
        meta(record).set("position", position);
        savePositioned(account, region, type, record);
        return mapper.createObjectNode();
    }

    ObjectNode getPosition(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode record = positioned(account, region, in.get("ResourceType").asText(),
                in.get("ResourceIdentifier").asText());
        JsonNode position = meta(record).path("position");
        if (!position.isObject()) {
            throw notFound("Position", in.get("ResourceIdentifier").asText());
        }
        ObjectNode out = position.deepCopy();
        out.put("SolverType", "GNSS").put("SolverProvider", "Semtech").put("SolverVersion", "1.0");
        return out;
    }

    ObjectNode putPositionConfiguration(ObjectNode in, String region) {
        String account = store.account();
        String type = in.get("ResourceType").asText();
        ObjectNode record = positioned(account, region, type, in.get("ResourceIdentifier").asText());
        ObjectNode configuration = mapper.createObjectNode();
        if (in.has("Solvers")) {
            JsonNode gnss = in.get("Solvers").path("SemtechGnss");
            ObjectNode solvers = configuration.putObject("Solvers");
            if (gnss.isObject()) {
                solvers.putObject("SemtechGnss").put("Provider", "Semtech").put("Type", "GNSS")
                        .put("Status", gnss.get("Status").asText()).put("Fec", gnss.get("Fec").asText());
            }
        }
        if (in.hasNonNull("Destination")) {
            if (store.get(account, region, DESTINATION, in.get("Destination").asText()).isEmpty()) {
                throw notFound(DESTINATION, in.get("Destination").asText());
            }
            configuration.put("Destination", in.get("Destination").asText());
        }
        meta(record).set("positionConfiguration", configuration);
        savePositioned(account, region, type, record);
        return mapper.createObjectNode();
    }

    ObjectNode getPositionConfiguration(ObjectNode in, String region) {
        String account = store.account();
        ObjectNode record = positioned(account, region, in.get("ResourceType").asText(),
                in.get("ResourceIdentifier").asText());
        JsonNode configuration = meta(record).path("positionConfiguration");
        if (!configuration.isObject()) {
            throw notFound("PositionConfiguration", in.get("ResourceIdentifier").asText());
        }
        return configuration.deepCopy();
    }

    ObjectNode listPositionConfigurations(ObjectNode in, String region) {
        String account = store.account();
        String type = in.path("ResourceType").asText(null);
        List<ObjectNode> items = new ArrayList<>();
        for (String kind : List.of(DEVICE, GATEWAY)) {
            if (type != null && !type.equals(kind)) {
                continue;
            }
            for (ObjectNode record : store.list(account, region, kind)) {
                JsonNode configuration = meta(record).path("positionConfiguration");
                if (configuration.isObject()) {
                    ObjectNode item = configuration.deepCopy();
                    item.put("ResourceIdentifier", record.get("Id").asText()).put("ResourceType", kind);
                    items.add(item);
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        WirelessStore.page(items, in, out, "PositionConfigurationList");
        return out;
    }

    /**
     * GetPositionEstimate. With no positioning database here, an estimate is only possible from what Floci knows:
     * Wi-Fi access points and cell towers whose positions were set on gateways' {@code UpdateResourcePosition}
     * (by gateway EUI as the MAC or cell ID); anything else is a ResourceNotFoundException, as the solver answers
     * for unknown inputs. GNSS scans need Semtech's almanac and aren't solved.
     */
    byte[] getPositionEstimate(ObjectNode in, String region) {
        String account = store.account();
        int sources = 0;
        for (String field : List.of("WiFiAccessPoints", "CellTowers", "Ip", "Gnss", "GnssMultiFrame")) {
            sources += in.has(field) ? 1 : 0;
        }
        if (sources == 0) {
            throw validation("Give Wi-Fi access points, cell towers, an IP address or a GNSS scan.");
        }
        double weight = 0;
        double longitude = 0;
        double latitude = 0;
        for (JsonNode ap : in.path("WiFiAccessPoints")) {
            String mac = ap.get("MacAddress").asText().replace(":", "").replace("-", "").toLowerCase();
            for (ObjectNode gateway : store.list(account, region, GATEWAY)) {
                String eui = gateway.path("LoRaWAN").path("GatewayEui").asText().toLowerCase();
                String geoJson = meta(gateway).path("geoJson").asText(null);
                if (geoJson != null && (eui.endsWith(mac) || eui.equals(mac))) {
                    JsonNode point;
                    try {
                        point = mapper.readTree(geoJson);
                    } catch (Exception e) {
                        continue;
                    }
                    double w = Math.pow(10, ap.get("Rss").asDouble() / 20);
                    longitude += point.get("coordinates").get(0).asDouble() * w;
                    latitude += point.get("coordinates").get(1).asDouble() * w;
                    weight += w;
                }
            }
        }
        if (weight == 0) {
            throw notFound("Position", "estimate");
        }
        ObjectNode geoJson = mapper.createObjectNode();
        geoJson.put("type", "Point");
        geoJson.putArray("coordinates").add(longitude / weight).add(latitude / weight);
        geoJson.putObject("properties").put("horizontalAccuracy", 50).put("horizontalConfidenceLevel", 0.68)
                .put("timestamp", WirelessStore.now());
        return geoJson.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- multicast downlinks

    /** Sends a multicast downlink through each connected participating gateway, on the session's frequency. */
    boolean sendMulticast(String account, String region, ObjectNode group, byte[] frame, int dr, long freq) {
        boolean sent = false;
        JsonNode gateways = group.path("LoRaWAN").path("ParticipatingGateways").path("GatewayList");
        List<String> ids = new ArrayList<>();
        gateways.forEach(g -> ids.add(g.asText()));
        if (ids.isEmpty()) {
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                if (entry.getKey().startsWith(account + "|" + region + "|")) {
                    ids.add(entry.getValue().gatewayId);
                }
            }
        }
        for (String id : ids) {
            Session session = sessions.get(sessionKey(account, region, id));
            if (session == null || !session.socket.isOpen()) {
                continue;
            }
            ObjectNode message = mapper.createObjectNode();
            message.put("msgtype", "dnmsg");
            message.put("DevEui", "00-00-00-00-00-00-00-00");
            message.put("dC", 2);
            message.put("diid", diids.getAndIncrement());
            message.put("pdu", LoRaWan.hex(frame));
            message.put("RX2DR", dr);
            message.put("RX2Freq", freq);
            message.put("priority", 0);
            message.put("MuxTime", System.currentTimeMillis() / 1000.0);
            session.socket.send(message.toString());
            sent = true;
        }
        return sent;
    }

    /** Sends an application downlink to one device at once if it's Class C, else queues it. */
    void queueOrSend(String account, String region, ObjectNode device, int fPort, byte[] payload) {
        ObjectNode in = mapper.createObjectNode();
        in.put("Id", device.get("Id").asText());
        in.put("TransmitMode", 0);
        in.put("PayloadData", Base64.getEncoder().encodeToString(payload));
        in.putObject("WirelessMetadata").putObject("LoRaWAN").put("FPort", fPort);
        sendDataToWirelessDevice(in, region);
    }

    static String hexOf(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
