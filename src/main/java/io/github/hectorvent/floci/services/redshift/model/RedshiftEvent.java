package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record RedshiftEvent(String id, String region, String sourceIdentifier, String sourceType,
                            String message, String date) {
}
