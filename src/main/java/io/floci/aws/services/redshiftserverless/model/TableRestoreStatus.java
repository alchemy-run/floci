package io.floci.aws.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** One table restore request, from a snapshot or from a recovery point, and how it ended. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableRestoreStatus {
    public String tableRestoreRequestId;
    public String status;
    public String message;
    public Instant requestTime;
    public String namespaceName;
    public String workgroupName;
    public String snapshotName;
    public String recoveryPointId;
    public String sourceDatabaseName;
    public String sourceSchemaName;
    public String sourceTableName;
    public String targetDatabaseName;
    public String targetSchemaName;
    public String newTableName;
    public double progressInMegaBytes;
    public double totalDataInMegaBytes;
}
