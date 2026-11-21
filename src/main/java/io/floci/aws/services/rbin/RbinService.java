package io.floci.aws.services.rbin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2RecycleBin;
import io.floci.aws.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recycle Bin retention rules, and the retention EC2 asks for when a snapshot, AMI or volume is deleted. A rule with
 * resource tags keeps the resources that carry any of them; a Region-level rule (no resource tags) keeps every
 * resource of its type except those with its exclusion tags. When several rules match, the longest retention wins.
 * Only Region-level rules lock; a locked rule can't be changed or deleted, and unlocking takes its unlock delay.
 */
@ApplicationScoped
public class RbinService implements Ec2RecycleBin, Resettable {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountAwareStorageBackend<ObjectNode> rules;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;

    @Inject
    public RbinService(StorageFactory storageFactory, ObjectMapper mapper, EmulatorConfig config) {
        this.rules = storageFactory.create("rbin", "rules.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.config = config;
    }

    @Override
    public void clear() {
        rules.clear();
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String id) {
        return new AwsException("ResourceNotFoundException", "The rule " + id + " doesn't exist.", 404,
                Map.of("Reason", "RULE_NOT_FOUND", "ResourceType", "RULE"));
    }

    private static AwsException conflict(String message, String reason) {
        return new AwsException("ConflictException", message, 409, Map.of("Reason", reason));
    }

    private static String key(String region, String id) {
        return region + "/" + id;
    }

    /** A rule, with its pending status or unlock delay settled. */
    private ObjectNode rule(String region, String id) {
        ObjectNode rule = rules.get(key(region, id)).map(ObjectNode::deepCopy).orElseThrow(() -> notFound(id));
        return settle(region, rule);
    }

    private ObjectNode settle(String region, ObjectNode rule) {
        boolean changed = false;
        ObjectNode meta = (ObjectNode) rule.get("_");
        if ("pending".equals(rule.path("Status").asText()) && now() >= meta.path("availableAt").asDouble()) {
            rule.put("Status", "available");
            changed = true;
        }
        if ("pending_unlock".equals(rule.path("LockState").asText()) && now() >= rule.path("LockEndTime")
                .asDouble()) {
            rule.put("LockState", "unlocked");
            rule.remove("LockEndTime");
            changed = true;
        }
        if (changed) {
            rules.put(key(region, rule.get("Identifier").asText()), rule);
        }
        return rule;
    }

    private void pending(ObjectNode rule) {
        rule.put("Status", "pending");
        ((ObjectNode) rule.get("_")).put("availableAt", now() + config.services().rbin().transitionMillis() / 1000.0);
    }

    private static void checkRetention(JsonNode retention) {
        int days = retention.path("RetentionPeriodValue").asInt();
        if (days < 1 || days > 365) {
            throw validation("RetentionPeriodValue must be from 1 to 365 days.");
        }
    }

    private static void checkTags(JsonNode resourceTags, JsonNode excludeTags) {
        if (resourceTags.size() > 50 || excludeTags.size() > 5) {
            throw validation("A rule has at most 50 resource tags and 5 exclusion tags.");
        }
        if (!resourceTags.isEmpty() && !excludeTags.isEmpty()) {
            throw validation("Exclusion tags are for Region-level rules, which have no resource tags.");
        }
    }

    private ObjectNode view(ObjectNode rule, String... fields) {
        ObjectNode view = mapper.createObjectNode();
        for (String field : fields) {
            if (rule.has(field) && !rule.get(field).isNull()) {
                view.set(field, rule.get(field).deepCopy());
            }
        }
        return view;
    }

    private static final String[] RULE_FIELDS = {"Identifier", "Description", "ResourceType", "RetentionPeriod",
        "ResourceTags", "Status", "LockConfiguration", "LockState", "LockEndTime", "RuleArn", "ExcludeResourceTags"};

    // ================================================================ operations

    public ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "CreateRule" -> createRule(in, region);
            case "GetRule" -> view(rule(region, in.path("Identifier").asText()), RULE_FIELDS);
            case "UpdateRule" -> updateRule(in, region);
            case "DeleteRule" -> deleteRule(in, region);
            case "ListRules" -> listRules(in, region);
            case "LockRule" -> lockRule(in, region);
            case "UnlockRule" -> unlockRule(in, region);
            case "TagResource" -> tag(in, region, true);
            case "UntagResource" -> tag(in, region, false);
            case "ListTagsForResource" -> listTags(in, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    private ObjectNode createRule(JsonNode in, String region) {
        checkRetention(in.path("RetentionPeriod"));
        JsonNode resourceTags = in.path("ResourceTags");
        JsonNode excludeTags = in.path("ExcludeResourceTags");
        checkTags(resourceTags, excludeTags);
        if (in.has("LockConfiguration") && !resourceTags.isEmpty()) {
            throw validation("Only Region-level rules can be locked.");
        }
        if (rules.scan(k -> k.startsWith(region + "/")).size() >= 250) {
            throw new AwsException("ServiceQuotaExceededException", "A Region has at most 250 rules.", 402);
        }
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        ObjectNode rule = mapper.createObjectNode();
        rule.putObject("_").putObject("tags");
        rule.put("Identifier", id.toString())
                .put("RuleArn", AwsArnUtils.Arn.of("rbin", region, rules.accountId(), "rule/" + id).toString())
                .put("ResourceType", in.path("ResourceType").asText());
        rule.set("RetentionPeriod", in.get("RetentionPeriod").deepCopy());
        if (in.hasNonNull("Description")) {
            rule.put("Description", in.get("Description").asText());
        }
        rule.set("ResourceTags", resourceTags.isArray() ? resourceTags.deepCopy() : mapper.createArrayNode());
        if (excludeTags.isArray() && !excludeTags.isEmpty()) {
            rule.set("ExcludeResourceTags", excludeTags.deepCopy());
        }
        if (in.has("LockConfiguration")) {
            checkDelay(in.get("LockConfiguration"));
            rule.set("LockConfiguration", in.get("LockConfiguration").deepCopy());
            rule.put("LockState", "locked");
        } else {
            rule.put("LockState", "unlocked");
        }
        pending(rule);
        addTags(rule, in.path("Tags"));
        rules.put(key(region, id.toString()), rule);
        ObjectNode out = view(rule, "Identifier", "RetentionPeriod", "Description", "ResourceType", "ResourceTags",
                "Status", "LockConfiguration", "LockState", "RuleArn", "ExcludeResourceTags");
        out.set("Tags", tagList(rule));
        return out;
    }

    private static void checkDelay(JsonNode lock) {
        int days = lock.at("/UnlockDelay/UnlockDelayValue").asInt();
        if (days < 7 || days > 30) {
            throw validation("UnlockDelayValue must be from 7 to 30 days.");
        }
    }

    private void unlockedOrFail(ObjectNode rule, String action) {
        String state = rule.path("LockState").asText("unlocked");
        if (!"unlocked".equals(state)) {
            throw conflict("The rule is " + state + "; unlock it before you " + action + " it.",
                    "INVALID_RULE_STATE");
        }
    }

    private ObjectNode updateRule(JsonNode in, String region) {
        ObjectNode rule = rule(region, in.path("Identifier").asText());
        unlockedOrFail(rule, "update");
        if (in.has("RetentionPeriod")) {
            checkRetention(in.get("RetentionPeriod"));
            rule.set("RetentionPeriod", in.get("RetentionPeriod").deepCopy());
        }
        if (in.has("Description")) {
            rule.put("Description", in.get("Description").asText());
        }
        if (in.hasNonNull("ResourceType")) {
            rule.put("ResourceType", in.get("ResourceType").asText());
        }
        if (in.has("ResourceTags")) {
            rule.set("ResourceTags", in.get("ResourceTags").deepCopy());
        }
        if (in.has("ExcludeResourceTags")) {
            rule.set("ExcludeResourceTags", in.get("ExcludeResourceTags").deepCopy());
        }
        checkTags(rule.path("ResourceTags"), rule.path("ExcludeResourceTags"));
        pending(rule);
        rules.put(key(region, rule.get("Identifier").asText()), rule);
        return view(rule, "Identifier", "RetentionPeriod", "Description", "ResourceType", "ResourceTags", "Status",
                "LockState", "LockEndTime", "RuleArn", "ExcludeResourceTags");
    }

    private ObjectNode deleteRule(JsonNode in, String region) {
        ObjectNode rule = rule(region, in.path("Identifier").asText());
        unlockedOrFail(rule, "delete");
        rules.delete(key(region, rule.get("Identifier").asText()));
        return mapper.createObjectNode();
    }

    private ObjectNode lockRule(JsonNode in, String region) {
        ObjectNode rule = rule(region, in.path("Identifier").asText());
        if (!rule.path("ResourceTags").isEmpty()) {
            throw validation("Only Region-level rules can be locked.");
        }
        if ("locked".equals(rule.path("LockState").asText())) {
            throw conflict("The rule is already locked.", "INVALID_RULE_STATE");
        }
        checkDelay(in.path("LockConfiguration"));
        rule.set("LockConfiguration", in.get("LockConfiguration").deepCopy());
        rule.put("LockState", "locked");
        rule.remove("LockEndTime");
        rules.put(key(region, rule.get("Identifier").asText()), rule);
        return view(rule, "Identifier", "Description", "ResourceType", "RetentionPeriod", "ResourceTags", "Status",
                "LockConfiguration", "LockState", "RuleArn", "ExcludeResourceTags");
    }

    private ObjectNode unlockRule(JsonNode in, String region) {
        ObjectNode rule = rule(region, in.path("Identifier").asText());
        if (!"locked".equals(rule.path("LockState").asText())) {
            throw conflict("The rule isn't locked.", "INVALID_RULE_STATE");
        }
        int days = rule.at("/LockConfiguration/UnlockDelay/UnlockDelayValue").asInt(7);
        rule.put("LockState", "pending_unlock").put("LockEndTime", now() + days * 86400.0);
        rules.put(key(region, rule.get("Identifier").asText()), rule);
        return view(rule, RULE_FIELDS);
    }

    private static boolean sameTags(JsonNode a, JsonNode b) {
        Set<String> left = new HashSet<>();
        Set<String> right = new HashSet<>();
        a.forEach(t -> left.add(t.path("ResourceTagKey").asText() + "=" + t.path("ResourceTagValue").asText()));
        b.forEach(t -> right.add(t.path("ResourceTagKey").asText() + "=" + t.path("ResourceTagValue").asText()));
        return left.equals(right);
    }

    private ObjectNode listRules(JsonNode in, String region) {
        String type = in.path("ResourceType").asText();
        List<ObjectNode> found = new ArrayList<>();
        for (ObjectNode stored : rules.scan(k -> k.startsWith(region + "/"))) {
            ObjectNode rule = settle(region, stored.deepCopy());
            if (!type.equals(rule.path("ResourceType").asText())) {
                continue;
            }
            if (in.path("ResourceTags").isArray() && !in.get("ResourceTags").isEmpty()
                    && !sameTags(in.get("ResourceTags"), rule.path("ResourceTags"))) {
                continue;
            }
            if (in.path("ExcludeResourceTags").isArray() && !in.get("ExcludeResourceTags").isEmpty()
                    && !sameTags(in.get("ExcludeResourceTags"), rule.path("ExcludeResourceTags"))) {
                continue;
            }
            if (in.hasNonNull("LockState") && !in.get("LockState").asText().equals(rule.path("LockState").asText())) {
                continue;
            }
            found.add(rule);
        }
        found.sort(Comparator.comparing(rule -> rule.get("Identifier").asText()));
        int start = 0;
        if (in.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw validation("The NextToken is not valid.");
            }
        }
        int size = in.path("MaxResults").asInt(100);
        int end = Math.min(found.size(), start + size);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("Rules");
        for (ObjectNode rule : found.subList(Math.min(start, found.size()), end)) {
            list.add(view(rule, "Identifier", "Description", "RetentionPeriod", "LockState", "RuleArn"));
        }
        if (end < found.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding().encodeToString(String.valueOf(end)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    // ================================================================ tags

    private ObjectNode byArn(String encoded) {
        String arn = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        if (!AwsArnUtils.isArnFor(arn, "rbin")) {
            throw validation("The ARN " + arn + " isn't a Recycle Bin ARN.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String id = parsed.resource().substring(parsed.resource().indexOf('/') + 1);
        if (!parsed.accountId().equals(rules.accountId())) {
            throw notFound(id);
        }
        return rule(parsed.region(), id);
    }

    private static void addTags(ObjectNode rule, JsonNode tags) {
        ObjectNode map = (ObjectNode) rule.get("_").get("tags");
        for (JsonNode tag : tags) {
            String key = tag.path("Key").asText();
            if (key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
                throw validation("Tag keys can't start with aws:.");
            }
            map.put(key, tag.path("Value").asText(""));
        }
        if (map.size() > 50) {
            throw new AwsException("ServiceQuotaExceededException", "A rule has at most 50 tags.", 402);
        }
    }

    private ArrayNode tagList(ObjectNode rule) {
        ArrayNode list = mapper.createArrayNode();
        rule.get("_").get("tags").properties().forEach(tag -> list.addObject().put("Key", tag.getKey())
                .put("Value", tag.getValue().asText()));
        return list;
    }

    private ObjectNode tag(JsonNode in, String region, boolean add) {
        ObjectNode rule = byArn(in.path("ResourceArn").asText());
        if (add) {
            addTags(rule, in.path("Tags"));
        } else {
            in.path("TagKeys").forEach(key -> ((ObjectNode) rule.get("_").get("tags")).remove(key.asText()));
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(rule.get("RuleArn").asText());
        rules.put(key(parsed.region(), rule.get("Identifier").asText()), rule);
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagList(byArn(in.path("ResourceArn").asText())));
        return out;
    }

    // ================================================================ EC2

    private static boolean has(List<Tag> tags, JsonNode wanted) {
        for (Tag tag : tags) {
            if (tag.getKey().equals(wanted.path("ResourceTagKey").asText())
                    && (!wanted.hasNonNull("ResourceTagValue")
                    || wanted.get("ResourceTagValue").asText().equals(tag.getValue()))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<Duration> retention(String region, String resourceType, List<Tag> tags) {
        int best = 0;
        for (ObjectNode stored : rules.scan(k -> k.startsWith(region + "/"))) {
            ObjectNode rule = settle(region, stored.deepCopy());
            if (!resourceType.equals(rule.path("ResourceType").asText())
                    || !"available".equals(rule.path("Status").asText())) {
                continue;
            }
            boolean matches;
            if (rule.path("ResourceTags").isEmpty()) {
                matches = true;
                for (JsonNode excluded : rule.path("ExcludeResourceTags")) {
                    matches &= !has(tags, excluded);
                }
            } else {
                matches = false;
                for (JsonNode wanted : rule.path("ResourceTags")) {
                    matches |= has(tags, wanted);
                }
            }
            if (matches) {
                best = Math.max(best, rule.at("/RetentionPeriod/RetentionPeriodValue").asInt());
            }
        }
        return best == 0 ? Optional.empty() : Optional.of(Duration.ofDays(best));
    }
}
