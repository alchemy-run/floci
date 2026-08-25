package io.github.hectorvent.floci.services.aiops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aiops.model.InvestigationGroup;
import io.github.hectorvent.floci.services.aiops.model.InvestigationGroup.CrossAccountConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CloudWatch investigations (AIOps) investigation-group lifecycle.
 *
 * <p>AWS allows at most one investigation group per account per Region. Identifier arguments
 * accept either the group name or its ARN.
 */
@ApplicationScoped
public class AiOpsService implements TagHandler {

    private static final int DEFAULT_RETENTION_DAYS = 90;
    private static final int MIN_RETENTION_DAYS = 7;
    private static final int MAX_RETENTION_DAYS = 90;
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 100;
    private static final String TOKEN_PREFIX = "aiops:v1:";
    private static final String ENCRYPTION_AWS_OWNED = "AWS_OWNED_KEY";
    private static final String ENCRYPTION_CUSTOMER = "CUSTOMER_MANAGED_KMS_KEY";
    private static final Set<String> ENCRYPTION_TYPES = Set.of(ENCRYPTION_AWS_OWNED, ENCRYPTION_CUSTOMER);
    private static final Pattern CREATE_NAME_PATTERN =
            Pattern.compile("[\\-_A-Za-z0-9\\[\\]\\(\\)\\{\\}\\.: ]+");
    private static final Pattern IDENTIFIER_NAME_PATTERN = Pattern.compile("[\\-_A-Za-z0-9]{1,512}");
    private static final Pattern ARN_PATTERN = Pattern.compile("arn:.*");

    private final StorageBackend<String, InvestigationGroup> store;
    private final RegionResolver regionResolver;

