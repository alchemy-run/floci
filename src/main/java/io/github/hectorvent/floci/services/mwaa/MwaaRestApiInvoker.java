package io.github.hectorvent.floci.services.mwaa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.mwaa.model.Environment;
import io.github.hectorvent.floci.services.mwaa.model.EnvironmentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * InvokeRestApi: relays a call to the environment's Apache Airflow REST API
 * ({@code /api/v1{Path}}) as the environment's admin user, the way MWAA maps the
 * caller to an Airflow role, and wraps the reply in the MWAA envelope.
 *
 * <p>Floci only has an Airflow webserver to call when the environment runs real
 * containers ({@code mock=false}) on Airflow 2.x and is AVAILABLE. Otherwise no Airflow
 * response is fabricated: the call fails with {@code RestApiServerException} carrying
 * status 503, i.e. the webserver is unavailable.
 */
@ApplicationScoped
public class MwaaRestApiInvoker {

    private static final Logger LOG = Logger.getLogger(MwaaRestApiInvoker.class);
    private static final Set<String> METHODS = Set.of("GET", "PUT", "POST", "PATCH", "DELETE");
    private static final int MAX_PATH_LENGTH = 64;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Inject
    public MwaaRestApiInvoker(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    MwaaRestApiInvoker(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** Result of a relayed call: the Airflow status code and its parsed JSON body. */
    public record Result(int restApiStatusCode, Object restApiResponse) {
        public boolean isClientError() {
            return restApiStatusCode >= 400 && restApiStatusCode < 500;
        }

        public boolean isServerError() {
            return restApiStatusCode >= 500;
        }
    }

    /** Validates the request members that the MWAA model constrains. */
    public static void validate(String path, String method) {
        if (path == null || path.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'path' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + path + "' at 'path' failed to satisfy constraint: "
                            + "Member must have length less than or equal to " + MAX_PATH_LENGTH, 400);
        }
        if (method == null || !METHODS.contains(method)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + method + "' at 'method' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [GET, PUT, POST, PATCH, DELETE]", 400);
        }
    }

    public Result invoke(Environment environment, boolean mockMode, String path, String method,
                         Object queryParameters, Object body) {
        String unavailable = unavailableReason(environment, mockMode);
        if (unavailable != null) {
            return webserverUnavailable(unavailable);
        }
        URI uri;
        try {
            uri = URI.create("http://" + environment.getAirflowInternalHost() + ":"
                    + environment.getAirflowInternalPort() + "/api/v1" + (path.startsWith("/") ? "" : "/") + path
                    + queryString(queryParameters));
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + path + "' at 'path' is not a valid URI path", 400);
        }
        String credentials = MwaaEnvironmentManager.AIRFLOW_ADMIN_USER + ":" + environment.getAirflowAdminPassword();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        try {
            if (body != null) {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode(), parseBody(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return webserverUnavailable("the request to the Airflow webserver was interrupted");
        } catch (Exception e) {
            LOG.warnv("InvokeRestApi relay to MWAA environment {0} failed: {1}", environment.getName(), e.getMessage());
            return webserverUnavailable("the Airflow webserver did not respond");
        }
    }

    static String unavailableReason(Environment environment, boolean mockMode) {
        if (mockMode) {
            return "Floci runs this environment without an Airflow webserver (mock mode)";
        }
        if (environment.getStatus() != EnvironmentStatus.AVAILABLE) {
            return "the environment is " + environment.getStatus();
        }
        if (environment.getAirflowInternalHost() == null || environment.getAirflowAdminPassword() == null) {
            return "the Airflow webserver is not reachable";
        }
        String version = environment.getAirflowVersion();
        if (version != null && !version.startsWith("2.")) {
            return "Floci relays InvokeRestApi to the Airflow 2.x REST API only";
        }
        return null;
    }

    private static Result webserverUnavailable(String reason) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", "Service Unavailable");
        problem.put("status", 503);
        problem.put("detail", "The Airflow webserver is unavailable: " + reason + ".");
        return new Result(503, problem);
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return objectMapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            return body;
        }
    }

    static String queryString(Object queryParameters) {
        if (!(queryParameters instanceof Map<?, ?> params) || params.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        params.forEach((key, value) -> {
            String encodedKey = URLEncoder.encode(String.valueOf(key), StandardCharsets.UTF_8);
            if (value instanceof List<?> values) {
                for (Object v : values) {
                    pairs.add(encodedKey + "=" + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8));
                }
            } else if (value != null) {
                pairs.add(encodedKey + "=" + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
            }
        });
        return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
    }
}
