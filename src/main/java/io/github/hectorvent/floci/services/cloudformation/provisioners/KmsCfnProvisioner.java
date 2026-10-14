package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsAlias;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provisions {@code AWS::KMS::Key} and {@code AWS::KMS::Alias}. */
@ApplicationScoped
public class KmsCfnProvisioner implements CfnResourceProvisioner {

    private static final String KEY = "AWS::KMS::Key";
    private static final String ALIAS = "AWS::KMS::Alias";

    private final KmsService kmsService;

    public KmsCfnProvisioner(KmsService kmsService) {
        this.kmsService = kmsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(KEY, ALIAS);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case KEY -> provisionKey(r, props, ctx);
            case ALIAS -> provisionAlias(r, props, ctx);
            default -> throw new IllegalStateException(
                    "KmsCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        String description = ctx.resolveOptional(props, "Description");
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);
        KmsKey existing = ctx.isUpdate() ? liveKey(ctx.priorPhysicalId(), ctx.region()) : null;
        KmsKey key;
        if (existing != null) {
            // Description and Tags are mutable on AWS::KMS::Key, so an update converges the key it
            // already manages instead of minting a new one and orphaning every alias and grant.
            String desiredDescription = description != null ? description : "";
            String currentDescription = existing.getDescription() != null ? existing.getDescription() : "";
            if (!desiredDescription.equals(currentDescription)) {
                kmsService.updateKeyDescription(existing.getKeyId(), desiredDescription, ctx.region());
            }
            List<String> stale = ProvisionContext.staleTagKeys(existing.getTags(), tags);
            if (!stale.isEmpty()) {
                kmsService.untagResource(existing.getKeyId(), stale, ctx.region());
            }
            if (!tags.isEmpty()) {
                kmsService.tagResource(existing.getKeyId(), tags, ctx.region());
            }
            key = existing;
        } else {
            key = kmsService.createKey(description, null, tags, ctx.region());
        }
        r.setPhysicalId(key.getKeyId());
        r.getAttributes().put("Arn", key.getArn());
        r.getAttributes().put("KeyId", key.getKeyId());
    }

    private void provisionAlias(StackResource r, JsonNode props, ProvisionContext ctx) {
        String aliasName = ctx.resolveOptional(props, "AliasName");
        String targetKeyId = ctx.resolveOptional(props, "TargetKeyId");
        if (aliasName != null && targetKeyId != null) {
            KmsAlias existing = ctx.reusesPriorEntity(aliasName) ? liveAlias(aliasName, ctx.region()) : null;
            if (existing == null) {
                kmsService.createAlias(aliasName, targetKeyId, ctx.region());
            } else if (!kmsService.describeKey(targetKeyId, ctx.region()).getKeyId()
                    .equals(existing.getTargetKeyId())) {
                // TargetKeyId is the one mutable property; UpdateAlias is its update handler.
                kmsService.updateAlias(aliasName, targetKeyId, ctx.region());
            }
        }
        r.setPhysicalId(aliasName != null
                ? aliasName
                : "alias/cfn-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private KmsKey liveKey(String keyId, String region) {
        try {
            KmsKey key = kmsService.describeKey(keyId, region);
            return "PendingDeletion".equals(key.getKeyState()) ? null : key;
        } catch (AwsException e) {
            if ("NotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private KmsAlias liveAlias(String aliasName, String region) {
        return kmsService.listAliases(region).stream()
                .filter(alias -> aliasName.equals(alias.getAliasName()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // A KMS key cannot be deleted immediately, only scheduled, so a stack delete leaves it.
        if (ALIAS.equals(resourceType)) {
            kmsService.deleteAlias(physicalId, region);
        }
    }

    /**
     * Copied from {@code CloudFormationResourceProvisioner} rather than delegating to
     * {@link ProvisionContext#resolveTags}, which is not equivalent: it skips a blank key this
     * keeps and orders entries by insertion rather than hash. This copy dies when the monolith's
     * last caller migrates.
     */
    private Map<String, String> parseCfnTags(JsonNode tagsNode, ProvisionContext ctx) {
        tagsNode = ctx.engine().resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
