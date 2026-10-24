package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.services.rds.container.RdsBackendGate;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry of all active RDS auth proxies. One proxy per DB instance or cluster.
 */
@ApplicationScoped
public class RdsProxyManager {

    private static final Logger LOG = Logger.getLogger(RdsProxyManager.class);

    private final RdsSigV4Validator sigV4Validator;
    private final RdsProxyTlsCertificates tlsCertificates;
    private final EmulatorConfig config;
    private final RdsBackendGate backendGate;
    private final RdsContainerManager containerManager;
    private final ConcurrentHashMap<String, RdsAuthProxy> proxies = new ConcurrentHashMap<>();
    private final RdsEndpointRouter router;

    @Inject
    public RdsProxyManager(RdsSigV4Validator sigV4Validator, RdsProxyTlsCertificates tlsCertificates,
                           EmulatorConfig config, RdsBackendGate backendGate,
                           RdsContainerManager containerManager) {
        this.sigV4Validator = sigV4Validator;
        this.tlsCertificates = tlsCertificates;
        this.config = config;
        EmulatorConfig.RdsServiceConfig rdsConfig = config.services().rds();
        this.router = new RdsEndpointRouter(rdsConfig.proxyHandshakeTimeoutMillis(),
                rdsConfig.proxyBackendConnectTimeoutMillis(), rdsConfig.proxyMaxConnections());
    }

    /**
     * Serves a started proxy on its advertised endpoint: {@code hostnames} (the endpoint address
     * and any alias, such as a cluster's reader endpoint) on the endpoint's configured
     * {@code port}, which many resources can share. The TLS certificate covers every name, and
     * MySQL IAM tokens are checked against the advertised host and port rather than the internal
     * one. A no-op when no proxy runs under {@code relayKey}.
     *
     * @return false when the port could not be bound on this host
     */
    public synchronized boolean advertise(String relayKey, List<String> hostnames, int port) {
        RdsAuthProxy proxy = proxies.get(relayKey);
        if (proxy == null || hostnames == null || hostnames.isEmpty()) {
            return true;
        }
        for (String hostname : hostnames) {
            tlsCertificates.ensureHost(hostname);
        }
        proxy.updateMysqlBinding(new RdsMysqlBinding(hostnames.get(0), port, regionFromRelayKey(relayKey)));
        return router.register(relayKey, port, hostnames, proxy.getPort(), proxy.getEngine());
    }

    /**
     * Routes another service's PostgreSQL-protocol endpoint (a Redshift cluster) through the same
     * shared listeners: {@code hostnames} on {@code port} go to the listener on {@code targetPort}.
     *
     * @return false when the port could not be bound on this host
     */
    public synchronized boolean routePostgresEndpoint(String key, List<String> hostnames, int port, int targetPort) {
        for (String hostname : hostnames) {
            tlsCertificates.ensureHost(hostname);
        }
        return router.register(key, port, hostnames, targetPort, DatabaseEngine.POSTGRES);
    }

    public synchronized void unroutePostgresEndpoint(String key) {
        router.unregister(key);
        this.backendGate = backendGate;
        this.containerManager = containerManager;
    }

    public synchronized void startProxy(String instanceId, String backendRuntimeId,
                                        DatabaseEngine engine, boolean iamEnabled,
                                        int proxyPort, String backendHost, int backendPort,
                                        String advertisedHost,
                                        String masterUsername, String masterPassword, String dbName,
                                        RdsAuthProxy.MasterPasswordCheck passwordValidator,
                                        RdsProxyBinding binding) {
        startProxy(instanceId, backendRuntimeId, engine, iamEnabled, proxyPort, backendHost,
                backendPort, advertisedHost, masterUsername, masterPassword, dbName,
                passwordValidator, binding, RdsAuthProxy.EndpointKind.DATABASE);
    }

