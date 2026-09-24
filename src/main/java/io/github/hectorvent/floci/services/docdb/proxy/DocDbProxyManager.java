package io.github.hectorvent.floci.services.docdb.proxy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyTlsCertificates;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The DocumentDB listeners, one per port, and which cluster each connection on them reaches.
 *
 * <p>A cluster first reserves a port, which it needs before its container starts because the
 * replica set advertises it, then attaches its container once that is up. Clusters with TLS
 * enabled share a port, told apart by the hostname a client connects to; a cluster with TLS
 * disabled takes a port of its own.
 */
@ApplicationScoped
public class DocDbProxyManager {

    private static final Logger LOG = Logger.getLogger(DocDbProxyManager.class);
    static final int DEFAULT_PORT = 27017;
    static final int MAX_PORT = 27117;

    private final RdsProxyTlsCertificates tlsCertificates;
    private final Map<Integer, DocDbProxy> listeners = new HashMap<>();
    private final Map<String, Integer> clusterPorts = new HashMap<>();

    /**
     * DocumentDB serves certificates issued by the Amazon RDS certificate authority, so the
     * listeners serve the certificate of Floci's RDS CA, which a client trusts once for both.
     */
    @Inject
    public DocDbProxyManager(RdsProxyTlsCertificates tlsCertificates) {
        this.tlsCertificates = tlsCertificates;
    }

    /**
     * Reserves the port a cluster is served on: the one requested, or 27017, when a cluster with
     * these TLS settings can be served there; otherwise the next port from 27018 that can.
     */
    public synchronized int reserve(String clusterKey, Integer requestedPort, boolean tlsRequired) {
        release(clusterKey);
        Set<Integer> candidates = new LinkedHashSet<>();
        candidates.add(requestedPort != null ? requestedPort : DEFAULT_PORT);
        for (int port = DEFAULT_PORT; port <= MAX_PORT; port++) {
            candidates.add(port);
        }
        for (int port : candidates) {
            DocDbProxy listener = listeners.get(port);
            if (listener == null) {
                listener = open(port);
                if (listener == null) {
                    continue;
                }
            } else if (!listener.canShareWith(tlsRequired)) {
                continue;
            }
            listener.putRoute(new DocDbProxy.Route(clusterKey, tlsRequired, Set.of(), null, 0));
            clusterPorts.put(clusterKey, port);
            if (requestedPort != null && port != requestedPort) {
                LOG.infov("DocumentDB port {0} cannot serve cluster {1}; serving it on {2}",
                        String.valueOf(requestedPort), clusterKey, String.valueOf(port));
            }
            return port;
        }
        throw new AwsException("DBClusterQuotaExceededFault", "No port in the range " + DEFAULT_PORT + "-"
                + MAX_PORT + " is free to serve another DocumentDB cluster.", 403);
    }

    /** Sends the cluster's connections, for any of these hostnames, to its container. */
    public synchronized void attach(String clusterKey, List<String> hostnames, String backendHost, int backendPort) {
        DocDbProxy listener = listenerOf(clusterKey);
        DocDbProxy.Route route = listener.route(clusterKey);
        Set<String> names = lowerCase(hostnames);
        if (route.tlsRequired()) {
            names.forEach(this::ensureCertificateCovers);
        }
        listener.putRoute(new DocDbProxy.Route(clusterKey, route.tlsRequired(), names, backendHost, backendPort));
    }

    /** Adds a hostname, an instance endpoint, that reaches an attached cluster. */
    public synchronized void addHostname(String clusterKey, String hostname) {
        if (hostname == null) {
            return;
        }
        DocDbProxy listener = clusterPorts.containsKey(clusterKey) ? listenerOf(clusterKey) : null;
        DocDbProxy.Route route = listener == null ? null : listener.route(clusterKey);
        if (route == null || !route.ready()) {
            return;
        }
        Set<String> hostnames = new LinkedHashSet<>(route.hostnames());
        hostnames.add(hostname.toLowerCase(Locale.ROOT));
        if (route.tlsRequired()) {
            ensureCertificateCovers(hostname.toLowerCase(Locale.ROOT));
        }
        listener.putRoute(new DocDbProxy.Route(clusterKey, route.tlsRequired(), Set.copyOf(hostnames),
                route.backendHost(), route.backendPort()));
    }

    public synchronized void removeHostname(String clusterKey, String hostname) {
        if (hostname == null) {
            return;
        }
        DocDbProxy listener = clusterPorts.containsKey(clusterKey) ? listenerOf(clusterKey) : null;
        DocDbProxy.Route route = listener == null ? null : listener.route(clusterKey);
        if (route == null) {
            return;
        }
        Set<String> hostnames = new LinkedHashSet<>(route.hostnames());
        hostnames.remove(hostname.toLowerCase(Locale.ROOT));
        listener.putRoute(new DocDbProxy.Route(clusterKey, route.tlsRequired(), Set.copyOf(hostnames),
                route.backendHost(), route.backendPort()));
    }

    /** Stops serving a cluster, closing its listener once no cluster is left on it. */
    public synchronized void release(String clusterKey) {
        if (clusterKey == null) {
            return;
        }
        Integer port = clusterPorts.remove(clusterKey);
        if (port == null) {
            return;
        }
        DocDbProxy listener = listeners.get(port);
        if (listener == null) {
            return;
        }
        listener.removeRoute(clusterKey);
        if (!listener.hasRoutes()) {
            listeners.remove(port);
            listener.stop();
            LOG.infov("Closed DocumentDB listener on port {0}", String.valueOf(port));
        }
    }

    /** The port a cluster is served on, or null when it is not served. */
    public synchronized Integer portOf(String clusterKey) {
        return clusterPorts.get(clusterKey);
    }

    @PreDestroy
    public synchronized void stopAll() {
        listeners.values().forEach(DocDbProxy::stop);
        listeners.clear();
        clusterPorts.clear();
    }

    private DocDbProxy listenerOf(String clusterKey) {
        Integer port = clusterPorts.get(clusterKey);
        if (port == null || !listeners.containsKey(port)) {
            throw new IllegalStateException("DocumentDB cluster " + clusterKey + " has no reserved port");
        }
        return listeners.get(port);
    }

    /** Opens a listener, or returns null when something else on this machine holds the port. */
    private DocDbProxy open(int port) {
        DocDbProxy listener = new DocDbProxy(port, this::tlsContext);
        try {
            listener.start();
        } catch (IOException e) {
            LOG.debugv("Port {0} could not be opened: {1}", String.valueOf(port), e.getMessage());
            return null;
        }
        listeners.put(port, listener);
        return listener;
    }

    private SSLContext tlsContext() {
        if (tlsCertificates == null) {
            throw new IllegalStateException("DocumentDB listeners were built without a TLS certificate");
        }
        return tlsCertificates.sslContext();
    }

    /** Names the hostname in the served certificate, for clients that verify it against the CA. */
    private void ensureCertificateCovers(String hostname) {
        if (tlsCertificates != null) {
            tlsCertificates.ensureHost(hostname);
        }
    }

    private static Set<String> lowerCase(List<String> hostnames) {
        Set<String> lower = new LinkedHashSet<>();
        for (String hostname : hostnames) {
            if (hostname != null) {
                lower.add(hostname.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(lower);
    }
}
