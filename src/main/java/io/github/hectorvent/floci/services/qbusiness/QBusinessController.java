package io.github.hectorvent.floci.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.qbusiness.model.Application;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.function.Supplier;

/**
 * Amazon Q Business restJson1 (service {@code qbusiness}).
 *
 * <p>{@link QBusinessRoutingFilter} prefixes SigV4-{@code qbusiness} traffic onto
 * {@code /aws-qbusiness} so {@code /applications} does not collide with AppConfig.
 */
@Path(QBusinessRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QBusinessController {

    private final QBusinessService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public QBusinessController(
            QBusinessService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/applications")
    public Response createApplication(@Context HttpHeaders headers, String body) {
        return run(() -> {
            Application application = service.createApplication(region(headers), parse(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("applicationId", application.getApplicationId());
            response.put("applicationArn", application.getApplicationArn());
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/applications")
    @Consumes(MediaType.WILDCARD)
    public Response listApplications(@Context HttpHeaders headers) {
        return run(() -> {
            ObjectNode response = objectMapper.createObjectNode();
            var list = response.putArray("applications");
            for (Application application : service.listApplications(region(headers))) {
                list.add(toApplication(application));
            }
            return Response.ok(response).build();
        });
    }

    @GET
    @Path("/applications/{applicationId}")
    @Consumes(MediaType.WILDCARD)
    public Response getApplication(
            @Context HttpHeaders headers, @PathParam("applicationId") String applicationId) {
        return run(() -> Response.ok(toApplication(service.getApplication(region(headers), applicationId))).build());
    }

    @PUT
    @Path("/applications/{applicationId}")
    public Response updateApplication(
            @Context HttpHeaders headers, @PathParam("applicationId") String applicationId, String body) {
        return run(() -> {
            service.updateApplication(region(headers), applicationId, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @DELETE
    @Path("/applications/{applicationId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteApplication(
            @Context HttpHeaders headers, @PathParam("applicationId") String applicationId) {
        return run(() -> {
            service.deleteApplication(region(headers), applicationId);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/applications/{applicationId}/conversations")
    public Response chatSync(
            @PathParam("applicationId") String applicationId,
            @QueryParam("sync") String sync,
            String body) {
        return run(() -> Response.ok(service.chatSync(applicationId, parse(body))).build());
    }

    @GET
    @Path("/applications/{applicationId}/conversations")
    @Consumes(MediaType.WILDCARD)
    public Response listConversations(@PathParam("applicationId") String applicationId) {
        return run(() -> Response.ok(service.listConversations(applicationId)).build());
    }

    @DELETE
    @Path("/applications/{applicationId}/conversations/{conversationId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteConversation(
            @PathParam("applicationId") String applicationId,
            @PathParam("conversationId") String conversationId) {
        return run(() -> Response.ok(service.deleteConversation(applicationId, conversationId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/conversations/{conversationId}")
    @Consumes(MediaType.WILDCARD)
    public Response listMessages(
            @PathParam("applicationId") String applicationId,
            @PathParam("conversationId") String conversationId) {
        return run(() -> Response.ok(service.listMessages(applicationId, conversationId)).build());
    }

    @POST
    @Path("/applications/{applicationId}/conversations/{conversationId}/messages/{messageId}/feedback")
    public Response putFeedback(
            @PathParam("applicationId") String applicationId,
            @PathParam("conversationId") String conversationId,
            @PathParam("messageId") String messageId,
            String body) {
        return run(() -> Response.ok(service.putFeedback(applicationId, conversationId, messageId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/conversations/{conversationId}/messages/{messageId}/media/{mediaId}")
    @Consumes(MediaType.WILDCARD)
    public Response getMedia(
            @PathParam("applicationId") String applicationId,
            @PathParam("conversationId") String conversationId,
            @PathParam("messageId") String messageId,
            @PathParam("mediaId") String mediaId) {
        return run(() -> Response.ok(
                service.getMedia(applicationId, conversationId, messageId, mediaId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/attachments")
    @Consumes(MediaType.WILDCARD)
    public Response listAttachments(@PathParam("applicationId") String applicationId) {
        return run(() -> Response.ok(service.listAttachments(applicationId)).build());
    }

    @DELETE
    @Path("/applications/{applicationId}/conversations/{conversationId}/attachments/{attachmentId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAttachment(
            @PathParam("applicationId") String applicationId,
            @PathParam("conversationId") String conversationId,
            @PathParam("attachmentId") String attachmentId) {
        return run(() -> Response.ok(
                service.deleteAttachment(applicationId, conversationId, attachmentId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/chatcontrols")
    @Consumes(MediaType.WILDCARD)
    public Response getChatControls(@PathParam("applicationId") String applicationId) {
        return run(() -> Response.ok(service.getChatControls(applicationId)).build());
    }

    @PATCH
    @Path("/applications/{applicationId}/chatcontrols")
    public Response updateChatControls(@PathParam("applicationId") String applicationId, String body) {
        return run(() -> Response.ok(service.updateChatControls(applicationId, parse(body))).build());
    }

    @DELETE
    @Path("/applications/{applicationId}/chatcontrols")
    @Consumes(MediaType.WILDCARD)
    public Response deleteChatControls(@PathParam("applicationId") String applicationId) {
        return run(() -> Response.ok(service.deleteChatControls(applicationId)).build());
    }

    @POST
    @Path("/applications/{applicationId}/relevant-content")
    public Response searchRelevantContent(@PathParam("applicationId") String applicationId, String body) {
        return run(() -> Response.ok(service.searchRelevantContent(applicationId, parse(body))).build());
    }

    @POST
    @Path("/applications/{applicationId}/users")
    public Response createUser(@PathParam("applicationId") String applicationId, String body) {
        return run(() -> Response.ok(service.createUser(applicationId, parse(body))).build());
    }

    @GET
    @Path("/applications/{applicationId}/users/{userId}")
    @Consumes(MediaType.WILDCARD)
    public Response getUser(
            @PathParam("applicationId") String applicationId, @PathParam("userId") String userId) {
        return run(() -> Response.ok(service.getUser(applicationId, userId)).build());
    }

    @PUT
    @Path("/applications/{applicationId}/users/{userId}")
    public Response updateUser(
            @PathParam("applicationId") String applicationId,
            @PathParam("userId") String userId,
            String body) {
        return run(() -> Response.ok(service.updateUser(applicationId, userId, parse(body))).build());
    }

    @DELETE
    @Path("/applications/{applicationId}/users/{userId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteUser(
            @PathParam("applicationId") String applicationId, @PathParam("userId") String userId) {
        return run(() -> Response.ok(service.deleteUser(applicationId, userId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/policy")
    @Consumes(MediaType.WILDCARD)
    public Response getPolicy(@PathParam("applicationId") String applicationId) {
        return run(() -> Response.ok(service.getPolicy(applicationId)).build());
    }

    @POST
    @Path("/applications/{applicationId}/policy")
    public Response associatePermission(@PathParam("applicationId") String applicationId, String body) {
        return run(() -> Response.ok(service.associatePermission(applicationId, parse(body))).build());
    }

    @DELETE
    @Path("/applications/{applicationId}/policy/{statementId}")
    @Consumes(MediaType.WILDCARD)
    public Response disassociatePermission(
            @PathParam("applicationId") String applicationId,
            @PathParam("statementId") String statementId) {
        return run(() -> Response.ok(service.disassociatePermission(applicationId, statementId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/subscriptions")
    @Consumes(MediaType.WILDCARD)
    public Response listSubscriptions(@PathParam("applicationId") String applicationId) {
        return run(() -> Response.ok(service.listSubscriptions(applicationId)).build());
    }

    @POST
    @Path("/applications/{applicationId}/subscriptions")
    public Response createSubscription(@PathParam("applicationId") String applicationId, String body) {
        return run(() -> Response.ok(service.createSubscription(applicationId, parse(body))).build());
    }

    @PUT
    @Path("/applications/{applicationId}/subscriptions/{subscriptionId}")
    public Response updateSubscription(
            @PathParam("applicationId") String applicationId,
            @PathParam("subscriptionId") String subscriptionId,
            String body) {
        return run(() -> Response.ok(
                service.updateSubscription(applicationId, subscriptionId, parse(body))).build());
    }

    @DELETE
    @Path("/applications/{applicationId}/subscriptions/{subscriptionId}")
    @Consumes(MediaType.WILDCARD)
    public Response cancelSubscription(
            @PathParam("applicationId") String applicationId,
            @PathParam("subscriptionId") String subscriptionId) {
        return run(() -> Response.ok(service.cancelSubscription(applicationId, subscriptionId)).build());
    }

    @POST
    @Path("/applications/{applicationId}/indices")
    public Response createIndex(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            String body) {
        return run(() -> Response.ok(service.createIndex(region(headers), applicationId, parse(body))).build());
    }

    @GET
    @Path("/applications/{applicationId}/indices/{indexId}")
    @Consumes(MediaType.WILDCARD)
    public Response getIndex(
            @Context HttpHeaders headers,
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId) {
        return run(() -> {
            var index = service.getIndex(region(headers), applicationId, indexId);
            ObjectNode node = objectMapper.createObjectNode();
            node.put("applicationId", applicationId);
            node.put("indexId", index.getIndexId());
            node.put("indexArn", index.getIndexArn());
            node.put("displayName", index.getDisplayName());
            node.put("status", index.getStatus());
            if (index.getDescription() != null) {
                node.put("description", index.getDescription());
            }
            if (index.getCreatedAt() != null) {
                node.put("createdAt", index.getCreatedAt());
            }
            if (index.getUpdatedAt() != null) {
                node.put("updatedAt", index.getUpdatedAt());
            }
            return Response.ok(node).build();
        });
    }

    @POST
    @Path("/applications/{applicationId}/indices/{indexId}/documents")
    public Response batchPutDocument(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            String body) {
        return run(() -> Response.ok(service.batchPutDocument(applicationId, indexId, parse(body))).build());
    }

    @POST
    @Path("/applications/{applicationId}/indices/{indexId}/documents/delete")
    public Response batchDeleteDocument(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            String body) {
        return run(() -> Response.ok(service.batchDeleteDocument(applicationId, indexId, parse(body))).build());
    }

    @GET
    @Path("/applications/{applicationId}/index/{indexId}/documents")
    @Consumes(MediaType.WILDCARD)
    public Response listDocuments(
            @PathParam("applicationId") String applicationId, @PathParam("indexId") String indexId) {
        return run(() -> Response.ok(service.listDocuments(applicationId, indexId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/index/{indexId}/documents/{documentId}/content")
    @Consumes(MediaType.WILDCARD)
    public Response getDocumentContent(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            @PathParam("documentId") String documentId) {
        return run(() -> Response.ok(service.getDocumentContent(applicationId, indexId, documentId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/index/{indexId}/users/{userId}/documents/{documentId}/check-document-access")
    @Consumes(MediaType.WILDCARD)
    public Response checkDocumentAccess(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            @PathParam("userId") String userId,
            @PathParam("documentId") String documentId) {
        return run(() -> Response.ok(
                service.checkDocumentAccess(applicationId, indexId, userId, documentId)).build());
    }

    @POST
    @Path("/applications/{applicationId}/indices/{indexId}/datasources/{dataSourceId}/startsync")
    @Consumes(MediaType.WILDCARD)
    public Response startDataSourceSyncJob(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        return run(() -> Response.ok(
                service.startDataSourceSyncJob(applicationId, indexId, dataSourceId)).build());
    }

    @POST
    @Path("/applications/{applicationId}/indices/{indexId}/datasources/{dataSourceId}/stopsync")
    @Consumes(MediaType.WILDCARD)
    public Response stopDataSourceSyncJob(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            @PathParam("dataSourceId") String dataSourceId,
            String body) {
        return run(() -> Response.ok(
                service.stopDataSourceSyncJob(applicationId, indexId, dataSourceId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/indices/{indexId}/datasources/{dataSourceId}/syncjobs")
    @Consumes(MediaType.WILDCARD)
    public Response listDataSourceSyncJobs(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            @PathParam("dataSourceId") String dataSourceId) {
        return run(() -> Response.ok(
                service.listDataSourceSyncJobs(applicationId, indexId, dataSourceId)).build());
    }

    @PUT
    @Path("/applications/{applicationId}/indices/{indexId}/groups")
    public Response putGroup(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            String body) {
        return run(() -> Response.ok(service.putGroup(applicationId, indexId, parse(body))).build());
    }

    @GET
    @Path("/applications/{applicationId}/indices/{indexId}/groups")
    @Consumes(MediaType.WILDCARD)
    public Response listGroups(
            @PathParam("applicationId") String applicationId, @PathParam("indexId") String indexId) {
        return run(() -> Response.ok(service.listGroups(applicationId, indexId)).build());
    }

    @GET
    @Path("/applications/{applicationId}/indices/{indexId}/groups/{groupName}")
    @Consumes(MediaType.WILDCARD)
    public Response getGroup(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            @PathParam("groupName") String groupName) {
        return run(() -> Response.ok(service.getGroup(applicationId, indexId, groupName)).build());
    }

    @DELETE
    @Path("/applications/{applicationId}/indices/{indexId}/groups/{groupName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteGroup(
            @PathParam("applicationId") String applicationId,
            @PathParam("indexId") String indexId,
            @PathParam("groupName") String groupName) {
        return run(() -> Response.ok(service.deleteGroup(applicationId, indexId, groupName)).build());
    }

    @POST
    @Path("/applications/{applicationId}/experiences/{webExperienceId}/anonymous-url")
    public Response createAnonymousWebExperienceUrl(
            @PathParam("applicationId") String applicationId,
            @PathParam("webExperienceId") String webExperienceId,
            String body) {
        return run(() -> Response.ok(
                service.createAnonymousWebExperienceUrl(applicationId, webExperienceId)).build());
    }

    private ObjectNode toApplication(Application application) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("applicationId", application.getApplicationId());
        node.put("applicationArn", application.getApplicationArn());
        node.put("displayName", application.getDisplayName());
        node.put("identityType", application.getIdentityType());
        node.put("status", application.getStatus());
        if (application.getDescription() != null) {
            node.put("description", application.getDescription());
        }
        if (application.getRoleArn() != null) {
            node.put("roleArn", application.getRoleArn());
        }
        if (application.getIdentityCenterInstanceArn() != null) {
            node.put("identityCenterApplicationArn", application.getIdentityCenterInstanceArn());
        }
        if (application.getIamIdentityProviderArn() != null) {
            node.put("iamIdentityProviderArn", application.getIamIdentityProviderArn());
        }
        if (application.getCreatedAt() != null) {
            node.put("createdAt", application.getCreatedAt());
        }
        if (application.getUpdatedAt() != null) {
            node.put("updatedAt", application.getUpdatedAt());
        }
        if (application.getErrorMessage() != null) {
            ObjectNode error = node.putObject("error");
            error.put("errorMessage", application.getErrorMessage());
            if (application.getErrorCode() != null) {
                error.put("errorCode", application.getErrorCode());
            }
        }
        return node;
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response run(Supplier<Response> action) {
        try {
            return action.get();
        } catch (AwsException e) {
            return error(e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private Response error(AwsException exception) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("__type", exception.jsonType());
        node.put("message", exception.getMessage());
        if (exception.getExtendedData() != null) {
            exception.getExtendedData().forEach((k, v) -> node.set(k, objectMapper.valueToTree(v)));
        }
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(node)
                .build();
    }
}
