package io.github.hectorvent.floci.services.securitylake;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Amazon Security Lake restJson1. Binding probes
 * ({@code ListDataLakeExceptions}, {@code GetDataLakeSources}) succeed with
 * empty collections and a default data-lake ARN so local stacks can monitor
 * a lake that has not yet onboarded sources.
 */
@ApplicationScoped
public class SecurityLakeService implements Resettable {

    static final String SERVICE = "securitylake";

    private final RegionResolver regionResolver;

    @Inject
    public SecurityLakeService(RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
    }

    public List<Object> listExceptions() {
        return List.of();
    }

    public List<Object> listSources() {
        return List.of();
    }

    public String dataLakeArn(String region) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), "data-lake/default")
                .toString();
    }

    static AwsException validation(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    @Override
    public void clear() {
        // Binding list operations are stateless.
    }
}
