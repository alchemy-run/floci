package io.floci.aws.services.mailmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Archived messages and what's done with them: rule sets' Archive actions keep each message with its envelope and
 * metadata until the archive's retention ends; searches and exports filter them by time and by To, From, Cc, Subject,
 * envelope addresses and attachments; exports write each message (and, if asked, its metadata) to S3. A message is
 * downloaded from a presigned URL, and its content read as text and HTML.
 */
@ApplicationScoped
public class MailManagerArchives {

    private final MailManagerStore store;
    private final ObjectMapper mapper;
    private final Provider<S3Service> s3;
    private final Provider<MailManagerResources> resources;

    @Inject
    public MailManagerArchives(MailManagerStore store, Provider<S3Service> s3,
                               Provider<MailManagerResources> resources) {
        this.store = store;
        this.mapper = store.mapper();
        this.s3 = s3;
        this.resources = resources;
    }

    // ================================================================ archiving

    void archive(String account, String region, String target, ObjectNode ingress, ObjectNode ruleSet,
                 MailManagerPolicies.Mail mail, byte[] raw, String messageId) {
        ObjectNode archive = null;
        String id = MailManagerStore.idOf(target);
        archive = store.getForAccount(account, region, MailManagerStore.ARCHIVE, id).orElse(null);
        if (archive == null) {
            for (ObjectNode candidate : store.listForAccount(account, region, MailManagerStore.ARCHIVE, "")) {
                if (target.equals(candidate.path("ArchiveName").asText())) {
                    archive = candidate;
                }
            }
        }
        if (archive == null || !"ACTIVE".equals(archive.path("ArchiveState").asText())) {
            throw new AwsException("ActionFailed", "The archive " + target + " isn't active.", 400);
        }
        String archivedId = UUID.randomUUID().toString().replace("-", "");
        ObjectNode record = store.record();
        record.put("ArchivedMessageId", archivedId);
        record.put("ArchiveId", archive.path("ArchiveId").asText());
        record.put("ReceivedTimestamp", MailManagerStore.epoch());
        MailManagerMime.Parsed parsed = mail.message() == null ? MailManagerMime.parse(raw) : mail.message();
        putHeader(record, "Date", parsed.header("Date"));
        putHeader(record, "To", parsed.header("To"));
        putHeader(record, "From", parsed.header("From"));
        putHeader(record, "Cc", parsed.header("Cc"));
        putHeader(record, "Subject", parsed.header("Subject"));
        putHeader(record, "MessageId", parsed.header("Message-ID"));
        putHeader(record, "InReplyTo", parsed.header("In-Reply-To"));
        putHeader(record, "XMailer", parsed.header("X-Mailer"));
        putHeader(record, "XOriginalMailer", parsed.header("X-Original-Mailer"));
        putHeader(record, "XPriority", parsed.header("X-Priority"));
        record.put("HasAttachments", parsed.attachments());
        ArrayNode received = record.putArray("ReceivedHeaders");
        parsed.all("Received").forEach(received::add);
        record.put("IngressPointId", ingress.path("IngressPointId").asText());
        record.put("SenderHostname", mail.helo() == null ? "" : mail.helo());
        record.put("SenderIpAddress", mail.sourceIp());
        ObjectNode envelope = record.putObject("Envelope");
        envelope.put("Helo", mail.helo() == null ? "" : mail.helo());
        envelope.put("From", mail.mailFrom());
        ArrayNode to = envelope.putArray("To");
        mail.recipients().forEach(to::add);
        record.put("SourceArn", ingress.path("IngressPointArn").asText());
        ObjectNode meta = MailManagerStore.meta(record);
        meta.put("raw", Base64.getEncoder().encodeToString(raw));
        meta.put("trafficPolicyId", ingress.path("TrafficPolicyId").asText());
        meta.put("ruleSetId", ruleSet.path("RuleSetId").asText());
        meta.put("messageId", messageId);
        store.putForAccount(account, region, MailManagerStore.MESSAGE, archive.path("ArchiveId").asText() + "/"
                + archivedId, record);
    }

