package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Resources AWS deletes in the background read back as deleting for a moment before they are gone, as
 * outside tests. The RDS and DocumentDB backends are mocked: the deleting window is metadata.
 */
public class AsyncDeletesProfile implements QuarkusTestProfile {

    /** How long a deleted resource reads back as deleting. */
    public static final long DELETING_MILLIS = 1500;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.async-delete-millis", String.valueOf(DELETING_MILLIS),
                "floci.services.rds.mock", "true",
                "floci.services.docdb.mock", "true");
    }
}
