package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.RoutedResource;
import jakarta.ws.rs.container.ResourceInfo;

final class S3SignatureFilterScope {

    private S3SignatureFilterScope() {
    }

    /**
     * S3 verifies request signatures when {@code floci.auth.validate-signatures} is on. With it off,
     * {@code floci.services.s3.enforce-auth} alone authorizes the principal a credential names without
     * checking it signed, as every other Floci service does.
     */
    static boolean verifiesSignatures(S3Service s3Service, PreSignedUrlGenerator presignGenerator) {
        return presignGenerator != null && presignGenerator.shouldValidateSignatures();
    }

    /** Safe from a response filter too, which also sees requests routing turned away. */
    static boolean routedToS3(ResourceInfo resourceInfo) {
        return S3Controller.class.equals(RoutedResource.resourceClass(resourceInfo));
    }
}
