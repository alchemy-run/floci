package io.github.hectorvent.floci.services.apprunner;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.apprunner.model.AppRunnerServiceRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs one Docker container per App Runner service, publishing the configured
 * application port so the gateway can reverse-proxy {@code *.awsapprunner.com}.
 */
@ApplicationScoped
public class AppRunnerContainerManager implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(AppRunnerContainerManager.class);
    private static final int DEFAULT_PORT = 8080;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final EmulatorConfig config;
    private final ConcurrentHashMap<String, Runtime> runtimes = new ConcurrentHashMap<>();

    @Inject
    public AppRunnerContainerManager(
            ContainerBuilder containerBuilder,
            ContainerLifecycleManager lifecycleManager,
            ContainerLogStreamer logStreamer,
            EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.config = config;
    }

    public void start(AppRunnerServiceRecord record) {
        if (record == null || record.getServiceId() == null) {
            return;
        }
        stop(record.getServiceId());
        String image = imageIdentifier(record);
        if (image == null) {
            return;
        }
        int port = applicationPort(record);
        try {
            ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                    .withName(ContainerStorageHelper.dockerName(config, "floci-apprunner-" + record.getServiceId()))
                    .withDynamicPort(port)
                    .withEnv("PORT", String.valueOf(port))
                    .withEnv("AWS_REGION", record.getRegion())
                    .withEnv("AWS_DEFAULT_REGION", record.getRegion())
                    .withDockerNetwork(config.services().dockerNetwork())
                    .withHostDockerInternalOnLinux()
                    .withEmbeddedDns()
                    .withLogRotation();
            addRuntimeEnv(builder, record);
            String startCommand = startCommand(record);
            if (startCommand != null) {
                builder.withCmd(List.of("sh", "-c", startCommand));
            }
            ContainerSpec spec = builder.build();
            ContainerInfo info = lifecycleManager.createAndStart(spec);
            EndpointInfo endpoint = info.getEndpoint(port);
            String logGroup = "/aws/apprunner/" + record.getServiceName() + "/" + record.getServiceId()
                    + "/application";
            String stream = logStreamer.generateLogStreamName("instance/" + record.getServiceId());
            Closeable logs = logStreamer.attach(info.containerId(), logGroup, stream, record.getRegion(),
                    "apprunner:" + record.getServiceName());
            InetSocketAddress address = endpoint == null
                    ? new InetSocketAddress("127.0.0.1", port)
                    : new InetSocketAddress(endpoint.host(), endpoint.port());
            runtimes.put(record.getServiceId(), new Runtime(info.containerId(), address, logs));
            LOG.infov("Started App Runner container for {0} at {1}:{2}",
                    record.getServiceName(), address.getHostString(), address.getPort());
        } catch (Exception e) {
            LOG.warnv(e, "Failed to start App Runner container for {0} image {1}",
                    record.getServiceName(), image);
        }
    }

    public void stop(String serviceId) {
        if (serviceId == null) {
            return;
        }
        Runtime runtime = runtimes.remove(serviceId);
        if (runtime == null) {
            return;
        }
        try {
            lifecycleManager.stopAndRemove(runtime.containerId(), runtime.logs());
        } catch (Exception e) {
            LOG.warnv("Failed to stop App Runner container {0}: {1}", serviceId, e.getMessage());
        }
    }

    public Optional<InetSocketAddress> endpoint(String serviceId) {
        Runtime runtime = runtimes.get(serviceId);
        return runtime == null ? Optional.empty() : Optional.of(runtime.address());
    }

    @Override
    public void stopManagedContainers() {
        for (String serviceId : List.copyOf(runtimes.keySet())) {
            stop(serviceId);
        }
    }

    private void addRuntimeEnv(ContainerBuilder.Builder builder, AppRunnerServiceRecord record) {
        if (record.getSourceConfiguration() == null) {
            return;
        }
        JsonNode env = record.getSourceConfiguration()
                .path("ImageRepository")
                .path("ImageConfiguration")
                .path("RuntimeEnvironmentVariables");
        if (env == null || !env.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = env.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value != null && value.isValueNode()) {
                builder.withEnv(field.getKey(), value.asText());
            }
        }
    }

    private static String imageIdentifier(AppRunnerServiceRecord record) {
        if (record.getSourceConfiguration() == null) {
            return null;
        }
        JsonNode image = record.getSourceConfiguration().path("ImageRepository").path("ImageIdentifier");
        if (image == null || image.isMissingNode() || image.isNull()) {
            return null;
        }
        String identifier = image.asText();
        return identifier.isBlank() ? null : identifier;
    }

    private static int applicationPort(AppRunnerServiceRecord record) {
        if (record.getSourceConfiguration() == null) {
            return DEFAULT_PORT;
        }
        JsonNode port = record.getSourceConfiguration().path("ImageRepository")
                .path("ImageConfiguration").path("Port");
        if (port == null || port.isMissingNode() || port.isNull()) {
            return DEFAULT_PORT;
        }
        try {
            int parsed = Integer.parseInt(port.asText());
            return parsed > 0 ? parsed : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    private static String startCommand(AppRunnerServiceRecord record) {
        if (record.getSourceConfiguration() == null) {
            return null;
        }
        JsonNode command = record.getSourceConfiguration().path("ImageRepository")
                .path("ImageConfiguration").path("StartCommand");
        if (command == null || command.isMissingNode() || command.isNull()) {
            return null;
        }
        String text = command.asText();
        return text.isBlank() ? null : text;
    }

    private record Runtime(String containerId, InetSocketAddress address, Closeable logs) {
    }
}