    private static void putHeader(ObjectNode record, String field, String value) {
        if (value != null) {
            record.put(field, value);
        }
    }

    /** An archive's messages, without those past its retention. */
    private List<ObjectNode> messages(String region, ObjectNode archive) {
        String id = archive.path("ArchiveId").asText();
        double cutoff = cutoff(archive.path("Retention").path("RetentionPeriod").asText("PERMANENT"));
        List<ObjectNode> kept = new ArrayList<>();
        for (ObjectNode message : store.list(region, MailManagerStore.MESSAGE, id + "/")) {
            if (message.path("ReceivedTimestamp").asDouble() < cutoff) {
                store.delete(region, MailManagerStore.MESSAGE, message.path("_").path("id").asText());
            } else {
                kept.add(message);
            }
        }
        return kept;
    }

    private static double cutoff(String period) {
        int months = switch (period) {
            case "THREE_MONTHS" -> 3;
            case "SIX_MONTHS" -> 6;
            case "NINE_MONTHS" -> 9;
            case "ONE_YEAR" -> 12;
            case "EIGHTEEN_MONTHS" -> 18;
            case "TWO_YEARS" -> 24;
            case "THIRTY_MONTHS" -> 30;
            case "THREE_YEARS" -> 36;
            case "FOUR_YEARS" -> 48;
            case "FIVE_YEARS" -> 60;
            case "SIX_YEARS" -> 72;
            case "SEVEN_YEARS" -> 84;
            case "EIGHT_YEARS" -> 96;
            case "NINE_YEARS" -> 108;
            case "TEN_YEARS" -> 120;
            default -> -1;
        };
        if (months < 0) {
            return Double.NEGATIVE_INFINITY;
        }
        return ZonedDateTime.now(ZoneOffset.UTC).minusMonths(months).toInstant().toEpochMilli() / 1000.0;
    }

    /** The messages a search or export selects: in its time range, passing its filters, at most its maximum. */
    private List<ObjectNode> select(String region, ObjectNode archive, JsonNode in, int defaultMax) {
        double from = in.path("FromTimestamp").asDouble(Double.NEGATIVE_INFINITY);
        double to = in.path("ToTimestamp").asDouble(Double.POSITIVE_INFINITY);
        int max = in.path("MaxResults").asInt(defaultMax);
        List<ObjectNode> selected = new ArrayList<>();
        for (ObjectNode message : messages(region, archive)) {
            double at = message.path("ReceivedTimestamp").asDouble();
            if (at < from || at > to || !filtered(in.path("Filters"), message)) {
                continue;
            }
            selected.add(message);
        }
        selected.sort((a, b) -> Double.compare(b.path("ReceivedTimestamp").asDouble(),
                a.path("ReceivedTimestamp").asDouble()));
        return selected.size() > max ? selected.subList(0, max) : selected;
    }

    private static boolean filtered(JsonNode filters, ObjectNode message) {
        for (JsonNode condition : filters.path("Include")) {
            if (!condition(condition, message)) {
                return false;
            }
        }
        for (JsonNode condition : filters.path("Unless")) {
            if (condition(condition, message)) {
                return false;
            }
        }
        return true;
    }