    public synchronized void startProxy(String instanceId, String backendRuntimeId,
                                        DatabaseEngine engine, boolean iamEnabled,
                                        int proxyPort, String backendHost, int backendPort,
                                        String advertisedHost,
                                        String masterUsername, String masterPassword, String dbName,
                                        RdsAuthProxy.MasterPasswordCheck passwordValidator,
                                        RdsProxyBinding binding, RdsAuthProxy.EndpointKind endpointKind) {
        if (containerManager == null) {
            throw new IllegalStateException("RDS container manager is required for dynamic backend resolution");
        }
        AtomicBoolean endpointFailureLogged = new AtomicBoolean();
        RdsAuthProxy.BackendEndpointResolver endpointResolver = () -> {
            try {
                EndpointInfo endpoint = containerManager.resolveBackendEndpoint(backendRuntimeId, engine);
                endpointFailureLogged.set(false);
                return new RdsAuthProxy.BackendEndpoint(endpoint.host(), endpoint.port());
            } catch (RuntimeException e) {
                if (endpointFailureLogged.compareAndSet(false, true)) {
                    LOG.warnv(e, "Could not resolve backend endpoint for RDS runtime {0}",
                            backendRuntimeId);
                }
                throw new IOException(
                        "Could not resolve backend endpoint for RDS runtime " + backendRuntimeId, e);
            }
        };
        startProxy(instanceId, backendRuntimeId, engine, iamEnabled, proxyPort,
                backendHost, backendPort, endpointResolver, advertisedHost, masterUsername,
                masterPassword, dbName, passwordValidator, binding, endpointKind);
    }

    private void startProxy(String instanceId, String backendRuntimeId,
                            DatabaseEngine engine, boolean iamEnabled,
                            int proxyPort, String backendHost, int backendPort,
                            RdsAuthProxy.BackendEndpointResolver endpointResolver,
                            String advertisedHost,
                            String masterUsername, String masterPassword, String dbName,
                            RdsAuthProxy.MasterPasswordCheck passwordValidator,
                            RdsProxyBinding binding, RdsAuthProxy.EndpointKind endpointKind) {
        tlsCertificates.ensureHost(advertisedHost);
        EmulatorConfig.RdsServiceConfig rdsConfig = config.services().rds();
        RdsAuthProxy proxy = new RdsAuthProxy(
                instanceId, backendHost, backendPort, endpointResolver, backendRuntimeId,
                engine, iamEnabled, masterUsername, masterPassword, dbName, sigV4Validator,
                tlsCertificates, passwordValidator, rdsConfig.proxyHandshakeTimeoutMillis(),
                rdsConfig.proxyBackendConnectTimeoutMillis(), rdsConfig.proxyMaxConnections(),
                binding, username -> binding != null, backendGate, endpointKind);
        try {
            proxy.start(proxyPort);
        } catch (IOException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to start RDS proxy for instance " + instanceId
                            + " on port " + proxyPort, e);
            cleanupFailedStart(proxy, failure);
            throw failure;
        } catch (RuntimeException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to register RDS proxy for instance " + instanceId
                            + " on port " + proxyPort, e);
            cleanupFailedStart(proxy, failure);
            throw failure;
        }
        RdsAuthProxy previous;
        try {
            previous = proxies.put(instanceId, proxy);
        } catch (RuntimeException e) {
            RuntimeException failure = new RuntimeException(
                    "Failed to register RDS proxy for instance " + instanceId
                            + " on port " + proxyPort, e);
            cleanupFailedStart(proxy, failure);
            throw failure;
        }
        if (previous != null) {
            try {
                previous.stop();
            } catch (RuntimeException e) {
                proxies.put(instanceId, previous);
                RuntimeException failure = new RuntimeException(
                        "Failed to replace RDS proxy for instance " + instanceId, e);
                cleanupFailedStart(proxy, failure);
                throw failure;
            }
        }
    }

    public synchronized void updateMasterPassword(String instanceId, String newPassword) {
        RdsAuthProxy proxy = proxies.get(instanceId);
        if (proxy != null) {
            proxy.updateMasterPassword(newPassword);
            LOG.infov("Updated RDS proxy master password for instance {0}", instanceId);
        }
    }

    public synchronized void updateIamEnabled(String instanceId, boolean enabled) {
        RdsAuthProxy proxy = proxies.get(instanceId);
        if (proxy != null) {
            proxy.updateIamEnabled(enabled);
            LOG.infov("Updated RDS proxy IAM authentication for instance {0}", instanceId);
        }
    }

    public synchronized void stopProxy(String instanceId) {
        router.unregister(instanceId);
        RdsAuthProxy proxy = proxies.get(instanceId);
        if (proxy != null) {
            proxy.stop();
            proxies.remove(instanceId, proxy);
            LOG.infov("Stopped RDS proxy for instance {0}", instanceId);
        }
    }

    public synchronized void stopAll() {
        router.closeAll();
        proxies.forEach((instanceId, proxy) -> {
            try {
                proxy.stop();
                proxies.remove(instanceId, proxy);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Failed to stop RDS proxy for instance {0} during shutdown",
                        instanceId);
            }
        });
        LOG.info("Stopped all RDS proxies");
    }

    private void cleanupFailedStart(RdsAuthProxy proxy, RuntimeException failure) {
        try {
            proxy.stop();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
