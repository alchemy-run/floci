package io.floci.aws.services.apprunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.ssm.SsmService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The containers App Runner services run in. A deployment pulls the service's image (from Floci's
 * ECR, or from ECR Public), starts it with the service's environment, its secrets resolved from
 * Secrets Manager and SSM, and its instance role's credentials, and waits for the health check
 * on its port. Standard output goes to the service's {@code application} log group, and App
 * Runner's own messages to its {@code service} log group.
 */
@ApplicationScoped
public class AppRunnerRuntime {
    private static final Logger LOG = Logger.getLogger(AppRunnerRuntime.class);
    private static final DateTimeFormatter STREAM_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final Duration HEALTH_LIMIT = Duration.ofSeconds(90);

    /** A service's running container. */
    record Running(String containerId, String host, int port, Closeable logs, String accessKeyId) {}

    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycle;
    private final ContainerLogStreamer logStreamer;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials roleCredentials;
    private final Instance<EcrRegistryManager> ecr;
    private final Instance<SecretsManagerService> secrets;
    private final Instance<SsmService> ssm;
    private final Instance<CloudWatchLogsService> logs;
    private final EmulatorConfig config;

    @Inject
    public AppRunnerRuntime(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycle,
                            ContainerLogStreamer logStreamer, LaunchedContainerAwsEnv awsEnv,
                            LambdaExecutionRoleCredentials roleCredentials, Instance<EcrRegistryManager> ecr,
                            Instance<SecretsManagerService> secrets, Instance<SsmService> ssm,
                            Instance<CloudWatchLogsService> logs, EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycle = lifecycle;
        this.logStreamer = logStreamer;
        this.awsEnv = awsEnv;
        this.roleCredentials = roleCredentials;
        this.ecr = ecr;
        this.secrets = secrets;
        this.ssm = ssm;
        this.logs = logs;
        this.config = config;
    }

    @PreDestroy
    void shutdown() {
        new ArrayList<>(running.keySet()).forEach(this::stop);
    }

    static String applicationLogGroup(String name, String id) {
        return "/aws/apprunner/" + name + "/" + id + "/application";
    }

    static String serviceLogGroup(String name, String id) {
        return "/aws/apprunner/" + name + "/" + id + "/service";
    }

    /** Writes one of App Runner's own messages to the service's {@code service} log group. */
    void serviceLog(String account, String region, String name, String id, String message) {
        if (!logs.isResolvable()) {
            return;
        }
        String group = serviceLogGroup(name, id);
        String stream = "events";
        try {
            logStreamer.ensureLogGroupAndStreamForAccount(account, group, stream, region);
            logs.get().putLogEventsForAccount(account, group, stream, List.of(Map.of("timestamp",
                    Instant.now().toEpochMilli(), "message", Instant.now() + " [AppRunner] " + message)), region);
        } catch (RuntimeException e) {
            LOG.debugv("Could not write App Runner service log {0}: {1}", group, e.getMessage());
        }
    }

    /**
     * Starts a service's container and waits until it is healthy. Replaces any container the service
     * already runs. Fails with the reason, which the caller logs and turns into the service's status.
     */
    void deploy(String account, String region, JsonNode service) {
        String arn = service.path("ServiceArn").asText();
        String name = service.path("ServiceName").asText();
        String id = service.path("ServiceId").asText();
        JsonNode source = service.path("SourceConfiguration");
        JsonNode repository = source.path("ImageRepository");
        if (!repository.isObject()) {
            throw new AwsException("InvalidRequestException", "Floci can't build services from a source code "
                    + "repository; deploy an image from ECR or ECR Public.", 400);
        }
        JsonNode image = repository.path("ImageConfiguration");
        int port = Integer.parseInt(image.path("Port").asText("8080"));
        String imageUri = repository.path("ImageIdentifier").asText();
        if ("ECR".equals(repository.path("ImageRepositoryType").asText()) && ecr.isResolvable()) {
            imageUri = ecr.get().rewriteImageUri(imageUri);
        }
        serviceLog(account, region, name, id, "Pulling image " + repository.path("ImageIdentifier").asText()
                + " from " + repository.path("ImageRepositoryType").asText() + " repository.");

        Optional<SessionCreds> credentials = Optional.empty();
        String instanceRole = service.path("InstanceConfiguration").path("InstanceRoleArn").asText("");
        if (!instanceRole.isEmpty()) {
            credentials = roleCredentials.forRole(instanceRole, account, "apprunner-" + name);
            if (credentials.isEmpty()) {
                throw new AwsException("InvalidRequestException", "The instance role " + instanceRole
                        + " can't be assumed by tasks.apprunner.amazonaws.com.", 400); // partition-literal: service principal is the same in every partition
            }
        }
        List<String> env = new ArrayList<>(awsEnv.sdkBaselineEnv(region, Optional.empty(), credentials, account));
        env.add("PORT=" + port);
        image.path("RuntimeEnvironmentVariables").fields().forEachRemaining(e -> env.add(e.getKey() + "="
                + e.getValue().asText()));
        image.path("RuntimeEnvironmentSecrets").fields().forEachRemaining(e -> env.add(e.getKey() + "="
                + secret(e.getValue().asText(), region)));
        long memoryMb = memoryMb(service.path("InstanceConfiguration").path("Memory").asText("2048"));
        String containerName = ContainerStorageHelper.prefixedDockerName(config, "floci-apprunner",
                name + "-" + UUID.randomUUID().toString().substring(0, 8));
        ContainerBuilder.Builder builder = containerBuilder.newContainer(imageUri)
                .withName(containerName)
                .withEnv(env)
                .withMemoryMb((int) memoryMb)
                .withLoopbackPortBinding(port, 0)
                .withDockerNetwork(config.services().lambda().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withEmbeddedDns()
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels("apprunner", name, account, region));
        String start = image.path("StartCommand").asText("");
        if (!start.isEmpty()) {
            builder.withEntrypoint(List.of("/bin/sh", "-c"));
            builder.withCmd(List.of(start));
        }
        ContainerSpec spec = builder.build();
        String containerId;
        ContainerInfo info;
        try {
            containerId = lifecycle.create(spec, "linux/amd64");
            info = lifecycle.startCreated(containerId, spec);
        } catch (RuntimeException e) {
            credentials.ifPresent(c -> roleCredentials.unregister(account, c.accessKeyId()));
            throw new AwsException("InvalidRequestException", "The image " + repository.path("ImageIdentifier")
                    .asText() + " couldn't be pulled or started: " + e.getMessage(), 400);
        }
        Closeable logStream = logStreamer.attachForAccount(account, containerId, applicationLogGroup(name, id),
                "instance/" + STREAM_DATE.format(Instant.now()).replace("/", "") + "-" + containerId.substring(0, 12),
                region, "apprunner:" + name);
        EndpointInfo endpoint = info.getEndpoint(port);
        Running next = new Running(containerId, endpoint.host(), endpoint.port(), logStream,
                credentials.map(SessionCreds::accessKeyId).orElse(null));
        try {
            awaitHealthy(service.path("HealthCheckConfiguration"), next);
        } catch (AwsException e) {
            release(account, next);
            throw e;
        }
        serviceLog(account, region, name, id, "Health check on port '" + port + "' passed.");
        Running previous = running.put(arn, next);
        if (previous != null) {
            release(account, previous);
        }
    }

