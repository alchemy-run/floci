package io.floci.aws.services.pinpointsmsvoicev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.firehose.FirehoseService;
import io.floci.aws.services.firehose.model.Record;
import io.floci.aws.services.pinpointsmsvoicev2.SmsVoiceCatalog.Country;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.services.sns.SnsService;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sending with SMS and Voice: text, voice, media, RCS and Notify messages. A send is checked as the service checks it
 * (origination identity, capability, registration, country, sandbox, opt-outs, protect configurations, price and spend
 * limits); then its delivery events go to the configuration set's event destinations (SNS, CloudWatch Logs, Firehose).
 * A message to one of an account's own numbers arrives there: keywords answer it, {@code STOP} opts the sender out,
 * and a two-way number publishes it to its SNS channel.
 */
@ApplicationScoped
public class SmsVoiceMessaging {

    private static final Logger LOG = Logger.getLogger(SmsVoiceMessaging.class);
    private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "video/mp4",
            "video/3gpp", "audio/mpeg", "audio/mp4", "text/vcard", "text/x-vcard", "application/pdf", "text/csv",
            "text/plain");

    private final SmsVoiceStore store;
    private final SmsVoiceService service;
    private final ObjectMapper mapper;
    private final Provider<SnsService> sns;
    private final Provider<CloudWatchLogsService> logs;
    private final Provider<FirehoseService> firehose;
    private final Provider<S3Service> s3;
    private final Vertx vertx;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "sms-voice");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public SmsVoiceMessaging(SmsVoiceStore store, SmsVoiceService service, Provider<SnsService> sns,
                             Provider<CloudWatchLogsService> logs, Provider<FirehoseService> firehose,
                             Provider<S3Service> s3, Vertx vertx) {
        this.store = store;
        this.service = service;
        this.mapper = store.mapper();
        this.sns = sns;
        this.logs = logs;
        this.firehose = firehose;
        this.s3 = s3;
        this.vertx = vertx;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /** A message on its way: who sends it, to whom, through what, at what price. */
    private record Outgoing(String channel, String origination, String originationNumber, ObjectNode identity,
                            String destination, Country country, String body, String messageType, ObjectNode configSet,
                            JsonNode context, int parts, double price, boolean feedback) {
    }

    // ================================================================ checks

    private static String spendKind(String channel) {
        return switch (channel) {
            case "VOICE" -> "VOICE";
            case "MEDIA" -> "MEDIA";
            case "RCS" -> "RCS";
            case "NOTIFY" -> "NOTIFY";
            default -> "TEXT";
        };
    }

    private static String capability(String channel) {
        return switch (channel) {
            case "VOICE" -> "VOICE";
            case "MEDIA" -> "MMS";
            case "RCS" -> "RCS";
            default -> "SMS";
        };
    }

    /** The origination identity to send from: a phone number, sender ID or RCS agent, picked from a pool if named. */
    private ObjectNode origination(String region, String reference, Country country, String capability,
                                   ObjectNode configSet) {
        if (reference == null) {
            if (configSet != null && configSet.hasNonNull("DefaultSenderId") && "SMS".equals(capability)) {
                String senderId = configSet.get("DefaultSenderId").asText();
                ObjectNode found = store.get(region, SmsVoiceStore.SENDER_ID, senderId + "/" + country.iso())
                        .orElse(null);
                if (found != null) {
                    return found;
                }
            }
            for (ObjectNode phone : store.all(region, SmsVoiceStore.PHONE)) {
                if (country.iso().equals(phone.path("IsoCountryCode").asText())
                        && "ACTIVE".equals(phone.path("Status").asText()) && capable(phone, capability)) {
                    return phone;
                }
            }
            throw SmsVoiceStore.conflict("NO_ORIGINATION_IDENTITIES_FOUND", SmsVoiceStore.PHONE, "",
                    "No origination identity can send " + capability + " to " + country.name() + ".");
        }
        ObjectNode identity;
        if (reference.startsWith("pool-") || reference.contains(":" + SmsVoiceStore.POOL + "/")) {
            ObjectNode pool = service.pool(region, reference);
            identity = null;
            for (ObjectNode member : service.members(region, pool)) {
                boolean fits = SmsVoiceStore.SENDER_ID.equals(SmsVoiceService.kind(member))
                        ? country.iso().equals(member.path("IsoCountryCode").asText()) && "SMS".equals(capability)
                        : capable(member, capability) && (country.iso().equals(member.path("IsoCountryCode").asText())
                        || pool.path("SharedRoutesEnabled").asBoolean());
                if (fits && (identity == null || !"ACTIVE".equals(identity.path("Status").asText("ACTIVE")))) {
                    identity = member;
                }
            }
            if (identity == null) {
                throw SmsVoiceStore.conflict("NO_ORIGINATION_IDENTITIES_FOUND", SmsVoiceStore.POOL,
                        SmsVoiceStore.id(pool), "The pool has no identity that can send " + capability + " to "
                                + country.name() + ".");
            }
        } else {
            identity = service.member(region, reference, country.iso());
        }
        if (!service.accessible(identity)) {
            throw SmsVoiceStore.notFound(SmsVoiceService.kind(identity), reference);
        }
        return identity;
    }

    private static boolean capable(ObjectNode identity, String capability) {
        if (SmsVoiceStore.SENDER_ID.equals(SmsVoiceService.kind(identity))) {
            return "SMS".equals(capability);
        }
        if (SmsVoiceStore.RCS.equals(SmsVoiceService.kind(identity))) {
            return "RCS".equals(capability);
        }
        for (JsonNode cap : identity.path("NumberCapabilities")) {
            if (capability.equals(cap.asText())) {
                return true;
            }
        }
        return false;
    }

    /** The opt-out list, two-way settings and opt-out management an identity follows: its pool's when pooled. */
    private ObjectNode settings(String region, ObjectNode identity) {
        if (identity.hasNonNull("PoolId")) {
            ObjectNode pool = store.get(region, SmsVoiceStore.POOL, identity.get("PoolId").asText()).orElse(null);
            if (pool != null) {
                return pool;
            }
        }
        return identity;
    }

    private Outgoing check(String region, String channel, JsonNode in, String body, boolean internal) {
        String destination = SmsVoiceCatalog.e164(SmsVoiceService.req(in, "DestinationPhoneNumber"),
                "DestinationPhoneNumber");
        Country country = SmsVoiceCatalog.countryOf(destination);
        if (country == null) {
            throw SmsVoiceStore.validation("DESTINATION_COUNTRY_BLOCKED", "Floci doesn't deliver to "
                    + destination + ".");
        }
        ObjectNode configSet = SmsVoiceService.opt(in, "ConfigurationSetName") == null ? null
                : service.configSet(region, in.get("ConfigurationSetName").asText());
        String capability = capability(channel);
        ObjectNode identity = origination(region, SmsVoiceService.opt(in, "OriginationIdentity"), country,
                capability, configSet);
        String kind = SmsVoiceService.kind(identity);
        String id = SmsVoiceStore.id(identity);
        if (SmsVoiceStore.PHONE.equals(kind)) {
            if (!"ACTIVE".equals(identity.path("Status").asText())) {
                throw SmsVoiceStore.conflict("RESOURCE_NOT_ACTIVE", kind, id, "The number is "
                        + identity.path("Status").asText() + ".");
            }
            if (!capable(identity, capability)) {
                if ("VOICE".equals(capability)) {
                    throw SmsVoiceStore.validation("VOICE_CAPABILITY_NOT_AVAILABLE", "The number can't send voice "
                            + "messages.");
                }
                throw SmsVoiceStore.conflict("NUMBER_CAPABILITIES_MISMATCH", kind, id, "The number can't send "
                        + capability + ".");
            }
            String type = identity.path("NumberType").asText();
            if ("US".equals(identity.path("IsoCountryCode").asText()) && ("TOLL_FREE".equals(type)
                    || "TEN_DLC".equals(type))) {
                String registration = identity.path("RegistrationId").asText("");
                boolean complete = !registration.isEmpty() && store.get(region, SmsVoiceStore.REGISTRATION,
                        registration).map(r -> "COMPLETE".equals(r.path("RegistrationStatus").asText()))
                        .orElse(false);
                if (!complete) {
                    throw SmsVoiceStore.conflict("REGISTRATION_NOT_COMPLETE", kind, id, "A US " + type
                            + " number sends once its registration is COMPLETE.");
                }
            }
            if (!country.iso().equals(identity.path("IsoCountryCode").asText())
                    && !identity.path("InternationalSendingEnabled").asBoolean()) {
                throw SmsVoiceStore.validation("INVALID_IDENTITY_FOR_DESTINATION_COUNTRY", "The "
                        + identity.path("IsoCountryCode").asText() + " number can't send to " + country.name()
                        + " without international sending.");
            }
        } else if (SmsVoiceStore.SENDER_ID.equals(kind)) {
            if (!"SMS".equals(capability)) {
                throw SmsVoiceStore.conflict("NUMBER_CAPABILITIES_MISMATCH", kind, id, "Sender IDs send SMS only.");
            }
            if (!country.iso().equals(identity.path("IsoCountryCode").asText())) {
                throw SmsVoiceStore.validation("INVALID_IDENTITY_FOR_DESTINATION_COUNTRY", "The sender ID is for "
                        + identity.path("IsoCountryCode").asText() + ".");
            }
            if (!identity.path("Registered").asBoolean()) {
                throw SmsVoiceStore.validation("SENDER_ID_NOT_REGISTERED", "The sender ID needs a COMPLETE "
                        + "registration in " + country.name() + ".");
            }
        }
        if (!internal && !service.destinationAllowed(region, destination)) {
            throw SmsVoiceStore.conflict("DESTINATION_PHONE_NUMBER_NOT_VERIFIED", SmsVoiceStore.VERIFIED,
                    destination, "In the sandbox, the account sends only to verified destination numbers.");
        }
        ObjectNode settings = settings(region, identity);
        String list = settings.path("OptOutListName").asText(SmsVoiceService.DEFAULT_LIST);
        if (!settings.path("SelfManagedOptOutsEnabled").asBoolean() && service.optedOut(region, list, destination)) {
            throw SmsVoiceStore.conflict("DESTINATION_PHONE_NUMBER_OPTED_OUT", SmsVoiceStore.OPTED_OUT, destination,
                    destination + " opted out of " + list + ".");
        }
        String protect = SmsVoiceService.opt(in, "ProtectConfigurationId");
        if (protect == null && configSet != null) {
            protect = SmsVoiceService.opt(configSet, "ProtectConfigurationId");
        }
        if (protect == null) {
            protect = SmsVoiceService.opt(service.account(region), "DefaultProtectConfigurationId");
        }
        service.checkProtect(region, protect, capability, destination);
        int parts = "VOICE".equals(channel) ? 1 : SmsVoiceCatalog.parts(body == null ? "" : body);
        boolean simulator = "SIMULATOR".equals(identity.path("NumberType").asText());
        double price = simulator ? 0 : switch (channel) {
            case "VOICE" -> country.voicePrice();
            case "MEDIA" -> 0.0200;
            default -> parts * country.textPrice();
        };
        String max = SmsVoiceService.opt(in, "VOICE".equals(channel) ? "MaxPricePerMinute" : "MaxPrice");
        if (max != null && Double.parseDouble(max) < price) {
            throw SmsVoiceStore.validation("PRICE_OVER_THRESHOLD", "The message costs " + price + " USD, more than "
                    + "the MaxPrice of " + max + ".");
        }
        String number = identity.path("PhoneNumber").asText(identity.path("SenderId").asText(id));
        boolean feedback = in.has("MessageFeedbackEnabled") ? in.get("MessageFeedbackEnabled").asBoolean()
                : configSet != null && configSet.path("DefaultMessageFeedbackEnabled").asBoolean();
        String messageType = SmsVoiceService.opt(in, "MessageType");
        if (messageType == null) {
            messageType = configSet != null && configSet.hasNonNull("DefaultMessageType")
                    ? configSet.get("DefaultMessageType").asText() : identity.path("MessageType").asText("TRANSACTIONAL");
        }
        return new Outgoing(channel, id, number, identity, destination, country, body, messageType, configSet,
                in.path("Context"), parts, price, feedback);
    }

    private void spend(String region, String channel, double price) {
        ObjectNode account = service.account(region);
        String month = YearMonth.now(ZoneOffset.UTC).toString();
        ObjectNode all = account.has("Spent") ? (ObjectNode) account.get("Spent") : account.putObject("Spent");
        ObjectNode spent = all.has(month) ? (ObjectNode) all.get(month) : all.putObject(month);
        String kind = spendKind(channel);
        double total = spent.path(kind).asDouble() + price;
        if (total > service.enforcedLimit(account, kind)) {
            throw SmsVoiceStore.quota("MONTHLY_SPEND_LIMIT_REACHED_FOR_" + kind, "The message would pass this "
                    + "month's " + kind.toLowerCase(Locale.ROOT) + " spend limit of "
                    + service.enforcedLimit(account, kind) + " USD.");
        }
        spent.put(kind, total);
        service.saveAccount(region, account);
    }

    // ================================================================ sending

    private String send(String region, Outgoing message, boolean dryRun, int depth) {
        String id = UUID.randomUUID().toString();
        if (dryRun) {
            return id;
        }
        spend(region, message.channel(), message.price());
        ObjectNode record = store.record();
        record.put("MessageId", id).put("Channel", message.channel()).put("Destination", message.destination())
                .put("Origination", message.originationNumber()).put("FeedbackEnabled", message.feedback());
        if (message.configSet() != null) {
            record.put("ConfigurationSetName", SmsVoiceStore.id(message.configSet()));
        }
        store.put(region, SmsVoiceStore.MESSAGE, id, record);
        String account = store.account();
        later(account, region, () -> deliver(account, region, message, id, depth));
        return id;
    }

    private void later(String account, String region, Runnable task) {
        vertx.setTimer(Math.max(1, store.config().deliveryMillis()), timer -> worker.submit(() -> {
            try {
                RequestScopes.runAs(account, region, task);
            } catch (RuntimeException e) {
                LOG.warnv("SMS and Voice delivery failed: {0}", e.getMessage());
            }
        }));
    }

    private void deliver(String account, String region, Outgoing message, String id, int depth) {
        List<String[]> events = switch (message.channel()) {
            case "VOICE" -> List.of(new String[] {"VOICE_INITIATED", "INITIATED"},
                    new String[] {"VOICE_RINGING", "RINGING"}, new String[] {"VOICE_ANSWERED", "ANSWERED"},
                    new String[] {"VOICE_COMPLETED", "COMPLETED"});
            case "MEDIA" -> List.of(new String[] {"MEDIA_SUCCESSFUL", "SUCCESSFUL"},
                    new String[] {"MEDIA_DELIVERED", "DELIVERED"});
            case "RCS_FALLBACK" -> List.of(new String[] {"RCS_FALLEN_BACK_TO_SMS", "FALLEN_BACK_TO_SMS"},
                    new String[] {"TEXT_SUCCESSFUL", "SUCCESSFUL"}, new String[] {"TEXT_DELIVERED", "DELIVERED"});
            default -> List.of(new String[] {"TEXT_SUCCESSFUL", "SUCCESSFUL"},
                    new String[] {"TEXT_DELIVERED", "DELIVERED"});
        };
        for (int i = 0; i < events.size(); i++) {
            publish(region, message, id, events.get(i)[0], events.get(i)[1], i == events.size() - 1);
        }
        if (!"VOICE".equals(message.channel())) {
            arrive(message, id, depth);
        }
    }

    /** Sends an event to the configuration set's enabled destinations that match its type. */
    private void publish(String region, Outgoing message, String id, String type, String status, boolean last) {
        if (message.configSet() == null) {
            return;
        }
        ObjectNode set = store.get(region, SmsVoiceStore.CONFIG_SET, SmsVoiceStore.id(message.configSet()))
                .orElse(null);
        if (set == null) {
            return;
        }
        String family = type.substring(0, type.indexOf('_')) + "_ALL";
        ObjectNode event = mapper.createObjectNode().put("eventType", type).put("eventVersion", "1.0")
                .put("eventTimestamp", System.currentTimeMillis()).put("isFinal", last)
                .put("originationPhoneNumber", message.originationNumber())
                .put("destinationPhoneNumber", message.destination()).put("isoCountryCode", message.country().iso())
                .put("messageId", id).put("messageRequestTimestamp", System.currentTimeMillis())
                .put("messageType", message.messageType()).put("messageStatus", status)
                .put("configurationSetName", SmsVoiceStore.id(set));
        if (!"VOICE".equals(message.channel())) {
            event.put("messageEncoding", SmsVoiceCatalog.gsm(message.body() == null ? "" : message.body())
                    ? "GSM" : "UCS2").put("totalMessageParts", message.parts());
        }
        event.put("totalMessagePrice", message.price()).put("totalCarrierFee", 0.0)
                .put("carrierName", "Floci Wireless");
        if (message.context() != null && message.context().isObject()) {
            event.set("context", message.context());
        }
        String body = event.toString();
        for (JsonNode destination : set.path("EventDestinations")) {
            if (!destination.path("Enabled").asBoolean(true)) {
                continue;
            }
            boolean match = false;
            for (JsonNode wanted : destination.path("MatchingEventTypes")) {
                String value = wanted.asText();
                match |= value.equals("ALL") || value.equals(family) || value.equals(type);
            }
            if (match) {
                send(destination, body);
            }
        }
    }

    private void send(JsonNode destination, String body) {
        try {
            if (destination.has("SnsDestination")) {
                String topic = destination.at("/SnsDestination/TopicArn").asText();
                AwsArnUtils.Arn arn = AwsArnUtils.parse(topic);
                RequestScopes.runAs(arn.accountId(), arn.region(), () -> sns.get().publish(topic, null, body, null,
                        arn.region()));
            } else if (destination.has("CloudWatchLogsDestination")) {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(destination.at("/CloudWatchLogsDestination/LogGroupArn")
                        .asText());
                String group = SmsVoiceService.groupName(arn.resource());
                RequestScopes.runAs(arn.accountId(), arn.region(), () -> {
                    String stream = "sms-voice-events";
                    try {
                        logs.get().createLogStream(group, stream, arn.region());
                    } catch (AwsException e) {
                        LOG.debugv("Log stream {0} exists: {1}", stream, e.getMessage());
                    }
                    logs.get().putLogEvents(group, stream, List.of(Map.of("timestamp", System.currentTimeMillis(),
                            "message", body)), arn.region());
                });
            } else if (destination.has("KinesisFirehoseDestination")) {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(destination.at("/KinesisFirehoseDestination/DeliveryStreamArn")
                        .asText());
                String stream = arn.resource().substring(arn.resource().indexOf('/') + 1);
                firehose.get().putRecord(arn.accountId(), arn.region(), stream,
                        new Record((body + "\n").getBytes(StandardCharsets.UTF_8)));
            }
        } catch (RuntimeException e) {
            LOG.warnv("SMS and Voice couldn't deliver an event: {0}", e.getMessage());
        }
    }

    // ================================================================ inbound

    /** Delivers a message to one of an account's own numbers, if the destination is one. */
    private void arrive(Outgoing message, String previousId, int depth) {
        if (depth > 3 || message.body() == null) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry : store.everywhere(SmsVoiceStore.PHONE)) {
            ObjectNode phone = entry.value();
            if (message.destination().equals(phone.path("PhoneNumber").asText())) {
                String region = SmsVoiceStore.region(phone);
                RequestScopes.runAs(entry.accountId(), region, () -> inbound(region, phone, message, previousId,
                        depth));
                return;
            }
        }
    }

    private void inbound(String region, ObjectNode phone, Outgoing message, String previousId, int depth) {
        ObjectNode settings = settings(region, phone);
        String text = message.body().trim();
        String first = text.isEmpty() ? "" : text.split("\\s+")[0].toUpperCase(Locale.ROOT);
        ObjectNode keyword = null;
        for (ObjectNode candidate : service.keywordsFor(region, phone)) {
            if (candidate.path("Keyword").asText().toUpperCase(Locale.ROOT).equals(first)) {
                keyword = candidate;
            }
        }
        String sender = message.originationNumber();
        if (settings.path("TwoWayEnabled").asBoolean() && AwsArnUtils.isArnFor(settings.path("TwoWayChannelArn")
                .asText(), "sns")) {
            ObjectNode payload = mapper.createObjectNode().put("originationNumber", sender)
                    .put("destinationNumber", phone.path("PhoneNumber").asText())
                    .put("messageKeyword", keyword == null ? "" : keyword.path("Keyword").asText())
                    .put("messageBody", message.body()).put("inboundMessageId", UUID.randomUUID().toString())
                    .put("previousPublishedMessageId", previousId);
            String topic = settings.get("TwoWayChannelArn").asText();
            AwsArnUtils.Arn arn = AwsArnUtils.parse(topic);
            try {
                RequestScopes.runAs(arn.accountId(), arn.region(), () -> sns.get().publish(topic, null,
                        payload.toString(), null, arn.region()));
            } catch (RuntimeException e) {
                LOG.warnv("SMS and Voice couldn't publish an inbound message: {0}", e.getMessage());
            }
        }
        if (keyword == null || !sender.startsWith("+")) {
            return;
        }
        String list = settings.path("OptOutListName").asText(SmsVoiceService.DEFAULT_LIST);
        boolean selfManaged = settings.path("SelfManagedOptOutsEnabled").asBoolean();
        switch (keyword.path("KeywordAction").asText()) {
            case "OPT_OUT" -> {
                if (selfManaged) {
                    return;
                }
                service.ensureDefaultList(region);
                service.optOut(region, list, sender, true);
            }
            case "OPT_IN" -> {
                if (!selfManaged) {
                    service.optIn(region, list, sender);
                }
            }
            default -> {
            }
        }
        reply(region, phone, sender, keyword.path("KeywordMessage").asText(), previousId, depth);
    }

    /** A keyword's answer, sent from the number that received the keyword. */
    private void reply(String region, ObjectNode phone, String to, String body, String inReplyTo, int depth) {
        Country country = SmsVoiceCatalog.countryOf(to);
        if (country == null) {
            return;
        }
        Outgoing answer = new Outgoing("TEXT", SmsVoiceStore.id(phone), phone.path("PhoneNumber").asText(), phone,
                to, country, body, phone.path("MessageType").asText("TRANSACTIONAL"), null, null,
                SmsVoiceCatalog.parts(body), 0, false);
        String id = UUID.randomUUID().toString();
        LOG.debugv("SMS and Voice answers {0} with {1} in reply to {2}", to, id, inReplyTo);
        arrive(answer, id, depth + 1);
    }

    // ================================================================ operations

    private ObjectNode messageId(String id) {
        return mapper.createObjectNode().put("MessageId", id);
    }

    public ObjectNode sendTextMessage(JsonNode in, String region) {
        String body = SmsVoiceService.opt(in, "MessageBody");
        if (body == null) {
            throw SmsVoiceStore.validation("MISSING_PARAMETER", "MessageBody is required.");
        }
        if (body.length() > 1600) {
            throw SmsVoiceStore.validation("MAXIMUM_SIZE_EXCEEDED", "A text message is at most 1600 characters.");
        }
        Outgoing message = check(region, "TEXT", in, body, false);
        return messageId(send(region, message, in.path("DryRun").asBoolean(false), 0));
    }

    public ObjectNode sendVoiceMessage(JsonNode in, String region) {
        String body = SmsVoiceService.opt(in, "MessageBody");
        if (body == null) {
            throw SmsVoiceStore.validation("MISSING_PARAMETER", "MessageBody is required.");
        }
        if ("SSML".equals(SmsVoiceService.opt(in, "MessageBodyTextType"))) {
            if (!body.trim().startsWith("<speak")) {
                throw SmsVoiceStore.validation("INVALID_PARAMETER", "SSML starts with <speak>.");
            }
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw SmsVoiceStore.validation("CANNOT_PARSE", "The SSML isn't well-formed: " + e.getMessage());
            }
        }
        if (body.length() > 6000) {
            throw SmsVoiceStore.validation("MAXIMUM_SIZE_EXCEEDED", "A voice message is at most 6000 characters.");
        }
        Outgoing message = check(region, "VOICE", in, body, false);
        return messageId(send(region, message, in.path("DryRun").asBoolean(false), 0));
    }

    public ObjectNode sendMediaMessage(JsonNode in, String region) {
        String body = SmsVoiceService.opt(in, "MessageBody");
        JsonNode urls = in.path("MediaUrls");
        if (body == null && (!urls.isArray() || urls.isEmpty())) {
            throw SmsVoiceStore.validation("MISSING_PARAMETER", "A media message needs a MessageBody or MediaUrls.");
        }
        if (urls.size() > 10) {
            throw SmsVoiceStore.validation("MAXIMUM_SIZE_EXCEEDED", "A media message has at most 10 files.");
        }
        Outgoing message = check(region, "MEDIA", in, body == null ? "" : body, false);
        for (JsonNode url : urls) {
            String uri = url.asText();
            if (!uri.startsWith("s3://") || uri.indexOf('/', 5) < 0) {
                throw SmsVoiceStore.validation("INVALID_PARAMETER", "MediaUrls are s3:// URLs.");
            }
            String path = uri.substring(5);
            S3Object object;
            try {
                object = s3.get().getObject(path.substring(0, path.indexOf('/')), path.substring(path.indexOf('/')
                        + 1));
            } catch (AwsException e) {
                throw SmsVoiceStore.validation("RESOURCE_NOT_ACCESSIBLE", "The file " + uri + " can't be read: "
                        + e.getMessage());
            }
            String type = object.getContentType() == null ? "" : object.getContentType().split(";")[0].trim();
            if (!MEDIA_TYPES.contains(type)) {
                throw SmsVoiceStore.validation("MEDIA_TYPE_NOT_SUPPORTED", "The file " + uri + " is " + type
                        + ", which media messages don't carry.");
            }
            if (object.getData() != null && object.getData().length > (type.startsWith("image/") ? 600 : 2048)
                    * 1024) {
                throw SmsVoiceStore.validation("MAXIMUM_SIZE_EXCEEDED", "The file " + uri + " is too large.");
            }
        }
        return messageId(send(region, message, in.path("DryRun").asBoolean(false), 0));
    }

    public ObjectNode sendRcsMessage(JsonNode in, String region) {
        String destination = SmsVoiceCatalog.e164(SmsVoiceService.req(in, "DestinationPhoneNumber"),
                "DestinationPhoneNumber");
        ObjectNode agent = service.rcs(region, SmsVoiceService.req(in, "OriginationIdentity"));
        if ("ACTIVE".equals(agent.path("Status").asText())) {
            Country country = SmsVoiceCatalog.countryOf(destination);
            String body = in.at("/RcsMessageContent/Content/TextMessage/Body").asText("");
            Outgoing message = new Outgoing("RCS", SmsVoiceStore.id(agent), SmsVoiceStore.id(agent), agent,
                    destination, country, body, "TRANSACTIONAL", null, in.path("Context"), 1, 0.0, false);
            return messageId(send(region, message, in.path("DryRun").asBoolean(false), 0));
        }
        JsonNode fallback = in.path("FallbackConfiguration");
        if (!fallback.isObject()) {
            throw SmsVoiceStore.conflict("RESOURCE_NOT_ACTIVE", SmsVoiceStore.RCS, SmsVoiceStore.id(agent),
                    "The RCS agent is " + agent.path("Status").asText() + " and the message has no fallback.");
        }
        ObjectNode request = mapper.createObjectNode().put("DestinationPhoneNumber", destination);
        for (String member : List.of("ConfigurationSetName", "ProtectConfigurationId", "MaxPrice")) {
            if (in.has(member)) {
                request.set(member, in.get(member));
            }
        }
        if (fallback.hasNonNull("OriginationIdentity")) {
            request.set("OriginationIdentity", fallback.get("OriginationIdentity"));
        }
        if (in.has("Context")) {
            request.set("Context", in.get("Context"));
        }
        String body = fallback.path("MessageBody").asText(in.at("/RcsMessageContent/Content/TextMessage/Body")
                .asText(""));
        Outgoing checked = check(region, "MMS".equals(fallback.path("Channel").asText()) ? "MEDIA" : "TEXT",
                request, body, false);
        Outgoing message = new Outgoing("RCS_FALLBACK", checked.origination(), checked.originationNumber(),
                checked.identity(), checked.destination(), checked.country(), body, checked.messageType(),
                checked.configSet(), checked.context(), checked.parts(), checked.price(), checked.feedback());
        return messageId(send(region, message, in.path("DryRun").asBoolean(false), 0));
    }

    public ObjectNode sendDestinationNumberVerificationCode(JsonNode in, String region) {
        ObjectNode record = service.verified(region, SmsVoiceService.req(in, "VerifiedDestinationNumberId"));
        if ("VERIFIED".equals(record.path("Status").asText())) {
            throw SmsVoiceStore.conflict("VERIFICATION_ALREADY_COMPLETE", SmsVoiceStore.VERIFIED,
                    SmsVoiceStore.id(record), "The number is already verified.");
        }
        ObjectNode meta = SmsVoiceStore.meta(record);
        int attempts = meta.path("attempts").asInt();
        if (attempts >= 5) {
            throw SmsVoiceStore.quota("VERIFICATION_ATTEMPTS_PER_DAY", "A number gets at most 5 codes a day.");
        }
        String code = String.format("%06d", SmsVoiceStore.randomInt(1_000_000));
        String channel = "VOICE".equals(SmsVoiceService.req(in, "VerificationChannel")) ? "VOICE" : "TEXT";
        String body = "Your verification code is " + code + ".";
        ObjectNode request = mapper.createObjectNode().put("DestinationPhoneNumber",
                record.get("DestinationPhoneNumber").asText());
        for (String member : List.of("OriginationIdentity", "ConfigurationSetName", "Context")) {
            if (in.has(member)) {
                request.set(member, in.get(member));
            }
        }
        Outgoing message = check(region, channel, request, body, true);
        String id = send(region, message, false, 0);
        meta.put("code", code).put("expires", SmsVoiceStore.now() + 600).put("attempts", attempts + 1);
        service.save(record);
        return messageId(id);
    }

    private ObjectNode sendNotify(JsonNode in, String region, String channel) {
        ObjectNode config = service.notify(region, SmsVoiceService.req(in, "NotifyConfigurationId"));
        if (!"ACTIVE".equals(config.path("Status").asText())) {
            throw SmsVoiceStore.conflict("NOTIFY_CONFIGURATION_NOT_ACTIVE", SmsVoiceStore.NOTIFY,
                    SmsVoiceStore.id(config), "The Notify configuration is " + config.path("Status").asText() + ".");
        }
        boolean enabled = false;
        for (JsonNode c : config.path("EnabledChannels")) {
            enabled |= c.asText().equals("TEXT".equals(channel) ? "SMS" : channel);
        }
        if (!enabled) {
            throw SmsVoiceStore.validation("CHANNEL_NOT_ENABLED", "The Notify configuration doesn't send "
                    + channel + ".");
        }
        String destination = SmsVoiceCatalog.e164(SmsVoiceService.req(in, "DestinationPhoneNumber"),
                "DestinationPhoneNumber");
        Country country = SmsVoiceCatalog.countryOf(destination);
        boolean countryEnabled = config.path("EnabledCountries").isArray() && !config.get("EnabledCountries").isEmpty()
                ? config.get("EnabledCountries").toString().contains("\"" + (country == null ? "" : country.iso())
                + "\"") : country != null && SmsVoiceCatalog.NOTIFY_BASIC.contains(country.iso());
        if (!countryEnabled) {
            throw SmsVoiceStore.validation("COUNTRY_NOT_ENABLED", "The Notify configuration doesn't send to "
                    + destination + ".");
        }
        String templateId = SmsVoiceService.opt(in, "TemplateId");
        if (templateId == null) {
            templateId = config.path("DefaultTemplateId").asText("OTP_EN_US_V1");
        }
        SmsVoiceCatalog.Template template = SmsVoiceCatalog.TEMPLATES.get(templateId);
        if (template == null) {
            throw SmsVoiceStore.notFound("notify-template", templateId);
        }
        JsonNode variables = in.path("TemplateVariables");
        String code = variables.path("code").asText("");
        if (!code.matches("[0-9A-Za-z]{4,10}")) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", "TemplateVariables.code must be 4 to 10 letters or "
                    + "digits.");
        }
        String expiry = variables.path("expiry").asText("10");
        if (!expiry.matches("[0-9]+") || Integer.parseInt(expiry) < 1 || Integer.parseInt(expiry) > 60) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", "TemplateVariables.expiry must be 1 to 60.");
        }
        String brand = variables.path("brand").asText(config.path("DisplayName").asText());
        String body = template.content().replace("{{brand}}", brand).replace("{{code}}", code)
                .replace("{{expiry}}", expiry);
        String id;
        ObjectNode request = mapper.createObjectNode().put("DestinationPhoneNumber", destination);
        for (String member : List.of("ConfigurationSetName", "Context", "MessageFeedbackEnabled")) {
            if (in.has(member)) {
                request.set(member, in.get(member));
            }
        }
        if (config.hasNonNull("PoolId")) {
            request.put("OriginationIdentity", config.get("PoolId").asText());
            Outgoing checked = check(region, "VOICE".equals(channel) ? "VOICE" : "TEXT", request, body, true);
            Outgoing message = new Outgoing("VOICE".equals(channel) ? "VOICE" : "NOTIFY", checked.origination(),
                    checked.originationNumber(), checked.identity(), destination, checked.country(), body,
                    "TRANSACTIONAL", checked.configSet(), checked.context(), checked.parts(), checked.price(),
                    checked.feedback());
            id = send(region, message, in.path("DryRun").asBoolean(false), 0);
        } else {
            ObjectNode configSet = SmsVoiceService.opt(in, "ConfigurationSetName") == null ? null
                    : service.configSet(region, in.get("ConfigurationSetName").asText());
            Outgoing message = new Outgoing("VOICE".equals(channel) ? "VOICE" : "NOTIFY", "notify",
                    "+18885550100", config, destination, country, body, "TRANSACTIONAL", configSet,
                    in.path("Context"), SmsVoiceCatalog.parts(body), country.textPrice(),
                    in.path("MessageFeedbackEnabled").asBoolean(false));
            id = send(region, message, in.path("DryRun").asBoolean(false), 0);
        }
        return messageId(id).put("TemplateId", templateId).put("ResolvedMessageBody", body);
    }

    public ObjectNode sendNotifyTextMessage(JsonNode in, String region) {
        return sendNotify(in, region, "TEXT");
    }

    public ObjectNode sendNotifyVoiceMessage(JsonNode in, String region) {
        return sendNotify(in, region, "VOICE");
    }

    public ObjectNode putMessageFeedback(JsonNode in, String region) {
        String id = SmsVoiceService.req(in, "MessageId");
        ObjectNode record = store.get(region, SmsVoiceStore.MESSAGE, id)
                .orElseThrow(() -> SmsVoiceStore.notFound(SmsVoiceStore.MESSAGE, id));
        if (!record.path("FeedbackEnabled").asBoolean()) {
            throw SmsVoiceStore.validation("INVALID_REQUEST", "The message was sent without MessageFeedbackEnabled.");
        }
        String status = SmsVoiceService.req(in, "MessageFeedbackStatus");
        record.put("MessageFeedbackStatus", status);
        store.put(region, SmsVoiceStore.MESSAGE, id, record);
        return mapper.createObjectNode().put("MessageId", id).put("MessageFeedbackStatus", status);
    }
}
