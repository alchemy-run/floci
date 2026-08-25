package io.github.hectorvent.floci.services.dsql.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Postgres container + IAM auth proxy for every DSQL cluster.
 * Control-plane tests set {@code floci.services.dsql.mock=true} to skip Docker.
 */
@ApplicationScoped
public class DsqlDataPlane implements Resettable {

    private static final Logger LOG = Logger.getLogger(DsqlDataPlane.class);
    private static final String INSTANCE_ID = "dsql-shared";
    static final String MASTER_USERNAME = "admin";
    static final String DATABASE = "postgres";

    private final RdsContainerManager containerManager;
    private final EmulatorConfig config;
    private final DsqlSigV4Validator sigV4;
    private final DsqlTls tls;
    private final Set<String> liveEndpoints = ConcurrentHashMap.newKeySet();

    private final Object lock = new Object();
    private RdsContainerHandle container;
    private DsqlAuthProxy proxy;
    private String masterPassword;

    @Inject
    public DsqlDataPlane(RdsContainerManager containerManager,
                         EmulatorConfig config,
                         DsqlSigV4Validator sigV4,
                         DsqlTls tls) {
        this.containerManager = containerManager;
        this.config = config;
        this.sigV4 = sigV4;
        this.tls = tls;
    }

    public void ensureStarted() {
        if (config.services().dsql().mock()) {
            return;
        }
        synchronized (lock) {
            if (proxy != null) {
                return;
            }
            masterPassword = UUID.randomUUID().toString().replace("-", "");
            DsqlPostgresProtocolHandler.setServerSslContext(tls.sslContext());
            container = containerManager.start(
                    INSTANCE_ID,
                    INSTANCE_ID,
                    DatabaseEngine.POSTGRES,
                    config.services().rds().defaultPostgresImage(),
                    MASTER_USERNAME,
                    masterPassword,
                    DATABASE);
            proxy = new DsqlAuthProxy(
                    container.getHost(),
                    container.getPort(),
                    MASTER_USERNAME,
                    masterPassword,
                    DATABASE,
                    sigV4);
            try {
                proxy.start(config.services().dsql().proxyPort());
            } catch (Exception e) {
                proxy = null;
                throw new IllegalStateException(
                        "Failed to bind DSQL postgres proxy on port "
                                + config.services().dsql().proxyPort(), e);
            }
            LOG.infov("DSQL data plane ready at 0.0.0.0:{0} → {1}:{2}",
                    String.valueOf(config.services().dsql().proxyPort()),
                    container.getHost(), String.valueOf(container.getPort()));
        }
    }

    public void registerEndpoint(String endpoint) {
        if (endpoint != null && !endpoint.isBlank()) {
            liveEndpoints.add(endpoint);
        }
    }

    public void unregisterEndpoint(String endpoint) {
        if (endpoint != null) {
            liveEndpoints.remove(endpoint);
        }
    }

    public List<String> liveEndpoints() {
        return List.copyOf(liveEndpoints);
    }

    public Optional<Path> caCertPath() {
        return tls.caCertPath();
    }

    @PreDestroy
    @Override
    public void clear() {
        liveEndpoints.clear();
        synchronized (lock) {
            if (proxy != null) {
                proxy.stop();
                proxy = null;
            }
            if (container != null) {
                containerManager.stop(container);
                container = null;
            }
        }
    }
}
