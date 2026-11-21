package io.floci.aws.services.s3;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsNamespaces;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.XmlBuilder;
import io.floci.aws.core.common.XmlParser;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * S3 Control API endpoints used by Terraform AWS provider v6.x and other tools.
 * All endpoints are under /v20180820 matching the S3 Control API version.
 *
 * Protocol: REST-XML
 * Namespace: http://awss3control.amazonaws.com/doc/2018-08-20/
 */
@Path("/v20180820")
@Produces(MediaType.APPLICATION_XML)
public class S3ControlController {

    private static final Logger LOG = Logger.getLogger(S3ControlController.class);

    private static final String AMZ_REQUEST_ID = "x-amz-request-id";
    private static final String AMZN_REQUEST_ID = "x-amzn-RequestId";
    private static final String AMZ_ID_2 = "x-amz-id-2";

    private final S3Service s3Service;
    private final S3ControlDispatcher dispatcher;
    private final RegionResolver regionResolver;
    private final Instance<RequestContext> requestContextInstance;
    private final String defaultAccountId;

    @Inject
    public S3ControlController(S3Service s3Service,
                               S3ControlDispatcher dispatcher,
                               RegionResolver regionResolver,
                               Instance<RequestContext> requestContextInstance,
                               EmulatorConfig config) {
        this.s3Service = s3Service;
        this.dispatcher = dispatcher;
        this.regionResolver = regionResolver;
        this.requestContextInstance = requestContextInstance;
        this.defaultAccountId = config.defaultAccountId();
    }

    // ── Modeled operations ─────────────────────────────────────────────────

    @GET
    @Path("/{path: .*}")
    public Response getOperation(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return dispatch("GET", uriInfo, headers, null);
    }

    @PUT
    @Path("/{path: .*}")
    @Consumes(MediaType.WILDCARD)
    public Response putOperation(@Context UriInfo uriInfo, @Context HttpHeaders headers, String body) {
        return dispatch("PUT", uriInfo, headers, body);
    }

    @POST
    @Path("/{path: .*}")
    @Consumes(MediaType.WILDCARD)
    public Response postOperation(@Context UriInfo uriInfo, @Context HttpHeaders headers, String body) {
        return dispatch("POST", uriInfo, headers, body);
    }

    @PATCH
    @Path("/{path: .*}")
    @Consumes(MediaType.WILDCARD)
    public Response patchOperation(@Context UriInfo uriInfo, @Context HttpHeaders headers, String body) {
        return dispatch("PATCH", uriInfo, headers, body);
    }

    @DELETE
    @Path("/{path: .*}")
    public Response deleteOperation(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return dispatch("DELETE", uriInfo, headers, null);
    }

