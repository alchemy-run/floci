package io.floci.aws.services.iotwireless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.TextWebSocket;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

/**
 * Where LoRa Basics Station gateways connect: the LNS endpoint GetServiceEndpoint gives (its
 * {@code <endpoint>/router-info} WebSocket answers a gateway's router EUI with its traffic WebSocket, where it
 * sends uplinks and receives downlinks), and the CUPS endpoint, where a gateway posts {@code /update-info} and gets
 * back the LNS URI and trust when its copy is stale, and a firmware update when a gateway task is pending for it.
 */
@ApplicationScoped
public class WirelessStation {

    static final String PATH = "/_floci/iotwireless/";

    private final WirelessStore store;
    private final ObjectMapper mapper;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Instance<WirelessNetwork> network;
    private final Instance<WirelessFleet> fleet;
    private final Instance<FlociCertificateAuthority> authority;

    @Inject
    public WirelessStation(WirelessStore store, ServiceConfigAccess serviceConfigAccess,
                           Instance<WirelessNetwork> network, Instance<WirelessFleet> fleet,
                           Instance<FlociCertificateAuthority> authority) {
        this.store = store;
        this.mapper = store.mapper();
        this.serviceConfigAccess = serviceConfigAccess;
        this.network = network;
        this.fleet = fleet;
        this.authority = authority;
    }

    void register(@Observes Router router) {
        router.route(PATH + ":region/lns/router-info").handler(this::routerInfo);
        router.route(PATH + ":region/traffic/:eui").handler(this::traffic);
    }

    private boolean upgrade(RoutingContext context) {
        HttpServerRequest request = context.request();
        if (!serviceConfigAccess.isEnabled("iotwireless")
                || !"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
            context.next();
            return false;
        }
        String key = request.getHeader("Sec-WebSocket-Key");
        if (key == null || key.isBlank()) {
            request.response().setStatusCode(400).end("Missing Sec-WebSocket-Key.");
            return false;
        }
        request.response().setStatusCode(101).putHeader("Upgrade", "websocket").putHeader("Connection", "Upgrade")
                .putHeader("Sec-WebSocket-Accept", TextWebSocket.accept(key));
        return true;
    }

    /** The router-info exchange: the gateway's EUI in, its traffic URI (or an error) out, then the socket closes. */
    private void routerInfo(RoutingContext context) {
        if (!upgrade(context)) {
            return;
        }
        String region = context.pathParam("region");
        HttpServerRequest request = context.request();
        String scheme = request.isSSL() ? "wss" : "ws";
        String host = request.authority() == null ? request.getHeader("Host") : request.authority().toString();
        request.toNetSocket().onSuccess(socket -> {
            AtomicReference<TextWebSocket> holder = new AtomicReference<>();
            holder.set(new TextWebSocket(socket, text -> {
                ObjectNode reply = mapper.createObjectNode();
                JsonNode router;
                try {
                    router = mapper.readTree(text).path("router");
                } catch (Exception e) {
                    holder.get().close(1003, "Not JSON.");
                    return;
                }
                reply.set("router", router.deepCopy());
                String eui = router.isNumber() ? String.format("%016x", router.asLong())
                        : router.asText().replace("-", "").replace(":", "").toLowerCase();
                AccountAwareStorageBackend.AccountEntry<ObjectNode> gateway = network.get().gatewayByEui(region, eui);
                if (gateway == null) {
                    reply.put("error", "Gateway " + eui + " isn't registered in " + region + ".");
                } else {
                    reply.put("muxs", "floci-" + region);
                    reply.put("uri", scheme + "://" + host + PATH + region + "/traffic/" + eui);
                }
                holder.get().send(reply.toString());
                holder.get().close(1000, "");
            }, () -> {
            }));
        });
    }

    private void traffic(RoutingContext context) {
        String region = context.pathParam("region");
        String eui = context.pathParam("eui").toLowerCase();
        AccountAwareStorageBackend.AccountEntry<ObjectNode> entry = network.get().gatewayByEui(region, eui);
        if (entry == null) {
            if ("websocket".equalsIgnoreCase(context.request().getHeader("Upgrade"))) {
                context.request().response().setStatusCode(404).end("Gateway " + eui + " isn't registered.");
            } else {
                context.next();
            }
            return;
        }
        if (!upgrade(context)) {
            return;
        }
        String account = entry.accountId();
        context.request().toNetSocket().onSuccess(socket -> {
            AtomicReference<WirelessNetwork.Session> session = new AtomicReference<>();
            TextWebSocket station = new TextWebSocket(socket, text -> {
                if (session.get() != null) {
                    network.get().message(session.get(), text);
                }
            }, () -> {
                if (session.get() != null) {
                    network.get().closed(session.get());
                }
            });
            RequestScopes.runAs(account, region, () -> session.set(network.get().connect(account, region,
                    entry.value(), station)));
        });
    }

    /**
     * CUPS update-info: the response carries, each with its length, the CUPS URI and LNS URI when the gateway's
     * differ, the CUPS and LNS credentials (the trust certificate) when their CRCs differ, and an update with its
     * signature and the CRC of the key that signed it. Null when the router isn't a gateway of the Region.
     */
    byte[] updateInfo(String region, String body, String scheme, String host) {
        JsonNode in;
        try {
            in = mapper.readTree(body);
        } catch (Exception e) {
            throw WirelessStore.validation("The request isn't JSON.");
        }
        JsonNode router = in.path("router");
        String eui = router.isNumber() ? String.format("%016x", router.asLong())
                : router.asText().replace("-", "").replace(":", "").toLowerCase();
        AccountAwareStorageBackend.AccountEntry<ObjectNode> entry = network.get().gatewayByEui(region, eui);
        if (entry == null) {
            return null;
        }
        String cupsUri = scheme + "://" + host + PATH + region + "/cups";
        String tcUri = ("https".equals(scheme) ? "wss" : "ws") + "://" + host + PATH + region + "/lns";
        byte[] trust;
        try {
            trust = authority.get().certificate().getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
        long trustCrc = crc(trust);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUri(out, cupsUri.equals(in.path("cupsUri").asText()) ? "" : cupsUri);
        writeUri(out, tcUri.equals(in.path("tcUri").asText()) ? "" : tcUri);
        writeCredential(out, in.path("cupsCredCrc").asLong(-1) == trustCrc ? new byte[0] : trust);
        writeCredential(out, in.path("tcCredCrc").asLong(-1) == trustCrc ? new byte[0] : trust);
        WirelessFleet.Update update = RequestScopes.callAs(entry.accountId(), region,
                () -> fleet.get().gatewayUpdate(entry.accountId(), region, entry.value(), in));
        if (update == null) {
            writeInt(out, 0);
            writeInt(out, 0);
        } else {
            writeInt(out, update.signature().length + 4);
            writeInt(out, (int) update.keyCrc());
            out.writeBytes(update.signature());
            writeInt(out, update.data().length);
            out.writeBytes(update.data());
        }
        return out.toByteArray();
    }

    static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static void writeUri(ByteArrayOutputStream out, String uri) {
        byte[] bytes = uri.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeCredential(ByteArrayOutputStream out, byte[] credential) {
        out.write(credential.length & 0xFF);
        out.write((credential.length >> 8) & 0xFF);
        out.writeBytes(credential);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
