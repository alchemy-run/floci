package io.floci.aws.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;

import java.util.List;
import java.util.Map;

/**
 * Checks Location and Geo requests against the constraints vendored from botocore's models by
 * {@code tools/aws/regen_location_shapes.py}, answering {@code ValidationException}. Path
 * parameters are checked with the body, as the model makes them members of the request.
 */
final class LocationRequestShapes {
    private static final RequestConstraintCheck.Messages MESSAGES = new RequestConstraintCheck.Messages() {
        @Override
        public AwsException type(String path, String type) {
            return LocationService.validation(path + " must be a " + type + ".");
        }

        @Override
        public AwsException bounds(String path, String type, Long min, Long max, long size) {
            return LocationService.validation(path + " is outside the allowed bounds"
                    + (min == null ? "" : " (min " + min + ")") + (max == null ? "" : " (max " + max + ")") + ".");
        }

        @Override
        public AwsException pattern(String path, String pattern) {
            return LocationService.validation(path + " does not match the pattern " + pattern + ".");
        }

        @Override
        public AwsException enumValue(String path, List<String> values) {
            return LocationService.validation(path + " must be one of " + values + ".");
        }

        @Override
        public AwsException required(String path) {
            return LocationService.validation(path + " is required.");
        }
    };

    private static final Map<String, RequestConstraintCheck> CHECKS = Map.of(
            "location", RequestConstraintCheck.load("aws/location-request-shapes.json", "Location request shapes",
                    MESSAGES),
            "geo-maps", RequestConstraintCheck.load("aws/geo-maps-request-shapes.json", "Geo Maps request shapes",
                    MESSAGES),
            "geo-places", RequestConstraintCheck.load("aws/geo-places-request-shapes.json",
                    "Geo Places request shapes", MESSAGES),
            "geo-routes", RequestConstraintCheck.load("aws/geo-routes-request-shapes.json",
                    "Geo Routes request shapes", MESSAGES));

    private LocationRequestShapes() {
    }

    /** {@code pathMembers} alternates member names and values. */
    static void check(String service, String operation, JsonNode request, String... pathMembers) {
        JsonNode checked = request;
        boolean keyRestrictions = request.path("Restrictions").has("AllowResources");
        if (pathMembers.length > 0 || keyRestrictions) {
            ObjectNode copy = ((ObjectNode) request).deepCopy();
            if (keyRestrictions) {
                // The model's pattern predates wildcard Regions and accounts, which AWS accepts
                // (arn:aws:geo:*:*:map/*); LocationService checks these resources itself.
                ((ObjectNode) copy.get("Restrictions")).putArray("AllowResources")
                        .add("arn:aws:geo:us-east-1:000000000000:map/placeholder"); // partition-literal: never returned, only satisfies the model pattern
            }
            for (int i = 0; i + 1 < pathMembers.length; i += 2) {
                if (pathMembers[i + 1] != null) {
                    copy.put(pathMembers[i], pathMembers[i + 1]);
                }
            }
            checked = copy;
        }
        CHECKS.get(service).check(operation, checked);
    }
}
