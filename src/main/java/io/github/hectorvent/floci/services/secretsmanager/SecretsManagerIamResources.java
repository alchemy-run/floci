package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.ResourceArnResolver;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Supplies secrets to IAM policy evaluation: the real ARN of a secret a request names by name or
 * partial ARN, and the secret's resource policy.
 */
@ApplicationScoped
public class SecretsManagerIamResources implements ResourceArnResolver, ResourcePolicyProvider {

    private static final String SCOPE = "secretsmanager";

    private final SecretsManagerService secretsManagerService;

    @Inject
    public SecretsManagerIamResources(SecretsManagerService secretsManagerService) {
        this.secretsManagerService = secretsManagerService;
    }

    @Override
    public Optional<String> resolve(String credentialScope, String requestArn, String region) {
        if (!SCOPE.equals(credentialScope)) {
            return Optional.empty();
        }
        return find(requestArn, region).map(Secret::getArn);
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!SCOPE.equals(credentialScope)) {
            return List.of();
        }
        AwsArnUtils.Arn arn = parse(resourceArn);
        if (arn == null) {
            return List.of();
        }
        return find(resourceArn, arn.region())
                .map(secret -> List.of(new ResourcePolicy(secret.getResourcePolicy(), arn.accountId())))
                .orElseGet(List::of);
    }

    private Optional<Secret> find(String resourceArn, String region) {
        AwsArnUtils.Arn arn = parse(resourceArn);
        if (arn == null || !arn.resource().startsWith("secret:") || arn.resource().endsWith("*")) {
            return Optional.empty();
        }
        return secretsManagerService.findSecret(resourceArn, arn.region().isEmpty() ? region : arn.region());
    }

    private static AwsArnUtils.Arn parse(String resourceArn) {
        if (resourceArn == null || !AwsArnUtils.isArnFor(resourceArn, SCOPE)) {
            return null;
        }
        try {
            return AwsArnUtils.parse(resourceArn);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