    private static boolean condition(JsonNode condition, ObjectNode message) {
        if (condition.has("BooleanExpression")) {
            JsonNode expression = condition.get("BooleanExpression");
            boolean value = message.path("HasAttachments").asBoolean(false);
            return "IS_TRUE".equals(expression.path("Operator").asText()) == value;
        }
        JsonNode expression = condition.path("StringExpression");
        List<String> values = new ArrayList<>();
        switch (expression.path("Evaluate").path("Attribute").asText()) {
            case "TO" -> values.add(message.path("To").asText(""));
            case "FROM" -> values.add(message.path("From").asText(""));
            case "CC" -> values.add(message.path("Cc").asText(""));
            case "SUBJECT" -> values.add(message.path("Subject").asText(""));
            case "ENVELOPE_TO" -> message.path("Envelope").path("To").forEach(t -> values.add(t.asText()));
            case "ENVELOPE_FROM" -> values.add(message.path("Envelope").path("From").asText(""));
            default -> {
            }
        }
        for (String value : values) {
            for (JsonNode wanted : expression.path("Values")) {
                if (value.toLowerCase(Locale.ROOT).contains(wanted.asText().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private ObjectNode activeArchive(String region, String archiveId) {
        ObjectNode archive = resources.get().archive(region, archiveId);
        if (!"ACTIVE".equals(archive.path("ArchiveState").asText())) {
            throw MailManagerStore.validation("The archive is pending deletion.");
        }
        return archive;
    }

    // ================================================================ searches

    public ObjectNode startArchiveSearch(JsonNode in, String region) {
        ObjectNode archive = activeArchive(region, in.path("ArchiveId").asText());
        int running = 0;
        for (ObjectNode search : store.list(region, MailManagerStore.SEARCH, "")) {
            running += "RUNNING".equals(settle(region, MailManagerStore.SEARCH, search).path("Status").path("State")
                    .asText()) ? 1 : 0;
        }
        if (running >= 10) {
            throw MailManagerStore.quota("The account has the maximum number of running searches.");
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode message : select(region, archive, in, in.path("MaxResults").asInt(100))) {
            rows.add(row(message));
        }
        String searchId = UUID.randomUUID().toString();
        ObjectNode search = store.record();
        search.put("SearchId", searchId);
        search.put("ArchiveId", archive.path("ArchiveId").asText());
        if (in.has("Filters")) {
            search.set("Filters", in.get("Filters").deepCopy());
        }
        copy(in, search, "FromTimestamp", "ToTimestamp", "MaxResults");
        ObjectNode status = search.putObject("Status");
        status.put("SubmissionTimestamp", MailManagerStore.epoch());
        status.put("State", "RUNNING");
        ObjectNode meta = MailManagerStore.meta(search);
        meta.set("rows", mapper.valueToTree(rows));
        meta.put("doneAt", System.currentTimeMillis() + store.config().transitionMillis());
        store.put(region, MailManagerStore.SEARCH, searchId, search);
        ObjectNode out = mapper.createObjectNode();
        out.put("SearchId", searchId);
        return out;
    }

    private ObjectNode row(ObjectNode message) {
        ObjectNode row = MailManagerStore.view(message);
        row.remove(List.of("ArchiveId", "SourceArn"));
        return row;
    }

    private ObjectNode settle(String region, String kind, ObjectNode record) {
        ObjectNode meta = MailManagerStore.meta(record);
        ObjectNode status = (ObjectNode) record.get("Status");
        String state = status.path("State").asText();
        if (("RUNNING".equals(state) || "PROCESSING".equals(state) || "QUEUED".equals(state))
                && meta.path("doneAt").asLong() <= System.currentTimeMillis()) {
            status.put("State", "COMPLETED");
            status.put("CompletionTimestamp", MailManagerStore.epoch());
            meta.remove("doneAt");
            store.put(region, kind, meta.path("id").asText(), record);
        }
        return record;
    }

    private ObjectNode search(String region, String searchId) {
        ObjectNode search = store.get(region, MailManagerStore.SEARCH, searchId)
                .orElseThrow(() -> MailManagerStore.notFound("The search " + searchId + " does not exist."));
        return settle(region, MailManagerStore.SEARCH, search);
    }

    public ObjectNode getArchiveSearch(JsonNode in, String region) {
        ObjectNode out = MailManagerStore.view(search(region, in.path("SearchId").asText()));
        out.remove("SearchId");
        return out;
    }

    public ObjectNode getArchiveSearchResults(JsonNode in, String region) {
        ObjectNode search = search(region, in.path("SearchId").asText());
        if (!"COMPLETED".equals(search.path("Status").path("State").asText())) {
            throw MailManagerStore.conflict("The search is " + search.path("Status").path("State").asText() + ".");
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("Rows", search.path("_").path("rows").deepCopy());
        return out;
    }

    public ObjectNode stopArchiveSearch(JsonNode in, String region) {
        ObjectNode search = search(region, in.path("SearchId").asText());
        String state = search.path("Status").path("State").asText();
        if (!"RUNNING".equals(state) && !"QUEUED".equals(state)) {
            throw MailManagerStore.validation("The search is " + state + ".");
        }
        ((ObjectNode) search.get("Status")).put("State", "CANCELLED");
        ((ObjectNode) search.get("Status")).put("CompletionTimestamp", MailManagerStore.epoch());
        MailManagerStore.meta(search).remove("doneAt");
        store.put(region, MailManagerStore.SEARCH, search.path("SearchId").asText(), search);
        return mapper.createObjectNode();
    }

    public ObjectNode listArchiveSearches(JsonNode in, String region) {
        ObjectNode archive = resources.get().archive(region, in.path("ArchiveId").asText());
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode search : store.list(region, MailManagerStore.SEARCH, "")) {
            if (archive.path("ArchiveId").asText().equals(search.path("ArchiveId").asText())) {
                ObjectNode summary = mapper.createObjectNode();
                summary.put("SearchId", search.path("SearchId").asText());
                summary.set("Status", settle(region, MailManagerStore.SEARCH, search).path("Status").deepCopy());
                items.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "Searches", 20);
        return out;
    }

    // ================================================================ exports

    public ObjectNode startArchiveExport(JsonNode in, String region) {
        ObjectNode archive = activeArchive(region, in.path("ArchiveId").asText());
        String location = in.path("ExportDestinationConfiguration").path("S3").path("S3Location").asText(null);
        if (location == null || !location.startsWith("s3://")) {
            throw MailManagerStore.validation("The export destination needs an s3:// location.");
        }
        String[] parts = location.substring(5).split("/", 2);
        String bucket = parts[0];
        String prefix = parts.length > 1 ? parts[1] : "";
        String exportId = UUID.randomUUID().toString();
        ObjectNode export = store.record();
        export.put("ExportId", exportId);
        export.put("ArchiveId", archive.path("ArchiveId").asText());
        if (in.has("Filters")) {
            export.set("Filters", in.get("Filters").deepCopy());
        }
        copy(in, export, "FromTimestamp", "ToTimestamp", "MaxResults", "ExportDestinationConfiguration");
        ObjectNode status = export.putObject("Status");
        status.put("SubmissionTimestamp", MailManagerStore.epoch());
        status.put("State", "PROCESSING");
        try {
            String base = (prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/") + exportId + "/";
            for (ObjectNode message : select(region, archive, in, in.path("MaxResults").asInt(Integer.MAX_VALUE))) {
                String id = message.path("ArchivedMessageId").asText();
                s3.get().putObject(bucket, base + id + ".eml", Base64.getDecoder().decode(message.path("_")
                        .path("raw").asText()), "message/rfc822", Map.of());
                if (in.path("IncludeMetadata").asBoolean(false)) {
                    s3.get().putObject(bucket, base + id + ".metadata.json", row(message).toString()
                            .getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
                }
            }
            MailManagerStore.meta(export).put("doneAt", System.currentTimeMillis()
                    + store.config().transitionMillis());
        } catch (AwsException e) {
            status.put("State", "FAILED");
            status.put("ErrorMessage", "The export couldn't be written to " + location + ": " + e.getMessage());
            status.put("CompletionTimestamp", MailManagerStore.epoch());
        }
        store.put(region, MailManagerStore.EXPORT, exportId, export);
        ObjectNode out = mapper.createObjectNode();
        out.put("ExportId", exportId);
        return out;
    }

    private ObjectNode export(String region, String exportId) {
        ObjectNode export = store.get(region, MailManagerStore.EXPORT, exportId)
                .orElseThrow(() -> MailManagerStore.notFound("The export " + exportId + " does not exist."));
        return settle(region, MailManagerStore.EXPORT, export);
    }

    public ObjectNode getArchiveExport(JsonNode in, String region) {
        ObjectNode out = MailManagerStore.view(export(region, in.path("ExportId").asText()));
        out.remove("ExportId");
        return out;
    }

    public ObjectNode stopArchiveExport(JsonNode in, String region) {
        ObjectNode export = export(region, in.path("ExportId").asText());
        String state = export.path("Status").path("State").asText();
        if (!"PROCESSING".equals(state) && !"QUEUED".equals(state) && !"PREPROCESSING".equals(state)) {
            throw MailManagerStore.validation("The export is " + state + ".");
        }
        ((ObjectNode) export.get("Status")).put("State", "CANCELLED");
        ((ObjectNode) export.get("Status")).put("CompletionTimestamp", MailManagerStore.epoch());
        MailManagerStore.meta(export).remove("doneAt");
        store.put(region, MailManagerStore.EXPORT, export.path("ExportId").asText(), export);
        return mapper.createObjectNode();
    }

    public ObjectNode listArchiveExports(JsonNode in, String region) {
        ObjectNode archive = resources.get().archive(region, in.path("ArchiveId").asText());
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode export : store.list(region, MailManagerStore.EXPORT, "")) {
            if (archive.path("ArchiveId").asText().equals(export.path("ArchiveId").asText())) {
                ObjectNode summary = mapper.createObjectNode();
                summary.put("ExportId", export.path("ExportId").asText());
                summary.set("Status", settle(region, MailManagerStore.EXPORT, export).path("Status").deepCopy());
                items.add(summary);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        MailManagerStore.page(items, in, out, "Exports", 20);
        return out;
    }

    // ================================================================ messages

    private ObjectNode message(String region, String archivedId) {
        for (ObjectNode message : store.list(region, MailManagerStore.MESSAGE, "")) {
            if (archivedId.equals(message.path("ArchivedMessageId").asText())) {
                return message;
            }
        }
        throw MailManagerStore.notFound("The archived message " + archivedId + " does not exist.");
    }

    public ObjectNode getArchiveMessage(JsonNode in, String region) {
        ObjectNode message = message(region, in.path("ArchivedMessageId").asText());
        ObjectNode out = mapper.createObjectNode();
        out.put("MessageDownloadLink", store.putFile(Base64.getDecoder().decode(message.path("_").path("raw")
                .asText()), "message/rfc822"));
        ObjectNode metadata = out.putObject("Metadata");
        metadata.put("Timestamp", message.path("ReceivedTimestamp").asDouble());
        metadata.put("IngressPointId", message.path("IngressPointId").asText());
        metadata.put("TrafficPolicyId", message.path("_").path("trafficPolicyId").asText());
        metadata.put("RuleSetId", message.path("_").path("ruleSetId").asText());
        metadata.put("SenderHostname", message.path("SenderHostname").asText());
        metadata.put("SenderIpAddress", message.path("SenderIpAddress").asText());
        metadata.put("SendingMethod", "SMTP");
        metadata.put("SourceArn", message.path("SourceArn").asText());
        out.set("Envelope", message.path("Envelope").deepCopy());
        return out;
    }

    public ObjectNode getArchiveMessageContent(JsonNode in, String region) {
        ObjectNode message = message(region, in.path("ArchivedMessageId").asText());
        MailManagerMime.Parsed parsed = MailManagerMime.parse(Base64.getDecoder().decode(message.path("_")
                .path("raw").asText()));
        ObjectNode out = mapper.createObjectNode();
        ObjectNode body = out.putObject("Body");
        if (parsed.text() != null) {
            body.put("Text", parsed.text());
        }
        if (parsed.html() != null) {
            body.put("Html", parsed.html());
        }
        body.put("MessageMalformed", parsed.malformed());
        return out;
    }

    static void copy(JsonNode from, ObjectNode to, String... fields) {
        MailManagerResources.copy(from, to, fields);
    }

    static Instant now() {
        return Instant.now();
    }
}
