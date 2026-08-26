package io.github.hectorvent.floci.services.signer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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

import java.util.List;

/**
 * AWS Signer (Smithy restJson1). {@link SignerRoutingFilter} prefixes signer-signed
 * paths so they do not collide with S3's path-style catch-all.
 */
@Path(SignerRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SignerController {

    private final SignerService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final AccountResolver accountResolver;

    @Inject
    public SignerController(
            SignerService service,
            ObjectMapper objectMapper,
            RegionResolver regionResolver,
            AccountResolver accountResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.accountResolver = accountResolver;
    }

    @PUT
    @Path("/signing-profiles/{profileName}")
    public Response putSigningProfile(
            @Context HttpHeaders headers,
            @PathParam("profileName") String profileName,
            String body) {
        return Response.ok(service.putSigningProfile(
                account(headers), region(headers), profileName, parse(body))).build();
    }

    @GET
    @Path("/signing-profiles/{profileName}")
    @Consumes(MediaType.WILDCARD)
    public Response getSigningProfile(
            @Context HttpHeaders headers,
            @PathParam("profileName") String profileName) {
        return Response.ok(service.getSigningProfile(account(headers), region(headers), profileName)).build();
    }

    @DELETE
    @Path("/signing-profiles/{profileName}")
    @Consumes(MediaType.WILDCARD)
    public Response cancelSigningProfile(
            @Context HttpHeaders headers,
            @PathParam("profileName") String profileName) {
        service.cancelSigningProfile(account(headers), region(headers), profileName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/signing-profiles")
    @Consumes(MediaType.WILDCARD)
    public Response listSigningProfiles(
            @Context HttpHeaders headers,
            @QueryParam("includeCanceled") Boolean includeCanceled,
            @QueryParam("platformId") String platformId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(service.listSigningProfiles(
                account(headers), region(headers), includeCanceled, platformId, maxResults, nextToken)).build();
    }

    @POST
    @Path("/signing-profiles/{profileName}/permissions")
    public Response addProfilePermission(
            @Context HttpHeaders headers,
            @PathParam("profileName") String profileName,
            String body) {
        return Response.ok(service.addProfilePermission(
                account(headers), region(headers), profileName, parse(body))).build();
    }

    @GET
    @Path("/signing-profiles/{profileName}/permissions")
    @Consumes(MediaType.WILDCARD)
    public Response listProfilePermissions(
            @Context HttpHeaders headers,
            @PathParam("profileName") String profileName) {
        return Response.ok(service.listProfilePermissions(
                account(headers), region(headers), profileName)).build();
    }

    @DELETE
    @Path("/signing-profiles/{profileName}/permissions/{statementId}")
    @Consumes(MediaType.WILDCARD)
    public Response removeProfilePermission(
            @Context HttpHeaders headers,
            @PathParam("profileName") String profileName,
            @PathParam("statementId") String statementId,
            @QueryParam("revisionId") String revisionId) {
        return Response.ok(service.removeProfilePermission(
                account(headers), region(headers), profileName, statementId, revisionId)).build();
    }

    @PUT
    @Path("/signing-profiles/{profileName}/revoke")
    public Response revokeSigningProfile(
            @Context HttpHeaders headers,
            @PathParam("profileName") String profileName,
            String body) {
        service.revokeSigningProfile(account(headers), region(headers), profileName, parse(body));
        return Response.ok().build();
    }

    @GET
    @Path("/signing-platforms")
    @Consumes(MediaType.WILDCARD)
    public Response listSigningPlatforms(
            @QueryParam("category") String category,
            @QueryParam("partner") String partner,
            @QueryParam("target") String target,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(service.listSigningPlatforms(category, partner, target, maxResults, nextToken)).build();
    }

    @GET
    @Path("/signing-platforms/{platformId}")
    @Consumes(MediaType.WILDCARD)
    public Response getSigningPlatform(@PathParam("platformId") String platformId) {
        return Response.ok(service.getSigningPlatform(platformId)).build();
    }

    @POST
    @Path("/signing-jobs")
    public Response startSigningJob(@Context HttpHeaders headers, String body) {
        return Response.ok(service.startSigningJob(account(headers), region(headers), parse(body))).build();
    }

    @GET
    @Path("/signing-jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listSigningJobs(
            @Context HttpHeaders headers,
            @QueryParam("status") String status,
            @QueryParam("platformId") String platformId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(service.listSigningJobs(
                account(headers), region(headers), status, platformId, maxResults, nextToken)).build();
    }

    @GET
    @Path("/signing-jobs/{jobId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeSigningJob(
            @Context HttpHeaders headers,
            @PathParam("jobId") String jobId) {
        return Response.ok(service.describeSigningJob(account(headers), region(headers), jobId)).build();
    }

    @PUT
    @Path("/signing-jobs/{jobId}/revoke")
    public Response revokeSignature(
            @Context HttpHeaders headers,
            @PathParam("jobId") String jobId,
            String body) {
        service.revokeSignature(account(headers), region(headers), jobId, parse(body));
        return Response.ok().build();
    }

    @POST
    @Path("/signing-jobs/with-payload")
    public Response signPayload(@Context HttpHeaders headers, String body) {
        return Response.ok(service.signPayload(account(headers), region(headers), parse(body))).build();
    }

    @GET
    @Path("/revocations")
    @Consumes(MediaType.WILDCARD)
    public Response getRevocationStatus(
            @Context HttpHeaders headers,
            @QueryParam("signatureTimestamp") String signatureTimestamp,
            @QueryParam("platformId") String platformId,
            @QueryParam("profileVersionArn") String profileVersionArn,
            @QueryParam("jobArn") String jobArn,
            @QueryParam("certificateHashes") List<String> certificateHashes) {
        if (signatureTimestamp == null || signatureTimestamp.isBlank()) {
            throw new AwsException("ValidationException", "signatureTimestamp is required", 400);
        }
        return Response.ok(service.getRevocationStatus(
                account(headers), region(headers), platformId, profileVersionArn, jobArn, certificateHashes)).build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private String account(HttpHeaders headers) {
        return accountResolver.resolve(headers.getHeaderString("Authorization"));
    }
}
