package io.github.hectorvent.floci.services.kms.model;

import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum KmsMessageType {
    RAW, DIGEST;

    public static KmsMessageType fromString(String messageType) {
        try {
            return KmsMessageType.valueOf(messageType);
        } catch (IllegalArgumentException _) {
            throw SmithyValidationErrors.validationException("Value '" + messageType
                    + "' at 'messageType' failed to satisfy constraint: Member must satisfy enum value set: "
                    + "[RAW, DIGEST, EXTERNAL_MU]");
        }
    }
}
