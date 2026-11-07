package io.floci.aws.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The ModifyDBInstance members that decide when a change applies, and the managed master password
 * members: a null member is one the request left out. {@code applyImmediately} null is the API
 * default, false, under which changes RDS defers to the maintenance window are queued.
 */
@RegisterForReflection
public record DbInstanceModifyOptions(Boolean applyImmediately,
                                      Boolean multiAz,
                                      Boolean manageMasterUserPassword,
                                      String masterUserSecretKmsKeyId) {

    /**
     * What callers that predate the queue mean: every change takes effect now, the way
     * CloudFormation applies an update.
     */
    public static DbInstanceModifyOptions immediate() {
        return new DbInstanceModifyOptions(true, null, null, null);
    }

    public boolean appliesImmediately() {
        return Boolean.TRUE.equals(applyImmediately);
    }
}
