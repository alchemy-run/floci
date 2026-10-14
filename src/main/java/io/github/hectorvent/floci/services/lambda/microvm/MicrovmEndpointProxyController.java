package io.github.hectorvent.floci.services.lambda.microvm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.lambda.microvm.model.MicrovmRecord;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;

/**
 * The MicroVM endpoint data plane: proxies authenticated requests through to
 * the in-VM HTTP server (the Docker container behind the MicroVM). Reached
 * either host-style (via {@link MicrovmEndpointRoutingFilter}) or directly
 * path-style at {@code /_floci/microvm-endpoint/{microvmId}/...}.
 *
 * <p>Requests must carry the auth token minted by
 * {@code CreateMicrovmAuthToken} in the {@code X-Aws-Proxy-Auth} header — the
 * local mirror of the AWS proxy validating tokens at the MicroVM endpoint.
 */
@Path(MicrovmEndpointProxyController.BASE)
@Produces(MediaType.WILDCARD)
@Consumes(MediaType.WILDCARD)
public class MicrovmEndpointProxyController {

    static final String BASE = "/_floci/microvm-endpoint/{microvmId}";

    private static final Logger LOG = Logger.getLogger(MicrovmEndpointProxyController.class);
    private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(60);
    /** How long to retry connection refusals to a VM whose server has already answered. */
    private static final Duration CONNECT_RETRY_WINDOW = Duration.ofSeconds(5);
    /**
     * How long to wait for a freshly started VM's server to answer its first request. AWS
     * restores a MicroVM from a snapshot taken after the server was listening; Floci boots the
     * container from scratch, so the server is still starting when the VM first reports RUNNING.
     */
    static final Duration BOOT_RETRY_WINDOW = Duration.ofSeconds(30);
    private static final Duration CONNECT_RETRY_INTERVAL = Duration.ofMillis(200);

