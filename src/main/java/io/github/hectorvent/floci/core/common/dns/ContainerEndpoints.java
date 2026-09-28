package io.github.hectorvent.floci.core.common.dns;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Makes resource endpoints reachable from the containers Floci launches (Lambda, ECS, EC2) on the
 * hostname and port the endpoint advertises, whether Floci runs in Docker or from source.
 *
 * <p>AWS gives every resource endpoint its own DNS name, so any number of them can use the same
 * port. Floci advertises the same shape under its DNS suffix, and there are two ways to serve one:
 *
 * <ul>
 *   <li>A resource whose own container serves the endpoint on its advertised port is routed there
 *       by name ({@link #routeToContainer}): Floci's DNS answers the name with that container's
 *       address, so each resource is reached directly and ports are never contended.</li>
 *   <li>A resource served by a listener in Floci's own process is reached at Floci's address,
 *       which every name under the suffix resolves to. Floci in Docker listens there already;
 *       from source the listener is on the host, so the port is relayed to it through the
 *       source network helper ({@link #publishHostPort}).</li>
 *   <li>A resource served by a listener in Floci's process on a port other resources' endpoints
 *       also use gets a relay container of its own ({@link #relayToFloci}): its name resolves to
 *       the relay, which listens on the endpoint's port and relays to Floci's listener.</li>
 * </ul>
 */
@ApplicationScoped
public class ContainerEndpoints {

    private static final Logger LOG = Logger.getLogger(ContainerEndpoints.class);

    private final EmbeddedDnsServer dnsServer;
    private final SourceNetworkHelper sourceHelper;
    private final EndpointRelays relays;

    public ContainerEndpoints(EmbeddedDnsServer dnsServer, SourceNetworkHelper sourceHelper) {
        this(dnsServer, sourceHelper, null);
    }

    @Inject
    public ContainerEndpoints(EmbeddedDnsServer dnsServer, SourceNetworkHelper sourceHelper,
                              EndpointRelays relays) {
        this.dnsServer = dnsServer;
        this.sourceHelper = sourceHelper;
        this.relays = relays;
    }

    /** Containers resolve {@code hostname} to {@code address}, the container that serves it. */
    public void routeToContainer(String hostname, String address) {
        if (hostname == null || address == null) {
            return;
        }
        stopRelay(hostname);
        dnsServer.routeHost(hostname, address);
    }

    /**
     * Containers reach {@code hostname} on {@code port} at an address of its own, relayed to the
     * listener of Floci's process on {@code flociPort}. Unlike {@link #publishHostPort}, any
     * number of hostnames can be relayed on the same port.
     *
     * @return whether containers can now reach the hostname; false when the containers Floci
     *         launches do not use its DNS, so no name of it could be answered
     * @throws RuntimeException when the relay could not be started
     */
    public boolean relayToFloci(String hostname, int port, int flociPort) {
        if (hostname == null || relays == null) {
            return false;
        }
        String target = dnsServer.isSourceMode() ? SourceNetworkHelper.HOST_GATEWAY
                : dnsServer.getServerIp().orElse(null);
        if (target == null || dnsServer.getServerIp().isEmpty()) {
            return false;
        }
        String address = relays.start(hostname, port, target, flociPort);
        dnsServer.routeHost(hostname, address);
        return true;
    }

    /**
     * Containers get no address for {@code hostname}: the only listener they could reach under
     * it would be another resource's.
     */
    public void refuse(String hostname) {
        if (hostname != null) {
            stopRelay(hostname);
            dnsServer.refuseHost(hostname);
        }
    }

    /**
     * Drops a {@link #routeToContainer}, {@link #relayToFloci} or {@link #refuse}; the name
     * resolves to Floci again.
     */
    public void release(String hostname) {
        if (hostname != null) {
            dnsServer.releaseHost(hostname);
            stopRelay(hostname);
        }
    }

    private void stopRelay(String hostname) {
        if (relays != null) {
            relays.stop(hostname);
        }
    }

    /**
     * Makes a listener of Floci's process on {@code port} reachable from containers at Floci's
     * address. Pair every call with {@link #withdrawHostPort}.
     */
    public void publishHostPort(int port) {
        if (!dnsServer.isSourceMode()) {
            return;
        }
        try {
            sourceHelper.forwardPort(port);
        } catch (RuntimeException e) {
            LOG.warnv("Port {0} is not reachable from containers: {1}", String.valueOf(port), e.getMessage());
        }
    }

    public void withdrawHostPort(int port) {
        if (dnsServer.isSourceMode()) {
            sourceHelper.releaseForwardedPort(port);
        }
    }
}