    private void awaitHealthy(JsonNode check, Running target) {
        boolean http = "HTTP".equals(check.path("Protocol").asText("TCP"));
        String path = check.path("Path").asText("/");
        Instant deadline = Instant.now().plus(HEALTH_LIMIT);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        while (Instant.now().isBefore(deadline)) {
            try {
                if (http) {
                    int status = client.send(HttpRequest.newBuilder(URI.create("http://" + target.host() + ":"
                                    + target.port() + path)).timeout(Duration.ofSeconds(3)).GET().build(),
                            HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (status / 100 == 2) {
                        return;
                    }
                } else {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(target.host(), target.port()), 2000);
                        return;
                    }
                }
            } catch (IOException e) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AwsException("InvalidRequestException", "Health check failed on " + (http ? "HTTP path '" + path
                + "'" : "TCP") + " port " + target.port() + ".", 400);
    }

    /** The value a runtime secret names: a Secrets Manager secret (or one JSON key of it) or an SSM parameter. */
    private String secret(String reference, String region) {
        try {
            if (AwsArnUtils.isArnFor(reference, "ssm") || reference.startsWith("/")) {
                return ssm.get().getParameter(reference, true, region).getValue();
            }
            String secretId = reference;
            String jsonKey = null;
            String[] parts = reference.split(":");
            if (AwsArnUtils.isArnFor(reference, "secretsmanager") && parts.length > 7) {
                secretId = String.join(":", Arrays.copyOfRange(parts, 0, 7));
                jsonKey = parts[7].isEmpty() ? null : parts[7];
            }
            String value = secrets.get().getSecretValue(secretId, null, null, region).getSecretString();
            if (jsonKey != null) {
                return new ObjectMapper().readTree(value).path(jsonKey).asText();
            }
            return value;
        } catch (Exception e) {
            throw new AwsException("InvalidRequestException", "The runtime secret " + reference
                    + " couldn't be read: " + e.getMessage(), 400);
        }
    }

    private static long memoryMb(String memory) {
        String value = memory.trim().toUpperCase();
        if (value.endsWith("GB")) {
            return (long) (Double.parseDouble(value.replace("GB", "").trim()) * 1024);
        }
        long number = (long) Double.parseDouble(value.replace("MB", "").trim());
        return number <= 16 ? number * 1024 : number;
    }

    /** The service's container, or null when it runs none. */
    Running running(String serviceArn) {
        Running target = running.get(serviceArn);
        if (target != null && lifecycle.presenceOf(target.containerId())
                != ContainerLifecycleManager.ContainerPresence.RUNNING) {
            running.remove(serviceArn, target);
            return null;
        }
        return target;
    }

    void stop(String serviceArn) {
        Running target = running.remove(serviceArn);
        if (target != null) {
            release(null, target);
        }
    }

    private void release(String account, Running target) {
        try {
            lifecycle.stopAndRemove(target.containerId(), target.logs(), 2);
        } catch (RuntimeException e) {
            LOG.debugv("Could not remove App Runner container {0}: {1}", target.containerId(), e.getMessage());
        }
        if (target.accessKeyId() != null && account != null) {
            roleCredentials.unregister(account, target.accessKeyId());
        }
    }
}
