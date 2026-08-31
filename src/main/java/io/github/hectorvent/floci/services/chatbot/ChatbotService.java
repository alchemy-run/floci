package io.github.hectorvent.floci.services.chatbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.chatbot.model.Association;
import io.github.hectorvent.floci.services.chatbot.model.ChatbotState;
import io.github.hectorvent.floci.services.chatbot.model.CustomAction;
import io.github.hectorvent.floci.services.chatbot.model.SlackChannelConfiguration;
import io.github.hectorvent.floci.services.chatbot.model.TeamsChannelConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AWS Chatbot (Amazon Q Developer in chat applications) restJson1.
 *
 * <p>Slack workspace and Microsoft Teams team onboarding is a console OAuth
 * flow with no public create API. Create against an unknown workspace fails
 * with {@code InvalidRequestException} and reserves the configuration name
 * as an undeletable tombstone, matching live AWS.
 */
@ApplicationScoped
public class ChatbotService {

    static final String SERVICE = "chatbot";
    private static final String DEFAULT_GUARDRAIL = "arn:aws:iam::aws:policy/AdministratorAccess";
    private static final String DEFAULT_LOGGING = "NONE";
    private static final Set<String> LOGGING_LEVELS = Set.of("ERROR", "INFO", "NONE");
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern ACTION_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String SLACK_RESOURCE = "chat-configuration/slack-channel/";
    private static final String TEAMS_RESOURCE = "chat-configuration/microsoft-teams-channel/";

    private final StorageBackend<String, SlackChannelConfiguration> slackStore;
    private final StorageBackend<String, TeamsChannelConfiguration> teamsStore;
    private final StorageBackend<String, String> slackWorkspaces;
    private final StorageBackend<String, String> teamsTeams;
    private final StorageBackend<String, Association> associations;
    private final StorageBackend<String, CustomAction> customActions;
    private final StorageBackend<String, ChatbotState> extrasStore;
    private final RegionResolver regionResolver;

