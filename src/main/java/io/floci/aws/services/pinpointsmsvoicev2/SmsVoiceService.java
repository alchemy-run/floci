package io.floci.aws.services.pinpointsmsvoicev2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.firehose.FirehoseService;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.pinpointsmsvoicev2.SmsVoiceCatalog.Country;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * AWS End User Messaging SMS and Voice: configuration sets and their event destinations, opt-out lists, phone
 * numbers, pools, sender IDs, keywords, protect configurations, verified destination numbers, RCS agents, Notify
 * configurations, spend limits, resource policies and tags. Sending is {@link SmsVoiceMessaging}; registrations are
 * {@link SmsVoiceRegistrations}.
 */
@ApplicationScoped
public class SmsVoiceService {

    static final String DEFAULT_LIST = "Default";
    private static final List<String> CAPABILITIES = List.of("SMS", "VOICE", "MMS", "RCS");
    private static final Map<String, Integer> LIMITS = Map.of("PHONE_NUMBERS", 100, "POOLS", 100,
            "CONFIGURATION_SETS", 100, "OPT_OUT_LISTS", 100, "SENDER_IDS", 100, "REGISTRATIONS", 100,
            "REGISTRATION_ATTACHMENTS", 100, "VERIFIED_DESTINATION_NUMBERS", 10);
    static final List<String> SPEND = List.of("TEXT", "VOICE", "MEDIA", "NOTIFY", "RCS");

    private final SmsVoiceStore store;
    private final ObjectMapper mapper;
    private final Provider<SnsService> sns;
    private final Provider<CloudWatchLogsService> logs;
    private final Provider<FirehoseService> firehose;
    private final Provider<IamService> iam;

    @Inject
    public SmsVoiceService(SmsVoiceStore store, Provider<SnsService> sns, Provider<CloudWatchLogsService> logs,
                           Provider<FirehoseService> firehose, Provider<IamService> iam) {
        this.store = store;
        this.mapper = store.mapper();
        this.sns = sns;
        this.logs = logs;
        this.firehose = firehose;
        this.iam = iam;
    }

    // ================================================================ helpers

    ObjectNode out() {
        return mapper.createObjectNode();
    }

    static String req(JsonNode in, String member) {
        String value = in.path(member).asText("");
        if (value.isBlank()) {
            throw SmsVoiceStore.validation("MISSING_PARAMETER", member + " is required.");
        }
        return value;
    }

    static String opt(JsonNode in, String member) {
        return in.hasNonNull(member) && !in.get(member).asText().isBlank() ? in.get(member).asText() : null;
    }

    void save(ObjectNode record) {
        store.putForAccount(SmsVoiceStore.owner(record), SmsVoiceStore.region(record),
                SmsVoiceStore.meta(record).path("kind").asText(), SmsVoiceStore.id(record), record);
    }

    private ObjectNode copy(ObjectNode record, String... fields) {
        ObjectNode view = out();
        for (String field : fields) {
            if (record.has(field) && !record.get(field).isNull()) {
                view.set(field, record.get(field).deepCopy());
            }
        }
        return view;
    }

    private ObjectNode withTags(ObjectNode view, ObjectNode record) {
        view.set("Tags", SmsVoiceStore.tagList(record, mapper));
        return view;
    }

    private void quota(String region, String kind, String limit, String reason) {
        if (store.list(region, kind).size() >= LIMITS.get(limit)) {
            throw SmsVoiceStore.quota(reason, "The account has reached its " + limit.toLowerCase(Locale.ROOT)
                    .replace('_', ' ') + " quota.");
        }
    }

    /** Whether a resource's owner or its resource policy lets the caller use it. */
    boolean accessible(ObjectNode record) {
        String caller = store.account();
        if (caller.equals(SmsVoiceStore.owner(record))) {
            return true;
        }
        String policy = SmsVoiceStore.meta(record).path("policy").asText("");
        return policy.contains("\"" + caller + "\"") || policy.contains(":" + caller + ":root");
    }

