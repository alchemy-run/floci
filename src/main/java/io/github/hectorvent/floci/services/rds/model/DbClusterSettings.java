package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The listener, network, protection and log-export settings CreateDBCluster and ModifyDBCluster
 * carry. A null member is one the request omitted: the AWS default on create, unchanged on modify.
 *
 * <p>Create names the whole log-type set once in {@code EnableCloudwatchLogsExports}; modify sends
 * deltas in {@code CloudwatchLogsExportConfiguration}, so exactly one of the two log members is set
 * by any one request.
 */
@RegisterForReflection
public record DbClusterSettings(Integer port,
                                List<String> vpcSecurityGroupIds,
                                List<String> enabledCloudwatchLogsExports,
                                LogExportChanges logExportChanges,
                                Boolean deletionProtection,
                                String networkType,
                                Integer backupRetentionPeriod) {

    public static DbClusterSettings unchanged() {
        return new DbClusterSettings(null, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return port == null && vpcSecurityGroupIds == null && enabledCloudwatchLogsExports == null
                && logExportChanges == null && deletionProtection == null && networkType == null
                && backupRetentionPeriod == null;
    }
}
