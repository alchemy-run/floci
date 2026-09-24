package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.ecr.model.ImageIdentifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins a container-image function's {@code ImageUri} to the ECR manifest digest it names at
 * deploy time, as Lambda does for {@code ResolvedImageUri}: the function keeps running that digest
 * until the next UpdateFunctionCode, even if the tag is later moved.
 */
@ApplicationScoped
public class LambdaImageDigestResolver {

    private static final Logger LOG = Logger.getLogger(LambdaImageDigestResolver.class);
    private static final Pattern ECR_HOST = Pattern.compile("^(\\d{12})\\.dkr\\.ecr\\.([a-z0-9-]+)\\..+$");
    private static final Pattern PATH_STYLE_REPOSITORY = Pattern.compile("^(\\d{12})/([a-z0-9-]+)/(.+)$");
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");

    private final EcrService ecrService;

    @Inject
    public LambdaImageDigestResolver(EcrService ecrService) {
        this.ecrService = ecrService;
    }

    /**
     * An ECR image reference split into the parts DescribeImages needs. {@code repositoryUri} is
     * the reference without its tag or digest, spelled exactly as the caller wrote it, so the
     * resolved URI keeps the caller's registry host.
     */
    record EcrImageReference(String registryId, String region, String repositoryName,
                             String repositoryUri, String tag, String digest) {
    }

    static EcrImageReference parse(String imageUri) {
        if (imageUri == null || imageUri.isBlank()) {
            return null;
        }
        int slash = imageUri.indexOf('/');
        if (slash <= 0 || slash == imageUri.length() - 1) {
            return null;
        }
        String host = imageUri.substring(0, slash);
        String remainder = imageUri.substring(slash + 1);

        String digest = null;
        int at = remainder.indexOf('@');
        if (at >= 0) {
            digest = remainder.substring(at + 1);
            remainder = remainder.substring(0, at);
            if (!DIGEST.matcher(digest).matches()) {
                return null;
            }
        }
        String tag = null;
        int colon = remainder.lastIndexOf(':');
        if (colon >= 0) {
            tag = remainder.substring(colon + 1);
            remainder = remainder.substring(0, colon);
        }
        if (remainder.isEmpty() || (tag != null && tag.isEmpty())) {
            return null;
        }
        if (tag == null && digest == null) {
            tag = "latest";
        }
        String repositoryUri = host + "/" + remainder;

        Matcher hostMatcher = ECR_HOST.matcher(host);
        if (hostMatcher.matches()) {
            return new EcrImageReference(hostMatcher.group(1), hostMatcher.group(2), remainder,
                    repositoryUri, tag, digest);
        }
        Matcher pathMatcher = PATH_STYLE_REPOSITORY.matcher(remainder);
        if (pathMatcher.matches()) {
            return new EcrImageReference(pathMatcher.group(1), pathMatcher.group(2), pathMatcher.group(3),
                    repositoryUri, tag, digest);
        }
        return null;
    }

    /**
     * Returns {@code repositoryUri@sha256:...} for an ECR image reference, or {@code null} when
     * the reference is not an ECR image or the registry cannot answer for it.
     */
    public String resolve(String imageUri) {
        EcrImageReference reference = parse(imageUri);
        if (reference == null) {
            return null;
        }
        if (reference.digest() != null) {
            return reference.repositoryUri() + "@" + reference.digest();
        }
        try {
            EcrService.DescribeImagesResult result = ecrService.describeImages(reference.repositoryName(),
                    List.of(new ImageIdentifier(reference.tag(), null)),
                    reference.registryId(), reference.region());
            for (ImageDetail detail : result.imageDetails()) {
                String imageDigest = detail.getImageDigest();
                if (imageDigest != null && DIGEST.matcher(imageDigest).matches()) {
                    return reference.repositoryUri() + "@" + imageDigest;
                }
            }
            LOG.debugv("ECR returned no digest for Lambda image {0}", imageUri);
            return null;
        } catch (AwsException e) {
            LOG.debugv("Could not resolve Lambda image {0} to a digest: {1}", imageUri, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            LOG.warnv("ECR digest lookup failed for Lambda image {0}: {1}", imageUri, e.getMessage());
            return null;
        }
    }
}
