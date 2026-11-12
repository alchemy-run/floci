package io.floci.aws.services.lambda;

import java.util.List;
import java.util.Optional;

/**
 * Checks a deployment package's code signature for a function with a code signing configuration. Discovered through
 * CDI, so Lambda doesn't import the signing service (AWS Signer) that implements it.
 */
public interface CodeSignatureVerifier {

    /**
     * Why the package isn't validly signed by one of the allowed signing profile versions, or empty when it is: it
     * carries no signature, a signature that doesn't match its content, one from another profile, or one whose
     * profile or job has been revoked or has expired.
     */
    Optional<String> rejection(byte[] zip, List<String> allowedProfileVersionArns);
}
