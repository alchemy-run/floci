package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Account-level Virtual Deliverability Manager (VDM) settings, returned by GetAccount and set by
 * PutAccountVdmAttributes. Each flag maps to the AWS {@code FeatureStatus} enum (ENABLED / DISABLED).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountVdmAttributes(boolean vdmEnabled,
                                   boolean engagementMetrics,
                                   boolean optimizedSharedDelivery) {

    @JsonCreator
    public static AccountVdmAttributes fromJson(
            @JsonProperty("vdmEnabled") @JsonAlias("VdmEnabled") JsonNode enabled,
            @JsonProperty("engagementMetrics") @JsonAlias("EngagementMetrics") JsonNode metrics,
            @JsonProperty("optimizedSharedDelivery") @JsonAlias("OptimizedSharedDelivery") JsonNode delivery) {
        return new AccountVdmAttributes(enabled(enabled), enabled(metrics), enabled(delivery));
    }

    private static boolean enabled(JsonNode value) {
        return value != null && (value.asBoolean(false) || "ENABLED".equals(value.asText()));
    }
}