    @Inject
    public AiOpsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(
                "aiops",
                "aiops-investigation-groups.json",
                new TypeReference<Map<String, InvestigationGroup>>() {
                }), regionResolver);
    }

    AiOpsService(StorageBackend<String, InvestigationGroup> store) {
        this(store, new RegionResolver("us-east-1", "000000000000"));
    }

    AiOpsService(StorageBackend<String, InvestigationGroup> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    public InvestigationGroup create(String region, JsonNode request) {
        return createInvestigationGroup(region, regionResolver.getAccountId(), request);
    }

    public synchronized InvestigationGroup createInvestigationGroup(
            String region, String account, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateCreateName(name);
        String roleArn = requireText(request, "roleArn");
        validateRoleArn(roleArn);

        if (store.get(region).isPresent()) {
            throw new AwsException(
                    "ConflictException",
                    "An investigation group already exists in this Region.",
                    409);
        }

        long now = System.currentTimeMillis();
        String principal = "arn:aws:iam::" + account + ":root";
        String id = newGroupId();
        InvestigationGroup group = new InvestigationGroup();
        group.setId(id);
        group.setName(name);
        group.setArn(AwsArnUtils.Arn.of("aiops", region, account, "investigation-group/" + id).toString());
        group.setRoleArn(roleArn);
        group.setCreatedAt(now);
        group.setLastModifiedAt(now);
        group.setCreatedBy(principal);
        group.setLastModifiedBy(principal);
        group.setRetentionInDays(readRetention(request));
        applyEncryption(group, optionalObject(request, "encryptionConfiguration"), true);
        group.setTagKeyBoundaries(readStringArray(request, "tagKeyBoundaries"));
        group.setChatbotNotificationChannel(readChatbotChannel(request));
        group.setCloudTrailEventHistoryEnabled(request.has("isCloudTrailEventHistoryEnabled")
                ? requireBoolean(request, "isCloudTrailEventHistoryEnabled")
                : true);
        group.setCrossAccountConfigurations(readCrossAccount(request));
        Map<String, String> tags = readTags(request.get("tags"));
        group.setTags(tags == null ? new LinkedHashMap<>() : tags);
        store.put(region, group);
        return group;
    }

    public InvestigationGroup get(String region, String identifier) {
        return getInvestigationGroup(region, identifier);
    }

    public InvestigationGroup getInvestigationGroup(String region, String identifier) {
        return requireGroup(region, identifier);
    }

    public synchronized InvestigationGroup update(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        InvestigationGroup group = requireGroup(region, identifier);
        boolean changed = false;
        if (request.has("roleArn")) {
            String roleArn = requireText(request, "roleArn");
            validateRoleArn(roleArn);
            group.setRoleArn(roleArn);
            changed = true;
        }
        if (request.has("encryptionConfiguration")) {
            applyEncryption(group, optionalObject(request, "encryptionConfiguration"), false);
            changed = true;
        }
        if (request.has("tagKeyBoundaries")) {
            group.setTagKeyBoundaries(readStringArray(request, "tagKeyBoundaries"));
            changed = true;
        }
        if (request.has("chatbotNotificationChannel")) {
            group.setChatbotNotificationChannel(readChatbotChannel(request));
            changed = true;
        }
        if (request.has("isCloudTrailEventHistoryEnabled")) {
            group.setCloudTrailEventHistoryEnabled(requireBoolean(request, "isCloudTrailEventHistoryEnabled"));
            changed = true;
        }
        if (request.has("crossAccountConfigurations")) {
            group.setCrossAccountConfigurations(readCrossAccount(request));
            changed = true;
        }
        if (changed) {
            group.setLastModifiedAt(System.currentTimeMillis());
            group.setLastModifiedBy(callerPrincipal());
            store.put(region, group);
        }
        return group;
    }

    public synchronized void delete(String region, String identifier) {
        requireGroup(region, identifier);
        store.delete(region);
    }

    public Page list(String region, String maxResultsValue, String nextToken) {
        int maxResults = parseMaxResults(maxResultsValue);
        List<InvestigationGroup> groups = new ArrayList<>();
        store.get(region).ifPresent(groups::add);
        groups.sort(Comparator.comparing(InvestigationGroup::getName, Comparator.nullsLast(String::compareTo)));
        int offset = decodeOffset(nextToken, groups.size());
        int end = Math.min(offset + maxResults, groups.size());
        String responseToken = end < groups.size() ? encodeOffset(end) : null;
        return new Page(groups.subList(offset, end), responseToken);
    }

    public String getPolicy(String region, String identifier) {
        InvestigationGroup group = requireGroup(region, identifier);
        if (group.getPolicy() == null || group.getPolicy().isBlank()) {
            throw policyNotFound(group.getArn());
        }
        return group.getPolicy();
    }

    public synchronized InvestigationGroup putPolicy(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        String policy = requireText(request, "policy");
        InvestigationGroup group = requireGroup(region, identifier);
        group.setPolicy(policy);
        group.setLastModifiedAt(System.currentTimeMillis());
        group.setLastModifiedBy(callerPrincipal());
        store.put(region, group);
        return group;
    }

    public synchronized void deletePolicy(String region, String identifier) {
        InvestigationGroup group = requireGroup(region, identifier);
        if (group.getPolicy() == null || group.getPolicy().isBlank()) {
            throw policyNotFound(group.getArn());
        }
        group.setPolicy(null);
        group.setLastModifiedAt(System.currentTimeMillis());
        group.setLastModifiedBy(callerPrincipal());
        store.put(region, group);
    }

    @Override
    public String serviceKey() {
        return "aiops";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        InvestigationGroup group = requireGroup(region, arn);
        return group.getTags() == null ? Map.of() : Map.copyOf(group.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        InvestigationGroup group = requireGroup(region, arn);
        Map<String, String> current = group.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(group.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        group.setTags(current);
        group.setLastModifiedAt(System.currentTimeMillis());
        group.setLastModifiedBy(callerPrincipal());
        store.put(region, group);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        InvestigationGroup group = requireGroup(region, arn);
        if (group.getTags() != null && tagKeys != null) {
            tagKeys.forEach(group.getTags()::remove);
        }
        group.setLastModifiedAt(System.currentTimeMillis());
        group.setLastModifiedBy(callerPrincipal());
        store.put(region, group);
    }

    private InvestigationGroup requireGroup(String region, String identifier) {
        String decoded = decode(identifier);
        InvestigationGroup group = store.get(region).orElseThrow(() -> resourceNotFound(decoded));
        if (decoded.startsWith("arn:")) {
            if (!decoded.equals(group.getArn())) {
                throw resourceNotFound(decoded);
            }
            return group;
        }
        if (!IDENTIFIER_NAME_PATTERN.matcher(decoded).matches()) {
            throw validation("identifier is invalid.");
        }
        if (!decoded.equals(group.getName())) {
            throw resourceNotFound(decoded);
        }
        return group;
    }

    private String callerPrincipal() {
        return "arn:aws:iam::" + regionResolver.getAccountId() + ":root";
    }

    private static String newGroupId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static void validateCreateName(String name) {
        if (name.length() < 1 || name.length() > 512 || !CREATE_NAME_PATTERN.matcher(name).matches()) {
            throw validation("name must match [\\-_A-Za-z0-9\\[\\]\\(\\)\\{\\}\\.: ]+ and contain at most 512 characters.");
        }
    }

    private static void validateRoleArn(String roleArn) {
        if (roleArn.length() < 20 || roleArn.length() > 2048 || !ARN_PATTERN.matcher(roleArn).matches()) {
            throw validation("roleArn is invalid.");
        }
    }

    private static int readRetention(JsonNode request) {
        if (!request.has("retentionInDays")) {
            return DEFAULT_RETENTION_DAYS;
        }
        JsonNode value = request.get("retentionInDays");
        if (value == null || !value.isNumber()) {
            throw validation("retentionInDays must be a number.");
        }
        int days = value.intValue();
        if (days < MIN_RETENTION_DAYS || days > MAX_RETENTION_DAYS) {
            throw validation("retentionInDays must be between 7 and 90.");
        }
        return days;
    }

    private static void applyEncryption(InvestigationGroup group, JsonNode configuration, boolean creating) {
        if (configuration == null) {
            if (creating) {
                group.setEncryptionType(ENCRYPTION_AWS_OWNED);
                group.setKmsKeyId(null);
            }
            return;
        }
        String type = configuration.has("type") ? requireText(configuration, "type") : ENCRYPTION_AWS_OWNED;
        if (!ENCRYPTION_TYPES.contains(type)) {
            throw validation("encryptionConfiguration.type must be AWS_OWNED_KEY or CUSTOMER_MANAGED_KMS_KEY.");
        }
        String kmsKeyId = configuration.has("kmsKeyId") ? requireText(configuration, "kmsKeyId") : null;
        if (ENCRYPTION_CUSTOMER.equals(type) && (kmsKeyId == null || kmsKeyId.isBlank())) {
            throw validation("encryptionConfiguration.kmsKeyId is required when type is CUSTOMER_MANAGED_KMS_KEY.");
        }
        group.setEncryptionType(type);
        group.setKmsKeyId(ENCRYPTION_AWS_OWNED.equals(type) ? null : kmsKeyId);
    }

    private static List<String> readStringArray(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode array = parent.get(field);
        if (!array.isArray()) {
            throw validation(field + " must be an array.");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                throw validation(field + " members must be strings.");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static Map<String, List<String>> readChatbotChannel(JsonNode request) {
        if (!request.has("chatbotNotificationChannel") || request.get("chatbotNotificationChannel").isNull()) {
            return null;
        }
        JsonNode node = request.get("chatbotNotificationChannel");
        if (!node.isObject()) {
            throw validation("chatbotNotificationChannel must be an object.");
        }
        Map<String, List<String>> channel = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || !value.isArray()) {
                throw validation("chatbotNotificationChannel values must be arrays of ARNs.");
            }
            List<String> arns = new ArrayList<>(value.size());
            for (JsonNode arn : value) {
                if (!arn.isTextual()) {
                    throw validation("chatbotNotificationChannel values must be arrays of ARNs.");
                }
                arns.add(arn.textValue());
            }
            channel.put(entry.getKey(), arns);
        });
        return channel;
    }

    private static List<CrossAccountConfiguration> readCrossAccount(JsonNode request) {
        if (!request.has("crossAccountConfigurations") || request.get("crossAccountConfigurations").isNull()) {
            return null;
        }
        JsonNode array = request.get("crossAccountConfigurations");
        if (!array.isArray()) {
            throw validation("crossAccountConfigurations must be an array.");
        }
        List<CrossAccountConfiguration> configurations = new ArrayList<>(array.size());
        for (JsonNode entry : array) {
            requireObject(entry, "crossAccountConfigurations members");
            String sourceRoleArn = entry.has("sourceRoleArn") ? requireText(entry, "sourceRoleArn") : null;
            configurations.add(new CrossAccountConfiguration(sourceRoleArn));
        }
        return configurations;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return null;
        }
        if (!tagsNode.isObject() || tagsNode.size() > 50) {
            throw validation("tags must be an object with at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || !valueNode.isTextual()) {
                throw validation("tags contains an invalid key or value.");
            }
            tags.put(entry.getKey(), valueNode.textValue());
        });
        return tags;
    }

    private static JsonNode optionalObject(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        requireObject(value, field);
        return value;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static int parseMaxResults(String value) {
        if (value == null) {
            return DEFAULT_MAX_RESULTS;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > MAX_RESULTS) {
                throw validation("maxResults must be between 1 and 100.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw validation("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException resourceNotFound(String identifier) {
        return new AwsException(
                "ResourceNotFoundException",
                "Investigation group " + identifier + " does not exist.",
                404);
    }

    private static AwsException policyNotFound(String arn) {
        return new AwsException(
                "ResourceNotFoundException",
                "No resource policy is attached to investigation group " + arn + ".",
                404);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record Page(List<InvestigationGroup> groups, String nextToken) {
        public Page {
            groups = List.copyOf(groups);
        }
    }
}