    /** Hop-by-hop / transport headers never forwarded in either direction. */
    private static final Set<String> SKIPPED_HEADERS = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "upgrade",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "x-aws-proxy-auth");

    private final MicrovmRuntimeService runtimeService;
    private final MicrovmAuthTokenService tokenService;
    // HTTP/1.1 pinned: the default (HTTP_2) attempts an h2c upgrade on
    // plaintext targets, and the Upgrade/HTTP2-Settings headers make some
    // in-VM servers (e.g. Node's http module behind effect's platform layer)
    // deliver an empty request body.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    public MicrovmEndpointProxyController(MicrovmRuntimeService runtimeService,
                                          MicrovmAuthTokenService tokenService) {
        this.runtimeService = runtimeService;
        this.tokenService = tokenService;
    }

    @GET
    @Path("{path: .*}")
    public Response get(@PathParam("microvmId") String microvmId, @PathParam("path") String path,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return forward("GET", microvmId, path, headers, uriInfo, null);
    }

    @HEAD
    @Path("{path: .*}")
    public Response head(@PathParam("microvmId") String microvmId, @PathParam("path") String path,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return forward("HEAD", microvmId, path, headers, uriInfo, null);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@PathParam("microvmId") String microvmId, @PathParam("path") String path,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return forward("POST", microvmId, path, headers, uriInfo, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@PathParam("microvmId") String microvmId, @PathParam("path") String path,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return forward("PUT", microvmId, path, headers, uriInfo, body);
    }

    @PATCH
    @Path("{path: .*}")
    public Response patch(@PathParam("microvmId") String microvmId, @PathParam("path") String path,
                          @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return forward("PATCH", microvmId, path, headers, uriInfo, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@PathParam("microvmId") String microvmId, @PathParam("path") String path,
                           @Context HttpHeaders headers, @Context UriInfo uriInfo, byte[] body) {
        return forward("DELETE", microvmId, path, headers, uriInfo, body);
    }

    private Response forward(String method, String microvmId, String path,
                             HttpHeaders headers, UriInfo uriInfo, byte[] body) {
        MicrovmRecord vm = runtimeService.findById(microvmId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "MicroVM not found: " + microvmId, 404));

        tokenService.validate(headers.getHeaderString(MicrovmAuthTokenService.HEADER),
                microvmId, vm.getPort());

        // The AWS proxy resumes an idle-suspended VM on the next request when
        // the idle policy opted in — mirror it so suspension is transparent.
        if ("SUSPENDED".equals(vm.getState()) && autoResumeEnabled(vm)) {
            LOG.infov("Auto-resuming suspended MicroVM {0} on request", microvmId);
            runtimeService.resumeMicrovm(vm);
        }

        if (!"RUNNING".equals(vm.getState())) {
            throw new AwsException("ConflictException",
                    "MicroVM " + microvmId + " is " + vm.getState(), 409);
        }
        runtimeService.touchActivity(vm);

        ContainerLifecycleManager.EndpointInfo endpoint = runtimeService.resolveVmEndpoint(vm);
        LOG.debugv("MicroVM proxy: {0} {1} vm={2} bodyBytes={3}",
                method, path, microvmId, body == null ? -1 : body.length);
        String query = uriInfo.getRequestUri().getRawQuery();
        String target = "http://" + endpoint.host() + ":" + endpoint.port()
                + "/" + (path != null ? path : "")
                + (query != null && !query.isBlank() ? "?" + query : "");

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(FORWARD_TIMEOUT)
                .method(method, body != null && body.length > 0
                        ? HttpRequest.BodyPublishers.ofByteArray(body)
                        : HttpRequest.BodyPublishers.noBody());
        headers.getRequestHeaders().forEach((name, values) -> {
            if (!SKIPPED_HEADERS.contains(name.toLowerCase())) {
                for (String value : values) {
                    try {
                        builder.header(name, value);
                    } catch (IllegalArgumentException ignored) {
                        // restricted header (e.g. Content-Length variants) — skip
                    }
                }
            }
        });

        // RunMicrovm reports RUNNING as soon as the container process starts;
        // the in-VM server may still be starting. Until it has answered once,
        // the port is either refused or (behind Docker's userland port proxy)
        // accepted and closed with no bytes, so retry both until the boot
        // window closes. After that only connection refusals are retried.
        boolean booting = !runtimeService.hasServed(vm);
        long deadline = System.nanoTime()
                + (booting ? BOOT_RETRY_WINDOW : CONNECT_RETRY_WINDOW).toNanos();
        Exception failure;
        while (true) {
            try {
                HttpResponse<byte[]> response = httpClient.send(
                        builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                runtimeService.markServed(vm);
                Response.ResponseBuilder out = Response.status(response.statusCode());
                response.headers().map().forEach((name, values) -> {
                    if (!SKIPPED_HEADERS.contains(name.toLowerCase()) && !name.startsWith(":")) {
                        values.forEach(v -> out.header(name, v));
                    }
                });
                return out.entity(response.body()).build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = e;
                break;
            } catch (Exception e) {
                failure = e;
                if (!isRetryable(e, booting) || System.nanoTime() >= deadline) {
                    break;
                }
                try {
                    Thread.sleep(CONNECT_RETRY_INTERVAL.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // Some transport exceptions (interrupts, wrapped socket errors) carry a
        // null message — never NPE while rendering the error envelope.
        String message = failure.getMessage() != null
                ? failure.getMessage()
                : failure.getClass().getSimpleName();
        LOG.warnv("MicroVM endpoint proxy failed for {0} {1}: {2}", method, target, message);
        return Response.status(502)
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"message\":\"MicroVM endpoint unreachable: "
                        + message.replace("\"", "'") + "\"}")
                .build();
    }

    /**
     * A refused or timed-out connection never reached the server, so it is always safe to retry.
     * Any other transport failure (such as a connection closed before a single response byte)
     * is retried only while the VM is booting and its server has never answered: once it has,
     * such a failure could follow a request the server already acted on.
     */
    static boolean isRetryable(Exception failure, boolean booting) {
        if (failure instanceof ConnectException || failure instanceof HttpConnectTimeoutException) {
            return true;
        }
        if (failure instanceof HttpTimeoutException) {
            return false;
        }
        return booting && failure instanceof IOException;
    }

    private static boolean autoResumeEnabled(MicrovmRecord vm) {
        return vm.getIdlePolicy() != null
                && Boolean.TRUE.equals(vm.getIdlePolicy().get("autoResumeEnabled"));
    }
}
