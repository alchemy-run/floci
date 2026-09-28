package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/**
 * A SES v2 tenant (multi-tenancy). Created by {@code CreateTenant} and returned by
 * {@code GetTenant}/{@code ListTenants}. {@code sendingStatus} is the AWS {@code SendingStatus} enum
 * (ENABLED / REINSTATED / DISABLED); a freshly created tenant is ENABLED.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record Tenant(String tenantName,
                     String tenantId,
                     String tenantArn,
                     Instant createdTimestamp,
                     List<Tag> tags,
                     String sendingStatus,
                     TenantSuppressionAttributes suppressionAttributes) {

    @JsonCreator
    public static Tenant fromJson(
            @JsonProperty("tenantName") @JsonAlias("TenantName") String name,
            @JsonProperty("tenantId") @JsonAlias("TenantId") String id,
            @JsonProperty("tenantArn") @JsonAlias("TenantArn") String arn,
            @JsonProperty("createdTimestamp") @JsonAlias("CreatedTimestamp") Instant created,
            @JsonProperty("tags") @JsonAlias("Tags") List<Tag> tags,
            @JsonProperty("sendingStatus") @JsonAlias("SendingStatus") String sending,
            @JsonProperty("suppressionAttributes") TenantSuppressionAttributes suppression,
            @JsonProperty("SuppressedReasons") List<String> legacyReasons,
            @JsonProperty("SuppressionScope") String legacyScope) {
        TenantSuppressionAttributes attributes = suppression;
        if (attributes == null && legacyReasons != null && legacyScope != null) {
            attributes = new TenantSuppressionAttributes(legacyReasons, legacyScope);
        }
        return new Tenant(name, id, arn, created, tags == null ? List.of() : tags,
                sending == null ? "ENABLED" : sending, attributes);
    }

    /** Copy with different tags (records are immutable; the store is replace-only). */
    public Tenant withTags(List<Tag> newTags) {
        return new Tenant(tenantName, tenantId, tenantArn, createdTimestamp, newTags, sendingStatus,
                suppressionAttributes);
    }

    /** Copy with different suppression attributes (records are immutable; the store is replace-only). */
    public Tenant withSuppressionAttributes(TenantSuppressionAttributes attrs) {
        return new Tenant(tenantName, tenantId, tenantArn, createdTimestamp, tags, sendingStatus, attrs);
    }
}
