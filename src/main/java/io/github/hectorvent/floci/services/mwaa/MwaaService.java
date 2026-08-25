package io.github.hectorvent.floci.services.mwaa;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.mwaa.model.CreateEnvironmentRequest;
import io.github.hectorvent.floci.services.mwaa.model.Environment;
import io.github.hectorvent.floci.services.mwaa.model.EnvironmentStatus;
import io.github.hectorvent.floci.services.mwaa.model.InvokeRestApiRequest;
import io.github.hectorvent.floci.services.mwaa.model.UpdateEnvironmentRequest;
import io.github.hectorvent.floci.services.mwaa.proxy.MwaaProxyManager;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class MwaaService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(MwaaService.class);

    private final StorageBackend<String, Environment> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final MwaaEnvironmentManager environmentManager;
    private final MwaaProxyManager proxyManager;
    private final PortAllocator portAllocator;
    private final S3Service s3Service;

    private final ScheduledExecutorService readinessPoller = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService dagSyncPoller = Executors.newSingleThreadScheduledExecutor();

    /** Opaque CLI tokens minted by CreateCliToken, per environment. In-memory only — not persisted. */
    private final Map<String, Set<String>> cliTokensByEnvironment = new ConcurrentHashMap<>();

    /** Last-synced DAG file state (S3 key relative to DagS3Path -> ETag), per environment. */
    private final Map<String, Map<String, String>> dagSyncState = new ConcurrentHashMap<>();

    /** Last-installed requirements.txt ETag, per environment. */
    private final Map<String, String> requirementsEtagByEnvironment = new ConcurrentHashMap<>();

    private static final Set<String> REST_API_METHODS = Set.of("GET", "PUT", "POST", "PATCH", "DELETE");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    public MwaaService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                       MwaaEnvironmentManager environmentManager, MwaaProxyManager proxyManager,
                       PortAllocator portAllocator, S3Service s3Service) {
        this.storage = storageFactory.create("mwaa", "mwaa-environments.json",
                new TypeReference<Map<String, Environment>>() {
                });
        this.config = config;
        this.regionResolver = regionResolver;
        this.environmentManager = environmentManager;
        this.proxyManager = proxyManager;
        this.portAllocator = portAllocator;
        this.s3Service = s3Service;
    }

    @PostConstruct
    public void init() {
        if (!config.services().mwaa().mock()) {
            startReadinessPoller();
            startDagSyncPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        readinessPoller.shutdownNow();
        dagSyncPoller.shutdownNow();
        if (!config.services().mwaa().mock() && !config.services().mwaa().keepRunningOnShutdown()) {
            for (Environment environment : allEnvironments()) {
                proxyManager.stopProxy(environment.getName());
                environmentManager.stopEnvironment(environment);
            }
        }
    }

    public Environment createEnvironment(String name, CreateEnvironmentRequest request) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "Environment name is required", 400);
        }
        if (storage.get(name).isPresent()) {
            // CreateEnvironment's botocore model declares only ServiceUnavailableException,
            // ValidationException, and InternalServerException — no "already exists" shape — so an
            // SDK client can't map a ResourceAlreadyExistsException here.
            throw new AwsException("ValidationException",
                    "Environment already exists: " + name, 400);
        }

        String version = resolveAirflowVersion(request.getAirflowVersion());

        String region = config.defaultRegion();
        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("airflow", region, accountId, "environment/" + name).toString();

        Environment environment = new Environment();
        environment.setName(name);
        environment.setArn(arn);
        environment.setAccountId(accountId);
        environment.setCreatedAt(Instant.now());
        environment.setStatus(EnvironmentStatus.CREATING);
        environment.setExecutionRoleArn(request.getExecutionRoleArn());
        environment.setAirflowVersion(version);
        environment.setSourceBucketArn(request.getSourceBucketArn());
        environment.setDagS3Path(request.getDagS3Path());
        environment.setPluginsS3Path(request.getPluginsS3Path());
        environment.setRequirementsS3Path(request.getRequirementsS3Path());
        environment.setStartupScriptS3Path(request.getStartupScriptS3Path());
        environment.setNetworkConfiguration(request.getNetworkConfiguration());
        environment.setLoggingConfiguration(request.getLoggingConfiguration());
        environment.setAirflowConfigurationOptions(request.getAirflowConfigurationOptions() != null
                ? new HashMap<>(request.getAirflowConfigurationOptions()) : new HashMap<>());
        environment.setEnvironmentClass(request.getEnvironmentClass() != null ? request.getEnvironmentClass() : "mw1.small");
        environment.setWebserverAccessMode(request.getWebserverAccessMode() != null
                ? request.getWebserverAccessMode() : "PUBLIC_ONLY");
        environment.setMaxWorkers(request.getMaxWorkers() != null ? request.getMaxWorkers() : 10);
        environment.setMinWorkers(request.getMinWorkers() != null ? request.getMinWorkers() : 1);
        environment.setSchedulers(request.getSchedulers() != null ? request.getSchedulers() : 2);
        environment.setTags(request.getTags() != null ? new HashMap<>(request.getTags()) : new HashMap<>());

        if (config.services().mwaa().mock()) {
            environment.setStatus(EnvironmentStatus.AVAILABLE);
            environment.setWebserverUrl(buildWebserverUrl(config.services().mwaa().proxyBasePort()));
        } else {
            // proxyPort/provisioned tracked outside the try so the finally block below knows exactly
            // how far setup got, mirroring NeptuneService.createDbCluster's rollback shape: a partial
            // failure must never leak the reserved port or leave orphaned Postgres/Airflow containers
            // behind — regardless of which of the three steps below actually failed.
            int proxyPort = -1;
            boolean provisioned = false;
            try {
                byte[] startupScriptContent = fetchStartupScript(environment);
                environmentManager.startEnvironment(environment, version, startupScriptContent);
                proxyPort = portAllocator.allocate(
                        config.services().mwaa().proxyBasePort(), config.services().mwaa().proxyMaxPort());
                environment.setProxyPort(proxyPort);
                environment.setWebserverUrl(buildWebserverUrl(proxyPort));
                String airflowContainerId = environment.getAirflowContainerId();
                proxyManager.startProxy(name, proxyPort,
                        environment.getAirflowInternalHost(), environment.getAirflowInternalPort(),
                        this::isValidCliToken,
                        cliCommand -> environmentManager.runAirflowCli(airflowContainerId, cliCommand));
                provisioned = true;
            } catch (Exception e) {
                LOG.errorv(e, "Failed to start MWAA environment {0}", name);
                environment.setStatus(EnvironmentStatus.CREATE_FAILED);
            } finally {
                if (!provisioned) {
                    rollbackFailedCreate(environment, proxyPort);
                }
            }
        }

        storage.put(name, environment);
        return environment;
    }

    /**
     * Best-effort teardown for a create that didn't fully provision — stops the proxy (a no-op if it
     * never started, since {@link MwaaProxyManager#stopProxy} just finds nothing registered),
     * stops any containers {@code environmentManager.startEnvironment} did manage to start before
     * failing ({@link MwaaEnvironmentManager#stopEnvironment} null-checks each container id, so it's
     * safe even when only Postgres — or nothing — started), and always releases the reserved proxy
     * port. Mirrors {@code NeptuneService.rollbackDbCluster}.
     */
    private void rollbackFailedCreate(Environment environment, int proxyPort) {
        try {
            proxyManager.stopProxy(environment.getName());
        } catch (Exception e) {
            LOG.warnv("Error stopping proxy while rolling back failed MWAA environment {0}: {1}",
                    environment.getName(), e.getMessage());
        }
        try {
            environmentManager.stopEnvironment(environment);
        } catch (Exception e) {
            LOG.warnv("Error stopping containers while rolling back failed MWAA environment {0}: {1}",
                    environment.getName(), e.getMessage());
        } finally {
            if (proxyPort >= 0) {
                portAllocator.release(proxyPort);
            }
        }
    }

    public Environment getEnvironment(String name) {
        return storage.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No environment found for name: " + name, 404));
    }

    public List<String> listEnvironments() {
        return storage.scan(k -> true).stream()
                .map(Environment::getName)
                .collect(Collectors.toList());
    }

    public Environment updateEnvironment(String name, UpdateEnvironmentRequest request) {
        Environment environment = getEnvironment(name);

        if (request.getAirflowVersion() != null && !request.getAirflowVersion().equals(environment.getAirflowVersion())) {
            throw new AwsException("ValidationException",
                    "Updating AirflowVersion is not supported; it would require recreating the "
                            + "environment's Airflow container. Delete and recreate the environment instead.", 400);
        }
        if (request.getAirflowConfigurationOptions() != null) {
            throw new AwsException("ValidationException",
                    "Updating AirflowConfigurationOptions is not supported; it would require "
                            + "recreating the environment's Airflow container.", 400);
        }
        if (request.getRequirementsS3Path() != null
                && !request.getRequirementsS3Path().equals(environment.getRequirementsS3Path())) {
            throw new AwsException("ValidationException",
                    "Changing RequirementsS3Path via UpdateEnvironment is not supported; requirements "
                            + "are re-installed automatically on the next DAG-sync pass when the file's "
                            + "content changes at its existing path.", 400);
        }
        if (request.getStartupScriptS3Path() != null
                && !request.getStartupScriptS3Path().equals(environment.getStartupScriptS3Path())) {
            throw new AwsException("ValidationException",
                    "Updating StartupScriptS3Path is not supported; it only runs once, at container "
                            + "creation, so it would require recreating the environment's Airflow "
                            + "container. Delete and recreate the environment instead.", 400);
        }

        if (request.getExecutionRoleArn() != null) {
            environment.setExecutionRoleArn(request.getExecutionRoleArn());
        }
        if (request.getSourceBucketArn() != null) {
            environment.setSourceBucketArn(request.getSourceBucketArn());
        }
        if (request.getDagS3Path() != null) {
            environment.setDagS3Path(request.getDagS3Path());
        }
        if (request.getPluginsS3Path() != null) {
            environment.setPluginsS3Path(request.getPluginsS3Path());
        }
        if (request.getNetworkConfiguration() != null) {
            environment.setNetworkConfiguration(request.getNetworkConfiguration());
        }
        if (request.getLoggingConfiguration() != null) {
            environment.setLoggingConfiguration(request.getLoggingConfiguration());
        }
        if (request.getEnvironmentClass() != null) {
            environment.setEnvironmentClass(request.getEnvironmentClass());
        }
        if (request.getWebserverAccessMode() != null) {
            environment.setWebserverAccessMode(request.getWebserverAccessMode());
        }
        if (request.getMaxWorkers() != null) {
            environment.setMaxWorkers(request.getMaxWorkers());
        }
        if (request.getMinWorkers() != null) {
            environment.setMinWorkers(request.getMinWorkers());
        }
        if (request.getSchedulers() != null) {
            environment.setSchedulers(request.getSchedulers());
        }

        environment.setLastUpdate(new Environment.LastUpdate("SUCCESS", Instant.now()));
        putEnvironment(environment);
        return environment;
    }

    public Environment deleteEnvironment(String name) {
        Environment environment = getEnvironment(name);
        environment.setStatus(EnvironmentStatus.DELETING);
        if (!config.services().mwaa().mock()) {
            proxyManager.stopProxy(name);
            environmentManager.stopEnvironment(environment);
            // Mirrors how Lambda/MSK/EC2/ECR release their allocated ports on cleanup — otherwise
            // repeated create/delete cycles exhaust the configured proxy port range even though no
            // MWAA proxy is actually running anymore.
            portAllocator.release(environment.getProxyPort());
        }
        cliTokensByEnvironment.remove(name);
        dagSyncState.remove(name);
        requirementsEtagByEnvironment.remove(name);
        storage.delete(name);
        return environment;
    }

    public Map<String, Object> createWebLoginToken(String name) {
        Environment environment = getEnvironment(name);
        String token = generateToken();
        return Map.of(
                "WebToken", token,
                "WebServerHostname", hostnameFromUrl(environment.getWebserverUrl()),
                "IamIdentity", "assumed-role/floci-local/user",
                "AirflowIdentity", "admin");
    }

    public Map<String, Object> createCliToken(String name) {
        Environment environment = getEnvironment(name);
        String token = generateToken();
        cliTokensByEnvironment.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(token);
        return Map.of(
                "CliToken", token,
                "WebServerHostname", hostnameFromUrl(environment.getWebserverUrl()));
    }

    boolean isValidCliToken(String environmentName, String token) {
        Set<String> tokens = cliTokensByEnvironment.get(environmentName);
        return tokens != null && tokens.contains(token);
    }

    /**
     * InvokeRestApi — POST /restapi/{Name}. Proxies Method/Path onto the environment's
     * Apache Airflow REST API ({@code /api/v1/...}). Missing environments raise
     * {@code ResourceNotFoundException} (HTTP 404), matching real MWAA.
     */
    public Map<String, Object> invokeRestApi(String name, InvokeRestApiRequest request) {
        if (request == null || request.getMethod() == null || request.getMethod().isBlank()
                || request.getPath() == null || request.getPath().isBlank()) {
            throw new AwsException("ValidationException", "Method and Path are required", 400);
        }
        String method = request.getMethod().trim().toUpperCase(Locale.ROOT);
        if (!REST_API_METHODS.contains(method)) {
            throw new AwsException("ValidationException",
                    "Method must be one of GET, PUT, POST, PATCH, DELETE", 400);
        }
        String path = request.getPath().trim();
        if (path.length() > 64) {
            throw new AwsException("ValidationException",
                    "Path must be between 1 and 64 characters", 400);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        Environment environment = getEnvironment(name);
        if (config.services().mwaa().mock()) {
            return mockInvokeRestApi(method, path);
        }
        return proxyInvokeRestApi(environment, method, path, request);
    }

    private Map<String, Object> mockInvokeRestApi(String method, String path) {
        String restPath = stripAirflowApiPrefix(path);
        if ("GET".equals(method) && ("/dags".equals(restPath) || "/dags/".equals(restPath))) {
            Map<String, Object> airflow = new LinkedHashMap<>();
            airflow.put("dags", List.of());
            airflow.put("total_entries", 0);
            return invokeRestApiSuccess(200, airflow);
        }
        Map<String, Object> notFound = new LinkedHashMap<>();
        notFound.put("status", 404);
        notFound.put("title", "Not Found");
        notFound.put("detail", "The requested Airflow REST path was not found: " + restPath);
        throw restApiException("RestApiClientException", 404, notFound);
    }

    private Map<String, Object> proxyInvokeRestApi(Environment environment, String method, String path,
                                                    InvokeRestApiRequest request) {
        String host = environment.getAirflowInternalHost();
        int port = environment.getAirflowInternalPort();
        if (host == null || host.isBlank() || port <= 0) {
            throw new AwsException("InternalServerException",
                    "Environment webserver is not reachable: " + environment.getName(), 500);
        }

        String uri = "http://" + host + ":" + port + airflowApiPath(path)
                + encodeQuery(request.getQueryParameters());
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            String username = environment.getWebserverUsername();
            String password = environment.getWebserverPassword();
            if (username != null && password != null) {
                String token = Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + token);
            }

            boolean sendJsonBody = request.getBody() != null
                    && !"GET".equals(method)
                    && !"DELETE".equals(method);
            if (sendJsonBody) {
                builder.header("Content-Type", "application/json");
            }
            builder.method(method, restApiBodyPublisher(method, request.getBody()));

            HttpResponse<String> response = HTTP_CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            Object parsed = parseJsonOrRaw(response.body());
            if (status >= 200 && status < 300) {
                return invokeRestApiSuccess(status, parsed);
            }
            String code = status >= 500 ? "RestApiServerException" : "RestApiClientException";
            throw restApiException(code, status, parsed);
        } catch (AwsException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("InternalServerException",
                    "Interrupted invoking Airflow REST API", 500);
        } catch (IOException | IllegalArgumentException e) {
            throw new AwsException("InternalServerException",
                    "Failed to invoke Airflow REST API: " + e.getMessage(), 500);
        }
    }

    private static HttpRequest.BodyPublisher restApiBodyPublisher(String method, Object body)
            throws IOException {
        if ("GET".equals(method) || "DELETE".equals(method) || body == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body));
    }

    static String airflowApiPath(String path) {
        if (path.startsWith("/api/v1/") || "/api/v1".equals(path)) {
            return path;
        }
        return "/api/v1" + path;
    }

    static String stripAirflowApiPrefix(String path) {
        if (path.startsWith("/api/v1/")) {
            return path.substring("/api/v1".length());
        }
        if ("/api/v1".equals(path)) {
            return "/";
        }
        return path;
    }

    private static String encodeQuery(Object queryParameters) {
        if (!(queryParameters instanceof Map<?, ?> params) || params.isEmpty()) {
            return "";
        }
        StringBuilder query = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<?, ?> entry : params.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!first) {
                query.append('&');
            }
            first = false;
            query.append(URLEncoder.encode(String.valueOf(entry.getKey()), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return query.length() == 1 ? "" : query.toString();
    }

    private static Map<String, Object> invokeRestApiSuccess(int status, Object body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("RestApiStatusCode", status);
        if (body != null) {
            result.put("RestApiResponse", body);
        }
        return result;
    }

    private static AwsException restApiException(String code, int airflowStatus, Object body) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("RestApiStatusCode", airflowStatus);
        if (body != null) {
            extra.put("RestApiResponse", body);
        }
        return new AwsException(code,
                "Apache Airflow REST API returned status " + airflowStatus, 400, extra);
    }

    private static Object parseJsonOrRaw(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(body, Object.class);
        } catch (IOException e) {
            return body;
        }
    }

    private String resolveAirflowVersion(String requested) {
        List<String> supported = config.services().mwaa().supportedVersions();
        String version = requested != null && !requested.isBlank() ? requested : config.services().mwaa().defaultVersion();
        if (!supported.contains(version)) {
            throw new AwsException("ValidationException",
                    "Unsupported AirflowVersion '" + version + "'. Supported versions: " + supported, 400);
        }
        return version;
    }

    private String buildWebserverUrl(int proxyPort) {
        String scheme = config.tls().enabled() ? "https" : "http";
        String host = config.hostname().orElse("localhost");
        return scheme + "://" + host + ":" + proxyPort;
    }

    private static String hostnameFromUrl(String url) {
        if (url == null) {
            return "localhost";
        }
        String withoutScheme = url.replaceFirst("^[a-zA-Z]+://", "");
        int slash = withoutScheme.indexOf('/');
        return slash >= 0 ? withoutScheme.substring(0, slash) : withoutScheme;
    }

    private static String generateToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    @Override
    public String serviceKey() {
        return "airflow";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String resourceArn) {
        Environment environment = findByArn(resourceArn);
        return environment.getTags() != null ? environment.getTags() : Map.of();
    }

    @Override
    public void tagResource(String region, String resourceArn, Map<String, String> tags) {
        Environment environment = findByArn(resourceArn);
        if (environment.getTags() == null) {
            environment.setTags(new HashMap<>());
        }
        environment.getTags().putAll(tags);
        putEnvironment(environment);
    }

    @Override
    public void untagResource(String region, String resourceArn, List<String> tagKeys) {
        Environment environment = findByArn(resourceArn);
        if (environment.getTags() != null && tagKeys != null) {
            tagKeys.forEach(environment.getTags()::remove);
        }
        putEnvironment(environment);
    }

    private Environment findByArn(String resourceArn) {
        int idx = resourceArn.lastIndexOf('/');
        if (idx < 0 || idx == resourceArn.length() - 1) {
            throw new AwsException("ValidationException", "Invalid resource ARN: " + resourceArn, 400);
        }
        return getEnvironment(resourceArn.substring(idx + 1));
    }

    private void startReadinessPoller() {
        readinessPoller.scheduleAtFixedRate(() -> {
            try {
                for (Environment environment : allEnvironments()) {
                    if (environment.getStatus() == EnvironmentStatus.CREATING
                            && environmentManager.isReady(environment)) {
                        LOG.infov("MWAA environment {0} is now AVAILABLE", environment.getName());
                        environment.setStatus(EnvironmentStatus.AVAILABLE);
                        putEnvironment(environment);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in MWAA readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    private void startDagSyncPoller() {
        int intervalSeconds = Math.max(1, config.services().mwaa().dagSyncIntervalSeconds());
        dagSyncPoller.scheduleAtFixedRate(() -> {
            try {
                for (Environment environment : allEnvironments()) {
                    if (environment.getStatus() != EnvironmentStatus.AVAILABLE) {
                        continue;
                    }
                    try {
                        syncDags(environment);
                    } catch (Exception e) {
                        // A bad/unsyncable DAG or S3 error must never fail the environment itself —
                        // only this poller pass for this one environment is affected.
                        LOG.warnv("DAG sync failed for MWAA environment {0}: {1}",
                                environment.getName(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in MWAA DAG-sync poller", e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void syncDags(Environment environment) {
        if (environment.getSourceBucketArn() == null || environment.getDagS3Path() == null) {
            return;
        }
        String bucket = bucketNameFromArn(environment.getSourceBucketArn());
        String prefix = environment.getDagS3Path().endsWith("/")
                ? environment.getDagS3Path() : environment.getDagS3Path() + "/";

        List<S3Object> objects = s3Service.listObjects(bucket, prefix, null, 1000);
        Map<String, String> currentByKey = new HashMap<>();
        for (S3Object obj : objects) {
            String relative = obj.getKey().substring(prefix.length());
            if (relative.isBlank()) {
                continue;
            }
            currentByKey.put(relative, obj.getETag());
        }

        Map<String, String> previous = dagSyncState.computeIfAbsent(environment.getName(), k -> new HashMap<>());
        for (Map.Entry<String, String> entry : currentByKey.entrySet()) {
            String relative = entry.getKey();
            String etag = entry.getValue();
            if (!etag.equals(previous.get(relative))) {
                try {
                    S3Object obj = s3Service.getObject(bucket, prefix + relative);
                    environmentManager.copyDagFile(environment, relative, obj.getData());
                } catch (Exception e) {
                    LOG.warnv("Skipping unsyncable DAG {0} for environment {1}: {2}",
                            relative, environment.getName(), e.getMessage());
                }
            }
        }
        for (String relative : new HashSet<>(previous.keySet())) {
            if (!currentByKey.containsKey(relative)) {
                environmentManager.removeDagFile(environment, relative);
            }
        }
        previous.clear();
        previous.putAll(currentByKey);

        syncRequirementsIfNeeded(environment, bucket);
    }

    // installRequirements re-checks on every DAG-sync pass (rather than only at CreateEnvironment
    // time) so that updating requirements.txt in S3 after creation takes effect automatically,
    // matching how DAG file changes are already picked up — at the cost of re-running `pip install`
    // on every pass where the file's ETag changed, which is the same cadence as DAG changes.
    private void syncRequirementsIfNeeded(Environment environment, String bucket) {
        if (!config.services().mwaa().installRequirements() || environment.getRequirementsS3Path() == null) {
            return;
        }
        try {
            S3Object requirements = s3Service.getObject(bucket, environment.getRequirementsS3Path());
            String lastEtag = requirementsEtagByEnvironment.get(environment.getName());
            if (!requirements.getETag().equals(lastEtag)) {
                environmentManager.installRequirements(environment, requirements.getData());
                requirementsEtagByEnvironment.put(environment.getName(), requirements.getETag());
            }
        } catch (Exception e) {
            LOG.warnv("Could not sync requirements.txt for environment {0}: {1}",
                    environment.getName(), e.getMessage());
        }
    }

    // Unlike DAG/requirements sync (which run periodically post-creation and must never fail the
    // environment over one bad object), the startup script only ever runs once, at container
    // creation — a missing/unreadable object here is a genuine configuration error, so it
    // propagates and fails CreateEnvironment, matching real MWAA gating environment creation on
    // a startup-script problem.
    private byte[] fetchStartupScript(Environment environment) {
        if (environment.getStartupScriptS3Path() == null) {
            return null;
        }
        if (environment.getSourceBucketArn() == null) {
            throw new AwsException("ValidationException",
                    "StartupScriptS3Path requires SourceBucketArn to be set", 400);
        }
        String bucket = bucketNameFromArn(environment.getSourceBucketArn());
        return s3Service.getObject(bucket, environment.getStartupScriptS3Path()).getData();
    }

    private static String bucketNameFromArn(String sourceBucketArn) {
        int idx = sourceBucketArn.lastIndexOf(':');
        return idx >= 0 ? sourceBucketArn.substring(idx + 1) : sourceBucketArn;
    }

    private List<Environment> allEnvironments() {
        if (storage instanceof AccountAwareStorageBackend<Environment> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private void putEnvironment(Environment environment) {
        if (environment.getAccountId() != null && storage instanceof AccountAwareStorageBackend<Environment> aware) {
            aware.putForAccount(environment.getAccountId(), environment.getName(), environment);
        } else {
            storage.put(environment.getName(), environment);
        }
    }
}
