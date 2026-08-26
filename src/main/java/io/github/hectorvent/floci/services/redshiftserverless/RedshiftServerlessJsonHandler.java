package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Amazon Redshift Serverless. Dispatched from
 * {@code AwsJson11Controller} under the {@code RedshiftServerless.} target prefix.
 */
@ApplicationScoped
public class RedshiftServerlessJsonHandler { // snapshots

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessJsonHandler.class);
    static final String TARGET_PREFIX = "RedshiftServerless.";

    private final RedshiftServerlessService service;
    private final ObjectMapper objectMapper;

    @Inject
    public RedshiftServerlessJsonHandler(RedshiftServerlessService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Redshift Serverless action: {0}", action);
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? objectMapper.createObjectNode()
                : request;
        try {
            return switch (action) {
                case "CreateNamespace" -> ok(service.createNamespace(body, region));
                case "GetNamespace" -> ok(service.getNamespace(body));
                case "UpdateNamespace" -> ok(service.updateNamespace(body));
                case "DeleteNamespace" -> ok(service.deleteNamespace(body));
                case "ListNamespaces" -> ok(service.listNamespaces());
                case "CreateWorkgroup" -> ok(service.createWorkgroup(body, region));
                case "GetWorkgroup" -> ok(service.getWorkgroup(body));
                case "UpdateWorkgroup" -> ok(service.updateWorkgroup(body));
                case "DeleteWorkgroup" -> ok(service.deleteWorkgroup(body));
                case "ListWorkgroups" -> ok(service.listWorkgroups());
                case "GetCredentials" -> ok(service.getCredentials(body, null));
                case "CreateSnapshot" -> ok(service.createSnapshot(body, region));
                case "GetSnapshot" -> ok(service.getSnapshot(body));
                case "ListSnapshots" -> ok(service.listSnapshots(body));
                case "UpdateSnapshot" -> ok(service.updateSnapshot(body));
                case "DeleteSnapshot" -> ok(service.deleteSnapshot(body));
                case "ListRecoveryPoints" -> ok(service.listRecoveryPoints());
                case "ListTableRestoreStatus" -> ok(service.listTableRestoreStatus());
                case "ListTagsForResource" -> ok(service.listTagsForResource(body));
                case "TagResource" -> ok(service.tagResource(body));
                case "UntagResource" -> ok(service.untagResource(body));
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse(TARGET_PREFIX + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
