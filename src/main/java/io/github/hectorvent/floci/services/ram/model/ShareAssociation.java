package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * One row of a {@code GetResourceShareAssociations} response.
 *
 * @param associationType {@code PRINCIPAL}, {@code RESOURCE}, or {@code SOURCE}
 * @param status          {@code ASSOCIATED} while the entity is on a live share,
 *                        {@code DISASSOCIATED} once the share was deleted
 */
@RegisterForReflection
public record ShareAssociation(
        String resourceShareArn,
        String resourceShareName,
        String associatedEntity,
        String associationType,
        String status,
        Instant creationTime,
        Instant lastUpdatedTime,
        boolean external) {
}
