package io.floci.aws.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEventStreamDecoder;
import io.floci.aws.core.common.AwsEventStreamWriter;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RestJsonDispatcher;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Amazon Q Business REST API, routed here by {@link QBusinessRouteFilter} and matched to operations by its HTTP
 * bindings. {@code Chat}, whose input and output are event streams, is answered here directly.
 */
@Path(QBusinessRouteFilter.QBUSINESS)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class QBusinessController {

    private static final Pattern CHAT = Pattern.compile("/applications/([^/]+)/conversations");

    static RequestConstraintCheck shapes(String resource, String label) {
        return RequestConstraintCheck.load(resource, label, new RequestConstraintCheck.Messages() {
            @Override
            public AwsException type(String path, String type) {
                return QBusinessStore.validation(path + " must be a " + type + ".");
            }

            @Override
            public AwsException bounds(String path, String type, Long min, Long max, long size) {
                String what = switch (type) {
                    case "string" -> "length";
                    case "integer", "long", "double", "float" -> "value";
                    default -> "size";
                };
                return QBusinessStore.validation(path + " has " + what + " " + size + ", outside "
                        + (min == null ? "" : min) + ".." + (max == null ? "" : max) + ".");
            }

            @Override
            public AwsException pattern(String path, String pattern) {
                return QBusinessStore.validation(path + " must match " + pattern + ".");
            }

            @Override
            public AwsException enumValue(String path, List<String> values) {
                return QBusinessStore.validation(path + " must be one of " + String.join(", ", values) + ".");
            }

            @Override
            public AwsException required(String path) {
                return QBusinessStore.validation(path + " is required.");
            }
        });
    }

    private static final RequestConstraintCheck QBUSINESS_SHAPES = shapes("aws/qbusiness-request-shapes.json",
            "Q Business request shapes");

    private final QBusinessService service;
    private final QBusinessChat chat;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private final RestJsonDispatcher qbusiness;

    @Inject
    public QBusinessController(QBusinessService service, QBusinessChat chat, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.chat = chat;
        this.regions = regions;
        this.mapper = mapper;
        this.qbusiness = new RestJsonDispatcher(mapper, "qbusiness");
    }

    @GET
    @Path("{path: .*}")
    public Response get(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("GET", headers, uri, body);
    }

    @POST
    @Path("{path: .*}")
    public Response post(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("POST", headers, uri, body);
    }

    @PUT
    @Path("{path: .*}")
    public Response put(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PUT", headers, uri, body);
    }

    @PATCH
    @Path("{path: .*}")
    public Response patch(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("PATCH", headers, uri, body);
    }

    @DELETE
    @Path("{path: .*}")
    public Response delete(@Context HttpHeaders headers, @Context UriInfo uri, byte[] body) {
        return dispatch("DELETE", headers, uri, body);
    }

    private Response dispatch(String method, HttpHeaders headers, UriInfo uri, byte[] body) {
        String raw = uri.getRequestUri().getRawPath();
        String region = regions.resolveRegion(headers);
        String path = raw.substring(QBusinessRouteFilter.QBUSINESS.length());
        Matcher chatPath = CHAT.matcher(path);
        if ("POST".equals(method) && chatPath.matches() && !uri.getQueryParameters().containsKey("sync")) {
            return chatStream(chatPath.group(1), uri, region, body);
        }
        return qbusiness.dispatch(method, path, uri.getQueryParameters(), headers, body, (operation, input) -> {
            QBUSINESS_SHAPES.check(operation, input);
            return handle(operation, input, region);
        });
    }

    /** {@code Chat}: reads the input event stream, answers the turn, and writes the output event stream. */
    private Response chatStream(String applicationId, UriInfo uri, String region, byte[] body) {
        ObjectNode input = mapper.createObjectNode().put("applicationId", applicationId);
        uri.getQueryParameters().forEach((name, values) -> {
            if (name.equals("userGroups")) {
                values.forEach(value -> input.withArray("userGroups").add(value));
            } else if (!values.isEmpty()) {
                input.put(name, values.get(0));
            }
        });
        try {
            AwsEventStreamDecoder decoder = new AwsEventStreamDecoder(new ByteArrayInputStream(body == null
                    ? new byte[0] : body));
            AwsEventStreamDecoder.Message message;
            while ((message = decoder.next()) != null) {
                String type = message.header(":event-type");
                JsonNode payload = message.payload().length == 0 ? mapper.createObjectNode()
                        : mapper.readTree(message.payload());
                switch (type == null ? "" : type) {
                    case "configurationEvent" -> payload.properties().forEach(e -> input.set(e.getKey(), e.getValue()));
                    case "textEvent" -> input.put("userMessage", input.path("userMessage").asText("")
                            + payload.path("userMessage").asText(""));
                    case "attachmentEvent" -> input.withArray("attachments").add(payload.path("attachment"));
                    case "actionExecutionEvent" -> input.set("actionExecution", payload);
                    case "authChallengeResponseEvent" -> input.set("authChallengeResponse", payload);
                    default -> {
                    }
                }
            }
        } catch (IOException e) {
            throw QBusinessStore.validation("The chat input stream can't be read: " + e.getMessage());
        }
        ObjectNode result = chat.chatSync(input, region);
        StreamingOutput stream = output -> {
            ObjectNode text = mapper.createObjectNode().put("systemMessageType", result.path("sourceAttributions")
                    .isEmpty() ? "RESPONSE" : "GROUNDED_RESPONSE").put("conversationId",
                    result.path("conversationId").asText()).put("userMessageId", result.path("userMessageId").asText())
                    .put("systemMessageId", result.path("systemMessageId").asText())
                    .put("systemMessage", result.path("systemMessage").asText());
            AwsEventStreamWriter.writeEvent(mapper, output, "textEvent", text);
            if (result.has("actionReview")) {
                ObjectNode review = ((ObjectNode) result.get("actionReview")).deepCopy();
                review.put("conversationId", result.path("conversationId").asText())
                        .put("userMessageId", result.path("userMessageId").asText())
                        .put("systemMessageId", result.path("systemMessageId").asText());
                AwsEventStreamWriter.writeEvent(mapper, output, "actionReviewEvent", review);
            }
            for (JsonNode failed : result.path("failedAttachments")) {
                ObjectNode event = mapper.createObjectNode().put("conversationId", result.path("conversationId")
                        .asText()).put("userMessageId", result.path("userMessageId").asText());
                event.set("attachment", failed);
                AwsEventStreamWriter.writeEvent(mapper, output, "failedAttachmentEvent", event);
            }
            ObjectNode metadata = mapper.createObjectNode().put("conversationId", result.path("conversationId")
                    .asText()).put("userMessageId", result.path("userMessageId").asText())
                    .put("systemMessageId", result.path("systemMessageId").asText())
                    .put("finalTextMessage", result.path("systemMessage").asText());
            metadata.set("sourceAttributions", result.path("sourceAttributions"));
            AwsEventStreamWriter.writeEvent(mapper, output, "metadataEvent", metadata);
        };
        return Response.ok(stream, "application/vnd.amazon.eventstream").build();
    }

    private ObjectNode handle(String operation, ObjectNode input, String region) {
        return switch (operation) {
            case "ChatSync", "Chat" -> chat.chatSync(input, region);
            case "SearchRelevantContent" -> chat.searchRelevantContent(input, region);
            case "PutFeedback" -> chat.putFeedback(input, region);
            case "ListConversations" -> chat.listConversations(input, region);
            case "DeleteConversation" -> chat.deleteConversation(input, region);
            case "ListMessages" -> chat.listMessages(input, region);
            case "ListAttachments" -> chat.listAttachments(input, region);
            case "DeleteAttachment" -> chat.deleteAttachment(input, region);
            case "GetMedia" -> chat.getMedia(input, region);
            case "AssociatePermission" -> service.associatePermission(input, region);
            case "BatchDeleteDocument" -> service.batchDeleteDocument(input, region);
            case "BatchPutDocument" -> service.batchPutDocument(input, region);
            case "CancelSubscription" -> service.cancelSubscription(input, region);
            case "CheckDocumentAccess" -> service.checkDocumentAccess(input, region);
            case "CreateAnonymousWebExperienceUrl" -> service.createAnonymousWebExperienceUrl(input, region);
            case "CreateApplication" -> service.createApplication(input, region);
            case "CreateChatResponseConfiguration" -> service.createChatResponseConfiguration(input, region);
            case "CreateDataAccessor" -> service.createDataAccessor(input, region);
            case "CreateDataSource" -> service.createDataSource(input, region);
            case "CreateIndex" -> service.createIndex(input, region);
            case "CreatePlugin" -> service.createPlugin(input, region);
            case "CreateRetriever" -> service.createRetriever(input, region);
            case "CreateSubscription" -> service.createSubscription(input, region);
            case "CreateUser" -> service.createUser(input, region);
            case "CreateWebExperience" -> service.createWebExperience(input, region);
            case "DeleteApplication" -> service.deleteApplication(input, region);
            case "DeleteChatControlsConfiguration" -> service.deleteChatControlsConfiguration(input, region);
            case "DeleteChatResponseConfiguration" -> service.deleteChatResponseConfiguration(input, region);
            case "DeleteDataAccessor" -> service.deleteDataAccessor(input, region);
            case "DeleteDataSource" -> service.deleteDataSource(input, region);
            case "DeleteGroup" -> service.deleteGroup(input, region);
            case "DeleteIndex" -> service.deleteIndex(input, region);
            case "DeletePlugin" -> service.deletePlugin(input, region);
            case "DeleteRetriever" -> service.deleteRetriever(input, region);
            case "DeleteUser" -> service.deleteUser(input, region);
            case "DeleteWebExperience" -> service.deleteWebExperience(input, region);
            case "DisassociatePermission" -> service.disassociatePermission(input, region);
            case "GetApplication" -> service.getApplication(input, region);
            case "GetChatControlsConfiguration" -> service.getChatControlsConfiguration(input, region);
            case "GetChatResponseConfiguration" -> service.getChatResponseConfiguration(input, region);
            case "GetDataAccessor" -> service.getDataAccessor(input, region);
            case "GetDataSource" -> service.getDataSource(input, region);
            case "GetDocumentContent" -> service.getDocumentContent(input, region);
            case "GetGroup" -> service.getGroup(input, region);
            case "GetIndex" -> service.getIndex(input, region);
            case "GetPlugin" -> service.getPlugin(input, region);
            case "GetPolicy" -> service.getPolicy(input, region);
            case "GetRetriever" -> service.getRetriever(input, region);
            case "GetUser" -> service.getUser(input, region);
            case "GetWebExperience" -> service.getWebExperience(input, region);
            case "ListApplications" -> service.listApplications(input, region);
            case "ListChatResponseConfigurations" -> service.listChatResponseConfigurations(input, region);
            case "ListDataAccessors" -> service.listDataAccessors(input, region);
            case "ListDataSourceSyncJobs" -> service.listDataSourceSyncJobs(input, region);
            case "ListDataSources" -> service.listDataSources(input, region);
            case "ListDocuments" -> service.listDocuments(input, region);
            case "ListGroups" -> service.listGroups(input, region);
            case "ListIndices" -> service.listIndices(input, region);
            case "ListPluginActions" -> service.listPluginActions(input, region);
            case "ListPluginTypeActions" -> service.listPluginTypeActions(input, region);
            case "ListPluginTypeMetadata" -> service.listPluginTypeMetadata(input, region);
            case "ListPlugins" -> service.listPlugins(input, region);
            case "ListRetrievers" -> service.listRetrievers(input, region);
            case "ListSubscriptions" -> service.listSubscriptions(input, region);
            case "ListTagsForResource" -> service.listTagsForResource(input, region);
            case "ListWebExperiences" -> service.listWebExperiences(input, region);
            case "PutGroup" -> service.putGroup(input, region);
            case "StartDataSourceSyncJob" -> service.startDataSourceSyncJob(input, region);
            case "StopDataSourceSyncJob" -> service.stopDataSourceSyncJob(input, region);
            case "TagResource" -> service.tagResource(input, region);
            case "UntagResource" -> service.untagResource(input, region);
            case "UpdateApplication" -> service.updateApplication(input, region);
            case "UpdateChatControlsConfiguration" -> service.updateChatControlsConfiguration(input, region);
            case "UpdateChatResponseConfiguration" -> service.updateChatResponseConfiguration(input, region);
            case "UpdateDataAccessor" -> service.updateDataAccessor(input, region);
            case "UpdateDataSource" -> service.updateDataSource(input, region);
            case "UpdateIndex" -> service.updateIndex(input, region);
            case "UpdatePlugin" -> service.updatePlugin(input, region);
            case "UpdateRetriever" -> service.updateRetriever(input, region);
            case "UpdateSubscription" -> service.updateSubscription(input, region);
            case "UpdateUser" -> service.updateUser(input, region);
            case "UpdateWebExperience" -> service.updateWebExperience(input, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }
}
