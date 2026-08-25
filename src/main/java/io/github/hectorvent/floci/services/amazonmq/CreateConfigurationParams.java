package io.github.hectorvent.floci.services.amazonmq;

import java.util.Map;

/**
 * Immutable carrier for the fields the controller parses out of a
 * {@code CreateConfiguration} request body.
 */
public record CreateConfigurationParams(
        String name,
        String engineType,
        String engineVersion,
        String authenticationStrategy,
        Map<String, String> tags) {
}
