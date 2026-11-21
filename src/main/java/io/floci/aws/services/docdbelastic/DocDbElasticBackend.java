package io.floci.aws.services.docdbelastic;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.docdb.container.DocDbContainerHandle;
import io.floci.aws.services.docdb.container.DocDbContainerManager;
import io.floci.aws.services.docdb.proxy.DocDbProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The MongoDB behind an elastic cluster: DocumentDB's container and TLS listener, which serve the cluster endpoint on
 * port 27017, and the {@code mongodump} archives that carry a cluster's data through a snapshot, a restore, a copy
 * and a stop. A cluster's bookkeeping ({@code _}) records the container and the port it is served on.
 *
 * <p>DocumentDB's {@code mock} setting applies here too: in mock mode, or with no reachable Docker daemon, clusters
 * are metadata only and a snapshot holds no data.
 */
@ApplicationScoped
public class DocDbElasticBackend {

    private static final Logger LOG = Logger.getLogger(DocDbElasticBackend.class);
    private static final String ARCHIVE = "floci-docdb-elastic.archive";
    private static final int EXEC_TIMEOUT_SECONDS = 120;

    private final DocDbContainerManager containers;
    private final DocDbProxyManager proxies;
    private final ContainerLifecycleManager lifecycle;
    private final EmulatorConfig config;

    @Inject
    public DocDbElasticBackend(DocDbContainerManager containers, DocDbProxyManager proxies,
                               ContainerLifecycleManager lifecycle, EmulatorConfig config) {
        this.containers = containers;
        this.proxies = proxies;
        this.lifecycle = lifecycle;
        this.config = config;
    }

    /** Whether clusters get a MongoDB container at all. */
    boolean enabled() {
        return !config.services().docdb().mock();
    }

    /** Whether a cluster's MongoDB is running. */
    static boolean running(ObjectNode cluster) {
        return meta(cluster).hasNonNull("containerId");
    }

    /**
     * Starts the cluster's MongoDB, with its admin user, behind a TLS listener for the cluster endpoint. Elastic
     * clusters accept TLS connections only, so the listener is one DocumentDB's TLS clusters share.
     */
    void start(ObjectNode cluster, String password) {
        if (!enabled() || running(cluster)) {
            return;
        }
        String arn = cluster.path("clusterArn").asText();
        String endpoint = cluster.path("clusterEndpoint").asText();
        int port = proxies.reserve(arn, null, true);
        DocDbContainerHandle handle;
        try {
            handle = containers.tryStart(containerKey(cluster), config.services().docdb().defaultImage(),
                    cluster.path("adminUserName").asText(), password, endpoint, port);
        } catch (RuntimeException e) {
            proxies.release(arn);
            throw e;
        }
        if (handle == null) {
            proxies.release(arn);
            LOG.warnv("Elastic cluster {0} has no MongoDB: no Docker daemon is reachable", arn);
            return;
        }
        proxies.attach(arn, List.of(endpoint), handle.getHost(), handle.getPort());
        meta(cluster).put("containerId", handle.getContainerId()).put("containerHost", handle.getHost())
                .put("containerPort", handle.getPort()).put("port", port);
    }

    /** Stops the cluster's MongoDB and its listener; its data goes with the container unless it was dumped. */
    void stop(ObjectNode cluster) {
        ObjectNode meta = meta(cluster);
        proxies.release(cluster.path("clusterArn").asText());
        if (meta.hasNonNull("containerId")) {
            containers.stop(new DocDbContainerHandle(meta.path("containerId").asText(), containerKey(cluster),
                    meta.path("containerHost").asText(), meta.path("containerPort").asInt()));
        }
        meta.remove(List.of("containerId", "containerHost", "containerPort", "port"));
    }