    @Inject
    public ChatbotService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("chatbot", "chatbot-slack-channel-configurations.json",
                        new TypeReference<Map<String, SlackChannelConfiguration>>() {
                        }),
                storageFactory.create("chatbot", "chatbot-teams-channel-configurations.json",
                        new TypeReference<Map<String, TeamsChannelConfiguration>>() {
                        }),
                storageFactory.create("chatbot", "chatbot-slack-workspaces.json",
                        new TypeReference<Map<String, String>>() {
                        }),
                storageFactory.create("chatbot", "chatbot-configured-teams.json",
                        new TypeReference<Map<String, String>>() {
                        }),
                storageFactory.create("chatbot", "chatbot-associations.json",
                        new TypeReference<Map<String, Association>>() {
                        }),
                storageFactory.create("chatbot", "chatbot-custom-actions.json",
                        new TypeReference<Map<String, CustomAction>>() {
                        }),
                storageFactory.create("chatbot", "chatbot-account-state.json",
                        new TypeReference<Map<String, ChatbotState>>() {
                        }),
                regionResolver);
    }

    ChatbotService(
            StorageBackend<String, SlackChannelConfiguration> slackStore,
            StorageBackend<String, TeamsChannelConfiguration> teamsStore,
            StorageBackend<String, String> slackWorkspaces,
            StorageBackend<String, String> teamsTeams,
            StorageBackend<String, Association> associations,
            StorageBackend<String, CustomAction> customActions,
            StorageBackend<String, ChatbotState> extrasStore,
            RegionResolver regionResolver) {
        this.slackStore = slackStore;
        this.teamsStore = teamsStore;
        this.slackWorkspaces = slackWorkspaces;
        this.teamsTeams = teamsTeams;
        this.associations = associations;
        this.customActions = customActions;
        this.extrasStore = extrasStore;
        this.regionResolver = regionResolver;
    }

    public synchronized SlackChannelConfiguration createSlackChannelConfiguration(JsonNode request) {
        requireObject(request);
        String name = requireText(request, "ConfigurationName");
        validateName(name);
        String teamId = requireText(request, "SlackTeamId");
        String channelId = requireText(request, "SlackChannelId");
        String iamRoleArn = requireText(request, "IamRoleArn");
        String account = regionResolver.getAccountId();

        if (slackStore.get(name).isPresent()) {
            throw conflict("A chat configuration named '" + name + "' already exists.");
        }

        Optional<String> teamName = slackWorkspaces.get(teamId);
        if (teamName.isEmpty()) {
            SlackChannelConfiguration tombstone = new SlackChannelConfiguration();
            tombstone.setConfigurationName(name);
            tombstone.setSlackTeamId(teamId);
            tombstone.setSlackChannelId(channelId);
            tombstone.setIamRoleArn(iamRoleArn);
            tombstone.setChatConfigurationArn(slackArn(account, name));
            tombstone.setTombstone(true);
            slackStore.put(name, tombstone);
            throw new AwsException(
                    "InvalidRequestException",
                    "The Slack workspace " + teamId + " is not authorized with AWS account " + account + ".",
                    400);
        }

        SlackChannelConfiguration config = new SlackChannelConfiguration();
        config.setConfigurationName(name);
        config.setSlackTeamId(teamId);
        config.setSlackTeamName(teamName.get());
        config.setSlackChannelId(channelId);
        config.setSlackChannelName(optionalText(request, "SlackChannelName", channelId));
        config.setChatConfigurationArn(slackArn(account, name));
        config.setIamRoleArn(iamRoleArn);
        config.setSnsTopicArns(readStringList(request, "SnsTopicArns"));
        config.setLoggingLevel(readLoggingLevel(request));
        config.setGuardrailPolicyArns(readGuardrails(request));
        config.setUserAuthorizationRequired(optionalBoolean(request, "UserAuthorizationRequired", false));
        config.setTags(readTags(request));
        config.setState("ENABLED");
        slackStore.put(name, config);
        return config;
    }

    public List<SlackChannelConfiguration> describeSlackChannelConfigurations(JsonNode request) {
        requireObject(request);
        String arn = optionalText(request, "ChatConfigurationArn", null);
        List<SlackChannelConfiguration> configs = slackStore.scan(key -> true);
        configs.removeIf(SlackChannelConfiguration::isTombstone);
        if (arn != null) {
            configs.removeIf(config -> !arn.equals(config.getChatConfigurationArn()));
        }
        configs.sort(Comparator.comparing(
                SlackChannelConfiguration::getConfigurationName, Comparator.nullsLast(String::compareTo)));
        return configs;
    }

    public synchronized SlackChannelConfiguration updateSlackChannelConfiguration(JsonNode request) {
        requireObject(request);
        SlackChannelConfiguration config = requireLiveSlack(requireText(request, "ChatConfigurationArn"));
        config.setSlackChannelId(requireText(request, "SlackChannelId"));
        String channelName = optionalText(request, "SlackChannelName", null);
        if (channelName != null) {
            config.setSlackChannelName(channelName);
        }
        if (hasField(request, "IamRoleArn")) {
            config.setIamRoleArn(requireText(request, "IamRoleArn"));
        }
        if (hasField(request, "SnsTopicArns")) {
            config.setSnsTopicArns(readStringList(request, "SnsTopicArns"));
        }
        if (hasField(request, "LoggingLevel")) {
            config.setLoggingLevel(readLoggingLevel(request));
        }
        if (hasField(request, "GuardrailPolicyArns")) {
            config.setGuardrailPolicyArns(readGuardrails(request));
        }
        if (hasField(request, "UserAuthorizationRequired")) {
            config.setUserAuthorizationRequired(optionalBoolean(request, "UserAuthorizationRequired", false));
        }
        slackStore.put(config.getConfigurationName(), config);
        return config;
    }

    public synchronized void deleteSlackChannelConfiguration(JsonNode request) {
        requireObject(request);
        SlackChannelConfiguration config = requireSlack(requireText(request, "ChatConfigurationArn"));
        if (config.isTombstone()) {
            return;
        }
        deleteAssociations(config.getChatConfigurationArn());
        slackStore.delete(config.getConfigurationName());
    }

    public synchronized TeamsChannelConfiguration createMicrosoftTeamsChannelConfiguration(JsonNode request) {
        requireObject(request);
        String name = requireText(request, "ConfigurationName");
        validateName(name);
        String teamId = requireText(request, "TeamId");
        String tenantId = requireText(request, "TenantId");
        String channelId = requireText(request, "ChannelId");
        String iamRoleArn = requireText(request, "IamRoleArn");
        String account = regionResolver.getAccountId();

        Optional<String> teamName = teamsTeams.get(teamId);
        if (teamName.isEmpty()) {
            throw new AwsException(
                    "InvalidRequestException",
                    "The Microsoft Teams team id you are using is not configured with AWS Chatbot.",
                    400);
        }

        if (teamsStore.get(name).isPresent()) {
            throw conflict("A chat configuration named '" + name + "' already exists.");
        }

        TeamsChannelConfiguration config = new TeamsChannelConfiguration();
        config.setConfigurationName(name);
        config.setChannelId(channelId);
        config.setChannelName(optionalText(request, "ChannelName", channelId));
        config.setTeamId(teamId);
        config.setTeamName(optionalText(request, "TeamName", teamName.get()));
        config.setTenantId(tenantId);
        config.setChatConfigurationArn(teamsArn(account, name));
        config.setIamRoleArn(iamRoleArn);
        config.setSnsTopicArns(readStringList(request, "SnsTopicArns"));
        config.setLoggingLevel(readLoggingLevel(request));
        config.setGuardrailPolicyArns(readGuardrails(request));
        config.setUserAuthorizationRequired(optionalBoolean(request, "UserAuthorizationRequired", false));
        config.setTags(readTags(request));
        config.setState("ENABLED");
        teamsStore.put(name, config);
        return config;
    }

    public TeamsChannelConfiguration getMicrosoftTeamsChannelConfiguration(JsonNode request) {
        requireObject(request);
        return requireTeams(requireText(request, "ChatConfigurationArn"));
    }

    public List<TeamsChannelConfiguration> listMicrosoftTeamsChannelConfigurations(JsonNode request) {
        requireObject(request);
        String teamId = optionalText(request, "TeamId", null);
        List<TeamsChannelConfiguration> configs = teamsStore.scan(key -> true);
        if (teamId != null) {
            configs.removeIf(config -> !teamId.equals(config.getTeamId()));
        }
        configs.sort(Comparator.comparing(
                TeamsChannelConfiguration::getConfigurationName, Comparator.nullsLast(String::compareTo)));
        return configs;
    }

    public synchronized TeamsChannelConfiguration updateMicrosoftTeamsChannelConfiguration(JsonNode request) {
        requireObject(request);
        TeamsChannelConfiguration config = requireTeams(requireText(request, "ChatConfigurationArn"));
        config.setChannelId(requireText(request, "ChannelId"));
        String channelName = optionalText(request, "ChannelName", null);
        if (channelName != null) {
            config.setChannelName(channelName);
        }
        if (hasField(request, "IamRoleArn")) {
            config.setIamRoleArn(requireText(request, "IamRoleArn"));
        }
        if (hasField(request, "SnsTopicArns")) {
            config.setSnsTopicArns(readStringList(request, "SnsTopicArns"));
        }
        if (hasField(request, "LoggingLevel")) {
            config.setLoggingLevel(readLoggingLevel(request));
        }
        if (hasField(request, "GuardrailPolicyArns")) {
            config.setGuardrailPolicyArns(readGuardrails(request));
        }
        if (hasField(request, "UserAuthorizationRequired")) {
            config.setUserAuthorizationRequired(optionalBoolean(request, "UserAuthorizationRequired", false));
        }
        teamsStore.put(config.getConfigurationName(), config);
        return config;
    }

    public synchronized void deleteMicrosoftTeamsChannelConfiguration(JsonNode request) {
        requireObject(request);
        TeamsChannelConfiguration config = requireTeams(requireText(request, "ChatConfigurationArn"));
        deleteAssociations(config.getChatConfigurationArn());
        teamsStore.delete(config.getConfigurationName());
    }

    public List<Association> listAssociations(JsonNode request) {
        requireObject(request);
        String chatConfiguration = requireText(request, "ChatConfiguration");
        requireChatbotArn(chatConfiguration);
        List<Association> items = new ArrayList<>();
        for (Association association : associations.scan(key -> true)) {
            if (chatConfiguration.equals(association.getChatConfigurationArn())) {
                items.add(association);
            }
        }
        items.sort(Comparator.comparing(Association::getResourceArn, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized void associateToConfiguration(JsonNode request) {
        requireObject(request);
        String chatConfiguration = requireText(request, "ChatConfiguration");
        String resource = requireText(request, "Resource");
        requireChatbotArn(chatConfiguration);
        requireChatbotArn(resource);
        if (findLiveSlack(chatConfiguration) == null && findTeams(chatConfiguration) == null) {
            throw channelNotFound(chatConfiguration);
        }
        associations.put(associationKey(chatConfiguration, resource),
                new Association(chatConfiguration, resource));
    }

    public synchronized void disassociateFromConfiguration(JsonNode request) {
        requireObject(request);
        String chatConfiguration = requireText(request, "ChatConfiguration");
        String resource = requireText(request, "Resource");
        requireChatbotArn(chatConfiguration);
        requireChatbotArn(resource);
        associations.delete(associationKey(chatConfiguration, resource));
    }

    public Map<String, String> listTagsForResource(JsonNode request) {
        requireObject(request);
        return tagsOf(requireText(request, "ResourceARN"));
    }

    public synchronized void tagResource(JsonNode request) {
        requireObject(request);
        String arn = requireText(request, "ResourceARN");
        Map<String, String> incoming = readTags(request);
        SlackChannelConfiguration slack = findLiveSlack(arn);
        if (slack != null) {
            slack.getTags().putAll(incoming);
            slackStore.put(slack.getConfigurationName(), slack);
            return;
        }
        TeamsChannelConfiguration teams = findTeams(arn);
        if (teams != null) {
            teams.getTags().putAll(incoming);
            teamsStore.put(teams.getConfigurationName(), teams);
            return;
        }
        CustomAction action = customActions.get(arn).orElse(null);
        if (action != null) {
            action.getTags().putAll(incoming);
            customActions.put(arn, action);
            return;
        }
        throw notFound(arn);
    }

    public synchronized void untagResource(JsonNode request) {
        requireObject(request);
        String arn = requireText(request, "ResourceARN");
        List<String> keys = readStringList(request, "TagKeys");
        SlackChannelConfiguration slack = findLiveSlack(arn);
        if (slack != null) {
            keys.forEach(slack.getTags()::remove);
            slackStore.put(slack.getConfigurationName(), slack);
            return;
        }
        TeamsChannelConfiguration teams = findTeams(arn);
        if (teams != null) {
            keys.forEach(teams.getTags()::remove);
            teamsStore.put(teams.getConfigurationName(), teams);
            return;
        }
        CustomAction action = customActions.get(arn).orElse(null);
        if (action != null) {
            keys.forEach(action.getTags()::remove);
            customActions.put(arn, action);
            return;
        }
        throw notFound(arn);
    }

    public List<Map<String, String>> describeSlackWorkspaces() {
        List<Map<String, String>> workspaces = new ArrayList<>();
        for (String teamId : slackWorkspaces.keys()) {
            slackWorkspaces.get(teamId).ifPresent(teamName -> {
                Map<String, String> workspace = new LinkedHashMap<>();
                workspace.put("SlackTeamId", teamId);
                workspace.put("SlackTeamName", teamName);
                workspaces.add(workspace);
            });
        }
        workspaces.sort(Comparator.comparing(w -> w.get("SlackTeamId")));
        return workspaces;
    }

    public List<Map<String, String>> listMicrosoftTeamsConfiguredTeams() {
        List<Map<String, String>> teams = new ArrayList<>();
        for (String teamId : teamsTeams.keys()) {
            teamsTeams.get(teamId).ifPresent(teamName -> {
                Map<String, String> team = new LinkedHashMap<>();
                team.put("TenantId", teamId);
                team.put("TeamId", teamId);
                team.put("TeamName", teamName);
                teams.add(team);
            });
        }
        teams.sort(Comparator.comparing(t -> t.get("TeamId")));
        return teams;
    }

    public Map<String, Boolean> getAccountPreferences() {
        ChatbotState state = extras();
        Map<String, Boolean> prefs = new LinkedHashMap<>();
        prefs.put("UserAuthorizationRequired", state.isUserAuthorizationRequired());
        prefs.put("TrainingDataCollectionEnabled", state.isTrainingDataCollectionEnabled());
        return prefs;
    }

    public synchronized Map<String, Boolean> updateAccountPreferences(JsonNode request) {
        requireObject(request);
        ChatbotState state = extras();
        if (hasField(request, "UserAuthorizationRequired")) {
            state.setUserAuthorizationRequired(optionalBoolean(request, "UserAuthorizationRequired", false));
        }
        if (hasField(request, "TrainingDataCollectionEnabled")) {
            state.setTrainingDataCollectionEnabled(
                    optionalBoolean(request, "TrainingDataCollectionEnabled", false));
        }
        extrasStore.put("state", state);
        return getAccountPreferences();
    }

    public List<Map<String, String>> describeSlackUserIdentities() {
        return List.of();
    }

    public List<Map<String, String>> listMicrosoftTeamsUserIdentities() {
        return List.of();
    }

    public void deleteSlackUserIdentity(JsonNode request) {
        requireObject(request);
        requireText(request, "ChatConfigurationArn");
        requireText(request, "SlackTeamId");
        requireText(request, "SlackUserId");
        throw notFound("Slack user identity");
    }

    public void deleteSlackWorkspaceAuthorization(JsonNode request) {
        requireObject(request);
        String teamId = requireText(request, "SlackTeamId");
        slackWorkspaces.delete(teamId);
    }

    public void deleteMicrosoftTeamsUserIdentity(JsonNode request) {
        requireObject(request);
        requireText(request, "ChatConfigurationArn");
        requireText(request, "UserId");
        throw notFound("Microsoft Teams user identity");
    }

    public void deleteMicrosoftTeamsConfiguredTeam(JsonNode request) {
        requireObject(request);
        String teamId = requireText(request, "TeamId");
        if (teamsTeams.get(teamId).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "No Team found for the team id", 404);
        }
        teamsTeams.delete(teamId);
    }

    public synchronized CustomAction createCustomAction(JsonNode request) {
        requireObject(request);
        String name = requireText(request, "ActionName");
        if (!ACTION_NAME_PATTERN.matcher(name).matches()) {
            throw invalidParameter("ActionName must match [A-Za-z0-9_-]{1,64}.");
        }
        JsonNode definition = request.get("Definition");
        if (definition == null || !definition.isObject()
                || !definition.has("CommandText") || !definition.get("CommandText").isTextual()) {
            throw invalidParameter("Definition.CommandText must be a string.");
        }
        String arn = "arn:aws:chatbot::" + regionResolver.getAccountId() + ":custom-action/" + name;
        if (customActions.get(arn).isPresent()) {
            throw conflict("A custom action named '" + name + "' already exists.");
        }
        CustomAction action = new CustomAction();
        action.setCustomActionArn(arn);
        action.setActionName(name);
        action.setCommandText(definition.get("CommandText").textValue());
        action.setAliasName(optionalText(request, "AliasName", null));
        if (hasField(request, "Attachments")) {
            action.setAttachments(request.get("Attachments"));
        }
        action.setTags(readTags(request));
        customActions.put(arn, action);
        return action;
    }

    public CustomAction getCustomAction(JsonNode request) {
        requireObject(request);
        String arn = requireText(request, "CustomActionArn");
        return customActions.get(arn).orElseThrow(() -> notFound(arn));
    }

    public synchronized CustomAction updateCustomAction(JsonNode request) {
        requireObject(request);
        String arn = requireText(request, "CustomActionArn");
        CustomAction action = customActions.get(arn).orElseThrow(() -> notFound(arn));
        JsonNode definition = request.get("Definition");
        if (definition == null || !definition.isObject()
                || !definition.has("CommandText") || !definition.get("CommandText").isTextual()) {
            throw invalidParameter("Definition.CommandText must be a string.");
        }
        action.setCommandText(definition.get("CommandText").textValue());
        if (hasField(request, "AliasName")) {
            action.setAliasName(optionalText(request, "AliasName", null));
        }
        if (hasField(request, "Attachments")) {
            action.setAttachments(request.get("Attachments"));
        }
        customActions.put(arn, action);
        return action;
    }

    public synchronized void deleteCustomAction(JsonNode request) {
        requireObject(request);
        String arn = requireText(request, "CustomActionArn");
        if (customActions.get(arn).isEmpty()) {
            throw notFound(arn);
        }
        customActions.delete(arn);
    }

    public List<String> listCustomActions() {
        List<String> arns = new ArrayList<>(customActions.keys());
        arns.sort(String::compareTo);
        return arns;
    }

    void authorizeSlackWorkspace(String teamId, String teamName) {
        slackWorkspaces.put(teamId, teamName);
    }

    void configureTeamsTeam(String teamId, String teamName) {
        teamsTeams.put(teamId, teamName);
    }

    private ChatbotState extras() {
        return extrasStore.get("state").orElseGet(ChatbotState::new);
    }

    private void deleteAssociations(String chatConfigurationArn) {
        for (Association association : associations.scan(key -> true)) {
            if (chatConfigurationArn.equals(association.getChatConfigurationArn())) {
                associations.delete(associationKey(association.getChatConfigurationArn(),
                        association.getResourceArn()));
            }
        }
    }

    private static String associationKey(String chatConfiguration, String resource) {
        return chatConfiguration + "|" + resource;
    }

    private static void requireChatbotArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!SERVICE.equals(parsed.service())) {
                throw invalidRequest("Invalid Chatbot ARN.");
            }
        } catch (IllegalArgumentException e) {
            throw invalidRequest("Invalid Chatbot ARN.");
        }
    }

    private SlackChannelConfiguration requireLiveSlack(String arn) {
        SlackChannelConfiguration config = requireSlack(arn);
        if (config.isTombstone()) {
            throw notFound(arn);
        }
        return config;
    }

    private SlackChannelConfiguration requireSlack(String arn) {
        SlackChannelConfiguration config = findSlack(arn);
        if (config == null) {
            throw notFound(arn);
        }
        return config;
    }

    private SlackChannelConfiguration findLiveSlack(String arn) {
        SlackChannelConfiguration config = findSlack(arn);
        if (config == null || config.isTombstone()) {
            return null;
        }
        return config;
    }

    private SlackChannelConfiguration findSlack(String arn) {
        String name = configurationName(arn, SLACK_RESOURCE);
        if (name == null) {
            return null;
        }
        return slackStore.get(name).orElse(null);
    }

    private TeamsChannelConfiguration requireTeams(String arn) {
        TeamsChannelConfiguration config = findTeams(arn);
        if (config == null) {
            throw notFound(arn);
        }
        return config;
    }

    private TeamsChannelConfiguration findTeams(String arn) {
        String name = configurationName(arn, TEAMS_RESOURCE);
        if (name == null) {
            return null;
        }
        return teamsStore.get(name).orElse(null);
    }

    private Map<String, String> tagsOf(String arn) {
        SlackChannelConfiguration slack = findLiveSlack(arn);
        if (slack != null) {
            return slack.getTags();
        }
        TeamsChannelConfiguration teams = findTeams(arn);
        if (teams != null) {
            return teams.getTags();
        }
        CustomAction action = customActions.get(arn).orElse(null);
        if (action != null) {
            return action.getTags();
        }
        throw notFound(arn);
    }

    private static String configurationName(String arn, String resourcePrefix) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw invalidParameter("ChatConfigurationArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw invalidParameter("ChatConfigurationArn is invalid.");
        }
        String resource = parsed.resource();
        if (resource == null || !resource.startsWith(resourcePrefix)) {
            return null;
        }
        return resource.substring(resourcePrefix.length());
    }

    private static String slackArn(String account, String name) {
        return "arn:aws:chatbot::" + account + ":" + SLACK_RESOURCE + name;
    }

    private static String teamsArn(String account, String name) {
        return "arn:aws:chatbot::" + account + ":" + TEAMS_RESOURCE + name;
    }

    private static void validateName(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw invalidParameter(
                    "ConfigurationName must match [A-Za-z0-9_-] and contain at most 128 characters.");
        }
    }

    private static String readLoggingLevel(JsonNode request) {
        String level = optionalText(request, "LoggingLevel", DEFAULT_LOGGING);
        if (!LOGGING_LEVELS.contains(level)) {
            throw invalidParameter("LoggingLevel must be ERROR, INFO, or NONE.");
        }
        return level;
    }

    private static List<String> readGuardrails(JsonNode request) {
        if (!hasField(request, "GuardrailPolicyArns")) {
            return List.of(DEFAULT_GUARDRAIL);
        }
        List<String> values = readStringList(request, "GuardrailPolicyArns");
        return values.isEmpty() ? List.of(DEFAULT_GUARDRAIL) : values;
    }

    private static Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode node = field(request, "Tags");
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isArray()) {
            throw invalidParameter("Tags must be an array.");
        }
        for (JsonNode entry : node) {
            if (entry == null || !entry.isObject()) {
                throw invalidParameter("Tags members must be objects.");
            }
            tags.put(requireText(entry, "TagKey"), requireText(entry, "TagValue"));
        }
        return tags;
    }

    private static List<String> readStringList(JsonNode request, String fieldName) {
        List<String> values = new ArrayList<>();
        JsonNode node = field(request, fieldName);
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            throw invalidParameter(fieldName + " must be an array.");
        }
        for (JsonNode entry : node) {
            if (entry == null || !entry.isTextual()) {
                throw invalidParameter(fieldName + " members must be strings.");
            }
            values.add(entry.textValue());
        }
        return values;
    }

    private static boolean optionalBoolean(JsonNode request, String fieldName, boolean defaultValue) {
        JsonNode node = field(request, fieldName);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw invalidParameter(fieldName + " must be a boolean.");
        }
        return node.booleanValue();
    }

    private static boolean hasField(JsonNode request, String fieldName) {
        JsonNode node = field(request, fieldName);
        return node != null && !node.isNull();
    }

    private static JsonNode field(JsonNode parent, String fieldName) {
        if (parent == null) {
            return null;
        }
        if (parent.has(fieldName)) {
            return parent.get(fieldName);
        }
        String camel = Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
        return parent.get(camel);
    }

    private static String requireText(JsonNode parent, String fieldName) {
        JsonNode value = field(parent, fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidParameter(fieldName + " must be a string.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String fieldName, String defaultValue) {
        JsonNode value = field(parent, fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual()) {
            throw invalidParameter(fieldName + " must be a string.");
        }
        String text = value.textValue();
        return text.isBlank() ? defaultValue : text;
    }

    private static void requireObject(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw invalidParameter("Request body must be a JSON object.");
        }
    }

    private static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException channelNotFound(String arn) {
        return new AwsException("ResourceNotFoundException", "Channel Arn " + arn + " does not exist!", 404);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }
}
