package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The ModifyDBInstance members that only a modification carries, as the request gives them: a
 * null member is one the request left out. {@code applyImmediately} null is the API default,
 * false, under which changes that honour the maintenance window are queued.
 */
@RegisterForReflection
public record DbInstanceModification(Boolean applyImmediately,
                                     Integer allocatedStorage,
                                     String dbInstanceClass,
                                     Boolean multiAz,
                                     String dbParameterGroupName,
                                     Boolean manageMasterUserPassword,
                                     String masterUserSecretKmsKeyId) {

    /** A request that names none of these members and leaves ApplyImmediately at its default. */
    public static DbInstanceModification none() {
        return new DbInstanceModification(null, null, null, null, null, null, null);
    }

    /**
     * What internal callers that predate the pending-modification queue mean: every change
     * takes effect now, the way CloudFormation applies an update.
     */
    public static DbInstanceModification immediate() {
        return new DbInstanceModification(true, null, null, null, null, null, null);
    }

    public boolean appliesImmediately() {
        return Boolean.TRUE.equals(applyImmediately);
    }
}
