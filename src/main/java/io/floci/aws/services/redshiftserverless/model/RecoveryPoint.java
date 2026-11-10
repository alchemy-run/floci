package io.floci.aws.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * An automatic recovery point of a namespace: a dump of its database taken by the recovery point
 * sweep, kept for a day. {@code namespaceId} ties it to the namespace it was taken from, so a point
 * of a deleted namespace never restores into a new one of the same name.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecoveryPoint {
    public String recoveryPointId;
    public String namespaceName;
    public String namespaceId;
    public String namespaceArn;
    public String workgroupName;
    public Instant recoveryPointCreateTime;
    public double totalSizeInMegaBytes;
    public String sqlDump;
}
