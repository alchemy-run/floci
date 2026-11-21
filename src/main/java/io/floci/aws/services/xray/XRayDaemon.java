package io.floci.aws.services.xray;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestScopes;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The X-Ray daemon: X-Ray SDKs send segments over UDP ({@code {"format": "json", "version": 1}} followed by the
 * segment document on the next line). A segment joins the account and Region that already hold its trace, as the
 * subsegments of a traced Lambda invocation do; others go to the default account in the default Region.
 */
@ApplicationScoped
public class XRayDaemon {

    private static final Logger LOG = Logger.getLogger(XRayDaemon.class);
    private static final Pattern TRACE = Pattern.compile("\"trace_id\"\\s*:\\s*\"(1-[0-9a-f]{8}-[0-9a-f]{24})\"");

    private final XRayService xray;
    private final EmulatorConfig config;
    private volatile DatagramSocket socket;

    @Inject
    public XRayDaemon(XRayService xray, EmulatorConfig config) {
        this.xray = xray;
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        int port = config.services().xray().daemonPort();
        if (!config.services().xray().enabled() || port <= 0) {
            return;
        }
        try {
            socket = new DatagramSocket(new InetSocketAddress("0.0.0.0", port));
        } catch (SocketException e) {
            LOG.warnv("The X-Ray daemon could not listen on UDP port {0}: {1}", String.valueOf(port), e.getMessage());
            return;
        }
        Thread.ofVirtual().name("xray-daemon").start(this::receive);
        LOG.infov("X-Ray daemon listening on UDP port {0}", String.valueOf(port));
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (socket != null) {
            socket.close();
        }
    }

    /** The UDP port the daemon listens on, or 0 when it isn't running. */
    public int port() {
        return socket == null ? 0 : socket.getLocalPort();
    }

    private void receive() {
        byte[] buffer = new byte[65536];
        while (socket != null && !socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                accept(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    LOG.debugv("X-Ray daemon receive failed: {0}", e.getMessage());
                }
            } catch (RuntimeException e) {
                LOG.debugv(e, "X-Ray daemon could not store a segment");
            }
        }
    }

    void accept(String datagram) {
        int newline = datagram.indexOf('\n');
        if (newline < 0 || !datagram.substring(0, newline).contains("\"format\"")) {
            return;
        }
        String document = datagram.substring(newline + 1).trim();
        Matcher trace = TRACE.matcher(document);
        String holder = trace.find() ? xray.accountHolding(trace.group(1)) : null;
        String account = holder != null ? holder.substring(0, holder.indexOf('|')) : config.defaultAccountId();
        String region = holder != null ? holder.substring(holder.indexOf('|') + 1) : config.defaultRegion();
        RequestScopes.runAs(account, region, () -> xray.ingest(region, document).ifPresent(rejected ->
                LOG.debugv("X-Ray daemon rejected a segment: {0}", rejected)));
    }
}
