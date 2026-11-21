package io.floci.aws.services.opensearchserverless;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.SigV4CredentialScope;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.Optional;

/**
 * A collection's endpoint. Requests signed for {@code aoss} reach the collection's OpenSearch once the collection's
 * network policies reach it and its data access policies grant the caller the permission the request needs: reading
 * documents, writing them, or creating, describing, updating or deleting indexes.
 */
@Path(AossDataPlane.PREFIX)
@Consumes(MediaType.WILDCARD)
public class AossDataPlane {

    static final String PREFIX = "/_aoss-data";
    static final String DNS_SUFFIX = "localhost.floci.io";

    private final AossService service;

    @Inject
    public AossDataPlane(AossService service) {
        this.service = service;
    }

    @GET
    @Path("{region}/{id}{path: (/.*)?}")
    public Response get(@PathParam("region") String region, @PathParam("id") String id,
                        @PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers) {
        return handle("GET", region, id, path, uri, headers, null);
    }

    @HEAD
    @Path("{region}/{id}{path: (/.*)?}")
    public Response head(@PathParam("region") String region, @PathParam("id") String id,
                         @PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers) {
        return handle("HEAD", region, id, path, uri, headers, null);
    }

    @POST
    @Path("{region}/{id}{path: (/.*)?}")
    public Response post(@PathParam("region") String region, @PathParam("id") String id,
                         @PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers,
                         byte[] body) {
        return handle("POST", region, id, path, uri, headers, body);
    }

    @PUT
    @Path("{region}/{id}{path: (/.*)?}")
    public Response put(@PathParam("region") String region, @PathParam("id") String id,
                        @PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers,
                        byte[] body) {
        return handle("PUT", region, id, path, uri, headers, body);
    }

    @DELETE
    @Path("{region}/{id}{path: (/.*)?}")
    public Response delete(@PathParam("region") String region, @PathParam("id") String id,
                           @PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers) {
        return handle("DELETE", region, id, path, uri, headers, null);
    }

    private static Response error(int status, String type, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).header("x-amzn-ErrorType", type)
                .entity("{\"status\":" + status + ",\"error\":{\"type\":\"" + type + "\",\"reason\":\""
                        + message.replace("\"", "'") + "\"}}").build();
    }

    /** The permission a request needs, and the index resource it needs it on. */
    static String[] permission(String method, String path, String collection) {
        String trimmed = path == null ? "" : path.replaceAll("^/+", "");
        String[] segments = trimmed.isEmpty() ? new String[0] : trimmed.split("/");
        String first = segments.length > 0 ? segments[0] : "";
        String action = segments.length > 1 ? segments[1] : "";
        if (first.isEmpty() || first.startsWith("_")) {
            String all = "index/" + collection + "/*";
            return switch (first) {
                case "_bulk" -> new String[] {"aoss:WriteDocument", all};
                case "_search", "_msearch", "_mget", "_count" -> new String[] {"aoss:ReadDocument", all};
                default -> new String[] {"aoss:DescribeIndex", all};
            };
        }
        String index = "index/" + collection + "/" + first;
        if (action.isEmpty()) {
            return switch (method) {
                case "PUT" -> new String[] {"aoss:CreateIndex", index};
                case "DELETE" -> new String[] {"aoss:DeleteIndex", index};
                default -> new String[] {"aoss:DescribeIndex", index};
            };
        }
        return switch (action) {
            case "_mapping", "_mappings", "_settings" -> new String[] {"GET".equals(method) || "HEAD".equals(method)
                    ? "aoss:DescribeIndex" : "aoss:UpdateIndex", index};
            case "_search", "_msearch", "_count", "_mget", "_explain", "_validate" ->
                    new String[] {"aoss:ReadDocument", index};
            case "_doc", "_source", "_create", "_update", "_bulk", "_delete_by_query", "_update_by_query" ->
                    new String[] {"GET".equals(method) || "HEAD".equals(method) ? "aoss:ReadDocument"
                            : "aoss:WriteDocument", index};
            default -> new String[] {"aoss:DescribeIndex", index};
        };
    }

    private Response handle(String method, String region, String id, String path, UriInfo uri, HttpHeaders headers,
                            byte[] body) {
        boolean signed = SigV4CredentialScope.serviceName(headers.getHeaderString("Authorization"))
                .map(AossStore.SERVICE::equals).orElse(false);
        if (!signed) {
            return error(403, "security_exception", "Requests to a collection must be signed with SigV4 for aoss.");
        }
        Optional<AccountAwareStorageBackend.AccountEntry<ObjectNode>> found = service.collectionAnywhere(region, id);
        if (found.isEmpty()) {
            return error(404, "resource_not_found_exception", "Collection " + id + " is not found.");
        }
        ObjectNode collection = found.get().value();
        if (!"ACTIVE".equals(collection.path("status").asText())) {
            return error(503, "unavailable", "Collection " + collection.path("name").asText() + " is "
                    + collection.path("status").asText() + ".");
        }
        String owner = found.get().accountId();
        String[] needed = permission(method, path, collection.path("name").asText());
        try {
            return RequestScopes.callAs(owner, region, () -> {
                service.authorize(region, collection, "index", needed[1], needed[0]);
                AossEngine.Backend backend = service.engine().backend(owner, region, id);
                if (backend == null) {
                    backend = service.engine().start(owner, region, id);
                }
                String query = uri.getRequestUri().getRawQuery();
                AossEngine.Answer answer = service.engine().send(backend, method, (path == null || path.isEmpty()
                        ? "/" : path) + (query == null ? "" : "?" + query), headers.getHeaderString("Content-Type"),
                        body);
                Response.ResponseBuilder out = Response.status(answer.status()).type(answer.contentType());
                return "HEAD".equals(method) ? out.build() : out.entity(answer.body()).build();
            });
        } catch (AwsException e) {
            return error(e.getHttpStatus(), "security_exception", e.getMessage());
        } catch (IllegalStateException e) {
            return error(503, "unavailable", e.getMessage());
        }
    }
}
