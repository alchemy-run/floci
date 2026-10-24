package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.RoutedResource;
import jakarta.ws.rs.container.ResourceInfo;

final class S3SignatureFilterScope {

    private S3SignatureFilterScope() {
    }

    /**
     * S3 verifies request signatures only when both {@code floci.services.s3.enforce-auth} and
     * {@code floci.auth.validate-signatures} are on. With signature validation off, S3 follows every
     * other Floci service and authorizes the principal a credential names without checking it signed.
     */
    static boolean verifiesSignatures(S3Service s3Service, PreSignedUrlGenerator presignGenerator) {
        return s3Service != null && s3Service.isAuthEnforced()
                && presignGenerator != null && presignGenerator.shouldValidateSignatures();
    }

    /** Safe from a response filter too, which also sees requests routing turned away. */
    static boolean routedToS3(ResourceInfo resourceInfo) {
        return S3Controller.class.equals(RoutedResource.resourceClass(resourceInfo));
    }
}
