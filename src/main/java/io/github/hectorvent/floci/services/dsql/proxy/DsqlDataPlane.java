package io.github.hectorvent.floci.services.dsql.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Postgres container + IAM auth proxy for every DSQL cluster.
 * Control-plane tests set {@code floci.services.dsql.mock=true} to skip Docker.
 *
 * <p>Docker start/stop is off the HTTP and shutdown path: {@code createCluster}
 * must not wait on the engine image, and {@code @PreDestroy} must not block
 * Quarkus hot-reload on a hung Docker daemon (that deadlock makes {@code :4566}
 * stop answering).
 */
@ApplicationScoped
public class DsqlDataPlane implements Resettable {

    private static final Logger LOG = Logger.getLogger(DsqlDataPlane.class);
    static final String INSTANCE_ID = "dsql-shared";
    public static final String MASTER_USERNAME = "admin";
    public static final String DATABASE = "postgres";
    /**
     * Stable master password so a reused {@code floci-rds-dsql-shared} volume still
     * matches the IAM proxy. A per-start random password left Alchemy
     * {@code DSQL.Connect} failing with {@code Backend database authentication failed}
     * after the first process (or hot-reload) because Postgres ignores
     * {@code POSTGRES_PASSWORD} when the data directory already exists.
     */
    public static final String MASTER_PASSWORD = "floci-dsql-admin";
    private static final Duration CONTAINER_STOP_JOIN = Duration.ofSeconds(6);

    private final RdsContainerManager containerManager;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final DsqlSigV4Validator sigV4;
    private final DsqlTls tls;
    private final Set<String> liveEndpoints = ConcurrentHashMap.newKeySet();

    private final Object lock = new Object();
    private RdsContainerHandle container;
    private DsqlAuthProxy proxy;
    private boolean starting;

    @Inject
    public DsqlDataPlane(RdsContainerManager containerManager,
                         ContainerLifecycleManager lifecycleManager,
                         EmulatorConfig config,
                         DsqlSigV4Validator sigV4,
                         DsqlTls tls) {
        this.containerManager = containerManager;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.sigV4 = sigV4;
        this.tls = tls;
    }

    /**
     * Kicks off the shared Postgres engine + IAM proxy. Returns immediately so
     * DSQL control-plane APIs stay responsive; Alchemy {@code DSQL.Connect}
     * readiness probes retry until the wire listener is up.
     */
    public void ensureStarted() {
        if (config.services().dsql().mock()) {
            return;
        }
        synchronized (lock) {
            if (proxy != null || starting) {
                return;
            }
            starting = true;
        }
        Thread.ofVirtual().name("dsql-dataplane-start").start(this::startBlocking);
    }

    /**
     * Drops leftover shared-engine container + volume so {@link #MASTER_PASSWORD}
     * is the password Postgres actually has (initdb), not an older random one
     * sealed into {@code floci-rds-dsql-shared}.
     */
    public void prepareSharedEngine() {
        if (lifecycleManager != null) {
            lifecycleManager.removeIfExists(
                    ContainerStorageHelper.resourceName(config, "rds", INSTANCE_ID, INSTANCE_ID));
        }
        containerManager.removeVolume(INSTANCE_ID, INSTANCE_ID);
    }

    private void startBlocking() {
        DsqlPostgresProtocolHandler.setServerSslContext(tls.sslContext());
        RdsContainerHandle startedContainer = null;
        DsqlAuthProxy startedProxy = null;
        try {
            // Bind :5432 immediately. Docker Desktop's host-gateway NAT to a
            // closed host port can hang Lambda sslmode=require until the
            // function timeout, which drops the Function URL socket
            // (Alchemy waitForFixture then fails without retrying).
            startedProxy = new DsqlAuthProxy(
                    "127.0.0.1", 1,
                    MASTER_USERNAME,
                    MASTER_PASSWORD,
                    DATABASE,
                    sigV4);
            startedProxy.start(config.services().dsql().proxyPort());
            prepareSharedEngine();
            startedContainer = containerManager.start(
                    INSTANCE_ID,
                    INSTANCE_ID,
                    DatabaseEngine.POSTGRES,
                    config.services().rds().defaultPostgresImage(),
                    MASTER_USERNAME,
                    MASTER_PASSWORD,
                    DATABASE);
            startedProxy.setBackend(startedContainer.getHost(), startedContainer.getPort());
            synchronized (lock) {
                if (!starting) {
                    stopQuietly(startedProxy);
                    stopContainerAsync(startedContainer);
                    return;
                }
                container = startedContainer;
                proxy = startedProxy;
                starting = false;
            }
            LOG.infov("DSQL data plane ready at 0.0.0.0:{0} → {1}:{2}",
                    String.valueOf(config.services().dsql().proxyPort()),
                    startedContainer.getHost(), String.valueOf(startedContainer.getPort()));
        } catch (Exception e) {
            LOG.warnv("DSQL data plane failed to start: {0}", e.getMessage());
            stopQuietly(startedProxy);
            if (startedContainer != null) {
                stopContainerAsync(startedContainer);
            }
            synchronized (lock) {
                if (proxy == null) {
                    starting = false;
                }
            }
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
        DsqlAuthProxy toStop;
        RdsContainerHandle toRemove;
        synchronized (lock) {
            starting = false;
            toStop = proxy;
            proxy = null;
            toRemove = container;
            container = null;
        }
        stopQuietly(toStop);
        if (toRemove != null) {
            Thread stopper = Thread.ofVirtual().name("dsql-container-stop")
                    .start(() -> stopContainerQuietly(toRemove));
            try {
                stopper.join(CONTAINER_STOP_JOIN);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void stopContainerAsync(RdsContainerHandle handle) {
        Thread.ofVirtual().name("dsql-container-stop").start(() -> stopContainerQuietly(handle));
    }

    private void stopContainerQuietly(RdsContainerHandle handle) {
        try {
            containerManager.stop(handle);
        } catch (Exception e) {
            LOG.warnv("Error stopping DSQL postgres container: {0}", e.getMessage());
        }
    }

    private static void stopQuietly(DsqlAuthProxy toStop) {
        if (toStop == null) {
            return;
        }
        try {
            toStop.stop();
        } catch (Exception e) {
            LOG.debugv("Error stopping DSQL proxy: {0}", e.getMessage());
        }
    }
}
