package io.github.hectorvent.floci.services.signer.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AWS Signer signing profile (restJson1). */
@RegisterForReflection
public class SigningProfile {
    public String accountId;
    public String region;
    public String profileName;
    public String profileVersion;
    public String arn;
    public String profileVersionArn;
    public String platformId;
    public String platformDisplayName;
    public String status;
    public String statusReason;
    public Integer signatureValidityValue;
    public String signatureValidityType;
    public String certificateArn;
    public JsonNode overrides;
    public Map<String, String> signingParameters = new LinkedHashMap<>();
    public Map<String, String> tags = new LinkedHashMap<>();
    public long createdAt;
    public String revocationReason;
    public Long revokedAt;
    public String revokedBy;
    public Long revocationEffectiveFrom;
    public String revisionId;
    public List<ProfilePermission> permissions = new ArrayList<>();

    public SigningProfile() {
    }
}
