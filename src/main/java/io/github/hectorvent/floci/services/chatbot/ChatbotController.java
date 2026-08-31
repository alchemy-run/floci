package io.github.hectorvent.floci.services.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.chatbot.model.CustomAction;
import io.github.hectorvent.floci.services.chatbot.model.SlackChannelConfiguration;
import io.github.hectorvent.floci.services.chatbot.model.TeamsChannelConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * AWS Chatbot restJson1. Literal kebab-case paths take JAX-RS precedence over
 * S3's {@code /{bucket}} catch-all. Wire names are PascalCase.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatbotController {

    private final ChatbotService service;
    private final ObjectMapper objectMapper;

    @Inject
    public ChatbotController(ChatbotService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/get-account-preferences")
    @Consumes(MediaType.WILDCARD)
    public Response getAccountPreferences(String body) {
        return handle(body, request -> Response.ok(wrapPreferences(service.getAccountPreferences())).build());
    }

    @POST
    @Path("/update-account-preferences")
    public Response updateAccountPreferences(String body) {
        return handle(body, request -> Response.ok(wrapPreferences(service.updateAccountPreferences(request)))
                .build());
    }

    @POST
    @Path("/describe-slack-workspaces")
    @Consumes(MediaType.WILDCARD)
    public Response describeSlackWorkspaces(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode list = response.putArray("SlackWorkspaces");
            for (Map<String, String> workspace : service.describeSlackWorkspaces()) {
                ObjectNode node = list.addObject();
                node.put("SlackTeamId", workspace.get("SlackTeamId"));
                node.put("SlackTeamName", workspace.get("SlackTeamName"));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/describe-slack-user-identities")
    @Consumes(MediaType.WILDCARD)
    public Response describeSlackUserIdentities(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("SlackUserIdentities");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/list-ms-teams-configured-teams")
    @Consumes(MediaType.WILDCARD)
    public Response listMicrosoftTeamsConfiguredTeams(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode list = response.putArray("ConfiguredTeams");
            for (Map<String, String> team : service.listMicrosoftTeamsConfiguredTeams()) {
                ObjectNode node = list.addObject();
                node.put("TenantId", team.get("TenantId"));
                node.put("TeamId", team.get("TeamId"));
                if (team.get("TeamName") != null) {
                    node.put("TeamName", team.get("TeamName"));
                }
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/list-ms-teams-user-identities")
    @Consumes(MediaType.WILDCARD)
    public Response listMicrosoftTeamsUserIdentities(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.putArray("TeamsUserIdentities");
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/delete-slack-user-identity")
    public Response deleteSlackUserIdentity(String body) {
        return handle(body, request -> {
            service.deleteSlackUserIdentity(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/delete-slack-workspace-authorization")
    public Response deleteSlackWorkspaceAuthorization(String body) {
        return handle(body, request -> {
            service.deleteSlackWorkspaceAuthorization(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/delete-ms-teams-user-identity")
    public Response deleteMicrosoftTeamsUserIdentity(String body) {
        return handle(body, request -> {
            service.deleteMicrosoftTeamsUserIdentity(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/delete-ms-teams-configured-teams")
    public Response deleteMicrosoftTeamsConfiguredTeam(String body) {
        return handle(body, request -> {
            service.deleteMicrosoftTeamsConfiguredTeam(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/create-slack-channel-configuration")
    public Response createSlackChannelConfiguration(String body) {
        return handle(body, request -> Response.ok(wrapSlack(service.createSlackChannelConfiguration(request)))
                .build());
    }

    @POST
    @Path("/describe-slack-channel-configurations")
    @Consumes(MediaType.WILDCARD)
    public Response describeSlackChannelConfigurations(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode list = response.putArray("SlackChannelConfigurations");
            for (SlackChannelConfiguration config : service.describeSlackChannelConfigurations(request)) {
                list.add(toSlack(config));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/update-slack-channel-configuration")
    public Response updateSlackChannelConfiguration(String body) {
        return handle(body, request -> Response.ok(wrapSlack(service.updateSlackChannelConfiguration(request)))
                .build());
    }

    @POST
    @Path("/delete-slack-channel-configuration")
    public Response deleteSlackChannelConfiguration(String body) {
        return handle(body, request -> {
            service.deleteSlackChannelConfiguration(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/create-ms-teams-channel-configuration")
    public Response createMicrosoftTeamsChannelConfiguration(String body) {
        return handle(body, request -> Response.ok(wrapTeams(
                service.createMicrosoftTeamsChannelConfiguration(request))).build());
    }

    @POST
    @Path("/get-ms-teams-channel-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response getMicrosoftTeamsChannelConfiguration(String body) {
        return handle(body, request -> Response.ok(wrapTeams(
                service.getMicrosoftTeamsChannelConfiguration(request))).build());
    }

    @POST
    @Path("/list-ms-teams-channel-configurations")
    @Consumes(MediaType.WILDCARD)
    public Response listMicrosoftTeamsChannelConfigurations(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode list = response.putArray("TeamChannelConfigurations");
            for (TeamsChannelConfiguration config : service.listMicrosoftTeamsChannelConfigurations(request)) {
                list.add(toTeams(config));
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/update-ms-teams-channel-configuration")
    public Response updateMicrosoftTeamsChannelConfiguration(String body) {
        return handle(body, request -> Response.ok(wrapTeams(
                service.updateMicrosoftTeamsChannelConfiguration(request))).build());
    }

    @POST
    @Path("/delete-ms-teams-channel-configuration")
    public Response deleteMicrosoftTeamsChannelConfiguration(String body) {
        return handle(body, request -> {
            service.deleteMicrosoftTeamsChannelConfiguration(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/list-associations")
    public Response listAssociations(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode associations = response.putArray("Associations");
            for (var association : service.listAssociations(request)) {
                ObjectNode listing = associations.addObject();
                listing.put("Resource", association.getResourceArn());
            }
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/associate-to-configuration")
    public Response associateToConfiguration(String body) {
        return handle(body, request -> {
            service.associateToConfiguration(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/disassociate-from-configuration")
    public Response disassociateFromConfiguration(String body) {
        return handle(body, request -> {
            service.disassociateFromConfiguration(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/create-custom-action")
    public Response createCustomAction(String body) {
        return handle(body, request -> {
            CustomAction action = service.createCustomAction(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("CustomActionArn", action.getCustomActionArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/get-custom-action")
    public Response getCustomAction(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("CustomAction", toCustomAction(service.getCustomAction(request)));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/update-custom-action")
    public Response updateCustomAction(String body) {
        return handle(body, request -> {
            CustomAction action = service.updateCustomAction(request);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("CustomActionArn", action.getCustomActionArn());
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/delete-custom-action")
    public Response deleteCustomAction(String body) {
        return handle(body, request -> {
            service.deleteCustomAction(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/list-custom-actions")
    @Consumes(MediaType.WILDCARD)
    public Response listCustomActions(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode actions = response.putArray("CustomActions");
            service.listCustomActions().forEach(actions::add);
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/list-tags-for-resource")
    public Response listTagsForResource(String body) {
        return handle(body, request -> {
            ObjectNode response = objectMapper.createObjectNode();
            putTags(response, service.listTagsForResource(request));
            return Response.ok(response).build();
        });
    }

    @POST
    @Path("/tag-resource")
    public Response tagResource(String body) {
        return handle(body, request -> {
            service.tagResource(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/untag-resource")
    public Response untagResource(String body) {
        return handle(body, request -> {
            service.untagResource(request);
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    private ObjectNode wrapPreferences(Map<String, Boolean> preferences) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode account = response.putObject("AccountPreferences");
        account.put("UserAuthorizationRequired", Boolean.TRUE.equals(preferences.get("UserAuthorizationRequired")));
        account.put(
                "TrainingDataCollectionEnabled",
                Boolean.TRUE.equals(preferences.get("TrainingDataCollectionEnabled")));
        return response;
    }

    private ObjectNode wrapSlack(SlackChannelConfiguration config) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ChannelConfiguration", toSlack(config));
        return response;
    }

    private ObjectNode wrapTeams(TeamsChannelConfiguration config) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ChannelConfiguration", toTeams(config));
        return response;
    }

    private ObjectNode toSlack(SlackChannelConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SlackTeamName", config.getSlackTeamName() == null ? "" : config.getSlackTeamName());
        node.put("SlackTeamId", config.getSlackTeamId());
        node.put("SlackChannelId", config.getSlackChannelId());
        node.put("SlackChannelName", config.getSlackChannelName() == null ? "" : config.getSlackChannelName());
        node.put("ChatConfigurationArn", config.getChatConfigurationArn());
        node.put("IamRoleArn", config.getIamRoleArn());
        putStringArray(node, "SnsTopicArns", config.getSnsTopicArns());
        if (config.getConfigurationName() != null) {
            node.put("ConfigurationName", config.getConfigurationName());
        }
        if (config.getLoggingLevel() != null) {
            node.put("LoggingLevel", config.getLoggingLevel());
        }
        putStringArray(node, "GuardrailPolicyArns", config.getGuardrailPolicyArns());
        node.put("UserAuthorizationRequired", config.isUserAuthorizationRequired());
        putTags(node, config.getTags());
        if (config.getState() != null) {
            node.put("State", config.getState());
        }
        if (config.getStateReason() != null) {
            node.put("StateReason", config.getStateReason());
        }
        return node;
    }

    private ObjectNode toTeams(TeamsChannelConfiguration config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ChannelId", config.getChannelId());
        if (config.getChannelName() != null) {
            node.put("ChannelName", config.getChannelName());
        }
        node.put("TeamId", config.getTeamId());
        if (config.getTeamName() != null) {
            node.put("TeamName", config.getTeamName());
        }
        node.put("TenantId", config.getTenantId());
        node.put("ChatConfigurationArn", config.getChatConfigurationArn());
        node.put("IamRoleArn", config.getIamRoleArn());
        putStringArray(node, "SnsTopicArns", config.getSnsTopicArns());
        if (config.getConfigurationName() != null) {
            node.put("ConfigurationName", config.getConfigurationName());
        }
        if (config.getLoggingLevel() != null) {
            node.put("LoggingLevel", config.getLoggingLevel());
        }
        putStringArray(node, "GuardrailPolicyArns", config.getGuardrailPolicyArns());
        node.put("UserAuthorizationRequired", config.isUserAuthorizationRequired());
        putTags(node, config.getTags());
        if (config.getState() != null) {
            node.put("State", config.getState());
        }
        if (config.getStateReason() != null) {
            node.put("StateReason", config.getStateReason());
        }
        return node;
    }

    private ObjectNode toCustomAction(CustomAction action) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CustomActionArn", action.getCustomActionArn());
        node.put("ActionName", action.getActionName());
        ObjectNode definition = node.putObject("Definition");
        definition.put("CommandText", action.getCommandText());
        if (action.getAliasName() != null) {
            node.put("AliasName", action.getAliasName());
        }
        if (action.getAttachments() != null) {
            node.set("Attachments", action.getAttachments());
        }
        return node;
    }

    private void putStringArray(ObjectNode parent, String field, List<String> values) {
        ArrayNode array = parent.putArray(field);
        if (values == null) {
            return;
        }
        for (String value : values) {
            array.add(value);
        }
    }

    private void putTags(ObjectNode parent, Map<String, String> tags) {
        ArrayNode array = parent.putArray("Tags");
        if (tags == null || tags.isEmpty()) {
            return;
        }
        tags.forEach((key, value) -> {
            ObjectNode tag = array.addObject();
            tag.put("TagKey", key);
            tag.put("TagValue", value);
        });
    }

    private Response handle(String body, Handler handler) {
        try {
            return handler.handle(parse(body));
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
                throw new AwsException("InvalidParameterException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterException", "Request body is not valid JSON.", 400);
        }
    }

    private static Response error(AwsException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.jsonType())
                .entity(new AwsErrorResponse(exception.jsonType(), exception.getMessage()))
                .build();
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(JsonNode request);
    }
}