    /** Records of a kind the caller owns ({@code SELF}) or that are shared with it ({@code SHARED}). */
    private List<ObjectNode> owned(String region, String kind, JsonNode in) {
        if (!"SHARED".equals(opt(in, "Owner"))) {
            return store.all(region, kind);
        }
        List<ObjectNode> shared = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(kind)) {
            ObjectNode record = entry.value();
            if (!entry.accountId().equals(store.account()) && region.equals(SmsVoiceStore.region(record))
                    && accessible(record)) {
                shared.add(store.settle(record));
            }
        }
        return shared;
    }

    /** Whether a record passes every {@code Filters} entry, by the named attributes. */
    private static boolean matches(ObjectNode record, JsonNode filters,
                                   Map<String, Function<ObjectNode, List<String>>> attributes) {
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText();
            Function<ObjectNode, List<String>> attribute = attributes.get(name);
            if (attribute == null) {
                throw SmsVoiceStore.validation("INVALID_FILTER_VALUES", "The filter " + name + " isn't supported.");
            }
            Set<String> wanted = new HashSet<>();
            filter.path("Values").forEach(value -> wanted.add(value.asText()));
            if (attribute.apply(record).stream().noneMatch(wanted::contains)) {
                return false;
            }
        }
        return true;
    }

    private static Function<ObjectNode, List<String>> field(String member) {
        return record -> {
            JsonNode value = record.get(member);
            if (value == null || value.isNull()) {
                return List.of();
            }
            if (value.isArray()) {
                List<String> values = new ArrayList<>();
                value.forEach(item -> values.add(item.asText()));
                return values;
            }
            return List.of(value.asText());
        };
    }

    private ObjectNode page(JsonNode in, List<ObjectNode> views, String name) {
        ObjectNode out = out();
        SmsVoiceStore.page(new ArrayList<JsonNode>(views), in, out, name);
        return out;
    }

    /** A record of a kind by id, with status changes applied, or a ResourceNotFoundException. */
    ObjectNode require(String region, String kind, String id) {
        return store.find(region, kind, id).orElseThrow(() -> SmsVoiceStore.notFound(kind, id));
    }

    /** The id a reference names: the id itself, or the resource part of the ARN after the type. */
    private static String idOf(String reference, String kind) {
        if (AwsArnUtils.isArn(reference)) {
            String resource = AwsArnUtils.parse(reference).resource();
            if (!resource.startsWith(kind + "/")) {
                throw SmsVoiceStore.validation("INVALID_ARN", "The ARN " + reference + " isn't a " + kind + " ARN.");
            }
            return resource.substring(kind.length() + 1);
        }
        return reference;
    }

    // ================================================================ account

    ObjectNode account(String region) {
        return store.get(region, SmsVoiceStore.ACCOUNT, "settings").orElseGet(() -> {
            ObjectNode record = store.record();
            record.putObject("SpendOverrides");
            record.putObject("Spent");
            return record;
        });
    }

    void saveAccount(String region, ObjectNode record) {
        store.put(region, SmsVoiceStore.ACCOUNT, "settings", record);
    }

    public ObjectNode describeAccountAttributes(JsonNode in, String region) {
        List<ObjectNode> attributes = new ArrayList<>();
        attributes.add(out().put("Name", "ACCOUNT_TIER").put("Value", store.config().accountTier()));
        String defaultProtect = account(region).path("DefaultProtectConfigurationId").asText("");
        if (!defaultProtect.isEmpty()) {
            attributes.add(out().put("Name", "DEFAULT_PROTECT_CONFIGURATION_ID").put("Value", defaultProtect));
        }
        return page(in, attributes, "AccountAttributes");
    }

    public ObjectNode describeAccountLimits(JsonNode in, String region) {
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put("PHONE_NUMBERS", SmsVoiceStore.PHONE);
        kinds.put("POOLS", SmsVoiceStore.POOL);
        kinds.put("CONFIGURATION_SETS", SmsVoiceStore.CONFIG_SET);
        kinds.put("OPT_OUT_LISTS", SmsVoiceStore.OPT_OUT_LIST);
        kinds.put("SENDER_IDS", SmsVoiceStore.SENDER_ID);
        kinds.put("REGISTRATIONS", SmsVoiceStore.REGISTRATION);
        kinds.put("REGISTRATION_ATTACHMENTS", SmsVoiceStore.ATTACHMENT);
        kinds.put("VERIFIED_DESTINATION_NUMBERS", SmsVoiceStore.VERIFIED);
        List<ObjectNode> limits = new ArrayList<>();
        kinds.forEach((name, kind) -> limits.add(out().put("Name", name).put("Used", store.list(region, kind).size())
                .put("Max", LIMITS.get(name))));
        return page(in, limits, "AccountLimits");
    }

    /** The monthly spend limit Floci enforces for a kind of message, in US dollars. */
    long enforcedLimit(ObjectNode account, String kind) {
        JsonNode override = account.path("SpendOverrides").get(kind);
        return override == null ? store.config().monthlySpendLimit() : override.asLong();
    }

    public ObjectNode describeSpendLimits(JsonNode in, String region) {
        ObjectNode account = account(region);
        List<ObjectNode> limits = new ArrayList<>();
        for (String kind : SPEND) {
            String name = kind + "_MESSAGE_MONTHLY_SPEND_LIMIT";
            limits.add(out().put("Name", name).put("EnforcedLimit", enforcedLimit(account, kind))
                    .put("MaxLimit", store.config().monthlySpendLimit())
                    .put("Overridden", account.path("SpendOverrides").has(kind)));
        }
        return page(in, limits, "SpendLimits");
    }

    private ObjectNode setSpend(JsonNode in, String region, String kind) {
        long limit = in.path("MonthlyLimit").asLong();
        if (limit > store.config().monthlySpendLimit()) {
            throw SmsVoiceStore.validation("REQUESTED_SPEND_LIMIT_HIGHER_THAN_SERVICE_LIMIT", "The monthly limit "
                    + limit + " is higher than the service limit of " + store.config().monthlySpendLimit() + ".");
        }
        ObjectNode account = account(region);
        ((ObjectNode) account.get("SpendOverrides")).put(kind, limit);
        saveAccount(region, account);
        return out().put("MonthlyLimit", limit);
    }

    private ObjectNode deleteSpend(String region, String kind) {
        ObjectNode account = account(region);
        JsonNode previous = ((ObjectNode) account.get("SpendOverrides")).remove(kind);
        saveAccount(region, account);
        ObjectNode out = out();
        if (previous != null) {
            out.put("MonthlyLimit", previous.asLong());
        }
        return out;
    }

    public ObjectNode setTextMessageSpendLimitOverride(JsonNode in, String region) {
        return setSpend(in, region, "TEXT");
    }

    public ObjectNode setVoiceMessageSpendLimitOverride(JsonNode in, String region) {
        return setSpend(in, region, "VOICE");
    }

    public ObjectNode setMediaMessageSpendLimitOverride(JsonNode in, String region) {
        return setSpend(in, region, "MEDIA");
    }

    public ObjectNode setNotifyMessageSpendLimitOverride(JsonNode in, String region) {
        return setSpend(in, region, "NOTIFY");
    }

    public ObjectNode setRcsMessageSpendLimitOverride(JsonNode in, String region) {
        return setSpend(in, region, "RCS");
    }

    public ObjectNode deleteTextMessageSpendLimitOverride(JsonNode in, String region) {
        return deleteSpend(region, "TEXT");
    }

    public ObjectNode deleteVoiceMessageSpendLimitOverride(JsonNode in, String region) {
        return deleteSpend(region, "VOICE");
    }

    public ObjectNode deleteMediaMessageSpendLimitOverride(JsonNode in, String region) {
        return deleteSpend(region, "MEDIA");
    }

    public ObjectNode deleteNotifyMessageSpendLimitOverride(JsonNode in, String region) {
        return deleteSpend(region, "NOTIFY");
    }

    public ObjectNode deleteRcsMessageSpendLimitOverride(JsonNode in, String region) {
        return deleteSpend(region, "RCS");
    }

    // ================================================================ configuration sets

    private static final String[] CONFIG_FIELDS = {"ConfigurationSetArn", "ConfigurationSetName",
        "EventDestinations", "DefaultMessageType", "DefaultSenderId", "DefaultMessageFeedbackEnabled",
        "CreatedTimestamp", "ProtectConfigurationId"};

    ObjectNode configSet(String region, String reference) {
        return require(region, SmsVoiceStore.CONFIG_SET, idOf(reference, SmsVoiceStore.CONFIG_SET));
    }

    public ObjectNode createConfigurationSet(JsonNode in, String region) {
        String name = req(in, "ConfigurationSetName");
        if (store.get(region, SmsVoiceStore.CONFIG_SET, name).isPresent()) {
            throw SmsVoiceStore.conflict("RESOURCE_ALREADY_EXISTS", SmsVoiceStore.CONFIG_SET, name,
                    "The configuration set " + name + " already exists.");
        }
        quota(region, SmsVoiceStore.CONFIG_SET, "CONFIGURATION_SETS", "CONFIGURATION_SETS_PER_ACCOUNT");
        ObjectNode record = store.record();
        record.put("ConfigurationSetArn", store.arn(region, SmsVoiceStore.CONFIG_SET + "/" + name))
                .put("ConfigurationSetName", name);
        record.putArray("EventDestinations");
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.CONFIG_SET, name, record);
        return withTags(copy(record, "ConfigurationSetArn", "ConfigurationSetName", "CreatedTimestamp"), record);
    }

    public ObjectNode describeConfigurationSets(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("ConfigurationSetNames").isArray() && !in.get("ConfigurationSetNames").isEmpty()) {
            in.get("ConfigurationSetNames").forEach(name -> records.add(configSet(region, name.asText())));
        } else {
            records.addAll(store.all(region, SmsVoiceStore.CONFIG_SET));
        }
        Map<String, Function<ObjectNode, List<String>>> attributes = new LinkedHashMap<>();
        attributes.put("event-destination-name", record -> {
            List<String> names = new ArrayList<>();
            record.path("EventDestinations").forEach(d -> names.add(d.path("EventDestinationName").asText()));
            return names;
        });
        attributes.put("matching-event-types", record -> {
            List<String> types = new ArrayList<>();
            record.path("EventDestinations").forEach(d -> d.path("MatchingEventTypes").forEach(t ->
                    types.add(t.asText())));
            return types;
        });
        attributes.put("default-message-type", field("DefaultMessageType"));
        attributes.put("default-sender-id", field("DefaultSenderId"));
        attributes.put("default-message-feedback-enabled", field("DefaultMessageFeedbackEnabled"));
        attributes.put("protect-configuration-id", field("ProtectConfigurationId"));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            if (matches(record, in.path("Filters"), attributes)) {
                views.add(copy(record, CONFIG_FIELDS));
            }
        }
        return page(in, views, "ConfigurationSets");
    }

    public ObjectNode deleteConfigurationSet(JsonNode in, String region) {
        ObjectNode record = configSet(region, req(in, "ConfigurationSetName"));
        store.delete(region, SmsVoiceStore.CONFIG_SET, SmsVoiceStore.id(record));
        return copy(record, "ConfigurationSetArn", "ConfigurationSetName", "EventDestinations", "DefaultMessageType",
                "DefaultSenderId", "DefaultMessageFeedbackEnabled", "CreatedTimestamp");
    }

    public ObjectNode setDefaultMessageType(JsonNode in, String region) {
        ObjectNode record = configSet(region, req(in, "ConfigurationSetName"));
        record.put("DefaultMessageType", req(in, "MessageType"));
        save(record);
        return copy(record, "ConfigurationSetArn", "ConfigurationSetName").put("MessageType",
                record.get("DefaultMessageType").asText());
    }

    public ObjectNode deleteDefaultMessageType(JsonNode in, String region) {
        ObjectNode record = configSet(region, req(in, "ConfigurationSetName"));
        JsonNode previous = record.remove("DefaultMessageType");
        save(record);
        ObjectNode out = copy(record, "ConfigurationSetArn", "ConfigurationSetName");
        if (previous != null) {
            out.set("MessageType", previous);
        }
        return out;
    }

    public ObjectNode setDefaultSenderId(JsonNode in, String region) {
        ObjectNode record = configSet(region, req(in, "ConfigurationSetName"));
        String senderId = req(in, "SenderId");
        if (!senderId.matches("[A-Za-z0-9_-]{1,11}")) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", "A sender ID is 1 to 11 letters, digits, - or _.");
        }
        record.put("DefaultSenderId", senderId);
        save(record);
        return copy(record, "ConfigurationSetArn", "ConfigurationSetName").put("SenderId", senderId);
    }

    public ObjectNode deleteDefaultSenderId(JsonNode in, String region) {
        ObjectNode record = configSet(region, req(in, "ConfigurationSetName"));
        JsonNode previous = record.remove("DefaultSenderId");
        save(record);
        ObjectNode out = copy(record, "ConfigurationSetArn", "ConfigurationSetName");
        if (previous != null) {
            out.set("SenderId", previous);
        }
        return out;
    }

    public ObjectNode setDefaultMessageFeedbackEnabled(JsonNode in, String region) {
        ObjectNode record = configSet(region, req(in, "ConfigurationSetName"));
        record.put("DefaultMessageFeedbackEnabled", in.path("MessageFeedbackEnabled").asBoolean());
        save(record);
        return copy(record, "ConfigurationSetArn", "ConfigurationSetName").put("MessageFeedbackEnabled",
                record.get("DefaultMessageFeedbackEnabled").asBoolean());
    }

    // ---------------------------------------------------------------- event destinations

    private void checkRole(String roleArn, String region) {
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !AwsArnUtils.parse(roleArn).resource().startsWith("role/")) {
            throw SmsVoiceStore.validation("INVALID_ARN", "IamRoleArn " + roleArn + " isn't a role ARN.");
        }
        String resource = AwsArnUtils.parse(roleArn).resource();
        try {
            RequestScopes.callAs(AwsArnUtils.accountOrDefault(roleArn, store.account()), region, () ->
                    iam.get().getRole(resource.substring(resource.lastIndexOf('/') + 1)));
        } catch (AwsException e) {
            throw SmsVoiceStore.validation("RESOURCE_NOT_ACCESSIBLE", "The role " + roleArn + " can't be used: "
                    + e.getMessage());
        }
    }

    /** Fails unless a destination names exactly one target, and that target exists. */
    private void checkDestination(JsonNode in, String region) {
        int targets = (in.has("CloudWatchLogsDestination") ? 1 : 0) + (in.has("KinesisFirehoseDestination") ? 1 : 0)
                + (in.has("SnsDestination") ? 1 : 0);
        if (targets != 1) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", "An event destination names exactly one of "
                    + "CloudWatchLogsDestination, KinesisFirehoseDestination and SnsDestination.");
        }
        try {
            if (in.has("SnsDestination")) {
                String topic = req(in.get("SnsDestination"), "TopicArn");
                AwsArnUtils.Arn arn = AwsArnUtils.parse(topic);
                RequestScopes.callAs(arn.accountId(), arn.region(), () -> sns.get().getTopicAttributes(topic,
                        arn.region()));
            } else if (in.has("CloudWatchLogsDestination")) {
                JsonNode destination = in.get("CloudWatchLogsDestination");
                checkRole(req(destination, "IamRoleArn"), region);
                AwsArnUtils.Arn arn = AwsArnUtils.parse(req(destination, "LogGroupArn"));
                String group = groupName(arn.resource());
                boolean found = RequestScopes.callAs(arn.accountId(), arn.region(), () -> logs.get()
                        .describeLogGroups(group, arn.region()).stream()
                        .anyMatch(g -> g.getLogGroupName().equals(group)));
                if (!found) {
                    throw SmsVoiceStore.validation("RESOURCE_NOT_ACCESSIBLE", "The log group " + group
                            + " doesn't exist.");
                }
            } else {
                JsonNode destination = in.get("KinesisFirehoseDestination");
                checkRole(req(destination, "IamRoleArn"), region);
                AwsArnUtils.Arn arn = AwsArnUtils.parse(req(destination, "DeliveryStreamArn"));
                firehose.get().describeDeliveryStream(arn.accountId(), arn.region(),
                        arn.resource().substring(arn.resource().indexOf('/') + 1));
            }
        } catch (IllegalArgumentException e) {
            throw SmsVoiceStore.validation("INVALID_ARN", e.getMessage());
        } catch (AwsException e) {
            if ("ValidationException".equals(e.getErrorCode())) {
                throw e;
            }
            throw SmsVoiceStore.validation("RESOURCE_NOT_ACCESSIBLE", "The event destination's target can't be "
                    + "used: " + e.getMessage());
        }
    }

    static String groupName(String resource) {
        String name = resource.substring(resource.indexOf(':') + 1);
        return name.endsWith(":*") ? name.substring(0, name.length() - 2) : name;
    }

    private static ObjectNode destinationBody(ObjectNode destination, JsonNode in) {
        for (String target : List.of("CloudWatchLogsDestination", "KinesisFirehoseDestination", "SnsDestination")) {
            destination.remove(target);
            if (in.has(target)) {
                destination.set(target, in.get(target).deepCopy());
            }
        }
        return destination;
    }

    public ObjectNode createEventDestination(JsonNode in, String region) {
        ObjectNode record = configSet(region, req(in, "ConfigurationSetName"));
        String name = req(in, "EventDestinationName");
        ArrayNode destinations = (ArrayNode) record.get("EventDestinations");
        for (JsonNode existing : destinations) {
            if (existing.path("EventDestinationName").asText().equals(name)) {
                throw SmsVoiceStore.conflict("RESOURCE_ALREADY_EXISTS", "event-destination", name,
                        "The event destination " + name + " already exists.");
            }
        }
        if (destinations.size() >= 5) {
            throw SmsVoiceStore.quota("EVENT_DESTINATIONS_PER_CONFIGURATION_SET", "A configuration set has at most "
                    + "5 event destinations.");
        }
        checkDestination(in, region);
        ObjectNode destination = mapper.createObjectNode().put("EventDestinationName", name).put("Enabled", true);
        destination.set("MatchingEventTypes", in.get("MatchingEventTypes").deepCopy());
        destinations.add(destinationBody(destination, in));
        save(record);
        ObjectNode out = copy(record, "ConfigurationSetArn", "ConfigurationSetName");
        out.set("EventDestination", destination);
        return out;
    }

    private ObjectNode destination(ObjectNode record, String name) {
        for (JsonNode destination : record.path("EventDestinations")) {
            if (destination.path("EventDestinationName").asText().equals(name)) {
                return (ObjectNode) destination;
            }
        }
        throw SmsVoiceStore.notFound("event-destination", name);
    }

    public ObjectNode updateEventDestination(JsonNode in, String region) {
        ObjectNode record = configSet(region, req(in, "ConfigurationSetName"));
        ObjectNode destination = destination(record, req(in, "EventDestinationName"));
        if (in.has("Enabled")) {
            destination.put("Enabled", in.get("Enabled").asBoolean());
        }
        if (in.path("MatchingEventTypes").isArray()) {
            destination.set("MatchingEventTypes", in.get("MatchingEventTypes").deepCopy());
        }
        if (in.has("CloudWatchLogsDestination") || in.has("KinesisFirehoseDestination") || in.has("SnsDestination")) {
            checkDestination(in, region);
            destinationBody(destination, in);
        }
        save(record);
        ObjectNode out = copy(record, "ConfigurationSetArn", "ConfigurationSetName");
        out.set("EventDestination", destination);
        return out;
    }

    public ObjectNode deleteEventDestination(JsonNode in, String region) {
        ObjectNode record = configSet(region, req(in, "ConfigurationSetName"));
        ObjectNode destination = destination(record, req(in, "EventDestinationName"));
        ArrayNode kept = mapper.createArrayNode();
        record.path("EventDestinations").forEach(d -> {
            if (d != destination) {
                kept.add(d);
            }
        });
        record.set("EventDestinations", kept);
        save(record);
        ObjectNode out = copy(record, "ConfigurationSetArn", "ConfigurationSetName");
        out.set("EventDestination", destination);
        return out;
    }

    // ================================================================ opt-out lists

    ObjectNode optOutList(String region, String reference) {
        String name = idOf(reference, SmsVoiceStore.OPT_OUT_LIST);
        if (DEFAULT_LIST.equals(name)) {
            return ensureDefaultList(region);
        }
        return require(region, SmsVoiceStore.OPT_OUT_LIST, name);
    }

    /** The account's Default opt-out list, made the first time it's needed. */
    ObjectNode ensureDefaultList(String region) {
        return store.get(region, SmsVoiceStore.OPT_OUT_LIST, DEFAULT_LIST).orElseGet(() -> {
            ObjectNode record = store.record();
            record.put("OptOutListArn", store.arn(region, SmsVoiceStore.OPT_OUT_LIST + "/" + DEFAULT_LIST))
                    .put("OptOutListName", DEFAULT_LIST);
            store.put(region, SmsVoiceStore.OPT_OUT_LIST, DEFAULT_LIST, record);
            return record;
        });
    }

    public ObjectNode createOptOutList(JsonNode in, String region) {
        String name = req(in, "OptOutListName");
        ensureDefaultList(region);
        if (store.get(region, SmsVoiceStore.OPT_OUT_LIST, name).isPresent()) {
            throw SmsVoiceStore.conflict("RESOURCE_ALREADY_EXISTS", SmsVoiceStore.OPT_OUT_LIST, name,
                    "The opt-out list " + name + " already exists.");
        }
        quota(region, SmsVoiceStore.OPT_OUT_LIST, "OPT_OUT_LISTS", "OPT_OUT_LISTS_PER_ACCOUNT");
        ObjectNode record = store.record();
        record.put("OptOutListArn", store.arn(region, SmsVoiceStore.OPT_OUT_LIST + "/" + name))
                .put("OptOutListName", name);
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.OPT_OUT_LIST, name, record);
        return withTags(copy(record, "OptOutListArn", "OptOutListName", "CreatedTimestamp"), record);
    }

    public ObjectNode describeOptOutLists(JsonNode in, String region) {
        ensureDefaultList(region);
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("OptOutListNames").isArray() && !in.get("OptOutListNames").isEmpty()) {
            in.get("OptOutListNames").forEach(name -> records.add(optOutList(region, name.asText())));
        } else {
            records.addAll(owned(region, SmsVoiceStore.OPT_OUT_LIST, in));
        }
        List<ObjectNode> views = new ArrayList<>();
        records.forEach(record -> views.add(copy(record, "OptOutListArn", "OptOutListName", "CreatedTimestamp")));
        return page(in, views, "OptOutLists");
    }

    public ObjectNode deleteOptOutList(JsonNode in, String region) {
        ObjectNode record = optOutList(region, req(in, "OptOutListName"));
        String name = record.get("OptOutListName").asText();
        if (DEFAULT_LIST.equals(name)) {
            throw SmsVoiceStore.conflict("RESOURCE_DELETION_NOT_ALLOWED", SmsVoiceStore.OPT_OUT_LIST, name,
                    "The Default opt-out list can't be deleted.");
        }
        for (String kind : List.of(SmsVoiceStore.PHONE, SmsVoiceStore.POOL, SmsVoiceStore.RCS)) {
            for (ObjectNode user : store.list(region, kind)) {
                if (name.equals(user.path("OptOutListName").asText())) {
                    throw SmsVoiceStore.conflict("RESOURCE_DELETION_NOT_ALLOWED", SmsVoiceStore.OPT_OUT_LIST, name,
                            "The opt-out list is used by " + SmsVoiceStore.id(user) + ".");
                }
            }
        }
        store.delete(region, SmsVoiceStore.OPT_OUT_LIST, name);
        for (ObjectNode number : store.list(region, SmsVoiceStore.OPTED_OUT, name + "/")) {
            store.delete(region, SmsVoiceStore.OPTED_OUT, SmsVoiceStore.id(number));
        }
        return copy(record, "OptOutListArn", "OptOutListName", "CreatedTimestamp");
    }

    /** Opts a number out of a list, as the end user ({@code STOP}) or by the account. */
    ObjectNode optOut(String region, String list, String number, boolean endUser) {
        ObjectNode record = store.record();
        record.put("OptedOutNumber", number).put("OptedOutTimestamp", SmsVoiceStore.now())
                .put("EndUserOptedOut", endUser);
        store.put(region, SmsVoiceStore.OPTED_OUT, list + "/" + number, record);
        return record;
    }

    boolean optedOut(String region, String list, String number) {
        return store.get(region, SmsVoiceStore.OPTED_OUT, list + "/" + number).isPresent();
    }

    void optIn(String region, String list, String number) {
        store.delete(region, SmsVoiceStore.OPTED_OUT, list + "/" + number);
    }

    public ObjectNode putOptedOutNumber(JsonNode in, String region) {
        ObjectNode list = optOutList(region, req(in, "OptOutListName"));
        String number = SmsVoiceCatalog.e164(req(in, "OptedOutNumber"), "OptedOutNumber");
        String name = list.get("OptOutListName").asText();
        ObjectNode record = store.get(region, SmsVoiceStore.OPTED_OUT, name + "/" + number)
                .orElseGet(() -> optOut(region, name, number, false));
        return copy(list, "OptOutListArn", "OptOutListName").setAll(copy(record, "OptedOutNumber",
                "OptedOutTimestamp", "EndUserOptedOut"));
    }

    public ObjectNode deleteOptedOutNumber(JsonNode in, String region) {
        ObjectNode list = optOutList(region, req(in, "OptOutListName"));
        String number = req(in, "OptedOutNumber");
        String name = list.get("OptOutListName").asText();
        ObjectNode record = store.get(region, SmsVoiceStore.OPTED_OUT, name + "/" + number)
                .orElseThrow(() -> SmsVoiceStore.notFound("opted-out-number", number));
        if (record.path("EndUserOptedOut").asBoolean() && store.config().endUserOptOutsLocked()) {
            throw SmsVoiceStore.validation("PHONE_NUMBER_CANNOT_BE_OPTED_IN", "The end user opted out of " + name
                    + "; only they can opt back in, by replying with an opt-in keyword.");
        }
        optIn(region, name, number);
        return copy(list, "OptOutListArn", "OptOutListName").setAll(copy(record, "OptedOutNumber",
                "OptedOutTimestamp", "EndUserOptedOut"));
    }

    public ObjectNode describeOptedOutNumbers(JsonNode in, String region) {
        ObjectNode list = optOutList(region, req(in, "OptOutListName"));
        String name = list.get("OptOutListName").asText();
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("OptedOutNumbers").isArray() && !in.get("OptedOutNumbers").isEmpty()) {
            in.get("OptedOutNumbers").forEach(number -> store.get(region, SmsVoiceStore.OPTED_OUT, name + "/"
                    + number.asText()).ifPresent(records::add));
        } else {
            records.addAll(store.list(region, SmsVoiceStore.OPTED_OUT, name + "/"));
        }
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            if (matches(record, in.path("Filters"), Map.of("end-user-opted-out", field("EndUserOptedOut")))) {
                views.add(copy(record, "OptedOutNumber", "OptedOutTimestamp", "EndUserOptedOut"));
            }
        }
        ObjectNode out = page(in, views, "OptedOutNumbers");
        out.setAll(copy(list, "OptOutListArn", "OptOutListName"));
        return out;
    }

    // ================================================================ phone numbers

    private static final String[] PHONE_FIELDS = {"PhoneNumberArn", "PhoneNumberId", "PhoneNumber", "Status",
        "IsoCountryCode", "MessageType", "NumberCapabilities", "NumberType", "MonthlyLeasingPrice", "TwoWayEnabled",
        "TwoWayChannelArn", "TwoWayChannelRole", "SelfManagedOptOutsEnabled", "OptOutListName",
        "InternationalSendingEnabled", "DeletionProtectionEnabled", "PoolId", "RegistrationId", "CreatedTimestamp"};

    ObjectNode phone(String region, String reference) {
        if (reference.startsWith("+")) {
            for (ObjectNode phone : store.all(region, SmsVoiceStore.PHONE)) {
                if (reference.equals(phone.path("PhoneNumber").asText())) {
                    return phone;
                }
            }
            throw SmsVoiceStore.notFound(SmsVoiceStore.PHONE, reference);
        }
        return require(region, SmsVoiceStore.PHONE, idOf(reference, SmsVoiceStore.PHONE));
    }

    private void checkTwoWay(JsonNode in, ObjectNode record) {
        if (in.has("TwoWayChannelArn")) {
            String channel = in.get("TwoWayChannelArn").asText();
            if (!channel.startsWith("connect.") && !AwsArnUtils.isArnFor(channel, "sns")) {
                throw SmsVoiceStore.validation("INVALID_ARN", "TwoWayChannelArn must be an SNS topic ARN or "
                        + "the Amazon Connect service.");
            }
            record.put("TwoWayChannelArn", channel);
        }
        if (in.has("TwoWayChannelRole")) {
            record.put("TwoWayChannelRole", in.get("TwoWayChannelRole").asText());
        }
        if (in.has("TwoWayEnabled")) {
            boolean enabled = in.get("TwoWayEnabled").asBoolean();
            if (enabled && !record.hasNonNull("TwoWayChannelArn")) {
                throw SmsVoiceStore.validation("TWO_WAY_CHANNEL_NOT_PRESENT", "Two-way messaging needs a "
                        + "TwoWayChannelArn.");
            }
            record.put("TwoWayEnabled", enabled);
        }
    }

    /** The keywords every new phone number and pool answers. */
    void seedKeywords(String region, String identity) {
        keyword(region, identity, "HELP", "This is an automated message. Reply STOP to opt out.",
                "AUTOMATIC_RESPONSE");
        keyword(region, identity, "STOP", "You've been unsubscribed and will receive no more messages.", "OPT_OUT");
    }

    private ObjectNode keyword(String region, String identity, String keyword, String message, String action) {
        ObjectNode record = store.record();
        record.put("Keyword", keyword).put("KeywordMessage", message).put("KeywordAction", action);
        store.put(region, SmsVoiceStore.KEYWORD, identity + "/" + keyword.toUpperCase(Locale.ROOT), record);
        return record;
    }

    public ObjectNode requestPhoneNumber(JsonNode in, String region) {
        Country country = SmsVoiceCatalog.country(req(in, "IsoCountryCode"));
        String type = req(in, "NumberType");
        SmsVoiceCatalog.checkNumberType(type, country);
        List<String> capabilities = new ArrayList<>();
        in.path("NumberCapabilities").forEach(c -> capabilities.add(c.asText()));
        if (capabilities.contains("RCS")) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", "Phone numbers don't send RCS; RCS agents do.");
        }
        if ("SIMULATOR".equals(type) && !capabilities.equals(List.of("SMS")) && !capabilities.contains("SMS")) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", "Simulator numbers send SMS.");
        }
        String registration = opt(in, "RegistrationId");
        if ("TEN_DLC".equals(type)) {
            if (registration == null) {
                throw SmsVoiceStore.validation("MISSING_PARAMETER", "A TEN_DLC number needs the RegistrationId of "
                        + "a 10DLC campaign registration.");
            }
            ObjectNode campaign = require(region, SmsVoiceStore.REGISTRATION, registration);
            if (!"COMPLETE".equals(campaign.path("RegistrationStatus").asText())
                    || !"US_TEN_DLC_CAMPAIGN_REGISTRATION".equals(campaign.path("RegistrationType").asText())) {
                throw SmsVoiceStore.conflict("REGISTRATION_NOT_COMPLETE", SmsVoiceStore.REGISTRATION, registration,
                        "The 10DLC campaign registration isn't COMPLETE.");
            }
        } else if (registration != null) {
            require(region, SmsVoiceStore.REGISTRATION, registration);
        }
        String list = opt(in, "OptOutListName") == null ? DEFAULT_LIST : in.get("OptOutListName").asText();
        optOutList(region, list);
        String poolId = opt(in, "PoolId");
        ObjectNode pool = poolId == null ? null : pool(region, poolId);
        quota(region, SmsVoiceStore.PHONE, "PHONE_NUMBERS", "PHONE_NUMBERS_PER_ACCOUNT");
        String number = SmsVoiceCatalog.newNumber(type, country);
        JsonNode preferences = in.path("NumberPreference");
        if (preferences.isArray() && !preferences.isEmpty()) {
            number = preferred(number, preferences, country, type);
        }
        String id = "phone-" + SmsVoiceStore.hex(32);
        ObjectNode record = store.record();
        record.put("PhoneNumberArn", store.arn(region, SmsVoiceStore.PHONE + "/" + id)).put("PhoneNumberId", id)
                .put("PhoneNumber", number).put("IsoCountryCode", country.iso())
                .put("MessageType", req(in, "MessageType")).put("NumberType", type)
                .put("MonthlyLeasingPrice", SmsVoiceCatalog.leasePrice(type)).put("TwoWayEnabled", false)
                .put("SelfManagedOptOutsEnabled", false).put("OptOutListName", list)
                .put("InternationalSendingEnabled", in.path("InternationalSendingEnabled").asBoolean(false))
                .put("DeletionProtectionEnabled", in.path("DeletionProtectionEnabled").asBoolean(false));
        ArrayNode caps = record.putArray("NumberCapabilities");
        capabilities.forEach(caps::add);
        if (registration != null) {
            record.put("RegistrationId", registration);
        }
        store.transition(record, "Status", "PENDING", "ACTIVE");
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.PHONE, id, record);
        seedKeywords(region, id);
        if (pool != null) {
            associate(region, pool, record, null);
            record = phone(region, id);
        }
        return withTags(copy(record, PHONE_FIELDS), record);
    }

    private String preferred(String fallback, JsonNode preferences, Country country, String type) {
        for (int attempt = 0; attempt < 2000; attempt++) {
            String candidate = attempt == 0 ? fallback : SmsVoiceCatalog.newNumber(type, country);
            boolean ok = true;
            for (JsonNode preference : preferences) {
                for (JsonNode filter : preference.path("Filter")) {
                    String digits = filter.asText();
                    boolean any = false;
                    for (JsonNode kind : preference.path("PreferenceType")) {
                        String local = candidate.substring(1 + country.dial().length());
                        any |= switch (kind.asText()) {
                            case "StartsWith" -> local.startsWith(digits);
                            case "EndsWith" -> local.endsWith(digits);
                            case "ExactMatch" -> local.equals(digits) || candidate.equals(digits);
                            default -> local.contains(digits);
                        };
                    }
                    ok &= any;
                }
            }
            if (ok) {
                return candidate;
            }
            if (attempt > 0 && preferences.toString().contains("ExactMatch")) {
                break;
            }
        }
        throw SmsVoiceStore.conflict("NO_ORIGINATION_IDENTITIES_FOUND", SmsVoiceStore.PHONE, "",
                "No available number matches the NumberPreference.");
    }

    public ObjectNode describePhoneNumbers(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("PhoneNumberIds").isArray() && !in.get("PhoneNumberIds").isEmpty()) {
            in.get("PhoneNumberIds").forEach(id -> records.add(phone(region, id.asText())));
        } else {
            records.addAll(owned(region, SmsVoiceStore.PHONE, in));
        }
        Map<String, Function<ObjectNode, List<String>>> attributes = new LinkedHashMap<>();
        attributes.put("status", field("Status"));
        attributes.put("iso-country-code", field("IsoCountryCode"));
        attributes.put("message-type", field("MessageType"));
        attributes.put("number-capability", field("NumberCapabilities"));
        attributes.put("number-type", field("NumberType"));
        attributes.put("two-way-enabled", field("TwoWayEnabled"));
        attributes.put("self-managed-opt-outs-enabled", field("SelfManagedOptOutsEnabled"));
        attributes.put("opt-out-list-name", field("OptOutListName"));
        attributes.put("deletion-protection-enabled", field("DeletionProtectionEnabled"));
        attributes.put("two-way-channel-arn", field("TwoWayChannelArn"));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            if (matches(record, in.path("Filters"), attributes)) {
                ObjectNode view = copy(record, PHONE_FIELDS);
                view.set("MessagingLimits", limits());
                views.add(view);
            }
        }
        return page(in, views, "PhoneNumbers");
    }

    private ObjectNode limits() {
        ObjectNode limits = out();
        limits.putObject("RateLimits").put("SMS", 1).put("VOICE", 1).put("MMS", 1);
        limits.putObject("DailyMessageCaps").put("SMS", 100000).put("MMS", 100000);
        return limits;
    }

    public ObjectNode updatePhoneNumber(JsonNode in, String region) {
        ObjectNode record = phone(region, req(in, "PhoneNumberId"));
        if (record.hasNonNull("PoolId") && (in.has("TwoWayEnabled") || in.has("OptOutListName")
                || in.has("SelfManagedOptOutsEnabled"))) {
            throw SmsVoiceStore.conflict("PHONE_NUMBER_ASSOCIATED_TO_POOL", SmsVoiceStore.PHONE,
                    SmsVoiceStore.id(record), "Update the pool the number belongs to instead.");
        }
        checkTwoWay(in, record);
        if (in.has("OptOutListName")) {
            record.put("OptOutListName", optOutList(region, in.get("OptOutListName").asText())
                    .get("OptOutListName").asText());
        }
        for (String member : List.of("SelfManagedOptOutsEnabled", "InternationalSendingEnabled",
                "DeletionProtectionEnabled")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asBoolean());
            }
        }
        save(record);
        return copy(record, PHONE_FIELDS);
    }

    public ObjectNode releasePhoneNumber(JsonNode in, String region) {
        ObjectNode record = phone(region, req(in, "PhoneNumberId"));
        String id = SmsVoiceStore.id(record);
        if (record.path("DeletionProtectionEnabled").asBoolean()) {
            throw SmsVoiceStore.conflict("DELETION_PROTECTION_ENABLED", SmsVoiceStore.PHONE, id,
                    "Turn off deletion protection before releasing the number.");
        }
        if (record.hasNonNull("PoolId")) {
            ObjectNode pool = pool(region, record.get("PoolId").asText());
            if (members(region, pool).size() <= 1) {
                throw SmsVoiceStore.conflict("LAST_PHONE_NUMBER", SmsVoiceStore.POOL, SmsVoiceStore.id(pool),
                        "The number is the last in its pool; delete the pool first.");
            }
        }
        store.delete(region, SmsVoiceStore.PHONE, id);
        deleteKeywords(region, id);
        record.put("Status", "DELETED");
        return copy(record, PHONE_FIELDS);
    }

    private void deleteKeywords(String region, String identity) {
        for (ObjectNode keyword : store.list(region, SmsVoiceStore.KEYWORD, identity + "/")) {
            store.delete(region, SmsVoiceStore.KEYWORD, SmsVoiceStore.id(keyword));
        }
    }

    public ObjectNode listAvailablePhoneNumbers(JsonNode in, String region) {
        Country country = SmsVoiceCatalog.country(req(in, "IsoCountryCode"));
        String type = req(in, "NumberType");
        SmsVoiceCatalog.checkNumberType(type, country);
        if (opt(in, "RegistrationId") != null) {
            require(region, SmsVoiceStore.REGISTRATION, in.get("RegistrationId").asText());
        }
        Set<String> numbers = new LinkedHashSet<>();
        int wanted = Math.max(1, Math.min(in.path("MaxResults").asInt(10), 25));
        JsonNode preferences = in.path("NumberPreference");
        for (int i = 0; numbers.size() < wanted && i < wanted * 4; i++) {
            String candidate = SmsVoiceCatalog.newNumber(type, country);
            numbers.add(preferences.isArray() && !preferences.isEmpty() ? preferred(candidate, preferences, country,
                    type) : candidate);
        }
        ObjectNode out = out();
        ArrayNode list = out.putArray("AvailablePhoneNumbers");
        numbers.forEach(list::add);
        return out;
    }

    // ================================================================ sender IDs

    private static final String[] SENDER_FIELDS = {"SenderIdArn", "SenderId", "IsoCountryCode", "MessageTypes",
        "MonthlyLeasingPrice", "DeletionProtectionEnabled", "Registered", "RegistrationId"};

    ObjectNode senderId(String region, String senderId, String iso) {
        return require(region, SmsVoiceStore.SENDER_ID, senderId + "/" + iso);
    }

    public ObjectNode requestSenderId(JsonNode in, String region) {
        String senderId = req(in, "SenderId");
        if (!senderId.matches("[A-Za-z0-9_-]{1,11}") || senderId.matches("[0-9]+")) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", "A sender ID is 1 to 11 letters, digits, - or _, "
                    + "with at least one letter.");
        }
        Country country = SmsVoiceCatalog.country(req(in, "IsoCountryCode"));
        if (country.iso().equals("US") || country.iso().equals("CA")) {
            throw SmsVoiceStore.validation("SENDER_ID_NOT_SUPPORTED", "Sender IDs aren't supported in "
                    + country.name() + ".");
        }
        String id = senderId + "/" + country.iso();
        if (store.get(region, SmsVoiceStore.SENDER_ID, id).isPresent()) {
            throw SmsVoiceStore.conflict("RESOURCE_ALREADY_EXISTS", SmsVoiceStore.SENDER_ID, id,
                    "The sender ID " + senderId + " is already registered in " + country.iso() + ".");
        }
        quota(region, SmsVoiceStore.SENDER_ID, "SENDER_IDS", "SENDER_IDS_PER_ACCOUNT");
        ObjectNode record = store.record();
        record.put("SenderIdArn", store.arn(region, SmsVoiceStore.SENDER_ID + "/" + id)).put("SenderId", senderId)
                .put("IsoCountryCode", country.iso()).put("MonthlyLeasingPrice", "0.00")
                .put("DeletionProtectionEnabled", in.path("DeletionProtectionEnabled").asBoolean(false))
                .put("Registered", !country.iso().equals("GB"));
        ArrayNode types = record.putArray("MessageTypes");
        if (in.path("MessageTypes").isArray() && !in.get("MessageTypes").isEmpty()) {
            in.get("MessageTypes").forEach(types::add);
        } else {
            types.add("TRANSACTIONAL").add("PROMOTIONAL");
        }
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.SENDER_ID, id, record);
        return withTags(copy(record, SENDER_FIELDS), record);
    }

    public ObjectNode describeSenderIds(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("SenderIds").isArray() && !in.get("SenderIds").isEmpty()) {
            in.get("SenderIds").forEach(s -> records.add(senderId(region, s.path("SenderId").asText(),
                    s.path("IsoCountryCode").asText())));
        } else {
            records.addAll(owned(region, SmsVoiceStore.SENDER_ID, in));
        }
        Map<String, Function<ObjectNode, List<String>>> attributes = new LinkedHashMap<>();
        attributes.put("sender-id", field("SenderId"));
        attributes.put("iso-country-code", field("IsoCountryCode"));
        attributes.put("message-type", field("MessageTypes"));
        attributes.put("deletion-protection-enabled", field("DeletionProtectionEnabled"));
        attributes.put("registered", field("Registered"));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            if (matches(record, in.path("Filters"), attributes)) {
                views.add(copy(record, SENDER_FIELDS).set("MessagingLimits", limits()));
            }
        }
        return page(in, views, "SenderIds");
    }

    public ObjectNode updateSenderId(JsonNode in, String region) {
        ObjectNode record = senderId(region, req(in, "SenderId"), req(in, "IsoCountryCode"));
        if (in.has("DeletionProtectionEnabled")) {
            record.put("DeletionProtectionEnabled", in.get("DeletionProtectionEnabled").asBoolean());
        }
        save(record);
        return copy(record, SENDER_FIELDS);
    }

    public ObjectNode releaseSenderId(JsonNode in, String region) {
        ObjectNode record = senderId(region, req(in, "SenderId"), req(in, "IsoCountryCode"));
        String id = SmsVoiceStore.id(record);
        if (record.path("DeletionProtectionEnabled").asBoolean()) {
            throw SmsVoiceStore.conflict("DELETION_PROTECTION_ENABLED", SmsVoiceStore.SENDER_ID, id,
                    "Turn off deletion protection before releasing the sender ID.");
        }
        if (record.hasNonNull("PoolId")) {
            throw SmsVoiceStore.conflict("SENDER_ID_ASSOCIATED_TO_POOL", SmsVoiceStore.SENDER_ID, id,
                    "Remove the sender ID from its pool first.");
        }
        store.delete(region, SmsVoiceStore.SENDER_ID, id);
        return copy(record, SENDER_FIELDS);
    }

    // ================================================================ pools

    private static final String[] POOL_FIELDS = {"PoolArn", "PoolId", "Status", "MessageType", "TwoWayEnabled",
        "TwoWayChannelArn", "TwoWayChannelRole", "SelfManagedOptOutsEnabled", "OptOutListName", "SharedRoutesEnabled",
        "DeletionProtectionEnabled", "CreatedTimestamp"};

    ObjectNode pool(String region, String reference) {
        return require(region, SmsVoiceStore.POOL, idOf(reference, SmsVoiceStore.POOL));
    }

    /** An origination identity by id, ARN or number: a phone number, sender ID or RCS agent. */
    ObjectNode member(String region, String reference, String iso) {
        if (reference.startsWith("+") || reference.startsWith("phone-")
                || reference.contains(":" + SmsVoiceStore.PHONE + "/")) {
            return phone(region, reference);
        }
        if (reference.startsWith("rcs-") || reference.contains(":" + SmsVoiceStore.RCS + "/")) {
            return require(region, SmsVoiceStore.RCS, idOf(reference, SmsVoiceStore.RCS));
        }
        if (AwsArnUtils.isArn(reference)) {
            String id = idOf(reference, SmsVoiceStore.SENDER_ID);
            return require(region, SmsVoiceStore.SENDER_ID, id);
        }
        if (iso != null) {
            return senderId(region, reference, iso);
        }
        for (ObjectNode sender : store.all(region, SmsVoiceStore.SENDER_ID)) {
            if (reference.equals(sender.path("SenderId").asText())) {
                return sender;
            }
        }
        throw SmsVoiceStore.notFound(SmsVoiceStore.SENDER_ID, reference);
    }

    static String kind(ObjectNode record) {
        return SmsVoiceStore.meta(record).path("kind").asText();
    }

    static String arnOf(ObjectNode record) {
        return switch (kind(record)) {
            case SmsVoiceStore.PHONE -> record.path("PhoneNumberArn").asText();
            case SmsVoiceStore.SENDER_ID -> record.path("SenderIdArn").asText();
            case SmsVoiceStore.RCS -> record.path("RcsAgentArn").asText();
            default -> record.path("PoolArn").asText();
        };
    }

    List<ObjectNode> members(String region, ObjectNode pool) {
        String poolId = SmsVoiceStore.id(pool);
        List<ObjectNode> members = new ArrayList<>();
        for (String kind : List.of(SmsVoiceStore.PHONE, SmsVoiceStore.SENDER_ID, SmsVoiceStore.RCS)) {
            for (ObjectNode record : store.all(region, kind)) {
                if (poolId.equals(record.path("PoolId").asText())) {
                    members.add(record);
                }
            }
        }
        return members;
    }

    /** Adds an origination identity to a pool, checking it matches the pool's settings. */
    private void associate(String region, ObjectNode pool, ObjectNode member, String iso) {
        String id = SmsVoiceStore.id(member);
        String kind = kind(member);
        if (member.hasNonNull("PoolId")) {
            throw SmsVoiceStore.conflict(SmsVoiceStore.PHONE.equals(kind) ? "PHONE_NUMBER_ASSOCIATED_TO_POOL"
                    : SmsVoiceStore.RCS.equals(kind) ? "RCS_AGENT_ASSOCIATED_TO_POOL" : "SENDER_ID_ASSOCIATED_TO_POOL",
                    kind, id, id + " already belongs to the pool " + member.get("PoolId").asText() + ".");
        }
        if (SmsVoiceStore.PHONE.equals(kind)) {
            if (!pool.path("MessageType").asText().equals(member.path("MessageType").asText())) {
                throw SmsVoiceStore.conflict("MESSAGE_TYPE_MISMATCH", kind, id, "The number's message type isn't "
                        + "the pool's.");
            }
            if (!pool.path("OptOutListName").asText().equals(member.path("OptOutListName").asText())) {
                throw SmsVoiceStore.conflict("OPT_OUT_LIST_MISMATCH", kind, id, "The number's opt-out list isn't "
                        + "the pool's.");
            }
            if (pool.path("TwoWayEnabled").asBoolean() != member.path("TwoWayEnabled").asBoolean()
                    || !pool.path("TwoWayChannelArn").asText().equals(member.path("TwoWayChannelArn").asText())) {
                throw SmsVoiceStore.conflict("TWO_WAY_CONFIG_MISMATCH", kind, id, "The number's two-way settings "
                        + "aren't the pool's.");
            }
            if (pool.path("SelfManagedOptOutsEnabled").asBoolean()
                    != member.path("SelfManagedOptOutsEnabled").asBoolean()) {
                throw SmsVoiceStore.conflict("SELF_MANAGED_OPT_OUTS_MISMATCH", kind, id, "The number's opt-out "
                        + "management isn't the pool's.");
            }
        } else if (SmsVoiceStore.SENDER_ID.equals(kind) && iso != null
                && !iso.equals(member.path("IsoCountryCode").asText())) {
            throw SmsVoiceStore.validation("COUNTRY_CODE_MISMATCH", "The sender ID is for "
                    + member.path("IsoCountryCode").asText() + ", not " + iso + ".");
        }
        member.put("PoolId", SmsVoiceStore.id(pool));
        save(member);
    }

    public ObjectNode createPool(JsonNode in, String region) {
        ObjectNode member = member(region, req(in, "OriginationIdentity"), opt(in, "IsoCountryCode"));
        quota(region, SmsVoiceStore.POOL, "POOLS", "POOLS_PER_ACCOUNT");
        String id = "pool-" + SmsVoiceStore.hex(32);
        ObjectNode record = store.record();
        String messageType = req(in, "MessageType");
        record.put("PoolArn", store.arn(region, SmsVoiceStore.POOL + "/" + id)).put("PoolId", id)
                .put("MessageType", messageType)
                .put("TwoWayEnabled", member.path("TwoWayEnabled").asBoolean(false))
                .put("SelfManagedOptOutsEnabled", member.path("SelfManagedOptOutsEnabled").asBoolean(false))
                .put("OptOutListName", member.path("OptOutListName").asText(DEFAULT_LIST))
                .put("SharedRoutesEnabled", false)
                .put("DeletionProtectionEnabled", in.path("DeletionProtectionEnabled").asBoolean(false));
        if (member.hasNonNull("TwoWayChannelArn")) {
            record.put("TwoWayChannelArn", member.get("TwoWayChannelArn").asText());
        }
        store.transition(record, "Status", "CREATING", "ACTIVE");
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.POOL, id, record);
        try {
            associate(region, record, member, opt(in, "IsoCountryCode"));
        } catch (AwsException e) {
            store.delete(region, SmsVoiceStore.POOL, id);
            throw e;
        }
        seedKeywords(region, id);
        return withTags(copy(record, POOL_FIELDS), record);
    }

    public ObjectNode describePools(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("PoolIds").isArray() && !in.get("PoolIds").isEmpty()) {
            in.get("PoolIds").forEach(id -> records.add(pool(region, id.asText())));
        } else {
            records.addAll(owned(region, SmsVoiceStore.POOL, in));
        }
        Map<String, Function<ObjectNode, List<String>>> attributes = new LinkedHashMap<>();
        for (String[] pair : new String[][] {{"status", "Status"}, {"message-type", "MessageType"},
            {"two-way-enabled", "TwoWayEnabled"}, {"self-managed-opt-outs-enabled", "SelfManagedOptOutsEnabled"},
            {"opt-out-list-name", "OptOutListName"}, {"shared-routes-enabled", "SharedRoutesEnabled"},
            {"deletion-protection-enabled", "DeletionProtectionEnabled"}, {"two-way-channel-arn", "TwoWayChannelArn"}}) {
            attributes.put(pair[0], field(pair[1]));
        }
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            if (matches(record, in.path("Filters"), attributes)) {
                views.add(copy(record, POOL_FIELDS));
            }
        }
        return page(in, views, "Pools");
    }

    public ObjectNode updatePool(JsonNode in, String region) {
        ObjectNode record = pool(region, req(in, "PoolId"));
        checkTwoWay(in, record);
        if (in.has("OptOutListName")) {
            record.put("OptOutListName", optOutList(region, in.get("OptOutListName").asText())
                    .get("OptOutListName").asText());
        }
        for (String member : List.of("SelfManagedOptOutsEnabled", "SharedRoutesEnabled",
                "DeletionProtectionEnabled")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asBoolean());
            }
        }
        save(record);
        for (ObjectNode member : members(region, record)) {
            if (SmsVoiceStore.PHONE.equals(kind(member))) {
                for (String field : List.of("TwoWayEnabled", "TwoWayChannelArn", "TwoWayChannelRole",
                        "SelfManagedOptOutsEnabled", "OptOutListName")) {
                    if (record.has(field)) {
                        member.set(field, record.get(field));
                    }
                }
                save(member);
            }
        }
        return copy(record, POOL_FIELDS);
    }

    public ObjectNode deletePool(JsonNode in, String region) {
        ObjectNode record = pool(region, req(in, "PoolId"));
        String id = SmsVoiceStore.id(record);
        if (record.path("DeletionProtectionEnabled").asBoolean()) {
            throw SmsVoiceStore.conflict("DELETION_PROTECTION_ENABLED", SmsVoiceStore.POOL, id,
                    "Turn off deletion protection before deleting the pool.");
        }
        for (ObjectNode notify : store.list(region, SmsVoiceStore.NOTIFY)) {
            if (id.equals(notify.path("PoolId").asText())) {
                throw SmsVoiceStore.conflict("POOL_ASSOCIATED_TO_NOTIFY_CONFIGURATION", SmsVoiceStore.POOL, id,
                        "The pool is used by the Notify configuration " + SmsVoiceStore.id(notify) + ".");
            }
        }
        for (ObjectNode member : members(region, record)) {
            member.remove("PoolId");
            save(member);
        }
        store.delete(region, SmsVoiceStore.POOL, id);
        deleteKeywords(region, id);
        record.put("Status", "DELETING");
        return copy(record, POOL_FIELDS);
    }

    public ObjectNode associateOriginationIdentity(JsonNode in, String region) {
        ObjectNode pool = pool(region, req(in, "PoolId"));
        ObjectNode member = member(region, req(in, "OriginationIdentity"), opt(in, "IsoCountryCode"));
        associate(region, pool, member, opt(in, "IsoCountryCode"));
        return copy(pool, "PoolArn", "PoolId").put("OriginationIdentityArn", arnOf(member))
                .put("OriginationIdentity", in.get("OriginationIdentity").asText())
                .put("IsoCountryCode", member.path("IsoCountryCode").asText());
    }

    public ObjectNode disassociateOriginationIdentity(JsonNode in, String region) {
        ObjectNode pool = pool(region, req(in, "PoolId"));
        ObjectNode member = member(region, req(in, "OriginationIdentity"), opt(in, "IsoCountryCode"));
        if (!SmsVoiceStore.id(pool).equals(member.path("PoolId").asText())) {
            throw SmsVoiceStore.conflict("PHONE_NUMBER_NOT_ASSOCIATED_TO_POOL", kind(member),
                    SmsVoiceStore.id(member), "The identity isn't in the pool.");
        }
        if (members(region, pool).size() <= 1) {
            throw SmsVoiceStore.conflict("LAST_PHONE_NUMBER", SmsVoiceStore.POOL, SmsVoiceStore.id(pool),
                    "A pool keeps at least one origination identity.");
        }
        member.remove("PoolId");
        save(member);
        return copy(pool, "PoolArn", "PoolId").put("OriginationIdentityArn", arnOf(member))
                .put("OriginationIdentity", in.get("OriginationIdentity").asText())
                .put("IsoCountryCode", member.path("IsoCountryCode").asText());
    }

    public ObjectNode listPoolOriginationIdentities(JsonNode in, String region) {
        ObjectNode pool = pool(region, req(in, "PoolId"));
        Map<String, Function<ObjectNode, List<String>>> attributes = new LinkedHashMap<>();
        attributes.put("iso-country-code", field("IsoCountryCode"));
        attributes.put("number-capability", field("NumberCapabilities"));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode member : members(region, pool)) {
            if (!matches(member, in.path("Filters"), attributes)) {
                continue;
            }
            ObjectNode view = out().put("OriginationIdentityArn", arnOf(member))
                    .put("IsoCountryCode", member.path("IsoCountryCode").asText(""));
            switch (kind(member)) {
                case SmsVoiceStore.PHONE -> view.put("OriginationIdentity", member.get("PhoneNumberId").asText())
                        .put("PhoneNumber", member.get("PhoneNumber").asText());
                case SmsVoiceStore.SENDER_ID -> view.put("OriginationIdentity", member.get("SenderId").asText());
                default -> view.put("OriginationIdentity", member.get("RcsAgentId").asText());
            }
            ArrayNode caps = view.putArray("NumberCapabilities");
            if (member.path("NumberCapabilities").isArray()) {
                member.get("NumberCapabilities").forEach(caps::add);
            } else {
                caps.add(SmsVoiceStore.RCS.equals(kind(member)) ? "RCS" : "SMS");
            }
            views.add(view);
        }
        ObjectNode out = page(in, views, "OriginationIdentities");
        out.setAll(copy(pool, "PoolArn", "PoolId"));
        return out;
    }

    // ================================================================ keywords

    /** The phone number or pool keywords are kept for, by any reference to it. */
    ObjectNode keywordOwner(String region, String reference) {
        if (reference.startsWith("pool-") || reference.contains(":" + SmsVoiceStore.POOL + "/")) {
            return pool(region, reference);
        }
        return phone(region, reference);
    }

    private ObjectNode keywordOut(ObjectNode owner, String reference) {
        return out().put("OriginationIdentityArn", arnOf(owner)).put("OriginationIdentity", reference);
    }

    public ObjectNode putKeyword(JsonNode in, String region) {
        String reference = req(in, "OriginationIdentity");
        ObjectNode owner = keywordOwner(region, reference);
        String keyword = req(in, "Keyword");
        String action = opt(in, "KeywordAction") == null ? "AUTOMATIC_RESPONSE" : in.get("KeywordAction").asText();
        String key = SmsVoiceStore.id(owner) + "/" + keyword.toUpperCase(Locale.ROOT);
        String upper = keyword.toUpperCase(Locale.ROOT);
        if ((upper.equals("STOP") && !action.equals("OPT_OUT")) || (upper.equals("HELP")
                && !action.equals("AUTOMATIC_RESPONSE"))) {
            throw SmsVoiceStore.conflict("KEYWORD_MISMATCH", SmsVoiceStore.KEYWORD, keyword, "The " + upper
                    + " keyword's action can't change.");
        }
        if (store.get(region, SmsVoiceStore.KEYWORD, key).isEmpty()
                && store.list(region, SmsVoiceStore.KEYWORD, SmsVoiceStore.id(owner) + "/").size() >= 30) {
            throw SmsVoiceStore.quota(SmsVoiceStore.POOL.equals(kind(owner)) ? "KEYWORDS_PER_POOL"
                    : "KEYWORDS_PER_PHONE_NUMBER", "An origination identity has at most 30 keywords.");
        }
        ObjectNode record = keyword(region, SmsVoiceStore.id(owner), keyword, req(in, "KeywordMessage"), action);
        return keywordOut(owner, reference).setAll(copy(record, "Keyword", "KeywordMessage", "KeywordAction"));
    }

    public ObjectNode deleteKeyword(JsonNode in, String region) {
        String reference = req(in, "OriginationIdentity");
        ObjectNode owner = keywordOwner(region, reference);
        String keyword = req(in, "Keyword");
        String key = SmsVoiceStore.id(owner) + "/" + keyword.toUpperCase(Locale.ROOT);
        ObjectNode record = store.get(region, SmsVoiceStore.KEYWORD, key)
                .orElseThrow(() -> SmsVoiceStore.notFound(SmsVoiceStore.KEYWORD, keyword));
        if (Set.of("HELP", "STOP").contains(keyword.toUpperCase(Locale.ROOT))) {
            throw SmsVoiceStore.conflict("RESOURCE_DELETION_NOT_ALLOWED", SmsVoiceStore.KEYWORD, keyword,
                    "The HELP and STOP keywords can't be deleted.");
        }
        store.delete(region, SmsVoiceStore.KEYWORD, key);
        return keywordOut(owner, reference).setAll(copy(record, "Keyword", "KeywordMessage", "KeywordAction"));
    }

    public ObjectNode describeKeywords(JsonNode in, String region) {
        String reference = req(in, "OriginationIdentity");
        ObjectNode owner = keywordOwner(region, reference);
        Set<String> wanted = new HashSet<>();
        in.path("Keywords").forEach(k -> wanted.add(k.asText().toUpperCase(Locale.ROOT)));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : store.list(region, SmsVoiceStore.KEYWORD, SmsVoiceStore.id(owner) + "/")) {
            if ((wanted.isEmpty() || wanted.contains(record.path("Keyword").asText().toUpperCase(Locale.ROOT)))
                    && matches(record, in.path("Filters"), Map.of("keyword-action", field("KeywordAction")))) {
                views.add(copy(record, "Keyword", "KeywordMessage", "KeywordAction"));
            }
        }
        ObjectNode out = page(in, views, "Keywords");
        out.setAll(keywordOut(owner, reference));
        return out;
    }

    /** The keywords an inbound message to a phone number answers: its pool's when it has one. */
    List<ObjectNode> keywordsFor(String region, ObjectNode phone) {
        String owner = phone.hasNonNull("PoolId") ? phone.get("PoolId").asText() : SmsVoiceStore.id(phone);
        return store.list(region, SmsVoiceStore.KEYWORD, owner + "/");
    }

    // ================================================================ protect configurations

    private static final String[] PROTECT_FIELDS = {"ProtectConfigurationArn", "ProtectConfigurationId",
        "CreatedTimestamp", "AccountDefault", "DeletionProtectionEnabled"};

    ObjectNode protect(String region, String reference) {
        ObjectNode record = require(region, SmsVoiceStore.PROTECT, idOf(reference, SmsVoiceStore.PROTECT));
        record.put("AccountDefault", SmsVoiceStore.id(record).equals(account(region)
                .path("DefaultProtectConfigurationId").asText()));
        return record;
    }

    public ObjectNode createProtectConfiguration(JsonNode in, String region) {
        String id = "protect-" + SmsVoiceStore.hex(32);
        ObjectNode record = store.record();
        record.put("ProtectConfigurationArn", store.arn(region, SmsVoiceStore.PROTECT + "/" + id))
                .put("ProtectConfigurationId", id).put("AccountDefault", false)
                .put("DeletionProtectionEnabled", in.path("DeletionProtectionEnabled").asBoolean(false));
        ObjectNode rules = record.putObject("CountryRuleSets");
        for (String capability : CAPABILITIES) {
            ObjectNode set = rules.putObject(capability);
            SmsVoiceCatalog.COUNTRIES.keySet().forEach(iso -> set.putObject(iso).put("ProtectStatus", "ALLOW"));
        }
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.PROTECT, id, record);
        return withTags(copy(record, PROTECT_FIELDS), record);
    }

    public ObjectNode describeProtectConfigurations(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("ProtectConfigurationIds").isArray() && !in.get("ProtectConfigurationIds").isEmpty()) {
            in.get("ProtectConfigurationIds").forEach(id -> records.add(protect(region, id.asText())));
        } else {
            store.all(region, SmsVoiceStore.PROTECT).forEach(r -> records.add(protect(region, SmsVoiceStore.id(r))));
        }
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            if (matches(record, in.path("Filters"), Map.of("account-default", field("AccountDefault"),
                    "deletion-protection-enabled", field("DeletionProtectionEnabled")))) {
                views.add(copy(record, PROTECT_FIELDS));
            }
        }
        return page(in, views, "ProtectConfigurations");
    }

    public ObjectNode updateProtectConfiguration(JsonNode in, String region) {
        ObjectNode record = protect(region, req(in, "ProtectConfigurationId"));
        if (in.has("DeletionProtectionEnabled")) {
            record.put("DeletionProtectionEnabled", in.get("DeletionProtectionEnabled").asBoolean());
        }
        save(record);
        return copy(record, PROTECT_FIELDS);
    }

    public ObjectNode deleteProtectConfiguration(JsonNode in, String region) {
        ObjectNode record = protect(region, req(in, "ProtectConfigurationId"));
        String id = SmsVoiceStore.id(record);
        if (record.path("DeletionProtectionEnabled").asBoolean()) {
            throw SmsVoiceStore.conflict("DELETION_PROTECTION_ENABLED", SmsVoiceStore.PROTECT, id,
                    "Turn off deletion protection first.");
        }
        if (record.path("AccountDefault").asBoolean()) {
            throw SmsVoiceStore.conflict("PROTECT_CONFIGURATION_IS_ACCOUNT_DEFAULT", SmsVoiceStore.PROTECT, id,
                    "Remove it as the account default first.");
        }
        for (ObjectNode set : store.list(region, SmsVoiceStore.CONFIG_SET)) {
            if (id.equals(set.path("ProtectConfigurationId").asText())) {
                throw SmsVoiceStore.conflict("PROTECT_CONFIGURATION_ASSOCIATED_WITH_CONFIGURATION_SET",
                        SmsVoiceStore.PROTECT, id, "The configuration set " + SmsVoiceStore.id(set) + " uses it.");
            }
        }
        store.delete(region, SmsVoiceStore.PROTECT, id);
        for (ObjectNode override : store.list(region, SmsVoiceStore.OVERRIDE, id + "/")) {
            store.delete(region, SmsVoiceStore.OVERRIDE, SmsVoiceStore.id(override));
        }
        return copy(record, PROTECT_FIELDS);
    }

    public ObjectNode getProtectConfigurationCountryRuleSet(JsonNode in, String region) {
        ObjectNode record = protect(region, req(in, "ProtectConfigurationId"));
        String capability = req(in, "NumberCapability");
        ObjectNode out = copy(record, "ProtectConfigurationArn", "ProtectConfigurationId")
                .put("NumberCapability", capability);
        out.set("CountryRuleSet", record.at("/CountryRuleSets/" + capability).deepCopy());
        return out;
    }

    public ObjectNode updateProtectConfigurationCountryRuleSet(JsonNode in, String region) {
        ObjectNode record = protect(region, req(in, "ProtectConfigurationId"));
        String capability = req(in, "NumberCapability");
        ObjectNode set = (ObjectNode) record.at("/CountryRuleSets/" + capability);
        in.path("CountryRuleSetUpdates").properties().forEach(entry -> {
            if (!SmsVoiceCatalog.COUNTRIES.containsKey(entry.getKey())) {
                throw SmsVoiceStore.validation("INVALID_PARAMETER", "Floci doesn't know the country "
                        + entry.getKey() + ".");
            }
            String status = entry.getValue().path("ProtectStatus").asText();
            if ("FILTER".equals(status) && !"SMS".equals(capability)) {
                throw SmsVoiceStore.validation("INVALID_PARAMETER", "FILTER applies to SMS only.");
            }
            set.putObject(entry.getKey()).put("ProtectStatus", status);
        });
        save(record);
        ObjectNode out = copy(record, "ProtectConfigurationArn", "ProtectConfigurationId")
                .put("NumberCapability", capability);
        out.set("CountryRuleSet", set.deepCopy());
        return out;
    }

    public ObjectNode setAccountDefaultProtectConfiguration(JsonNode in, String region) {
        ObjectNode record = protect(region, req(in, "ProtectConfigurationId"));
        ObjectNode account = account(region);
        account.put("DefaultProtectConfigurationId", SmsVoiceStore.id(record));
        saveAccount(region, account);
        return out().put("DefaultProtectConfigurationArn", record.get("ProtectConfigurationArn").asText())
                .put("DefaultProtectConfigurationId", SmsVoiceStore.id(record));
    }

    public ObjectNode deleteAccountDefaultProtectConfiguration(JsonNode in, String region) {
        ObjectNode account = account(region);
        String id = account.path("DefaultProtectConfigurationId").asText("");
        if (id.isEmpty()) {
            throw SmsVoiceStore.notFound(SmsVoiceStore.PROTECT, "account default");
        }
        ObjectNode record = protect(region, id);
        account.remove("DefaultProtectConfigurationId");
        saveAccount(region, account);
        return out().put("DefaultProtectConfigurationArn", record.get("ProtectConfigurationArn").asText())
                .put("DefaultProtectConfigurationId", id);
    }

    public ObjectNode associateProtectConfiguration(JsonNode in, String region) {
        ObjectNode protect = protect(region, req(in, "ProtectConfigurationId"));
        ObjectNode set = configSet(region, req(in, "ConfigurationSetName"));
        if (set.hasNonNull("ProtectConfigurationId")) {
            throw SmsVoiceStore.conflict("RESOURCE_ALREADY_ASSOCIATED", SmsVoiceStore.CONFIG_SET,
                    SmsVoiceStore.id(set), "The configuration set already has a protect configuration.");
        }
        set.put("ProtectConfigurationId", SmsVoiceStore.id(protect));
        save(set);
        return copy(set, "ConfigurationSetArn", "ConfigurationSetName").setAll(copy(protect,
                "ProtectConfigurationArn", "ProtectConfigurationId"));
    }

    public ObjectNode disassociateProtectConfiguration(JsonNode in, String region) {
        ObjectNode protect = protect(region, req(in, "ProtectConfigurationId"));
        ObjectNode set = configSet(region, req(in, "ConfigurationSetName"));
        if (!SmsVoiceStore.id(protect).equals(set.path("ProtectConfigurationId").asText())) {
            throw SmsVoiceStore.conflict("PROTECT_CONFIGURATION_NOT_ASSOCIATED_WITH_CONFIGURATION_SET",
                    SmsVoiceStore.CONFIG_SET, SmsVoiceStore.id(set), "The protect configuration isn't associated.");
        }
        set.remove("ProtectConfigurationId");
        save(set);
        return copy(set, "ConfigurationSetArn", "ConfigurationSetName").setAll(copy(protect,
                "ProtectConfigurationArn", "ProtectConfigurationId"));
    }

    private static final String[] OVERRIDE_FIELDS = {"DestinationPhoneNumber", "CreatedTimestamp", "Action",
        "IsoCountryCode", "ExpirationTimestamp"};

    public ObjectNode putProtectConfigurationRuleSetNumberOverride(JsonNode in, String region) {
        ObjectNode protect = protect(region, req(in, "ProtectConfigurationId"));
        String number = SmsVoiceCatalog.e164(req(in, "DestinationPhoneNumber"), "DestinationPhoneNumber");
        ObjectNode record = store.record();
        record.put("DestinationPhoneNumber", number).put("Action", req(in, "Action"));
        Country country = SmsVoiceCatalog.countryOf(number);
        if (country != null) {
            record.put("IsoCountryCode", country.iso());
        }
        if (in.has("ExpirationTimestamp")) {
            record.set("ExpirationTimestamp", in.get("ExpirationTimestamp"));
        }
        store.put(region, SmsVoiceStore.OVERRIDE, SmsVoiceStore.id(protect) + "/" + number, record);
        return copy(protect, "ProtectConfigurationArn", "ProtectConfigurationId").setAll(copy(record,
                OVERRIDE_FIELDS));
    }

    public ObjectNode deleteProtectConfigurationRuleSetNumberOverride(JsonNode in, String region) {
        ObjectNode protect = protect(region, req(in, "ProtectConfigurationId"));
        String number = req(in, "DestinationPhoneNumber");
        String key = SmsVoiceStore.id(protect) + "/" + number;
        ObjectNode record = store.get(region, SmsVoiceStore.OVERRIDE, key)
                .orElseThrow(() -> SmsVoiceStore.notFound("protect-configuration", number));
        store.delete(region, SmsVoiceStore.OVERRIDE, key);
        return copy(protect, "ProtectConfigurationArn", "ProtectConfigurationId").setAll(copy(record,
                OVERRIDE_FIELDS));
    }

    public ObjectNode listProtectConfigurationRuleSetNumberOverrides(JsonNode in, String region) {
        ObjectNode protect = protect(region, req(in, "ProtectConfigurationId"));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : store.list(region, SmsVoiceStore.OVERRIDE, SmsVoiceStore.id(protect) + "/")) {
            boolean keep = true;
            for (JsonNode filter : in.path("Filters")) {
                String value = filter.path("Values").path(0).asText("");
                double expires = record.path("ExpirationTimestamp").asDouble(Double.MAX_VALUE);
                double created = record.path("CreatedTimestamp").asDouble();
                keep &= switch (filter.path("Name").asText()) {
                    case "iso-country-code" -> value.equals(record.path("IsoCountryCode").asText());
                    case "destination-phone-number-begins-with" -> record.path("DestinationPhoneNumber").asText()
                            .startsWith(value);
                    case "action" -> value.equals(record.path("Action").asText());
                    case "expires-before" -> expires < time(value);
                    case "expires-after" -> expires > time(value);
                    case "created-before" -> created < time(value);
                    case "created-after" -> created > time(value);
                    default -> throw SmsVoiceStore.validation("INVALID_FILTER_VALUES", "Unknown filter.");
                };
            }
            if (keep) {
                views.add(copy(record, OVERRIDE_FIELDS));
            }
        }
        ObjectNode out = page(in, views, "RuleSetNumberOverrides");
        out.setAll(copy(protect, "ProtectConfigurationArn", "ProtectConfigurationId"));
        return out;
    }

    private static double time(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Instant.parse(value).toEpochMilli() / 1000.0;
        }
    }

    /**
     * Fails when a protect configuration blocks a destination: the number's override decides first, then the
     * destination country's rule for the capability.
     */
    void checkProtect(String region, String protectId, String capability, String destination) {
        if (protectId == null) {
            return;
        }
        ObjectNode protect = protect(region, protectId);
        ObjectNode override = store.get(region, SmsVoiceStore.OVERRIDE, SmsVoiceStore.id(protect) + "/"
                + destination).orElse(null);
        if (override != null && override.path("ExpirationTimestamp").asDouble(Double.MAX_VALUE)
                > SmsVoiceStore.now()) {
            if ("BLOCK".equals(override.path("Action").asText())) {
                throw SmsVoiceStore.conflict("DESTINATION_PHONE_NUMBER_BLOCKED_BY_PROTECT_NUMBER_OVERRIDE",
                        SmsVoiceStore.PROTECT, SmsVoiceStore.id(protect), "A number override blocks "
                                + destination + ".");
            }
            return;
        }
        Country country = SmsVoiceCatalog.countryOf(destination);
        if (country != null && "BLOCK".equals(protect.at("/CountryRuleSets/" + capability + "/" + country.iso()
                + "/ProtectStatus").asText())) {
            throw SmsVoiceStore.conflict("DESTINATION_COUNTRY_BLOCKED_BY_PROTECT_CONFIGURATION",
                    SmsVoiceStore.PROTECT, SmsVoiceStore.id(protect), "The protect configuration blocks "
                            + capability + " to " + country.name() + ".");
        }
    }

    // ================================================================ verified destination numbers

    private static final String[] VERIFIED_FIELDS = {"VerifiedDestinationNumberArn", "VerifiedDestinationNumberId",
        "DestinationPhoneNumber", "Status", "RcsAgentId", "CreatedTimestamp"};

    ObjectNode verified(String region, String id) {
        return require(region, SmsVoiceStore.VERIFIED, idOf(id, SmsVoiceStore.VERIFIED));
    }

    public ObjectNode createVerifiedDestinationNumber(JsonNode in, String region) {
        String number = SmsVoiceCatalog.e164(req(in, "DestinationPhoneNumber"), "DestinationPhoneNumber");
        if (opt(in, "RcsAgentId") != null) {
            require(region, SmsVoiceStore.RCS, in.get("RcsAgentId").asText());
        }
        for (ObjectNode existing : store.list(region, SmsVoiceStore.VERIFIED)) {
            if (number.equals(existing.path("DestinationPhoneNumber").asText())) {
                throw SmsVoiceStore.conflict("RESOURCE_ALREADY_EXISTS", SmsVoiceStore.VERIFIED,
                        SmsVoiceStore.id(existing), number + " is already a verified destination number.");
            }
        }
        quota(region, SmsVoiceStore.VERIFIED, "VERIFIED_DESTINATION_NUMBERS",
                "VERIFIED_DESTINATION_NUMBERS_PER_ACCOUNT");
        String id = "verified-" + SmsVoiceStore.hex(32);
        ObjectNode record = store.record();
        record.put("VerifiedDestinationNumberArn", store.arn(region, SmsVoiceStore.VERIFIED + "/" + id))
                .put("VerifiedDestinationNumberId", id).put("DestinationPhoneNumber", number)
                .put("Status", SmsVoiceCatalog.countryOf(number) == null ? "UNSUPPORTED" : "PENDING");
        if (opt(in, "RcsAgentId") != null) {
            record.put("RcsAgentId", in.get("RcsAgentId").asText());
        }
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.VERIFIED, id, record);
        return withTags(copy(record, VERIFIED_FIELDS), record);
    }

    public ObjectNode describeVerifiedDestinationNumbers(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        Set<String> numbers = new HashSet<>();
        in.path("DestinationPhoneNumbers").forEach(n -> numbers.add(n.asText()));
        if (in.path("VerifiedDestinationNumberIds").isArray() && !in.get("VerifiedDestinationNumberIds").isEmpty()) {
            in.get("VerifiedDestinationNumberIds").forEach(id -> records.add(verified(region, id.asText())));
        } else {
            records.addAll(store.all(region, SmsVoiceStore.VERIFIED));
        }
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            if ((numbers.isEmpty() || numbers.contains(record.path("DestinationPhoneNumber").asText()))
                    && matches(record, in.path("Filters"), Map.of("status", field("Status"), "rcs-agent-id",
                    field("RcsAgentId")))) {
                views.add(copy(record, VERIFIED_FIELDS));
            }
        }
        return page(in, views, "VerifiedDestinationNumbers");
    }

    public ObjectNode verifyDestinationNumber(JsonNode in, String region) {
        ObjectNode record = verified(region, req(in, "VerifiedDestinationNumberId"));
        String id = SmsVoiceStore.id(record);
        if ("VERIFIED".equals(record.path("Status").asText())) {
            throw SmsVoiceStore.conflict("VERIFICATION_ALREADY_COMPLETE", SmsVoiceStore.VERIFIED, id,
                    "The number is already verified.");
        }
        ObjectNode meta = SmsVoiceStore.meta(record);
        if (!meta.has("code")) {
            throw SmsVoiceStore.validation("VERIFICATION_CODE_MISMATCH", "No verification code has been sent.");
        }
        if (meta.path("expires").asDouble() < SmsVoiceStore.now()) {
            throw SmsVoiceStore.conflict("VERIFICATION_CODE_EXPIRED", SmsVoiceStore.VERIFIED, id,
                    "The verification code expired; send a new one.");
        }
        if (!meta.path("code").asText().equals(req(in, "VerificationCode"))) {
            throw SmsVoiceStore.validation("VERIFICATION_CODE_MISMATCH", "The verification code doesn't match.");
        }
        record.put("Status", "VERIFIED");
        meta.remove("code");
        save(record);
        return copy(record, "VerifiedDestinationNumberArn", "VerifiedDestinationNumberId", "DestinationPhoneNumber",
                "Status", "CreatedTimestamp");
    }

    public ObjectNode deleteVerifiedDestinationNumber(JsonNode in, String region) {
        ObjectNode record = verified(region, req(in, "VerifiedDestinationNumberId"));
        store.delete(region, SmsVoiceStore.VERIFIED, SmsVoiceStore.id(record));
        return copy(record, "VerifiedDestinationNumberArn", "VerifiedDestinationNumberId", "DestinationPhoneNumber",
                "CreatedTimestamp");
    }

    /** Whether the account may send to a destination: always outside the sandbox, else only verified numbers. */
    boolean destinationAllowed(String region, String destination) {
        if (!"SANDBOX".equals(store.config().accountTier())) {
            return true;
        }
        for (ObjectNode record : store.list(region, SmsVoiceStore.VERIFIED)) {
            if (destination.equals(record.path("DestinationPhoneNumber").asText())
                    && "VERIFIED".equals(record.path("Status").asText())) {
                return true;
            }
        }
        for (ObjectNode phone : store.list(region, SmsVoiceStore.PHONE)) {
            if (destination.equals(phone.path("PhoneNumber").asText())) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ RCS agents

    private static final String[] RCS_FIELDS = {"RcsAgentArn", "RcsAgentId", "Status", "CreatedTimestamp",
        "DeletionProtectionEnabled", "OptOutListName", "SelfManagedOptOutsEnabled", "TwoWayChannelArn",
        "TwoWayChannelRole", "TwoWayEnabled", "PoolId", "TwoWayMediaS3BucketName", "TwoWayMediaS3KeyPrefix",
        "TwoWayMediaS3Role", "TwoWayRcsEventsEnabled"};

    ObjectNode rcs(String region, String reference) {
        return require(region, SmsVoiceStore.RCS, idOf(reference, SmsVoiceStore.RCS));
    }

    public ObjectNode createRcsAgent(JsonNode in, String region) {
        String list = opt(in, "OptOutListName") == null ? DEFAULT_LIST : in.get("OptOutListName").asText();
        optOutList(region, list);
        String id = "rcs-" + SmsVoiceStore.hex(32);
        ObjectNode record = store.record();
        record.put("RcsAgentArn", store.arn(region, SmsVoiceStore.RCS + "/" + id)).put("RcsAgentId", id)
                .put("Status", "CREATED")
                .put("DeletionProtectionEnabled", in.path("DeletionProtectionEnabled").asBoolean(false))
                .put("OptOutListName", list).put("SelfManagedOptOutsEnabled", false).put("TwoWayEnabled", false);
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.RCS, id, record);
        return withTags(copy(record, RCS_FIELDS), record);
    }

    public ObjectNode describeRcsAgents(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("RcsAgentIds").isArray() && !in.get("RcsAgentIds").isEmpty()) {
            in.get("RcsAgentIds").forEach(id -> records.add(rcs(region, id.asText())));
        } else {
            records.addAll(owned(region, SmsVoiceStore.RCS, in));
        }
        Map<String, Function<ObjectNode, List<String>>> attributes = new LinkedHashMap<>();
        for (String[] pair : new String[][] {{"status", "Status"}, {"two-way-enabled", "TwoWayEnabled"},
            {"self-managed-opt-outs-enabled", "SelfManagedOptOutsEnabled"}, {"opt-out-list-name", "OptOutListName"},
            {"deletion-protection-enabled", "DeletionProtectionEnabled"}, {"two-way-channel-arn", "TwoWayChannelArn"}}) {
            attributes.put(pair[0], field(pair[1]));
        }
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            if (matches(record, in.path("Filters"), attributes)) {
                views.add(copy(record, RCS_FIELDS));
            }
        }
        return page(in, views, "RcsAgents");
    }

    public ObjectNode updateRcsAgent(JsonNode in, String region) {
        ObjectNode record = rcs(region, req(in, "RcsAgentId"));
        checkTwoWay(in, record);
        if (in.has("OptOutListName")) {
            record.put("OptOutListName", optOutList(region, in.get("OptOutListName").asText())
                    .get("OptOutListName").asText());
        }
        for (String member : List.of("SelfManagedOptOutsEnabled", "DeletionProtectionEnabled")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asBoolean());
            }
        }
        for (String member : List.of("TwoWayMediaS3BucketName", "TwoWayMediaS3KeyPrefix", "TwoWayMediaS3Role")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asText());
            }
        }
        if (in.has("TwoWayRcsEventsEnabled")) {
            record.set("TwoWayRcsEventsEnabled", in.get("TwoWayRcsEventsEnabled").deepCopy());
        }
        save(record);
        return copy(record, RCS_FIELDS);
    }

    public ObjectNode deleteRcsAgent(JsonNode in, String region) {
        ObjectNode record = rcs(region, req(in, "RcsAgentId"));
        String id = SmsVoiceStore.id(record);
        if (record.path("DeletionProtectionEnabled").asBoolean()) {
            throw SmsVoiceStore.conflict("DELETION_PROTECTION_ENABLED", SmsVoiceStore.RCS, id,
                    "Turn off deletion protection first.");
        }
        if (record.hasNonNull("PoolId")) {
            throw SmsVoiceStore.conflict("RCS_AGENT_ASSOCIATED_TO_POOL", SmsVoiceStore.RCS, id,
                    "Remove the agent from its pool first.");
        }
        store.delete(region, SmsVoiceStore.RCS, id);
        record.put("Status", "DELETED");
        return copy(record, RCS_FIELDS);
    }

    public ObjectNode describeRcsAgentCountryLaunchStatus(JsonNode in, String region) {
        ObjectNode record = rcs(region, req(in, "RcsAgentId"));
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode registration : store.all(region, SmsVoiceStore.REGISTRATION)) {
            if (!SmsVoiceStore.id(record).equals(registration.path("AdditionalAttributes").path("RCS_AGENT_ID")
                    .asText())) {
                continue;
            }
            String iso = registration.path("AdditionalAttributes").path("ISO_COUNTRY_CODE").asText("US");
            String status = "COMPLETE".equals(registration.path("RegistrationStatus").asText()) ? "ACTIVE" : "PENDING";
            ObjectNode view = out().put("IsoCountryCode", iso).put("Status", status)
                    .put("RegistrationId", SmsVoiceStore.id(registration));
            view.putArray("CarrierStatus");
            views.add(view);
        }
        ObjectNode out = page(in, views, "CountryLaunchStatus");
        out.put("RcsAgentId", SmsVoiceStore.id(record)).put("RcsAgentArn", record.get("RcsAgentArn").asText());
        return out;
    }

    // ================================================================ Notify

    private static final String[] NOTIFY_FIELDS = {"NotifyConfigurationArn", "NotifyConfigurationId",
        "DisplayName", "UseCase", "DefaultTemplateId", "PoolId", "EnabledCountries", "EnabledChannels", "Tier",
        "TierUpgradeStatus", "Status", "RejectionReason", "DeletionProtectionEnabled", "CreatedTimestamp"};

    ObjectNode notify(String region, String reference) {
        return require(region, SmsVoiceStore.NOTIFY, idOf(reference, SmsVoiceStore.NOTIFY));
    }

    private void checkNotify(JsonNode in, String region) {
        if (opt(in, "DefaultTemplateId") != null && !SmsVoiceCatalog.TEMPLATES.containsKey(
                in.get("DefaultTemplateId").asText())) {
            throw SmsVoiceStore.notFound("notify-template", in.get("DefaultTemplateId").asText());
        }
        if (opt(in, "PoolId") != null) {
            pool(region, in.get("PoolId").asText());
        }
        for (JsonNode iso : in.path("EnabledCountries")) {
            if (!SmsVoiceCatalog.COUNTRIES.containsKey(iso.asText())) {
                throw SmsVoiceStore.validation("INVALID_PARAMETER", "Notify doesn't send to " + iso.asText() + ".");
            }
        }
    }

    public ObjectNode createNotifyConfiguration(JsonNode in, String region) {
        checkNotify(in, region);
        String id = "notify-" + SmsVoiceStore.hex(32);
        ObjectNode record = store.record();
        record.put("NotifyConfigurationArn", store.arn(region, SmsVoiceStore.NOTIFY + "/" + id))
                .put("NotifyConfigurationId", id).put("DisplayName", req(in, "DisplayName"))
                .put("UseCase", req(in, "UseCase")).put("Tier", "BASIC").put("TierUpgradeStatus", "BASIC")
                .put("DeletionProtectionEnabled", in.path("DeletionProtectionEnabled").asBoolean(false));
        for (String member : List.of("DefaultTemplateId", "PoolId")) {
            if (opt(in, member) != null) {
                record.put(member, in.get(member).asText());
            }
        }
        record.set("EnabledChannels", in.get("EnabledChannels").deepCopy());
        if (in.has("EnabledCountries")) {
            record.set("EnabledCountries", in.get("EnabledCountries").deepCopy());
        }
        store.transition(record, "Status", "PENDING", "ACTIVE");
        SmsVoiceStore.addTags(record, in.path("Tags"));
        store.put(region, SmsVoiceStore.NOTIFY, id, record);
        return withTags(copy(record, NOTIFY_FIELDS), record);
    }

    public ObjectNode describeNotifyConfigurations(JsonNode in, String region) {
        List<ObjectNode> records = new ArrayList<>();
        if (in.path("NotifyConfigurationIds").isArray() && !in.get("NotifyConfigurationIds").isEmpty()) {
            in.get("NotifyConfigurationIds").forEach(id -> records.add(notify(region, id.asText())));
        } else {
            records.addAll(store.all(region, SmsVoiceStore.NOTIFY));
        }
        Map<String, Function<ObjectNode, List<String>>> attributes = new LinkedHashMap<>();
        for (String[] pair : new String[][] {{"display-name", "DisplayName"}, {"enabled-countries", "EnabledCountries"},
            {"enabled-channels", "EnabledChannels"}, {"default-template", "DefaultTemplateId"},
            {"default-pool", "PoolId"}, {"use-case", "UseCase"}, {"status", "Status"},
            {"deletion-protection-enabled", "DeletionProtectionEnabled"},
            {"tier-upgrade-status", "TierUpgradeStatus"}}) {
            attributes.put(pair[0], field(pair[1]));
        }
        List<ObjectNode> views = new ArrayList<>();
        for (ObjectNode record : records) {
            if (matches(record, in.path("Filters"), attributes)) {
                views.add(copy(record, NOTIFY_FIELDS));
            }
        }
        return page(in, views, "NotifyConfigurations");
    }

    public ObjectNode updateNotifyConfiguration(JsonNode in, String region) {
        ObjectNode record = notify(region, req(in, "NotifyConfigurationId"));
        checkNotify(in, region);
        for (String member : List.of("DefaultTemplateId", "PoolId")) {
            if (in.has(member)) {
                record.put(member, in.get(member).asText());
            }
        }
        for (String member : List.of("EnabledCountries", "EnabledChannels")) {
            if (in.has(member)) {
                record.set(member, in.get(member).deepCopy());
            }
        }
        if (in.has("DeletionProtectionEnabled")) {
            record.put("DeletionProtectionEnabled", in.get("DeletionProtectionEnabled").asBoolean());
        }
        save(record);
        return copy(record, NOTIFY_FIELDS);
    }

    public ObjectNode deleteNotifyConfiguration(JsonNode in, String region) {
        ObjectNode record = notify(region, req(in, "NotifyConfigurationId"));
        if (record.path("DeletionProtectionEnabled").asBoolean()) {
            throw SmsVoiceStore.conflict("DELETION_PROTECTION_ENABLED", SmsVoiceStore.NOTIFY,
                    SmsVoiceStore.id(record), "Turn off deletion protection first.");
        }
        store.delete(region, SmsVoiceStore.NOTIFY, SmsVoiceStore.id(record));
        return copy(record, NOTIFY_FIELDS);
    }

    public ObjectNode describeNotifyTemplates(JsonNode in, String region) {
        List<SmsVoiceCatalog.Template> templates = new ArrayList<>();
        if (in.path("TemplateIds").isArray() && !in.get("TemplateIds").isEmpty()) {
            for (JsonNode id : in.get("TemplateIds")) {
                SmsVoiceCatalog.Template template = SmsVoiceCatalog.TEMPLATES.get(id.asText());
                if (template == null) {
                    throw SmsVoiceStore.notFound("notify-template", id.asText());
                }
                templates.add(template);
            }
        } else {
            templates.addAll(SmsVoiceCatalog.TEMPLATES.values());
        }
        List<ObjectNode> views = new ArrayList<>();
        for (SmsVoiceCatalog.Template template : templates) {
            ObjectNode view = templateView(template);
            Map<String, Function<ObjectNode, List<String>>> attributes = new LinkedHashMap<>();
            attributes.put("template-type", field("TemplateType"));
            attributes.put("channels", field("Channels"));
            attributes.put("tier-access", field("TierAccess"));
            attributes.put("supported-countries", field("SupportedCountries"));
            attributes.put("language-code", field("LanguageCode"));
            attributes.put("supported-voice-ids", field("SupportedVoiceIds"));
            if (matches(view, in.path("Filters"), attributes)) {
                views.add(view);
            }
        }
        return page(in, views, "NotifyTemplates");
    }

    private ObjectNode templateView(SmsVoiceCatalog.Template template) {
        ObjectNode view = out().put("TemplateId", template.id()).put("Version", 1)
                .put("TemplateType", "OTP_VERIFICATION").put("Status", "ACTIVE")
                .put("LanguageCode", template.language()).put("Content", template.content())
                .put("CreatedTimestamp", 1_704_067_200);
        ArrayNode channels = view.putArray("Channels");
        template.channels().forEach(channels::add);
        ArrayNode tiers = view.putArray("TierAccess");
        template.tiers().forEach(tiers::add);
        ArrayNode countries = view.putArray("SupportedCountries");
        SmsVoiceCatalog.COUNTRIES.keySet().forEach(countries::add);
        ObjectNode variables = view.putObject("Variables");
        variables.putObject("code").put("Type", "STRING").put("Required", true).put("MaxLength", 10)
                .put("Pattern", "^[0-9A-Za-z]{4,10}$").put("Sample", "123456").put("Source", "CUSTOMER");
        variables.putObject("brand").put("Type", "STRING").put("Required", false).put("MaxLength", 30)
                .put("Description", "Defaults to the configuration's display name.").put("Source", "SYSTEM");
        variables.putObject("expiry").put("Type", "INTEGER").put("Required", false).put("MinValue", 1)
                .put("MaxValue", 60).put("DefaultValue", "10").put("Source", "CUSTOMER");
        view.putArray("SupportedVoiceIds").add("JOANNA").add("MATTHEW");
        return view;
    }

    public ObjectNode listNotifyCountries(JsonNode in, String region) {
        Set<String> channels = new HashSet<>();
        in.path("Channels").forEach(c -> channels.add(c.asText()));
        String tier = opt(in, "Tier");
        List<ObjectNode> views = new ArrayList<>();
        for (Country country : SmsVoiceCatalog.COUNTRIES.values()) {
            boolean basic = SmsVoiceCatalog.NOTIFY_BASIC.contains(country.iso());
            List<String> supported = country.iso().equals("US") || country.iso().equals("CA")
                    ? List.of("SMS", "VOICE") : List.of("SMS");
            if (!channels.isEmpty() && supported.stream().noneMatch(channels::contains)) {
                continue;
            }
            if ("BASIC".equals(tier) && !basic) {
                continue;
            }
            ObjectNode view = out().put("IsoCountryCode", country.iso()).put("CountryName", country.name())
                    .put("CustomerOwnedIdentityRequired", !basic);
            ArrayNode supportedChannels = view.putArray("SupportedChannels");
            supported.forEach(supportedChannels::add);
            view.putArray("SupportedUseCases").add("CODE_VERIFICATION");
            ArrayNode tiers = view.putArray("SupportedTiers");
            if (basic) {
                tiers.add("BASIC");
            }
            tiers.add("ADVANCED");
            views.add(view);
        }
        return page(in, views, "NotifyCountries");
    }

    // ================================================================ carrier lookup

    public ObjectNode carrierLookup(JsonNode in, String region) {
        String original = req(in, "PhoneNumber");
        String number = original.replaceAll("[\\s().-]", "");
        if (in.path("EnableCleansing").asBoolean(false) && !number.startsWith("+")) {
            number = (number.length() == 10 ? "+1" : "+") + number;
        }
        ObjectNode out = out().put("OriginalPhoneNumber", original);
        Country country = number.matches("\\+[1-9][0-9]{6,14}") ? SmsVoiceCatalog.countryOf(number) : null;
        if (country == null) {
            return out.put("E164PhoneNumber", number).put("PhoneNumberType", "INVALID");
        }
        String local = number.substring(1 + country.dial().length());
        boolean tollFree = country.dial().equals("1") && local.length() >= 3
                && SmsVoiceCatalog.TOLL_FREE.contains(local.substring(0, 3));
        boolean voip = false;
        for (ObjectNode phone : store.all(region, SmsVoiceStore.PHONE)) {
            voip |= number.equals(phone.path("PhoneNumber").asText());
        }
        out.put("E164PhoneNumber", number).put("DialingCountryCode", country.dial())
                .put("IsoCountryCode", country.iso()).put("Country", country.name())
                .put("PhoneNumberType", tollFree ? "OTHER" : voip ? "VOIP" : "MOBILE");
        if (!tollFree) {
            out.put("MCC", country.iso().equals("US") ? "310" : "000").put("MNC", "410")
                    .put("Carrier", "Floci Wireless");
        }
        return out;
    }

    // ================================================================ resource policies

    private ObjectNode policyTarget(String region, String arn) {
        if (!AwsArnUtils.isArnFor(arn, SmsVoiceStore.SERVICE)) {
            throw SmsVoiceStore.validation("INVALID_ARN", "The ARN " + arn + " isn't an SMS and Voice ARN.");
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String kind = resource.substring(0, resource.indexOf('/'));
        if (!Set.of(SmsVoiceStore.PHONE, SmsVoiceStore.SENDER_ID, SmsVoiceStore.POOL, SmsVoiceStore.OPT_OUT_LIST)
                .contains(kind)) {
            throw SmsVoiceStore.validation("INVALID_ARN", "Resource policies attach to phone numbers, sender IDs, "
                    + "pools and opt-out lists.");
        }
        return require(region, kind, resource.substring(kind.length() + 1));
    }

    public ObjectNode putResourcePolicy(JsonNode in, String region) {
        String arn = req(in, "ResourceArn");
        ObjectNode record = policyTarget(region, arn);
        String policy = req(in, "Policy");
        try {
            JsonNode parsed = mapper.readTree(policy);
            if (!parsed.path("Statement").isArray() && !parsed.path("Statement").isObject()) {
                throw SmsVoiceStore.validation("INVALID_PARAMETER", "The policy needs a Statement.");
            }
        } catch (JsonProcessingException e) {
            throw SmsVoiceStore.validation("CANNOT_PARSE", "The policy isn't valid JSON.");
        }
        SmsVoiceStore.meta(record).put("policy", policy).put("policyCreated", SmsVoiceStore.now());
        save(record);
        return out().put("ResourceArn", arn).put("Policy", policy).put("CreatedTimestamp",
                SmsVoiceStore.meta(record).path("policyCreated").asDouble());
    }

    public ObjectNode getResourcePolicy(JsonNode in, String region) {
        String arn = req(in, "ResourceArn");
        ObjectNode meta = SmsVoiceStore.meta(policyTarget(region, arn));
        ObjectNode out = out().put("ResourceArn", arn);
        if (meta.has("policy")) {
            out.put("Policy", meta.get("policy").asText()).put("CreatedTimestamp",
                    meta.path("policyCreated").asDouble());
        }
        return out;
    }

    public ObjectNode deleteResourcePolicy(JsonNode in, String region) {
        String arn = req(in, "ResourceArn");
        ObjectNode record = policyTarget(region, arn);
        ObjectNode meta = SmsVoiceStore.meta(record);
        if (!meta.has("policy")) {
            throw SmsVoiceStore.notFound("policy", arn);
        }
        ObjectNode out = out().put("ResourceArn", arn).put("Policy", meta.get("policy").asText())
                .put("CreatedTimestamp", meta.path("policyCreated").asDouble());
        meta.remove("policy");
        meta.remove("policyCreated");
        save(record);
        return out;
    }

    // ================================================================ tags

    /** A taggable record by its ARN. */
    ObjectNode byArn(String arn) {
        if (!AwsArnUtils.isArnFor(arn, SmsVoiceStore.SERVICE)) {
            throw SmsVoiceStore.validation("INVALID_ARN", "The ARN " + arn + " isn't an SMS and Voice ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        if (slash < 0 || !parsed.accountId().equals(store.account())) {
            throw SmsVoiceStore.notFound("account", arn);
        }
        String kind = resource.substring(0, slash);
        return require(parsed.region(), kind, resource.substring(slash + 1));
    }

    public ObjectNode tagResource(JsonNode in, String region) {
        ObjectNode record = byArn(req(in, "ResourceArn"));
        SmsVoiceStore.addTags(record, in.path("Tags"));
        save(record);
        return out();
    }

    public ObjectNode untagResource(JsonNode in, String region) {
        ObjectNode record = byArn(req(in, "ResourceArn"));
        in.path("TagKeys").forEach(key -> SmsVoiceStore.tags(record).remove(key.asText()));
        save(record);
        return out();
    }

    public ObjectNode listTagsForResource(JsonNode in, String region) {
        String arn = req(in, "ResourceArn");
        ObjectNode out = out().put("ResourceArn", arn);
        out.set("Tags", SmsVoiceStore.tagList(byArn(arn), mapper));
        return out;
    }
}
