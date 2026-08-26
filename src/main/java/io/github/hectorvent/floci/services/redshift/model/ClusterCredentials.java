package io.github.hectorvent.floci.services.redshift.model;

import java.time.Instant;

/**
 * Temporary database credentials returned by {@code GetClusterCredentials}
 * and {@code GetClusterCredentialsWithIAM}.
 */
public record ClusterCredentials(
        String dbUser,
        String dbPassword,
        Instant expiration,
        Instant nextRefreshTime
) {
}
