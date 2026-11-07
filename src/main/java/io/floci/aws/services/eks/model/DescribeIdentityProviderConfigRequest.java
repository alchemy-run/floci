package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record DescribeIdentityProviderConfigRequest(
        IdentityProviderConfig identityProviderConfig
) {
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IdentityProviderConfig(
            String type,
            String name
    ) {}
}
