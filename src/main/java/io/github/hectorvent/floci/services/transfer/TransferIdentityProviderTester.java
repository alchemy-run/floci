package io.github.hectorvent.floci.services.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.transfer.model.Server;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code TestIdentityProvider}: authenticates a user against a server's custom identity provider
 * exactly as a login would, and reports the provider's raw answer.
 *
 * <ul>
 *   <li>{@code AWS_LAMBDA}, invokes the configured function through Floci's Lambda runtime with
 *       the Transfer Family identity-provider event.</li>
 *   <li>{@code API_GATEWAY}, calls {@code GET {Url}/servers/{serverId}/users/{username}/config}
 *       with the {@code Password} header and {@code protocol}/{@code sourceIp} query parameters;
 *       AWS-style {@code execute-api} hosts are routed to Floci's path-style API Gateway plane.</li>
 *   <li>{@code SERVICE_MANAGED}, rejected with {@code InvalidRequestException}, as in AWS.</li>
 * </ul>
 */
@ApplicationScoped
public class TransferIdentityProviderTester {

    private static final Pattern SERVER_ID = Pattern.compile("s-([0-9a-f]{17})");
    private static final Pattern USER_NAME = Pattern.compile("[\\w][\\w@.-]{2,99}");
    private static final Pattern SOURCE_IP = Pattern.compile("[0-9a-fA-F.:]+");
    private static final List<String> PROTOCOLS = List.of("SFTP", "FTP", "FTPS", "AS2");
    private static final Pattern AWS_EXECUTE_API_HOST = Pattern.compile(
            "(?i)([a-z0-9]+)\\.execute-api\\.[a-z0-9-]+\\.amazonaws\\.com");
    private static final Duration IDP_TIMEOUT = Duration.ofSeconds(30);

    private final TransferService transferService;
    private final LambdaService lambdaService;
    private final ObjectMapper objectMapper;
    private final int gatewayPort;
    private final HttpClient httpClient;

    @Inject
    public TransferIdentityProviderTester(TransferService transferService, LambdaService lambdaService,
                                          EmulatorConfig config, ObjectMapper objectMapper) {
        this(transferService, lambdaService, objectMapper, config.port(),
                HttpClient.newBuilder().connectTimeout(IDP_TIMEOUT).build());
    }

    TransferIdentityProviderTester(TransferService transferService, LambdaService lambdaService,
                                   ObjectMapper objectMapper, int gatewayPort, HttpClient httpClient) {
        this.transferService = transferService;
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper;
        this.gatewayPort = gatewayPort;
        this.httpClient = httpClient;
    }

    public ObjectNode testIdentityProvider(String serverId, String serverProtocol, String sourceIp,
                                           String userName, String userPassword, String region) {
        new TransferRequestValidator()
                .required("serverId", serverId)
                .length("serverId", serverId, 19, 19, false)
                .pattern("serverId", serverId, SERVER_ID, "^s-([0-9a-f]{17})$")
                .required("userName", userName)
                .length("userName", userName, 3, 100, false)
                .pattern("userName", userName, USER_NAME, "^[\\w][\\w@.-]{2,99}$")
                .oneOf("serverProtocol", serverProtocol, PROTOCOLS)
                .length("sourceIp", sourceIp, 0, 32, false)
                .pattern("sourceIp", sourceIp, SOURCE_IP, "^[0-9a-fA-F\\.\\:]+$")
                .length("userPassword", userPassword, 0, 1024, true)
                .validate();

        Server server = transferService.getServer(serverId);
        String protocol = serverProtocol != null ? serverProtocol : "SFTP";
        String type = server.getIdentityProviderType();
        return switch (type == null ? "SERVICE_MANAGED" : type) {
            case "AWS_LAMBDA" -> testLambda(server, protocol, sourceIp, userName, userPassword, region);
            case "API_GATEWAY" -> testApiGateway(server, protocol, sourceIp, userName, userPassword);
            case "SERVICE_MANAGED" -> throw new AwsException("InvalidRequestException",
                    "TestIdentityProvider is not supported for servers with the SERVICE_MANAGED "
                            + "identity provider type.", 400);
            default -> throw new AwsException("NotImplementedException",
                    "TestIdentityProvider for the " + type + " identity provider type is not "
                            + "implemented by Floci.", 501);
        };
    }

    private ObjectNode testLambda(Server server, String protocol, String sourceIp, String userName,
                                  String userPassword, String region) {
        String function = detail(server, "Function");
        if (function == null) {
            throw new AwsException("InvalidRequestException",
                    "Server " + server.getServerId() + " has no identity provider Function configured.", 400);
        }
        Map<String, String> event = new LinkedHashMap<>();
        event.put("username", userName);
        if (userPassword != null) {
            event.put("password", userPassword);
        }
        event.put("protocol", protocol);
        event.put("serverId", server.getServerId());
        if (sourceIp != null) {
            event.put("sourceIp", sourceIp);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Url", function);
        try {
            InvokeResult result = lambdaService.invoke(region, function,
                    objectMapper.writeValueAsBytes(event), InvocationType.RequestResponse);
            String payload = result.getPayload() == null
                    ? "" : new String(result.getPayload(), StandardCharsets.UTF_8);
            response.put("StatusCode", result.getStatusCode());
            response.put("Response", payload);
            response.put("Message", result.getFunctionError() == null
                    ? "" : "Lambda function error (" + result.getFunctionError() + "): " + payload);
        } catch (AwsException e) {
            response.put("StatusCode", e.getHttpStatus());
            response.put("Message", "Unable to call identity provider: " + e.getMessage());
        } catch (IOException e) {
            response.put("StatusCode", 500);
            response.put("Message", "Unable to call identity provider: " + e.getMessage());
        }
        return response;
    }

    private ObjectNode testApiGateway(Server server, String protocol, String sourceIp, String userName,
                                      String userPassword) {
        String baseUrl = detail(server, "Url");
        if (baseUrl == null) {
            throw new AwsException("InvalidRequestException",
                    "Server " + server.getServerId() + " has no identity provider Url configured.", 400);
        }
        String url = stripTrailingSlash(baseUrl) + "/servers/" + encode(server.getServerId())
                + "/users/" + encode(userName) + "/config?protocol=" + encode(protocol)
                + (sourceIp != null ? "&sourceIp=" + encode(sourceIp) : "");

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Url", url);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(resolveLocally(url)))
                    .timeout(IDP_TIMEOUT)
                    .GET();
            if (userPassword != null) {
                request.header("Password", userPassword);
            }
            HttpResponse<String> result = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.put("StatusCode", result.statusCode());
            response.put("Response", result.body());
            response.put("Message", "");
        } catch (IOException | IllegalArgumentException e) {
            response.put("StatusCode", 500);
            response.put("Message", "Unable to call identity provider: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.put("StatusCode", 500);
            response.put("Message", "Unable to call identity provider: interrupted");
        }
        return response;
    }

    /**
     * {@code https://{apiId}.execute-api.{region}.amazonaws.com/{stage}/...} is served by Floci's
     * path-style plane at {@code http://localhost:{port}/execute-api/{apiId}/{stage}/...}.
     */
    String resolveLocally(String url) {
        URI uri = URI.create(url);
        Matcher host = uri.getHost() == null ? null : AWS_EXECUTE_API_HOST.matcher(uri.getHost());
        if (host == null || !host.matches()) {
            return url;
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return "http://localhost:" + gatewayPort + "/execute-api/" + host.group(1) + path + query;
    }

    private static String detail(Server server, String key) {
        Map<String, String> details = server.getIdentityProviderDetails();
        String value = details == null ? null : details.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
