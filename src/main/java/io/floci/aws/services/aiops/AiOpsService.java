package io.floci.aws.services.aiops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.kms.KmsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Amazon CloudWatch investigations (the {@code aiops} API): an account's investigation group in a
 * Region, at most one, with the IAM role investigations run as, its retention, encryption, tag key
 * boundaries, chat notification channels, cross-account sources, resource policy and tags.
 * Requests are checked against the constraints vendored from botocore by
 * {@code tools/aws/regen_aiops_shapes.py}.
 */
@ApplicationScoped
public class AiOpsService implements Resettable {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int DEFAULT_RETENTION_DAYS = 90;
    private static final int MAX_TAG_KEY_BOUNDARIES = 50;
    private static final String SERVICE_PRINCIPAL = "aiops.amazonaws.com"; // partition-literal: the service principal name AWS uses in every partition

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/aiops-request-shapes.json", "AIOps request shapes", new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return validation(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return validation(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return validation(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return validation(path + " is required.");
                }
            });

    private final AccountAwareStorageBackend<ObjectNode> groups;
    private final Instance<IamService> iam;
    private final Instance<KmsService> kms;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public AiOpsService(StorageFactory storageFactory, Instance<IamService> iam, Instance<KmsService> kms,
                        RegionResolver regionResolver, ObjectMapper mapper) {
        this.groups = storageFactory.create("aiops", "aiops-investigation-groups.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.iam = iam;
        this.kms = kms;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    /** Checks a request against the model; {@code pathMembers} alternates member names and values. */
    static void check(String operation, JsonNode request, String... pathMembers) {
        ObjectNode checked = request != null && request.isObject() ? ((ObjectNode) request).deepCopy()
                : new ObjectMapper().createObjectNode();
        for (int i = 0; i + 1 < pathMembers.length; i += 2) {
            if (pathMembers[i + 1] != null) {
                checked.put(pathMembers[i], pathMembers[i + 1]);
            }
        }
        SHAPES.check(operation, checked);
    }

    public synchronized ObjectNode create(JsonNode in, String region) {
        check("CreateInvestigationGroup", in);
        String name = in.path("name").asText();
        if (groups.get(region).isPresent()) {
            throw new AwsException("ConflictException", "An investigation group already exists in this account and "
                    + "Region. Each account can have one investigation group per Region.", 409);
        }
        checkRole(in.path("roleArn").asText(), region);
        ObjectNode group = mapper.createObjectNode();
        String account = regionResolver.getAccountId();
        String id = randomId();
        long now = Instant.now().toEpochMilli();
        group.put("createdBy", account);
        group.put("createdAt", now);
        group.put("lastModifiedBy", account);
        group.put("lastModifiedAt", now);
        group.put("name", name);
        group.put("arn", AwsArnUtils.Arn.of("aiops", region, account, "investigation-group/" + id).toString());
        group.put("roleArn", in.path("roleArn").asText());
        group.set("encryptionConfiguration", encryption(in.path("encryptionConfiguration"), region));
        group.put("retentionInDays", in.path("retentionInDays").asLong(DEFAULT_RETENTION_DAYS));
        applyMutable(group, in, region);
        if (!group.has("isCloudTrailEventHistoryEnabled")) {
            group.put("isCloudTrailEventHistoryEnabled", false);
        }
        ObjectNode tags = group.putObject("tags");
        in.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        groups.put(region, group);
        return mapper.createObjectNode().put("arn", group.path("arn").asText());
    }

    private void applyMutable(ObjectNode group, JsonNode in, String region) {
        if (in.has("tagKeyBoundaries")) {
            if (in.get("tagKeyBoundaries").size() > MAX_TAG_KEY_BOUNDARIES) {
                throw validation("An investigation group can have at most 50 tag key boundaries.");
            }
            group.set("tagKeyBoundaries", in.get("tagKeyBoundaries").deepCopy());
        }
        if (in.has("chatbotNotificationChannel")) {
            group.set("chatbotNotificationChannel", in.get("chatbotNotificationChannel").deepCopy());
        }
        if (in.has("isCloudTrailEventHistoryEnabled")) {
            group.put("isCloudTrailEventHistoryEnabled", in.get("isCloudTrailEventHistoryEnabled").asBoolean());
        }
        if (in.has("crossAccountConfigurations")) {
            for (JsonNode source : in.get("crossAccountConfigurations")) {
                if (!AwsArnUtils.isArnFor(source.path("sourceRoleArn").asText(""), "iam")) {
                    throw validation("crossAccountConfigurations.sourceRoleArn must be an IAM role ARN.");
                }
            }
            group.set("crossAccountConfigurations", in.get("crossAccountConfigurations").deepCopy());
        }
    }

    /** The role must exist and trust the AIOps service principal, as AWS checks when it is set. */
    private void checkRole(String roleArn, String region) {
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !roleArn.contains(":role/")) {
            throw validation("roleArn must be the ARN of an IAM role.");
        }
        if (!iam.isResolvable()) {
            return;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        Optional<IamRole> role = iam.get().findRole(arn.accountId(), name);
        if (role.isEmpty()) {
            throw validation("The role " + roleArn + " does not exist or cannot be assumed by " + SERVICE_PRINCIPAL
                    + ".");
        }
        String trust = role.get().getAssumeRolePolicyDocument();
        if (trust != null && trust.contains("%")) {
            trust = URLDecoder.decode(trust, StandardCharsets.UTF_8);
        }
        if (trust == null || !trust.contains(SERVICE_PRINCIPAL)) {
            throw validation("The role " + roleArn + " does not trust " + SERVICE_PRINCIPAL
                    + ", so investigations cannot assume it.");
        }
    }

    private ObjectNode encryption(JsonNode requested, String region) {
        ObjectNode out = mapper.createObjectNode();
        String type = requested.path("type").asText("AWS_OWNED_KEY");
        out.put("type", type);
        if ("CUSTOMER_MANAGED_KMS_KEY".equals(type)) {
            String key = requested.path("kmsKeyId").asText("");
            if (key.isEmpty()) {
                throw validation("A CUSTOMER_MANAGED_KMS_KEY encryption configuration needs a kmsKeyId.");
            }
            if (kms.isResolvable() && kms.get().findKeyArn(key, region).isEmpty()) {
                throw new AwsException("ResourceNotFoundException", "The KMS key " + key + " does not exist.", 404);
            }
            out.put("kmsKeyId", key);
        } else if (requested.hasNonNull("kmsKeyId")) {
            throw validation("kmsKeyId applies only to a CUSTOMER_MANAGED_KMS_KEY encryption configuration.");
        }
        return out;
    }

    public ObjectNode get(String identifier, String region) {
        check("GetInvestigationGroup", null, "identifier", identifier);
        ObjectNode copy = require(identifier, region).deepCopy();
        copy.remove(List.of("tags", "policy"));
        return copy;
    }

    public synchronized void update(String identifier, JsonNode in, String region) {
        check("UpdateInvestigationGroup", in, "identifier", identifier);
        ObjectNode group = require(identifier, region);
        if (in.hasNonNull("roleArn")) {
            checkRole(in.get("roleArn").asText(), region);
            group.put("roleArn", in.get("roleArn").asText());
        }
        if (in.has("encryptionConfiguration")) {
            group.set("encryptionConfiguration", encryption(in.get("encryptionConfiguration"), region));
        }
        applyMutable(group, in, region);
        group.put("lastModifiedBy", regionResolver.getAccountId());
        group.put("lastModifiedAt", Instant.now().toEpochMilli());
        groups.put(region, group);
    }

    public synchronized void delete(String identifier, String region) {
        check("DeleteInvestigationGroup", null, "identifier", identifier);
        require(identifier, region);
        groups.delete(region);
    }

    public ObjectNode list(Integer maxResults, String nextToken, String region) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("investigationGroups");
        if (nextToken != null && !nextToken.isEmpty()) {
            try {
                Base64.getDecoder().decode(nextToken);
            } catch (IllegalArgumentException e) {
                throw validation("The nextToken is not valid.");
            }
            return out;
        }
        groups.get(region).ifPresent(group -> list.addObject().put("arn", group.path("arn").asText())
                .put("name", group.path("name").asText()));
        return out;
    }

    public synchronized ObjectNode putPolicy(String identifier, JsonNode in, String region) {
        check("PutInvestigationGroupPolicy", in, "identifier", identifier);
        ObjectNode group = require(identifier, region);
        String policy = in.path("policy").asText("");
        try {
            JsonNode document = mapper.readTree(policy);
            if (document == null || !document.isObject() || !document.has("Statement")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw validation("The policy is not a valid JSON policy document.");
        }
        group.put("policy", policy);
        groups.put(region, group);
        return mapper.createObjectNode().put("investigationGroupArn", group.path("arn").asText());
    }

    public ObjectNode getPolicy(String identifier, String region) {
        check("GetInvestigationGroupPolicy", null, "identifier", identifier);
        ObjectNode group = require(identifier, region);
        if (!group.hasNonNull("policy")) {
            throw new AwsException("ResourceNotFoundException", "The investigation group has no resource policy.",
                    404);
        }
        return mapper.createObjectNode().put("investigationGroupArn", group.path("arn").asText())
                .put("policy", group.path("policy").asText());
    }

    public synchronized void deletePolicy(String identifier, String region) {
        check("DeleteInvestigationGroupPolicy", null, "identifier", identifier);
        ObjectNode group = require(identifier, region);
        if (!group.hasNonNull("policy")) {
            throw new AwsException("ResourceNotFoundException", "The investigation group has no resource policy.",
                    404);
        }
        group.remove("policy");
        groups.put(region, group);
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    public Map<String, String> listTags(String arn, String region) {
        Map<String, String> out = new LinkedHashMap<>();
        requireByArn(arn, region).path("tags").fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()
                .asText()));
        return out;
    }

    public synchronized void tag(String arn, Map<String, String> tags, String region) {
        ObjectNode group = requireByArn(arn, region);
        ObjectNode current = group.withObject("tags");
        tags.forEach(current::put);
        if (current.size() > 200) {
            throw validation("A resource can have at most 200 tags.");
        }
        groups.put(region, group);
    }

    public synchronized void untag(String arn, List<String> keys, String region) {
        ObjectNode group = requireByArn(arn, region);
        keys.forEach(group.withObject("tags")::remove);
        groups.put(region, group);
    }

    private ObjectNode requireByArn(String arn, String region) {
        ObjectNode group = groups.get(region).orElse(null);
        if (group == null || !group.path("arn").asText().equals(arn)) {
            throw new AwsException("ResourceNotFoundException", "The resource " + arn + " does not exist.", 404);
        }
        return group;
    }

    /** The Region's group, by its name or ARN. */
    private ObjectNode require(String identifier, String region) {
        ObjectNode group = groups.get(region).orElse(null);
        if (group == null || !(identifier.equals(group.path("name").asText())
                || identifier.equals(group.path("arn").asText())
                || identifier.equals(group.path("arn").asText().substring(group.path("arn").asText()
                        .lastIndexOf('/') + 1)))) {
            throw new AwsException("ResourceNotFoundException", "The investigation group " + identifier
                    + " does not exist.", 404);
        }
        return group;
    }

    private String randomId() {
        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    @Override
    public void clear() {
        groups.clear();
    }
}
