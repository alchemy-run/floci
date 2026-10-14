package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The RestoreDBInstanceToPointInTime request as the service consumes it. A null member is one the
 * request left out: the restored instance then takes the source instance's value, except where
 * AWS documents a default of its own. {@code settings} carries the storage, backup, port and
 * protection members the request shares with CreateDBInstance.
 */
@RegisterForReflection
public record PointInTimeRestoreRequest(String targetDbInstanceIdentifier,
                                        String sourceDbInstanceIdentifier,
                                        String sourceDbiResourceId,
                                        Instant restoreTime,
                                        boolean useLatestRestorableTime,
                                        String dbInstanceClass,
                                        String availabilityZone,
                                        Boolean multiAz,
                                        Boolean publiclyAccessible,
                                        Boolean autoMinorVersionUpgrade,
                                        String dbSubnetGroupName,
                                        List<String> vpcSecurityGroupIds,
                                        String dbParameterGroupName,
                                        String optionGroupName,
                                        Boolean iamDatabaseAuthenticationEnabled,
                                        Integer allocatedStorage,
                                        DbInstanceSettings settings,
                                        Map<String, String> tags) {
}
