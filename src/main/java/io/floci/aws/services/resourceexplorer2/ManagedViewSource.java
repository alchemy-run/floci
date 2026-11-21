package io.floci.aws.services.resourceexplorer2;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * A trusted service that owns Resource Explorer managed views, as CloudWatch's telemetry enrichment owns
 * {@code AWSManagedViewForCloudWatchTelemetry}. Each view is a {@code ManagedView} as GetManagedView answers it.
 */
public interface ManagedViewSource {

    /** The managed views the service holds in an account and Region. */
    List<ObjectNode> managedViews(String account, String region);
}