    /** Dumps every database but MongoDB's own into {@code target}; false when the cluster has no MongoDB. */
    boolean dump(ObjectNode cluster, String password, Path target) {
        if (!running(cluster)) {
            return false;
        }
        String containerId = meta(cluster).path("containerId").asText();
        String file = "/tmp/" + ARCHIVE;
        exec(containerId, "mongodump", List.of("mongodump", "--quiet", "--host", "127.0.0.1", "--port",
                meta(cluster).path("port").asText(), "-u", cluster.path("adminUserName").asText(), "-p", password,
                "--authenticationDatabase", "admin", "--archive=" + file, "--gzip"));
        try {
            Files.createDirectories(target.getParent());
            try (InputStream archive = lifecycle.getDockerClient().copyArchiveFromContainerCmd(containerId, file)
                    .exec()) {
                Files.copy(archive, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the dump of " + cluster.path("clusterArn").asText(), e);
        } finally {
            quietly(containerId, List.of("rm", "-f", file));
        }
        return true;
    }

    /** Loads a dump into the cluster's MongoDB, replacing collections of the same name. */
    void restore(ObjectNode cluster, String password, Path source) {
        if (!running(cluster) || !Files.exists(source)) {
            return;
        }
        String containerId = meta(cluster).path("containerId").asText();
        try (InputStream archive = Files.newInputStream(source)) {
            lifecycle.getDockerClient().copyArchiveToContainerCmd(containerId).withRemotePath("/tmp")
                    .withTarInputStream(archive).exec();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the dump " + source, e);
        }
        String file = "/tmp/" + ARCHIVE;
        try {
            exec(containerId, "mongorestore", List.of("mongorestore", "--quiet", "--host", "127.0.0.1", "--port",
                    meta(cluster).path("port").asText(), "-u", cluster.path("adminUserName").asText(), "-p",
                    password, "--authenticationDatabase", "admin", "--archive=" + file, "--gzip", "--drop",
                    "--nsExclude=admin.*", "--nsExclude=config.*", "--nsExclude=local.*"));
        } finally {
            quietly(containerId, List.of("rm", "-f", file));
        }
    }

    /** Changes the admin user's password in the cluster's MongoDB. */
    void changePassword(ObjectNode cluster, String oldPassword, String newPassword) {
        if (!running(cluster)) {
            return;
        }
        String user = cluster.path("adminUserName").asText();
        exec(meta(cluster).path("containerId").asText(), "mongosh", List.of("mongosh", "--quiet", "--host",
                "127.0.0.1", "--port", meta(cluster).path("port").asText(), "-u", user, "-p", oldPassword,
                "--authenticationDatabase", "admin", "--eval", "db.getSiblingDB('admin').changeUserPassword("
                        + quote(user) + ", " + quote(newPassword) + ")"));
    }

    /** Where a snapshot's, or a stopped cluster's, dump is kept. */
    Path archive(String kind, String id) {
        return Path.of(config.storage().persistentPath()).resolve("docdb-elastic").resolve(kind).resolve(id + ".tar");
    }

    void deleteArchive(String kind, String id) {
        try {
            Files.deleteIfExists(archive(kind, id));
        } catch (IOException e) {
            LOG.warnv(e, "Could not delete the DocumentDB elastic dump {0}/{1}", kind, id);
        }
    }

    void copyArchive(String kind, String from, String to) {
        Path source = archive(kind, from);
        if (!Files.exists(source)) {
            return;
        }
        try {
            Files.createDirectories(archive(kind, to).getParent());
            Files.copy(source, archive(kind, to), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not copy the DocumentDB elastic dump " + from, e);
        }
    }

    /** The name DocumentDB's container manager knows the cluster's container by. */
    static String containerKey(ObjectNode cluster) {
        String arn = cluster.path("clusterArn").asText();
        return "elastic-" + arn.substring(arn.lastIndexOf('/') + 1);
    }

    private void exec(String containerId, String tool, List<String> command) {
        ContainerExec.Result result = ContainerExec.run(lifecycle.getDockerClient(), containerId,
                command.toArray(String[]::new), EXEC_TIMEOUT_SECONDS).throwIfTimedOut(containerId);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(tool + " failed with exit code " + result.exitCode() + ": "
                    + result.summary());
        }
    }

    private void quietly(String containerId, List<String> command) {
        try {
            ContainerExec.run(lifecycle.getDockerClient(), containerId, command.toArray(String[]::new), 30);
        } catch (RuntimeException e) {
            LOG.debugv("Cleanup in container {0} failed: {1}", containerId, e.getMessage());
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static ObjectNode meta(ObjectNode record) {
        return (ObjectNode) record.get("_");
    }
}
