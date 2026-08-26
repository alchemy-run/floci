package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsProtocolClaimFilter;
import io.github.hectorvent.floci.core.common.ProtocolClaim;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceDescriptor;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Map;

/**
 * Records JSON 1.1 / Query mutating management API calls into CloudTrail Event
 * History and (when a trail is logging) the default EventBridge bus.
 */
@Provider
@Priority(100)
public class CloudTrailManagementFilter implements ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(CloudTrailManagementFilter.class);

    private final CloudTrailService cloudTrailService;
    private final RequestContext requestContext;
    private final RegionResolver regionResolver;
    private final AccountResolver accountResolver;

    @Inject
    public CloudTrailManagementFilter(CloudTrailService cloudTrailService,
                                      RequestContext requestContext,
                                      RegionResolver regionResolver,
                                      AccountResolver accountResolver) {
        this.cloudTrailService = cloudTrailService;
        this.requestContext = requestContext;
        this.regionResolver = regionResolver;
        this.accountResolver = accountResolver;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        int status = response.getStatus();
        if (status < 200 || status >= 400) {
            return;
        }
        try {
            String region = requestContext.getRegion() != null
                    ? requestContext.getRegion() : regionResolver.getDefaultRegion();
            String auth = request.getHeaderString("Authorization");
            String akid = accountResolver.extractAccessKeyId(auth);
            String userAgent = request.getHeaderString("User-Agent");

            S3TaggingCall s3 = s3PutBucketTagging(request);
            if (s3 != null) {
                cloudTrailService.emitManagementEvent(new CloudTrailService.ManagementEvent(
                        region, "s3.amazonaws.com", "PutBucketTagging", false, akid,
                        "127.0.0.1", userAgent, Map.of("bucketName", s3.bucket()),
                        System.currentTimeMillis()));
                return;
            }

            ProtocolClaim claim = (ProtocolClaim) request.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY);
            if (claim == null || claim.operation() == null || claim.operation().isBlank()) {
                return;
            }
            String operation = claim.operation();
            if (isReadOnly(operation)) {
                return;
            }
            cloudTrailService.emitManagementEvent(new CloudTrailService.ManagementEvent(
                    region,
                    eventSourceOf(claim),
                    operation,
                    false,
                    akid,
                    "127.0.0.1",
                    userAgent,
                    Map.of(),
                    System.currentTimeMillis()));
        } catch (Throwable e) {
            // Emission is best-effort: a linkage/hot-reload error must not
            // replace the AWS success payload with an untyped 500.
            LOG.tracev(e, "CloudTrail management filter failed");
        }
    }

    private static S3TaggingCall s3PutBucketTagging(ContainerRequestContext request) {
        if (!"PUT".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        if (request.getUriInfo().getQueryParameters().getFirst("tagging") == null) {
            return null;
        }
        String path = request.getUriInfo().getPath();
        if (path == null || path.isBlank()) {
            return null;
        }
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int slash = stripped.indexOf('/');
        if (slash >= 0 && slash < stripped.length() - 1) {
            // object tagging: bucket/key?tagging
            return null;
        }
        String bucket = slash < 0 ? stripped : stripped.substring(0, slash);
        if (bucket.isBlank() || bucket.contains(".lambda-url.")) {
            return null;
        }
        return new S3TaggingCall(bucket);
    }

    private record S3TaggingCall(String bucket) {}

    private static String eventSourceOf(ProtocolClaim claim) {
        ServiceDescriptor service = claim.service();
        String key = service != null ? service.externalKey() : null;
        if (key == null || key.isBlank()) {
            return "cloudtrail.amazonaws.com";
        }
        return key + ".amazonaws.com";
    }

    static boolean isReadOnly(String operation) {
        if (operation == null) {
            return true;
        }
        String op = operation.toLowerCase(Locale.ROOT);
        return op.startsWith("get")
                || op.startsWith("list")
                || op.startsWith("describe")
                || op.startsWith("lookup")
                || op.startsWith("head")
                || op.startsWith("test")
                || op.startsWith("select")
                || op.startsWith("query");
    }
}