    /**
     * Serves a modeled S3 Control operation: Multi-Region Access Point control requests in the
     * Region AWS serves them from, everything else in the request's signing Region.
     */
    private Response dispatch(String method, UriInfo uriInfo, HttpHeaders headers, String body) {
        try {
            requireCallerMayActOnAccount(headers.getHeaderString("x-amz-account-id"));
            String path = uriInfo.getRequestUri().getRawPath();
            Map<String, List<String>> query = new LinkedHashMap<>();
            uriInfo.getQueryParameters(true).forEach(query::put);
            Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
            headers.getRequestHeaders().forEach(requestHeaders::put);
            String region = regionResolver.resolveRegion(headers);
            S3ControlDispatcher.Result result = dispatcher.dispatch(method, path, body, query, requestHeaders,
                    region);
            Response.ResponseBuilder response = Response.status(result.status());
            result.headers().forEach(response::header);
            if (result.body() != null) {
                response.entity(result.body()).type(MediaType.APPLICATION_XML);
            }
            String requestId = UUID.randomUUID().toString();
            return response.header(AMZ_REQUEST_ID, requestId).header(AMZN_REQUEST_ID, requestId)
                    .header(AMZ_ID_2, requestId).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * Whether a tagging route is this controller's own: a bucket ARN, or a path naming no valid ARN,
     * which the bucket handler answers {@code InvalidRequest}. Every other ARN is an S3 Control
     * resource the dispatcher tags.
     */
    private static boolean namesBucket(String resourceArn) {
        try {
            return S3ControlArnUtils.parse(resourceArn).map(S3ControlArnUtils::namesBucket).orElse(true);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /**
     * ListTagsForResource — returns all tags on the specified S3 bucket.
     * Used by Terraform AWS provider v6.x during bucket read-back.
     *
     * GET /v20180820/tags/{resourceArn+}
     * Header: x-amz-account-id
     */
    @GET
    @Path("/tags/{resourceArn: .+}")
    public Response listTagsForResource(
            @PathParam("resourceArn") String resourceArn,
            @HeaderParam("x-amz-account-id") String accountId,
            @Context UriInfo uriInfo, @Context HttpHeaders headers) {

        if (!namesBucket(resourceArn)) {
            return dispatch("GET", uriInfo, headers, null);
        }
        try {
            AwsArnUtils.Arn arn = taggedBucketArn(resourceArn);
            Map<String, String> tags = s3Service.getBucketTaggingForAccount(arn.accountId(),
                    S3ControlArnUtils.bucketName(arn));

            XmlBuilder xml = new XmlBuilder()
                    .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .start("ListTagsForResourceResult", AwsNamespaces.S3_CONTROL)
                    .start("Tags");
            tags.forEach((k, v) ->
                    xml.start("Tag").elem("Key", k).elem("Value", v).end("Tag"));
            xml.end("Tags").end("ListTagsForResourceResult");
            return Response.ok(xml.build()).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * PutPublicAccessBlock — sets the account-level S3 Block Public Access configuration.
     *
     * <p>Account-scoped (keyed by the {@code x-amz-account-id} header), distinct from the
     * bucket-level operation. AWS LZA's {@code Custom::PutPublicAccessBlock} custom resource
     * calls this for every governed account during the LoggingStack deploy; without it the
     * custom-resource Lambda reports FAILED and the stack rolls back.
     *
     * PUT /v20180820/configuration/publicAccessBlock
     * Header: x-amz-account-id
     * Body: {@code <PublicAccessBlockConfiguration>} with the four boolean flags
     */
    @PUT
    @Path("/configuration/publicAccessBlock")
    @Consumes(MediaType.WILDCARD)
    public Response putPublicAccessBlock(
            @HeaderParam("x-amz-account-id") String accountId,
            String body) {
        try {
            requireCallerMayActOnAccount(accountId);
            if (!"PublicAccessBlockConfiguration".equals(XmlParser.rootElementName(body))) {
                throw new AwsException("MalformedXML",
                        "The XML you provided was not well-formed or did not validate against our published schema.",
                        400);
            }
            s3Service.putAccountPublicAccessBlock(accountId, canonicalPublicAccessBlockXml(body));
            return Response.ok().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * GetPublicAccessBlock — returns the account-level S3 Block Public Access configuration.
     *
     * GET /v20180820/configuration/publicAccessBlock
     * Header: x-amz-account-id
     */
    @GET
    @Path("/configuration/publicAccessBlock")
    public Response getPublicAccessBlock(
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            requireCallerMayActOnAccount(accountId);
            return Response.ok(s3Service.getAccountPublicAccessBlock(accountId)).build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * DeletePublicAccessBlock — removes the account-level S3 Block Public Access configuration.
     *
     * DELETE /v20180820/configuration/publicAccessBlock
     * Header: x-amz-account-id
     */
    @DELETE
    @Path("/configuration/publicAccessBlock")
    public Response deletePublicAccessBlock(
            @HeaderParam("x-amz-account-id") String accountId) {
        try {
            requireCallerMayActOnAccount(accountId);
            s3Service.deleteAccountPublicAccessBlock(accountId);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * Rejects an {@code x-amz-account-id} that names an account the caller has no claim on.
     *
     * <p>Block Public Access is a security control, and the header alone decides which account's
     * configuration an operation reads, overwrites or deletes. Without this check any caller could
     * manage any account's configuration by setting one header. AWS s3control answers
     * {@code AccessDenied} when the header does not match the caller's own account, because there
     * a cross-account call is made with credentials assumed <em>into</em> the target account, so
     * the header and the caller always agree.
     *
     * <p>The emulator keeps one deliberate exception: the configured default (management) account
     * may act on any account. Floci's launched Lambdas run on placeholder credentials that resolve
     * to the management account rather than assuming a role in the target account, so LZA's
     * {@code Custom::PutPublicAccessBlock} resource legitimately arrives as management-caller,
     * governed-account-header. This is the same allowance {@code S3Service.mutateBucket} already
     * makes for {@code Custom::S3PutBucketReplication}. Every other cross-account combination is
     * denied.
     *
     * <p>A blank header is left to the service, which raises the modelled {@code InvalidRequest}.
     */
    private void requireCallerMayActOnAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return;
        }
        String caller = callerAccountId();
        if (accountId.equals(caller) || defaultAccountId.equals(caller)) {
            return;
        }
        throw new AwsException("AccessDenied", "Access Denied", 403);
    }

    /**
     * The caller's account as resolved by {@code AccountContextFilter}, falling back to the
     * configured default outside a request scope — the same resolution
     * {@code AccountAwareStorageBackend} uses to pick a storage partition.
     */
    private String callerAccountId() {
        if (requestContextInstance != null) {
            try {
                String accountId = requestContextInstance.get().getAccountId();
                if (accountId != null && !accountId.isBlank()) {
                    return accountId;
                }
            } catch (ContextNotActiveException ignored) {
                // outside request scope — fall through to the default account
            }
        }
        return defaultAccountId;
    }

    /**
     * Normalises an incoming PublicAccessBlockConfiguration body into a canonical, namespaced
     * document so read-back is well-formed regardless of the client's request formatting or
     * namespace. Absent flags default to false, matching the AWS API.
     */
    private static String canonicalPublicAccessBlockXml(String requestXml) {
        String xml = requestXml == null ? "" : requestXml;
        return new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("PublicAccessBlockConfiguration", AwsNamespaces.S3_CONTROL)
                .elem("BlockPublicAcls", XmlParser.extractFirst(xml, "BlockPublicAcls", "false"))
                .elem("IgnorePublicAcls", XmlParser.extractFirst(xml, "IgnorePublicAcls", "false"))
                .elem("BlockPublicPolicy", XmlParser.extractFirst(xml, "BlockPublicPolicy", "false"))
                .elem("RestrictPublicBuckets", XmlParser.extractFirst(xml, "RestrictPublicBuckets", "false"))
                .end("PublicAccessBlockConfiguration")
                .build();
    }

    /**
     * TagResource — replaces all tags on the specified S3 bucket.
     *
     * POST /v20180820/tags/{resourceArn+}
     * Header: x-amz-account-id
     * Body: XML containing {@code <Tags><Tag><Key>…</Key><Value>…</Value></Tag></Tags>}
     */
    @POST
    @Path("/tags/{resourceArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response tagResource(
            @PathParam("resourceArn") String resourceArn,
            @HeaderParam("x-amz-account-id") String accountId,
            @Context UriInfo uriInfo, @Context HttpHeaders headers,
            byte[] body) {

        if (!namesBucket(resourceArn)) {
            return dispatch("POST", uriInfo, headers, new String(body, StandardCharsets.UTF_8));
        }
        try {
            AwsArnUtils.Arn arn = taggedBucketArn(resourceArn);
            String xml = new String(body, StandardCharsets.UTF_8);
            Map<String, String> tags = XmlParser.extractPairs(xml, "Tag", "Key", "Value");
            s3Service.putBucketTaggingForAccount(arn.accountId(), S3ControlArnUtils.bucketName(arn), tags);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * UntagResource — removes specific tags from the specified S3 bucket.
     *
     * DELETE /v20180820/tags/{resourceArn+}?tagKeys=Key1&tagKeys=Key2
     * Header: x-amz-account-id
     */
    @DELETE
    @Path("/tags/{resourceArn: .+}")
    public Response untagResource(
            @PathParam("resourceArn") String resourceArn,
            @HeaderParam("x-amz-account-id") String accountId,
            @QueryParam("tagKeys") List<String> tagKeys,
            @Context UriInfo uriInfo, @Context HttpHeaders headers) {

        if (!namesBucket(resourceArn)) {
            return dispatch("DELETE", uriInfo, headers, null);
        }
        try {
            AwsArnUtils.Arn arn = taggedBucketArn(resourceArn);
            String bucketName = S3ControlArnUtils.bucketName(arn);
            Map<String, String> existing =
                    new HashMap<>(s3Service.getBucketTaggingForAccount(arn.accountId(), bucketName));
            tagKeys.forEach(existing::remove);
            s3Service.putBucketTaggingForAccount(arn.accountId(), bucketName, existing);
            return Response.noContent().build();
        } catch (AwsException e) {
            return xmlErrorResponse(e);
        }
    }

    /**
     * The bucket ARN a tagging route names, decoded and parsed the one way every reader of it
     * shares: the handler here, the IAM resource the request is checked against, and the bucket
     * policy lookup. The regional form carries the account and region of the bucket it names, and
     * the global form the caller's own account, so a request naming another account's bucket acts
     * on that account's bucket, never on the caller's same-named one.
     */
    private static AwsArnUtils.Arn taggedBucketArn(String resourceArn) {
        Optional<AwsArnUtils.Arn> arn;
        try {
            arn = S3ControlArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidRequest",
                    "Malformed percent-encoding in resource ARN: " + e.getMessage(), 400);
        }
        return arn.filter(S3ControlArnUtils::namesBucket)
                .orElseThrow(() -> new AwsException("InvalidRequest",
                        "Unsupported resource type. Only S3 bucket ARNs are supported " +
                        "(arn:aws:s3:<region>:<account>:bucket/<name> or arn:aws:s3:::<name>).", 400)); // partition-literal: AWS's message text
    }

    /**
     * S3 Control is a REST-XML protocol, so error responses must also be XML.
     * AWS S3 Control wraps errors in an {@code <ErrorResponse xmlns=...>} envelope
     * containing the inner {@code <Error>} block and a top-level {@code <RequestId>}.
     *
     * <p>References: AWS Go SDK v2 s3control error deserializer expects this wrapper;
     * bare {@code <Error>} collapses to "UnknownError" at the SDK layer.
     * See issue #557.
     */
    // Same protocol safety net as S3Controller: unhandled failures on S3 Control routes
    // must render the REST-XML error contract, not Quarkus's plain-text error page.
    @ServerExceptionMapper
    public Response mapUnhandledThrowable(Throwable t) {
        LOG.error("Unhandled exception processing S3 Control request", t);
        return xmlErrorResponse(new AwsException("InternalError",
                "We encountered an internal error. Please try again.", 500));
    }

    @ServerExceptionMapper
    public Response mapEscapedAwsException(AwsException e) {
        return xmlErrorResponse(e);
    }

    private Response xmlErrorResponse(AwsException e) {
        String requestId = UUID.randomUUID().toString();
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("ErrorResponse", AwsNamespaces.S3_CONTROL)
                .start("Error")
                .elem("Code", e.getErrorCode())
                .elem("Message", e.getMessage())
                .elem("RequestId", requestId)
                .end("Error")
                .elem("RequestId", requestId)
                .end("ErrorResponse")
                .build();
        return Response.status(e.getHttpStatus())
                .type(MediaType.APPLICATION_XML)
                .header(AMZ_REQUEST_ID, requestId)
                .header(AMZN_REQUEST_ID, requestId)
                .header(AMZ_ID_2, requestId)
                .entity(xml)
                .build();
    }
}
