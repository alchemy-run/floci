package io.floci.aws.services.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The AWS Chatbot REST API: every operation is a {@code POST} to its own path, routed here by
 * {@link ChatbotRouteFilter} when the request is signed for {@code chatbot}. Chatbot is global, so no Region applies.
 */
@Path(ChatbotRouteFilter.PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class ChatbotController {
    private final ChatbotService service;
    private final ObjectMapper mapper;

    @Inject
    public ChatbotController(ChatbotService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @POST
    @Path("/associate-to-configuration")
    public Response associateToConfiguration(String body) {
        service.associate(parse(body));
        return empty();
    }

    @POST
    @Path("/create-chime-webhook-configuration")
    public Response createChimeWebhookConfiguration(String body) {
        return ok(service.createChime(parse(body)));
    }

    @POST
    @Path("/create-custom-action")
    public Response createCustomAction(String body) {
        return ok(service.createAction(parse(body)));
    }

    @POST
    @Path("/create-ms-teams-channel-configuration")
    public Response createMicrosoftTeamsChannelConfiguration(String body) {
        return ok(service.createTeams(parse(body)));
    }

    @POST
    @Path("/create-slack-channel-configuration")
    public Response createSlackChannelConfiguration(String body) {
        return ok(service.createSlack(parse(body)));
    }

    @POST
    @Path("/delete-chime-webhook-configuration")
    public Response deleteChimeWebhookConfiguration(String body) {
        service.delete(parse(body), "chime-webhook", "DeleteChimeWebhookConfiguration");
        return empty();
    }

    @POST
    @Path("/delete-custom-action")
    public Response deleteCustomAction(String body) {
        service.deleteAction(parse(body));
        return empty();
    }

    @POST
    @Path("/delete-ms-teams-channel-configuration")
    public Response deleteMicrosoftTeamsChannelConfiguration(String body) {
        service.delete(parse(body), "microsoft-teams-channel", "DeleteMicrosoftTeamsChannelConfiguration");
        return empty();
    }

    @POST
    @Path("/delete-ms-teams-configured-teams")
    public Response deleteMicrosoftTeamsConfiguredTeam(String body) {
        service.deleteTeam(parse(body));
        return empty();
    }

    @POST
    @Path("/delete-ms-teams-user-identity")
    public Response deleteMicrosoftTeamsUserIdentity(String body) {
        service.deleteTeamsIdentity(parse(body));
        return empty();
    }

    @POST
    @Path("/delete-slack-channel-configuration")
    public Response deleteSlackChannelConfiguration(String body) {
        service.delete(parse(body), "slack-channel", "DeleteSlackChannelConfiguration");
        return empty();
    }

    @POST
    @Path("/delete-slack-user-identity")
    public Response deleteSlackUserIdentity(String body) {
        service.deleteSlackIdentity(parse(body));
        return empty();
    }

    @POST
    @Path("/delete-slack-workspace-authorization")
    public Response deleteSlackWorkspaceAuthorization(String body) {
        service.deleteWorkspace(parse(body));
        return empty();
    }

    @POST
    @Path("/describe-chime-webhook-configurations")
    public Response describeChimeWebhookConfigurations(String body) {
        return ok(service.describeChime(parse(body)));
    }

    @POST
    @Path("/describe-slack-channel-configurations")
    public Response describeSlackChannelConfigurations(String body) {
        return ok(service.describeSlack(parse(body)));
    }

    @POST
    @Path("/describe-slack-user-identities")
    public Response describeSlackUserIdentities(String body) {
        return ok(service.describeSlackIdentities(parse(body)));
    }

    @POST
    @Path("/describe-slack-workspaces")
    public Response describeSlackWorkspaces(String body) {
        return ok(service.describeWorkspaces(parse(body)));
    }

    @POST
    @Path("/disassociate-from-configuration")
    public Response disassociateFromConfiguration(String body) {
        service.disassociate(parse(body));
        return empty();
    }

    @POST
    @Path("/get-account-preferences")
    public Response getAccountPreferences(String body) {
        return ok(service.getPreferences());
    }

    @POST
    @Path("/get-custom-action")
    public Response getCustomAction(String body) {
        return ok(service.getAction(parse(body)));
    }

    @POST
    @Path("/get-ms-teams-channel-configuration")
    public Response getMicrosoftTeamsChannelConfiguration(String body) {
        return ok(service.getTeams(parse(body)));
    }

    @POST
    @Path("/list-associations")
    public Response listAssociations(String body) {
        return ok(service.listAssociations(parse(body)));
    }

    @POST
    @Path("/list-custom-actions")
    public Response listCustomActions(String body) {
        return ok(service.listActions(parse(body)));
    }

    @POST
    @Path("/list-ms-teams-channel-configurations")
    public Response listMicrosoftTeamsChannelConfigurations(String body) {
        return ok(service.listTeamsConfigurations(parse(body)));
    }

    @POST
    @Path("/list-ms-teams-configured-teams")
    public Response listMicrosoftTeamsConfiguredTeams(String body) {
        return ok(service.listTeams(parse(body)));
    }

    @POST
    @Path("/list-ms-teams-user-identities")
    public Response listMicrosoftTeamsUserIdentities(String body) {
        return ok(service.listTeamsIdentities(parse(body)));
    }

    @POST
    @Path("/list-tags-for-resource")
    public Response listTagsForResource(String body) {
        return ok(service.listTags(parse(body)));
    }

    @POST
    @Path("/tag-resource")
    public Response tagResource(String body) {
        service.tag(parse(body));
        return empty();
    }

    @POST
    @Path("/untag-resource")
    public Response untagResource(String body) {
        service.untag(parse(body));
        return empty();
    }

    @POST
    @Path("/update-account-preferences")
    public Response updateAccountPreferences(String body) {
        return ok(service.updatePreferences(parse(body)));
    }

    @POST
    @Path("/update-chime-webhook-configuration")
    public Response updateChimeWebhookConfiguration(String body) {
        return ok(service.updateChime(parse(body)));
    }

    @POST
    @Path("/update-custom-action")
    public Response updateCustomAction(String body) {
        return ok(service.updateAction(parse(body)));
    }

    @POST
    @Path("/update-ms-teams-channel-configuration")
    public Response updateMicrosoftTeamsChannelConfiguration(String body) {
        return ok(service.updateTeams(parse(body)));
    }

    @POST
    @Path("/update-slack-channel-configuration")
    public Response updateSlackChannelConfiguration(String body) {
        return ok(service.updateSlack(parse(body)));
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    private Response empty() {
        return Response.ok(mapper.createObjectNode()).build();
    }

    private JsonNode parse(String body) {
        try {
            JsonNode parsed = body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            if (!parsed.isObject()) {
                throw ChatbotService.invalidParameter("The request body must be a JSON object.");
            }
            return parsed;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw ChatbotService.invalidParameter("The request body is not valid JSON.");
        }
    }
}
