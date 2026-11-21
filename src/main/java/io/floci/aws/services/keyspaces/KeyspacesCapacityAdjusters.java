package io.floci.aws.services.keyspaces;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.applicationautoscaling.CapacityAdjuster;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@link CapacityAdjuster}s for a Keyspaces table's provisioned read and write capacity, which Application Auto
 * Scaling sets when a table's target tracking policy scales it.
 */
public final class KeyspacesCapacityAdjusters {

    private KeyspacesCapacityAdjusters() {
    }

    abstract static class Adjuster implements CapacityAdjuster {
        private final KeyspacesService service;
        private final KeyspacesStore store;
        private final String dimension;

        Adjuster(KeyspacesService service, KeyspacesStore store, String dimension) {
            this.service = service;
            this.store = store;
            this.dimension = dimension;
        }

        @Override
        public boolean supports(String scalableDimension) {
            return ("cassandra:table:" + Character.toUpperCase(dimension.charAt(0)) + dimension.substring(1))
                    .equals(scalableDimension);
        }

        @Override
        public int getCurrentCapacity(String resourceId, String region) {
            String[] parts = resourceId.split("/");
            if (parts.length != 4) {
                throw new AwsException("ObjectNotFoundException", "No table " + resourceId + ".", 400);
            }
            return service.table(store.account(), region, parts[1], parts[3]).path("capacitySpecification")
                    .path(dimension).asInt();
        }

        @Override
        public void setCapacity(String resourceId, int capacity, String region) {
            service.setCapacity(store.account(), region, resourceId, dimension, capacity);
        }
    }

    /** {@code cassandra:table:ReadCapacityUnits}. */
    @Singleton
    public static class Read extends Adjuster {
        @Inject
        public Read(KeyspacesService service, KeyspacesStore store) {
            super(service, store, "readCapacityUnits");
        }
    }

    /** {@code cassandra:table:WriteCapacityUnits}. */
    @Singleton
    public static class Write extends Adjuster {
        @Inject
        public Write(KeyspacesService service, KeyspacesStore store) {
            super(service, store, "writeCapacityUnits");
        }
    }
}
