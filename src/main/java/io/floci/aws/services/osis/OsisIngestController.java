package io.floci.aws.services.osis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.SigV4CredentialScope;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * A pipeline's ingest endpoint. Requests signed for {@code osis} are forwarded to the pipeline's push source that
 * takes their path, on the pipeline's Data Prepper; the source's answer is the endpoint's.
 */
@Path(OsisIngestController.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class OsisIngestController {

    static final String PREFIX = "/_osis-ingest";

    private final OsisService service;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    public OsisIngestController(OsisService service) {
        this.service = service;
    }

    private static Response error(int status, String type, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .header("x-amzn-ErrorType", type)
                .entity("{\"message\":\"" + message.replace("\"", "'") + "\"}").build();
    }

    @POST
    @Path("{region}/{label}/{path: .*}")
    public Response ingest(@PathParam("region") String region, @PathParam("label") String label,
                           @PathParam("path") String path, @Context HttpHeaders headers, byte[] body) {
        boolean signed = SigV4CredentialScope.serviceName(headers.getHeaderString("Authorization"))
                .map(OsisStore.SERVICE::equals).orElse(false);
        if (!signed) {
            return error(403, "AccessDeniedException", "Requests to an ingest endpoint must be signed with "
                    + "SigV4 for the osis service.");
        }
        Optional<ObjectNode> found = service.ingestTarget(region, label);
        if (found.isEmpty()) {
            return error(404, "ResourceNotFoundException", "No pipeline answers on this endpoint.");
        }
        ObjectNode pipeline = found.get();
        String status = pipeline.path("Status").asText();
        if (!"ACTIVE".equals(status) && !"UPDATING".equals(status)) {
            return error(503, "ServiceUnavailableException", "Pipeline " + pipeline.path("PipelineName").asText()
                    + " is " + status + " and can't ingest data.");
        }
        String target = "/" + path;
        Integer port = null;
        for (JsonNode source : OsisStore.meta(pipeline).path("sources")) {
            if (target.equals(source.path("path").asText())) {
                port = source.path("port").asInt();
            }
        }
        if (port == null) {
            return error(404, "ResourceNotFoundException", "Pipeline " + pipeline.path("PipelineName").asText()
                    + " has no source on " + target + ".");
        }
        ObjectNode meta = OsisStore.meta(pipeline);
        OsisEngine.Backend backend = service.engine().backend(meta.path("account").asText(),
                meta.path("region").asText(), pipeline.path("PipelineName").asText());
        EndpointInfo endpoint = backend == null ? null : backend.sources().get(port);
        if (endpoint == null) {
            return error(503, "ServiceUnavailableException", "Pipeline " + pipeline.path("PipelineName").asText()
                    + " isn't running.");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + endpoint.host() + ":"
                + endpoint.port() + target)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        for (String header : new String[] {"Content-Type", "Content-Encoding"}) {
            String value = headers.getHeaderString(header);
            if (value != null) {
                request.header(header, value);
            }
        }
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            Response.ResponseBuilder out = Response.status(response.statusCode()).entity(response.body());
            response.headers().firstValue("Content-Type").ifPresent(out::type);
            return out.build();
        } catch (IOException e) {
            return error(503, "ServiceUnavailableException", "The pipeline's source didn't answer: "
                    + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(503, "ServiceUnavailableException", "The request was interrupted.");
        }
    }
}
