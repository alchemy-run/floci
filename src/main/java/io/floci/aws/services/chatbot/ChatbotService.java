package io.floci.aws.services.chatbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.sns.model.SnsTopicPublishedEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AWS Chatbot (Amazon Q Developer in chat applications): Slack, Microsoft Teams and Chime channel configurations,
 * custom actions and their associations with configurations, user identities, account preferences, and the
 * workspaces and teams an account has onboarded. Onboarding happens in the chat application, outside the API, so
 * every account starts with one Slack workspace and one Microsoft Teams team onboarded ({@link #SLACK_TEAM_ID},
 * {@link #TEAMS_TEAM_ID}); any other workspace or team is refused as AWS refuses one that isn't onboarded.
 *
 * <p>A message published to an SNS topic a configuration lists is delivered to the configuration's channel: it's
 * recorded as the channel's latest notifications, posted to a Chime configuration's webhook, and logged to
 * {@code /aws/chatbot/<configuration name>} by the configuration's logging level ({@code INFO} logs every
 * notification, {@code ERROR} only the ones that couldn't be delivered).
 */
@ApplicationScoped
public class ChatbotService implements Resettable {
    private static final Logger LOG = Logger.getLogger(ChatbotService.class);
    static final String SLACK_TEAM_ID = "TFLOCI00001";
    static final String SLACK_TEAM_NAME = "Floci";
    static final String TEAMS_TEAM_ID = "f10c1000-0000-4000-8000-000000000001";
    static final String TEAMS_TENANT_ID = "f10c1000-0000-4000-8000-0000000000aa";
    static final String TEAMS_TEAM_NAME = "Floci";
    private static final String SCOPE = "global";
    private static final int MAX_NOTIFICATIONS = 50;
    private static final int MAX_TAGS = 50;
    private static final List<String> LOGGING_LEVELS = List.of("ERROR", "INFO", "NONE");

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/chatbot-request-shapes.json", "Chatbot request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return invalidParameter(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return invalidParameter(path + " is outside the allowed bounds" + (min == null ? "" : " (min "
                            + min + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return invalidParameter(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return invalidParameter(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return invalidParameter(path + " is required.");
                }
            });

    private final AccountAwareStorageBackend<ObjectNode> configurations;
    private final AccountAwareStorageBackend<ObjectNode> actions;
    private final AccountAwareStorageBackend<ObjectNode> accounts;
    private final Instance<IamService> iam;
    private final Instance<SnsService> sns;
    private final Instance<CloudWatchLogsService> logs;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService deliveries = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "chatbot-deliveries");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public ChatbotService(StorageFactory storageFactory, Instance<IamService> iam, Instance<SnsService> sns,
                          Instance<CloudWatchLogsService> logs, RegionResolver regionResolver, ObjectMapper mapper) {
        this.configurations = store(storageFactory, "chatbot-configurations.json");
        this.actions = store(storageFactory, "chatbot-custom-actions.json");
        this.accounts = store(storageFactory, "chatbot-accounts.json");
        this.iam = iam;
        this.sns = sns;
        this.logs = logs;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    private static AccountAwareStorageBackend<ObjectNode> store(StorageFactory factory, String file) {
        return factory.create("chatbot", file, new TypeReference<Map<String, ObjectNode>>() {});
    }

    @PreDestroy
    void shutdown() {
        deliveries.shutdownNow();
    }

    static void check(String operation, JsonNode request) {
        SHAPES.check(operation, request);
    }

    // ── Account: preferences, workspaces, teams, identities ────────────────

    /** The account's state: preferences, and the workspaces and teams it has onboarded. */
    private ObjectNode account() {
        return accounts.get(SCOPE).orElseGet(() -> {
            ObjectNode fresh = mapper.createObjectNode();
            fresh.putObject("preferences").put("UserAuthorizationRequired", false)
                    .put("TrainingDataCollectionEnabled", true);
            fresh.putArray("slackWorkspaces").addObject().put("SlackTeamId", SLACK_TEAM_ID)
                    .put("SlackTeamName", SLACK_TEAM_NAME).put("State", "ENABLED");
            fresh.putArray("teams").addObject().put("TenantId", TEAMS_TENANT_ID).put("TeamId", TEAMS_TEAM_ID)
                    .put("TeamName", TEAMS_TEAM_NAME).put("State", "ENABLED");
            fresh.putArray("slackIdentities");
            fresh.putArray("teamsIdentities");
            return fresh;
        });
    }

    public synchronized ObjectNode getPreferences() {
        return mapper.createObjectNode().set("AccountPreferences", account().path("preferences").deepCopy());
    }

    public synchronized ObjectNode updatePreferences(JsonNode in) {
        check("UpdateAccountPreferences", in);
        ObjectNode account = account();
        ObjectNode preferences = account.with("preferences");
        for (String field : List.of("UserAuthorizationRequired", "TrainingDataCollectionEnabled")) {
            if (in.has(field)) {
                preferences.put(field, in.path(field).asBoolean());
            }
        }
        accounts.put(SCOPE, account);
        return mapper.createObjectNode().set("AccountPreferences", preferences.deepCopy());
    }

    public synchronized ObjectNode describeWorkspaces(JsonNode in) {
        check("DescribeSlackWorkspaces", in);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("SlackWorkspaces");
        page(list(account().path("slackWorkspaces")), in, out).forEach(list::add);
        return out;
    }

    /** Removes the workspace's authorization; its channel configurations must be deleted first. */
    public synchronized void deleteWorkspace(JsonNode in) {
        check("DeleteSlackWorkspaceAuthorization", in);
        String team = in.path("SlackTeamId").asText();
        if (all().stream().anyMatch(c -> team.equals(c.path("SlackTeamId").asText()))) {
            throw invalidParameter("The Slack workspace " + team + " has channel configurations; delete them first.");
        }
        ObjectNode account = account();
        remove(account.withArray("slackWorkspaces"), "SlackTeamId", team);
        accounts.put(SCOPE, account);
    }

    public synchronized ObjectNode listTeams(JsonNode in) {
        check("ListMicrosoftTeamsConfiguredTeams", in);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("ConfiguredTeams");
        page(list(account().path("teams")), in, out).forEach(list::add);
        return out;
    }

    public synchronized void deleteTeam(JsonNode in) {
        check("DeleteMicrosoftTeamsConfiguredTeam", in);
        String team = in.path("TeamId").asText();
        ObjectNode account = account();
        if (!remove(account.withArray("teams"), "TeamId", team)) {
            throw notFound("The Microsoft Teams team " + team + " isn't configured for this account.");
        }
        if (all().stream().anyMatch(c -> team.equals(c.path("TeamId").asText()))) {
            throw invalidParameter("The team " + team + " has channel configurations; delete them first.");
        }
        accounts.put(SCOPE, account);
    }

    public synchronized ObjectNode describeSlackIdentities(JsonNode in) {
        check("DescribeSlackUserIdentities", in);
        return identities(in, "slackIdentities", "SlackUserIdentities");
    }

    public synchronized ObjectNode listTeamsIdentities(JsonNode in) {
        check("ListMicrosoftTeamsUserIdentities", in);
        return identities(in, "teamsIdentities", "TeamsUserIdentities");
    }

    private ObjectNode identities(JsonNode in, String field, String output) {
        String configuration = in.path("ChatConfigurationArn").asText(null);
        List<ObjectNode> matching = list(account().path(field)).stream()
                .filter(i -> configuration == null || configuration.equals(i.path("ChatConfigurationArn").asText()))
                .toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray(output);
        page(matching, in, out).forEach(list::add);
        return out;
    }

    public synchronized void deleteSlackIdentity(JsonNode in) {
        check("DeleteSlackUserIdentity", in);
        ObjectNode account = account();
        ArrayNode identities = account.withArray("slackIdentities");
        for (int i = 0; i < identities.size(); i++) {
            JsonNode identity = identities.get(i);
            if (in.path("ChatConfigurationArn").asText().equals(identity.path("ChatConfigurationArn").asText())
                    && in.path("SlackTeamId").asText().equals(identity.path("SlackTeamId").asText())
                    && in.path("SlackUserId").asText().equals(identity.path("SlackUserId").asText())) {
                identities.remove(i);
                accounts.put(SCOPE, account);
                return;
            }
        }
        throw notFound("No Slack user identity " + in.path("SlackUserId").asText() + " is mapped in the "
                + "configuration.");
    }

    public synchronized void deleteTeamsIdentity(JsonNode in) {
        check("DeleteMicrosoftTeamsUserIdentity", in);
        ObjectNode account = account();
        ArrayNode identities = account.withArray("teamsIdentities");
        for (int i = 0; i < identities.size(); i++) {
            JsonNode identity = identities.get(i);
            if (in.path("ChatConfigurationArn").asText().equals(identity.path("ChatConfigurationArn").asText())
                    && in.path("UserId").asText().equals(identity.path("UserId").asText())) {
                identities.remove(i);
                accounts.put(SCOPE, account);
                return;
            }
        }
        throw notFound("No Microsoft Teams user identity " + in.path("UserId").asText() + " is mapped in the "
                + "configuration.");
    }

    // ── Channel configurations ─────────────────────────────────────────────

    public synchronized ObjectNode createSlack(JsonNode in) {
        check("CreateSlackChannelConfiguration", in);
        String team = in.path("SlackTeamId").asText();
        Optional<ObjectNode> workspace = list(account().path("slackWorkspaces")).stream()
                .filter(w -> team.equals(w.path("SlackTeamId").asText())).findFirst();
        if (workspace.isEmpty()) {
            throw new AwsException("InvalidRequestException", "Slack workspace " + team + " is not authorized with "
                    + "AWS account " + regionResolver.getAccountId() + ". Authorize the workspace in the AWS Chatbot "
                    + "console first.", 400);
        }
        ObjectNode configuration = newConfiguration(in, "slack-channel");
        configuration.put("SlackTeamName", workspace.get().path("SlackTeamName").asText());
        configuration.put("SlackTeamId", team);
        configuration.put("SlackChannelId", in.path("SlackChannelId").asText());
        configuration.put("SlackChannelName", in.path("SlackChannelName").asText(in.path("SlackChannelId").asText()));
        save(configuration);
        return mapper.createObjectNode().set("ChannelConfiguration", render(configuration));
    }

    public synchronized ObjectNode createTeams(JsonNode in) {
        check("CreateMicrosoftTeamsChannelConfiguration", in);
        String team = in.path("TeamId").asText();
        Optional<ObjectNode> configured = list(account().path("teams")).stream()
                .filter(t -> team.equals(t.path("TeamId").asText())
                        && in.path("TenantId").asText().equals(t.path("TenantId").asText())).findFirst();
        if (configured.isEmpty()) {
            throw new AwsException("InvalidRequestException", "The team id you are using is not configured for this "
                    + "AWS account. Configure the Microsoft Teams team in the AWS Chatbot console first.", 400);
        }
        ObjectNode configuration = newConfiguration(in, "microsoft-teams-channel");
        configuration.put("ChannelId", in.path("ChannelId").asText());
        configuration.put("ChannelName", in.path("ChannelName").asText(in.path("ChannelId").asText()));
        configuration.put("TeamId", team);
        configuration.put("TeamName", in.path("TeamName").asText(configured.get().path("TeamName").asText()));
        configuration.put("TenantId", in.path("TenantId").asText());
        save(configuration);
        return mapper.createObjectNode().set("ChannelConfiguration", render(configuration));
    }

    public synchronized ObjectNode createChime(JsonNode in) {
        check("CreateChimeWebhookConfiguration", in);
        String url = in.path("WebhookUrl").asText();
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw invalidParameter("WebhookUrl must be an HTTP(S) URL.");
        }
        ObjectNode configuration = newConfiguration(in, "chime-webhook");
        configuration.put("WebhookDescription", in.path("WebhookDescription").asText());
        configuration.put("WebhookUrl", url);
        save(configuration);
        return mapper.createObjectNode().set("WebhookConfiguration", render(configuration));
    }

    private ObjectNode newConfiguration(JsonNode in, String kind) {
        String name = in.path("ConfigurationName").asText();
        if (all().stream().anyMatch(c -> name.equals(c.path("ConfigurationName").asText()))) {
            throw new AwsException("ConflictException", "A configuration named " + name + " already exists.", 409);
        }
        if (all().size() >= 5000) {
            throw new AwsException("LimitExceededException", "The account has the maximum number of channel "
                    + "configurations.", 403);
        }
        ObjectNode configuration = mapper.createObjectNode();
        configuration.put("ChatConfigurationArn", AwsArnUtils.Arn.global(regionResolver.getPartition(), "chatbot",
                regionResolver.getAccountId(), "chat-configuration/" + kind + "/" + name).toString());
        configuration.put("Kind", kind);
        configuration.put("ConfigurationName", name);
        applyCommon(configuration, in, true);
        configuration.put("State", "ENABLED");
        ArrayNode tags = configuration.putArray("Tags");
        in.path("Tags").forEach(t -> tags.add(t.deepCopy()));
        configuration.put("CreatedAt", Instant.now().toEpochMilli());
        configuration.putArray("Notifications");
        configuration.putArray("Associations");
        return configuration;
    }

    /** Role, topics, logging level, guardrails and user authorization, checked as AWS checks them. */
    private void applyCommon(ObjectNode configuration, JsonNode in, boolean creating) {
        if (in.hasNonNull("IamRoleArn")) {
            String role = in.path("IamRoleArn").asText();
            if (!AwsArnUtils.isArnFor(role, "iam") || !role.contains(":role/")) {
                throw invalidParameter("IamRoleArn must be the ARN of an IAM role.");
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(role);
            if (iam.isResolvable() && iam.get().findRole(parsed.accountId(),
                    parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1)).isEmpty()) {
                throw invalidParameter("The IAM role " + role + " doesn't exist.");
            }
            configuration.put("IamRoleArn", role);
        }
        if (in.has("SnsTopicArns") || creating) {
            ArrayNode topics = configuration.putArray("SnsTopicArns");
            for (JsonNode topic : in.path("SnsTopicArns")) {
                String arn = topic.asText();
                if (sns.isResolvable() && AwsArnUtils.isArnFor(arn, "sns")
                        && !sns.get().topicExists(arn, AwsArnUtils.parse(arn).region())) {
                    throw invalidParameter("The SNS topic " + arn + " doesn't exist.");
                }
                topics.add(arn);
            }
        }
        if (in.hasNonNull("LoggingLevel") || creating) {
            String level = in.path("LoggingLevel").asText("NONE").toUpperCase();
            if (!LOGGING_LEVELS.contains(level)) {
                throw invalidParameter("LoggingLevel must be ERROR, INFO or NONE.");
            }
            configuration.put("LoggingLevel", level);
        }
        if (in.has("GuardrailPolicyArns") || creating && !configuration.has("WebhookUrl")
                && !"chime-webhook".equals(configuration.path("Kind").asText())) {
            ArrayNode guardrails = configuration.putArray("GuardrailPolicyArns");
            in.path("GuardrailPolicyArns").forEach(g -> guardrails.add(g.asText()));
            if (guardrails.isEmpty()) {
                guardrails.add(AwsArnUtils.Arn.global(regionResolver.getPartition(), "iam", "aws",
                        "policy/AdministratorAccess").toString());
            }
        }
        if (in.has("UserAuthorizationRequired") || creating && !"chime-webhook".equals(configuration.path("Kind")
                .asText())) {
            configuration.put("UserAuthorizationRequired", in.path("UserAuthorizationRequired").asBoolean(
                    account().path("preferences").path("UserAuthorizationRequired").asBoolean(false)));
        }
    }

    public synchronized ObjectNode describeSlack(JsonNode in) {
        check("DescribeSlackChannelConfigurations", in);
        return describe(in, "slack-channel", "SlackChannelConfigurations");
    }

    public synchronized ObjectNode describeChime(JsonNode in) {
        check("DescribeChimeWebhookConfigurations", in);
        return describe(in, "chime-webhook", "WebhookConfigurations");
    }

    public synchronized ObjectNode listTeamsConfigurations(JsonNode in) {
        check("ListMicrosoftTeamsChannelConfigurations", in);
        String team = in.path("TeamId").asText(null);
        List<ObjectNode> matching = all().stream().filter(c -> "microsoft-teams-channel".equals(c.path("Kind")
                .asText()) && (team == null || team.equals(c.path("TeamId").asText()))).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("TeamChannelConfigurations");
        page(matching, in, out).forEach(c -> list.add(render(c)));
        return out;
    }

    /** Configurations of a kind, or the one an ARN names (an unknown ARN gives an empty list, as on AWS). */
    private ObjectNode describe(JsonNode in, String kind, String output) {
        String arn = in.path("ChatConfigurationArn").asText(null);
        List<ObjectNode> matching = all().stream().filter(c -> kind.equals(c.path("Kind").asText())
                && (arn == null || arn.equals(c.path("ChatConfigurationArn").asText()))).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray(output);
        page(matching, in, out).forEach(c -> list.add(render(c)));
        return out;
    }

    public synchronized ObjectNode getTeams(JsonNode in) {
        check("GetMicrosoftTeamsChannelConfiguration", in);
        ObjectNode configuration = require(in.path("ChatConfigurationArn").asText(), "microsoft-teams-channel");
        return mapper.createObjectNode().set("ChannelConfiguration", render(configuration));
    }

    public synchronized ObjectNode updateSlack(JsonNode in) {
        check("UpdateSlackChannelConfiguration", in);
        ObjectNode configuration = require(in.path("ChatConfigurationArn").asText(), "slack-channel");
        configuration.put("SlackChannelId", in.path("SlackChannelId").asText());
        if (in.hasNonNull("SlackChannelName")) {
            configuration.put("SlackChannelName", in.path("SlackChannelName").asText());
        }
        applyCommon(configuration, in, false);
        save(configuration);
        return mapper.createObjectNode().set("ChannelConfiguration", render(configuration));
    }

    public synchronized ObjectNode updateTeams(JsonNode in) {
        check("UpdateMicrosoftTeamsChannelConfiguration", in);
        ObjectNode configuration = require(in.path("ChatConfigurationArn").asText(), "microsoft-teams-channel");
        configuration.put("ChannelId", in.path("ChannelId").asText());
        if (in.hasNonNull("ChannelName")) {
            configuration.put("ChannelName", in.path("ChannelName").asText());
        }
        applyCommon(configuration, in, false);
        save(configuration);
        return mapper.createObjectNode().set("ChannelConfiguration", render(configuration));
    }

    public synchronized ObjectNode updateChime(JsonNode in) {
        check("UpdateChimeWebhookConfiguration", in);
        ObjectNode configuration = require(in.path("ChatConfigurationArn").asText(), "chime-webhook");
        for (String field : List.of("WebhookDescription", "WebhookUrl")) {
            if (in.hasNonNull(field)) {
                configuration.put(field, in.path(field).asText());
            }
        }
        applyCommon(configuration, in, false);
        save(configuration);
        return mapper.createObjectNode().set("WebhookConfiguration", render(configuration));
    }

    /** Deletes a configuration, with its user identities and its custom action associations. */
    public synchronized void delete(JsonNode in, String kind, String operation) {
        check(operation, in);
        String arn = in.path("ChatConfigurationArn").asText();
        require(arn, kind);
        configurations.delete(arn);
        ObjectNode account = account();
        for (String field : List.of("slackIdentities", "teamsIdentities")) {
            ArrayNode identities = account.withArray(field);
            for (int i = identities.size() - 1; i >= 0; i--) {
                if (arn.equals(identities.get(i).path("ChatConfigurationArn").asText())) {
                    identities.remove(i);
                }
            }
        }
        accounts.put(SCOPE, account);
    }

    private ObjectNode render(ObjectNode stored) {
        ObjectNode out = stored.deepCopy();
        out.remove("Kind");
        out.remove("CreatedAt");
        out.remove("Notifications");
        out.remove("Associations");
        return out;
    }

    private ObjectNode require(String arn, String kind) {
        return configurations.get(arn).filter(c -> kind.equals(c.path("Kind").asText()))
                .orElseThrow(() -> notFound("The configuration " + arn + " doesn't exist."));
    }

    private void save(ObjectNode configuration) {
        configurations.put(configuration.path("ChatConfigurationArn").asText(), configuration);
    }

    private List<ObjectNode> all() {
        return configurations.scan(k -> true).stream()
                .sorted(Comparator.comparing(c -> c.path("CreatedAt").asLong())).toList();
    }

    // ── Custom actions ─────────────────────────────────────────────────────

    public synchronized ObjectNode createAction(JsonNode in) {
        check("CreateCustomAction", in);
        String name = in.path("ActionName").asText();
        String arn = actionArn(name);
        Optional<ObjectNode> existing = actions.get(name);
        if (existing.isPresent()) {
            if (in.hasNonNull("ClientToken")
                    && in.path("ClientToken").asText().equals(existing.get().path("ClientToken").asText())) {
                return mapper.createObjectNode().put("CustomActionArn", arn);
            }
            throw new AwsException("ConflictException", "A custom action named " + name + " already exists.", 409);
        }
        checkAlias(in.path("AliasName").asText(null), name);
        if (actions.scan(k -> true).size() >= 200) {
            throw new AwsException("LimitExceededException", "The account has the maximum of 200 custom actions.",
                    403);
        }
        ObjectNode action = mapper.createObjectNode();
        action.put("CustomActionArn", arn);
        action.put("ActionName", name);
        action.set("Definition", in.path("Definition").deepCopy());
        copy(in, action, "AliasName", "Attachments", "ClientToken");
        ArrayNode tags = action.putArray("Tags");
        in.path("Tags").forEach(t -> tags.add(t.deepCopy()));
        actions.put(name, action);
        return mapper.createObjectNode().put("CustomActionArn", arn);
    }

    public synchronized ObjectNode getAction(JsonNode in) {
        check("GetCustomAction", in);
        ObjectNode action = requireAction(in.path("CustomActionArn").asText());
        ObjectNode out = mapper.createObjectNode();
        copy(action, out, "CustomActionArn", "Definition", "AliasName", "Attachments", "ActionName");
        return mapper.createObjectNode().set("CustomAction", out);
    }

    public synchronized ObjectNode updateAction(JsonNode in) {
        check("UpdateCustomAction", in);
        ObjectNode action = requireAction(in.path("CustomActionArn").asText());
        checkAlias(in.path("AliasName").asText(null), action.path("ActionName").asText());
        action.set("Definition", in.path("Definition").deepCopy());
        for (String field : List.of("AliasName", "Attachments")) {
            if (in.has(field)) {
                action.set(field, in.get(field).deepCopy());
            } else {
                action.remove(field);
            }
        }
        actions.put(action.path("ActionName").asText(), action);
        return mapper.createObjectNode().put("CustomActionArn", action.path("CustomActionArn").asText());
    }

    /** Deletes an action and its associations. */
    public synchronized void deleteAction(JsonNode in) {
        check("DeleteCustomAction", in);
        ObjectNode action = requireAction(in.path("CustomActionArn").asText());
        actions.delete(action.path("ActionName").asText());
        for (ObjectNode configuration : all()) {
            if (remove(configuration.withArray("Associations"), null, action.path("CustomActionArn").asText())) {
                save(configuration);
            }
        }
    }

    public synchronized ObjectNode listActions(JsonNode in) {
        check("ListCustomActions", in);
        List<ObjectNode> all = actions.scan(k -> true).stream()
                .sorted(Comparator.comparing(a -> a.path("ActionName").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("CustomActions");
        page(all, in, out).forEach(a -> list.add(a.path("CustomActionArn").asText()));
        return out;
    }

    /** Aliases are unique in the account, as they name the action in chat ({@code @aws run <alias>}). */
    private void checkAlias(String alias, String name) {
        if (alias != null && actions.scan(k -> true).stream().anyMatch(a -> alias.equals(a.path("AliasName").asText())
                && !name.equals(a.path("ActionName").asText()))) {
            throw new AwsException("ConflictException", "The alias " + alias + " is used by another custom action.",
                    409);
        }
    }

    private ObjectNode requireAction(String arn) {
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        return actions.get(name).filter(a -> arn.equals(a.path("CustomActionArn").asText()))
                .orElseThrow(() -> notFound("The custom action " + arn + " doesn't exist."));
    }

    private String actionArn(String name) {
        return AwsArnUtils.Arn.global(regionResolver.getPartition(), "chatbot", regionResolver.getAccountId(),
                "custom-action/" + name).toString();
    }

    // ── Associations ───────────────────────────────────────────────────────

    public synchronized void associate(JsonNode in) {
        check("AssociateToConfiguration", in);
        ObjectNode configuration = configurations.get(in.path("ChatConfiguration").asText()).orElseThrow(() ->
                notFound("The configuration " + in.path("ChatConfiguration").asText() + " doesn't exist."));
        requireAction(in.path("Resource").asText());
        ArrayNode associations = configuration.withArray("Associations");
        for (JsonNode association : associations) {
            if (in.path("Resource").asText().equals(association.asText())) {
                return;
            }
        }
        if (associations.size() >= 50) {
            throw new AwsException("InvalidRequestException", "A configuration can have at most 50 custom actions.",
                    400);
        }
        associations.add(in.path("Resource").asText());
        save(configuration);
    }

    public synchronized void disassociate(JsonNode in) {
        check("DisassociateFromConfiguration", in);
        ObjectNode configuration = configurations.get(in.path("ChatConfiguration").asText()).orElseThrow(() ->
                notFound("The configuration " + in.path("ChatConfiguration").asText() + " doesn't exist."));
        if (!remove(configuration.withArray("Associations"), null, in.path("Resource").asText())) {
            throw notFound("The resource " + in.path("Resource").asText() + " isn't associated with the "
                    + "configuration.");
        }
        save(configuration);
    }

    public synchronized ObjectNode listAssociations(JsonNode in) {
        check("ListAssociations", in);
        List<ObjectNode> listings = new ArrayList<>();
        configurations.get(in.path("ChatConfiguration").asText()).ifPresent(c -> c.path("Associations")
                .forEach(a -> listings.add(mapper.createObjectNode().put("Resource", a.asText()))));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Associations");
        page(listings, in, out).forEach(list::add);
        return out;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public synchronized ObjectNode listTags(JsonNode in) {
        check("ListTagsForResource", in);
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagged(in.path("ResourceARN").asText()).path("Tags").deepCopy());
        return out;
    }

    public synchronized void tag(JsonNode in) {
        check("TagResource", in);
        ObjectNode resource = tagged(in.path("ResourceARN").asText());
        ArrayNode tags = resource.withArray("Tags");
        for (JsonNode tag : in.path("Tags")) {
            remove(tags, "TagKey", tag.path("TagKey").asText());
            tags.add(tag.deepCopy());
        }
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("TooManyTagsException", "A resource can have at most " + MAX_TAGS + " tags.", 400);
        }
        saveTagged(resource);
    }

    public synchronized void untag(JsonNode in) {
        check("UntagResource", in);
        ObjectNode resource = tagged(in.path("ResourceARN").asText());
        ArrayNode tags = resource.withArray("Tags");
        in.path("TagKeys").forEach(k -> remove(tags, "TagKey", k.asText()));
        saveTagged(resource);
    }

    private ObjectNode tagged(String arn) {
        if (arn.contains(":custom-action/")) {
            return requireAction(arn);
        }
        return configurations.get(arn).orElseThrow(() -> notFound("The resource " + arn + " doesn't exist."));
    }

    private void saveTagged(ObjectNode resource) {
        if (resource.has("ActionName")) {
            actions.put(resource.path("ActionName").asText(), resource);
        } else {
            save(resource);
        }
    }

    // ── Delivery ───────────────────────────────────────────────────────────

    /** Delivers an SNS message to the channels of every configuration that lists its topic. */
    void onPublished(@Observes SnsTopicPublishedEvent event) {
        List<AccountAwareStorageBackend.AccountEntry<ObjectNode>> subscribed = configurations
                .scanAllAccountEntries(k -> true).stream()
                .filter(e -> contains(e.value().path("SnsTopicArns"), event.topicArn()))
                .toList();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : subscribed) {
            deliveries.execute(() -> RequestScopes.runAs(entry.accountId(), event.region(),
                    () -> deliver(entry.key(), event)));
        }
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }

    private void deliver(String arn, SnsTopicPublishedEvent event) {
        ObjectNode configuration;
        synchronized (this) {
            configuration = configurations.get(arn).orElse(null);
        }
        if (configuration == null || !"ENABLED".equals(configuration.path("State").asText())) {
            return;
        }
        ChatbotNotifications.Rendered rendered = ChatbotNotifications.render(event.subject(), event.message(),
                mapper);
        String error = null;
        if (rendered == null) {
            error = "The event isn't one AWS Chatbot supports: it isn't a CloudWatch alarm, an EventBridge event of a "
                    + "supported service, or an AWS Chatbot custom notification.";
        } else if ("chime-webhook".equals(configuration.path("Kind").asText())) {
            error = postToChime(configuration.path("WebhookUrl").asText(), rendered);
        }
        synchronized (this) {
            configuration = configurations.get(arn).orElse(null);
            if (configuration == null) {
                return;
            }
            ArrayNode notifications = configuration.withArray("Notifications");
            ObjectNode record = notifications.addObject();
            record.put("MessageId", event.messageId());
            record.put("TopicArn", event.topicArn());
            record.put("DeliveredAt", Instant.now().toString());
            record.put("Status", error == null ? "DELIVERED" : "FAILED");
            if (rendered != null) {
                record.put("Title", rendered.title());
                record.put("Text", rendered.text());
            }
            if (error != null) {
                record.put("Error", error);
            }
            while (notifications.size() > MAX_NOTIFICATIONS) {
                notifications.remove(0);
            }
            save(configuration);
        }
        String level = configuration.path("LoggingLevel").asText("NONE");
        if ("INFO".equals(level) || "ERROR".equals(level) && error != null) {
            log(configuration, event, rendered, error);
        }
    }

    private String postToChime(String url, ChatbotNotifications.Rendered rendered) {
        try {
            String body = mapper.createObjectNode().put("Content", "/md " + rendered.title() + "\n" + rendered.text())
                    .toString();
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 300 ? null : "The Chime webhook answered HTTP " + response.statusCode() + ".";
        } catch (Exception e) {
            return "The Chime webhook couldn't be reached: " + e.getMessage();
        }
    }

    private void log(ObjectNode configuration, SnsTopicPublishedEvent event, ChatbotNotifications.Rendered rendered,
                     String error) {
        if (!logs.isResolvable()) {
            return;
        }
        String group = "/aws/chatbot/" + configuration.path("ConfigurationName").asText();
        String stream = configuration.path("SlackChannelId").asText(configuration.path("ChannelId").asText("webhook"));
        ObjectNode entry = mapper.createObjectNode();
        entry.put("level", error == null ? "INFO" : "ERROR");
        entry.put("message", error == null ? "Sent a message to the channel." : error);
        entry.put("chatConfigurationArn", configuration.path("ChatConfigurationArn").asText());
        entry.put("snsTopicArn", event.topicArn());
        entry.put("snsMessageId", event.messageId());
        if (rendered != null) {
            entry.put("title", rendered.title());
        }
        try {
            try {
                logs.get().createLogGroup(group, null, Map.of(), event.region());
            } catch (AwsException e) {
                // It exists: Chatbot creates the group once per configuration name.
            }
            try {
                logs.get().createLogStream(group, stream, event.region());
            } catch (AwsException e) {
                // It exists.
            }
            logs.get().putLogEvents(group, stream, List.of(Map.of("timestamp", Instant.now().toEpochMilli(),
                    "message", entry.toString())), event.region());
        } catch (RuntimeException e) {
            LOG.debugv("Could not log a Chatbot notification to {0}: {1}", group, e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<ObjectNode> list(JsonNode array) {
        List<ObjectNode> out = new ArrayList<>();
        array.forEach(item -> out.add((ObjectNode) item.deepCopy()));
        return out;
    }

    /** Removes the items whose {@code field} (or, with no field, whose text) is {@code value}. */
    private static boolean remove(ArrayNode array, String field, String value) {
        boolean removed = false;
        for (int i = array.size() - 1; i >= 0; i--) {
            String current = field == null ? array.get(i).asText() : array.get(i).path(field).asText();
            if (value.equals(current)) {
                array.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    private List<ObjectNode> page(List<ObjectNode> all, JsonNode in, ObjectNode out) {
        int max = in.path("MaxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(in.path("NextToken").asText());
            } catch (NumberFormatException e) {
                throw invalidParameter("The NextToken isn't valid.");
            }
        }
        if (start < 0 || start > all.size()) {
            throw invalidParameter("The NextToken isn't valid.");
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("NextToken", String.valueOf(end));
        }
        return all.subList(start, end);
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    /** The notifications a configuration's channel received, newest last. */
    public synchronized List<ObjectNode> notifications(String arn) {
        return configurations.get(arn).map(c -> list(c.path("Notifications"))).orElse(List.of());
    }

    @Override
    public void clear() {
        configurations.clear();
        actions.clear();
        accounts.clear();
    }
}
