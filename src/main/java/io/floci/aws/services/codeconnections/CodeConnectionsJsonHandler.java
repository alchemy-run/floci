package io.floci.aws.services.codeconnections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * AWS CodeConnections, under the {@code com.amazonaws.codeconnections.CodeConnections_20231201.} target, over JSON
 * 1.0 and Smithy RPC v2 CBOR.
 */
@ApplicationScoped
public class CodeConnectionsJsonHandler {
    private final CodeConnectionsService service;
    private final ObjectMapper mapper;

    @Inject
    public CodeConnectionsJsonHandler(CodeConnectionsService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            Object body = switch (action) {
                case "CreateConnection" -> service.createConnection(request, region);
                case "CreateHost" -> service.createHost(request, region);
                case "CreateRepositoryLink" -> service.createLink(request, region);
                case "CreateSyncConfiguration" -> service.createSync(request, region);
                case "DeleteConnection" -> empty(() -> service.deleteConnection(request, region));
                case "DeleteHost" -> empty(() -> service.deleteHost(request, region));
                case "DeleteRepositoryLink" -> empty(() -> service.deleteLink(request, region));
                case "DeleteSyncConfiguration" -> empty(() -> service.deleteSync(request, region));
                case "GetConnection" -> service.getConnection(request, region);
                case "GetHost" -> service.getHost(request, region);
                case "GetRepositoryLink" -> service.getLink(request, region);
                case "GetRepositorySyncStatus" -> service.repositorySyncStatus(request, region);
                case "GetResourceSyncStatus" -> service.resourceSyncStatus(request, region);
                case "GetSyncBlockerSummary" -> service.blockerSummary(request, region);
                case "GetSyncConfiguration" -> service.getSync(request, region);
                case "ListConnections" -> service.listConnections(request, region);
                case "ListHosts" -> service.listHosts(request, region);
                case "ListRepositoryLinks" -> service.listLinks(request, region);
                case "ListRepositorySyncDefinitions" -> service.listDefinitions(request, region);
                case "ListSyncConfigurations" -> service.listSyncs(request, region);
                case "ListTagsForResource" -> service.listTags(request, region);
                case "TagResource" -> empty(() -> service.tag(request, region));
                case "UntagResource" -> empty(() -> service.untag(request, region));
                case "UpdateHost" -> empty(() -> service.updateHost(request, region));
                case "UpdateRepositoryLink" -> service.updateLink(request, region);
                case "UpdateSyncBlocker" -> service.updateBlocker(request, region);
                case "UpdateSyncConfiguration" -> service.updateSync(request, region);
                default -> null;
            };
            if (body == null) {
                return Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                        "Operation " + action + " is not supported.")).build();
            }
            return Response.ok(body).build();
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Object empty(Runnable action) {
        action.run();
        return mapper.createObjectNode();
    }
}
