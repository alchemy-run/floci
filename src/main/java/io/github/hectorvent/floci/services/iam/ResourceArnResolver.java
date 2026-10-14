package io.github.hectorvent.floci.services.iam;

import java.util.Optional;

/**
 * Maps the resource ARN derived from a request onto the stored resource's real ARN, for
 * services whose requests may name a resource by a shorter identifier than its ARN (a Secrets
 * Manager secret named without its random suffix, for example). AWS evaluates identity and
 * resource policies against the real ARN, so a policy scoped to it must match either form.
 *
 * <p>Implemented by resource-owning services and consumed lazily by {@link ResourceArnBuilder}
 * via {@code Instance<ResourceArnResolver>}, so IAM never depends on those services directly.
 */
public interface ResourceArnResolver {

    /**
     * Returns the real ARN of the resource {@code requestArn} names, or empty when this resolver
     * does not own {@code credentialScope} or the resource does not exist.
     */
    Optional<String> resolve(String credentialScope, String requestArn, String region);
}
