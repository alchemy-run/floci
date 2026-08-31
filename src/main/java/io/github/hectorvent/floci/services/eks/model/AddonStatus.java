package io.github.hectorvent.floci.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** EKS managed add-on lifecycle status (serialized as the enum name, e.g. "ACTIVE"). */
@RegisterForReflection
public enum AddonStatus {
    CREATING,
    ACTIVE,
    CREATE_FAILED,
    UPDATING,
    DELETING,
    DELETE_FAILED,
    DEGRADED,
    UPDATE_FAILED
}
