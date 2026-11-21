package io.floci.aws.services.glacier;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Supplies a Glacier vault's access policy to IAM policy evaluation, with the account that owns the vault, so a call
 * to another account's vault is judged as cross-account and a vault policy can grant it.
 */
@ApplicationScoped
public class GlacierResourcePolicyProvider implements ResourcePolicyProvider {

    private final GlacierService service;

    @Inject
    public GlacierResourcePolicyProvider(GlacierService service) {
        this.service = service;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "glacier")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        if (!arn.resource().startsWith("vaults/") || arn.resource().indexOf('/', "vaults/".length()) >= 0) {
            return List.of();
        }
        String vault = arn.resource().substring("vaults/".length());
        String policy = service.accessPolicy(arn.accountId(), arn.region(), vault).orElse(null);
        return List.of(new ResourcePolicy(policy, arn.accountId()));
    }
}
