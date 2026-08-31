package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * CodeArtifact restJson1. Literal {@code /v1/domain}, {@code /v1/repository},
 * {@code /v1/package} paths take JAX-RS precedence over S3's {@code /{bucket}/{key}}
 * catch-all.
 */
@Path(CodeArtifactRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CodeArtifactController {

    private final CodeArtifactService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public CodeArtifactController(CodeArtifactService service, RegionResolver regionResolver,
                                  ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/v1/domain")
    public Response createDomain(@Context HttpHeaders headers,
                                 @QueryParam("domain") String domain,
                                 String body) {
        return handle(() -> wrap("domain",
                service.createDomain(region(headers), domain, parse(body))));
    }

    @GET
    @Path("/v1/domain")
    @Consumes(MediaType.WILDCARD)
    public Response describeDomain(@Context HttpHeaders headers,
                                   @QueryParam("domain") String domain,
                                   @QueryParam("domain-owner") String domainOwner) {
        return handle(() -> wrap("domain",
                service.describeDomain(region(headers), domain, domainOwner)));
    }

    @DELETE
    @Path("/v1/domain")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDomain(@Context HttpHeaders headers,
                                 @QueryParam("domain") String domain,
                                 @QueryParam("domain-owner") String domainOwner) {
        return handle(() -> {
            Map<String, Object> deleted = service.deleteDomain(region(headers), domain, domainOwner);
            if (deleted.isEmpty()) {
                return Response.ok(objectMapper.createObjectNode()).build();
            }
            return wrap("domain", deleted);
        });
    }

    @POST
    @Path("/v1/domains")
    @Consumes(MediaType.WILDCARD)
    public Response listDomains(@Context HttpHeaders headers, String body) {
        return handle(() -> Response.ok(service.listDomains(region(headers))).build());
    }

    @POST
    @Path("/v1/repository")
    public Response createRepository(@Context HttpHeaders headers,
                                     @QueryParam("domain") String domain,
                                     @QueryParam("domain-owner") String domainOwner,
                                     @QueryParam("repository") String repository,
                                     String body) {
        return handle(() -> wrap("repository",
                service.createRepository(region(headers), domain, domainOwner, repository, parse(body))));
    }

    @GET
    @Path("/v1/repository")
    @Consumes(MediaType.WILDCARD)
    public Response describeRepository(@Context HttpHeaders headers,
                                       @QueryParam("domain") String domain,
                                       @QueryParam("domain-owner") String domainOwner,
                                       @QueryParam("repository") String repository) {
        return handle(() -> wrap("repository",
                service.describeRepository(region(headers), domain, domainOwner, repository)));
    }

    @PUT
    @Path("/v1/repository")
    public Response updateRepository(@Context HttpHeaders headers,
                                     @QueryParam("domain") String domain,
                                     @QueryParam("domain-owner") String domainOwner,
                                     @QueryParam("repository") String repository,
                                     String body) {
        return handle(() -> wrap("repository",
                service.updateRepository(region(headers), domain, domainOwner, repository, parse(body))));
    }

    @DELETE
    @Path("/v1/repository")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRepository(@Context HttpHeaders headers,
                                     @QueryParam("domain") String domain,
                                     @QueryParam("domain-owner") String domainOwner,
                                     @QueryParam("repository") String repository) {
        return handle(() -> wrap("repository",
                service.deleteRepository(region(headers), domain, domainOwner, repository)));
    }

    @POST
    @Path("/v1/repository/external-connection")
    @Consumes(MediaType.WILDCARD)
    public Response associateExternalConnection(@Context HttpHeaders headers,
                                                @QueryParam("domain") String domain,
                                                @QueryParam("domain-owner") String domainOwner,
                                                @QueryParam("repository") String repository,
                                                @QueryParam("external-connection") String externalConnection) {
        return handle(() -> wrap("repository",
                service.associateExternalConnection(region(headers), domain, domainOwner, repository,
                        externalConnection)));
    }

    @DELETE
    @Path("/v1/repository/external-connection")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateExternalConnection(@Context HttpHeaders headers,
                                                   @QueryParam("domain") String domain,
                                                   @QueryParam("domain-owner") String domainOwner,
                                                   @QueryParam("repository") String repository,
                                                   @QueryParam("external-connection") String externalConnection) {
        return handle(() -> wrap("repository",
                service.disassociateExternalConnection(region(headers), domain, domainOwner, repository,
                        externalConnection)));
    }

    @GET
    @Path("/v1/repository/endpoint")
    @Consumes(MediaType.WILDCARD)
    public Response getRepositoryEndpoint(@Context HttpHeaders headers,
                                          @QueryParam("domain") String domain,
                                          @QueryParam("domain-owner") String domainOwner,
                                          @QueryParam("repository") String repository,
                                          @QueryParam("format") String format) {
        return handle(() -> Response.ok(service.getRepositoryEndpoint(
                region(headers), domain, domainOwner, repository, format)).build());
    }

    @POST
    @Path("/v1/repositories")
    @Consumes(MediaType.WILDCARD)
    public Response listRepositories(@Context HttpHeaders headers, String body) {
        return handle(() -> Response.ok(service.listRepositories(region(headers), null, null)).build());
    }

    @POST
    @Path("/v1/domain/repositories")
    @Consumes(MediaType.WILDCARD)
    public Response listRepositoriesInDomain(@Context HttpHeaders headers,
                                             @QueryParam("domain") String domain,
                                             @QueryParam("domain-owner") String domainOwner) {
        return handle(() -> Response.ok(service.listRepositories(region(headers), domain, domainOwner)).build());
    }

    @POST
    @Path("/v1/authorization-token")
    @Consumes(MediaType.WILDCARD)
    public Response getAuthorizationToken(@Context HttpHeaders headers,
                                          @QueryParam("domain") String domain,
                                          @QueryParam("domain-owner") String domainOwner,
                                          @QueryParam("duration") Long duration) {
        return handle(() -> Response.ok(service.getAuthorizationToken(
                region(headers), domain, domainOwner, duration)).build());
    }

    @POST
    @Path("/v1/package/version/publish")
    @Consumes(MediaType.WILDCARD)
    public Response publishPackageVersion(@Context HttpHeaders headers,
                                          @QueryParam("domain") String domain,
                                          @QueryParam("domain-owner") String domainOwner,
                                          @QueryParam("repository") String repository,
                                          @QueryParam("format") String format,
                                          @QueryParam("namespace") String namespace,
                                          @QueryParam("package") String packageName,
                                          @QueryParam("version") String version,
                                          @QueryParam("asset") String asset,
                                          @QueryParam("unfinished") Boolean unfinished,
                                          @HeaderParam("x-amz-content-sha256") String assetSha256,
                                          byte[] body) {
        return handle(() -> Response.ok(service.publishPackageVersion(
                region(headers), domain, domainOwner, repository, format, namespace, packageName,
                version, asset, assetSha256, unfinished, body)).build());
    }

    @GET
    @Path("/v1/package")
    @Consumes(MediaType.WILDCARD)
    public Response describePackage(@Context HttpHeaders headers,
                                    @QueryParam("domain") String domain,
                                    @QueryParam("domain-owner") String domainOwner,
                                    @QueryParam("repository") String repository,
                                    @QueryParam("format") String format,
                                    @QueryParam("namespace") String namespace,
                                    @QueryParam("package") String packageName) {
        return handle(() -> Response.ok(service.describePackage(
                region(headers), domain, domainOwner, repository, format, namespace, packageName)).build());
    }

    @GET
    @Path("/v1/package/version")
    @Consumes(MediaType.WILDCARD)
    public Response describePackageVersion(@Context HttpHeaders headers,
                                           @QueryParam("domain") String domain,
                                           @QueryParam("domain-owner") String domainOwner,
                                           @QueryParam("repository") String repository,
                                           @QueryParam("format") String format,
                                           @QueryParam("namespace") String namespace,
                                           @QueryParam("package") String packageName,
                                           @QueryParam("version") String version) {
        return handle(() -> Response.ok(service.describePackageVersion(
                region(headers), domain, domainOwner, repository, format, namespace, packageName, version)).build());
    }

    @POST
    @Path("/v1/packages")
    @Consumes(MediaType.WILDCARD)
    public Response listPackages(@Context HttpHeaders headers,
                                 @QueryParam("domain") String domain,
                                 @QueryParam("domain-owner") String domainOwner,
                                 @QueryParam("repository") String repository,
                                 @QueryParam("format") String format,
                                 @QueryParam("namespace") String namespace) {
        return handle(() -> Response.ok(service.listPackages(
                region(headers), domain, domainOwner, repository, format, namespace)).build());
    }

    @POST
    @Path("/v1/package/versions")
    @Consumes(MediaType.WILDCARD)
    public Response listPackageVersions(@Context HttpHeaders headers,
                                        @QueryParam("domain") String domain,
                                        @QueryParam("domain-owner") String domainOwner,
                                        @QueryParam("repository") String repository,
                                        @QueryParam("format") String format,
                                        @QueryParam("namespace") String namespace,
                                        @QueryParam("package") String packageName,
                                        @QueryParam("status") String status) {
        return handle(() -> Response.ok(service.listPackageVersions(
                region(headers), domain, domainOwner, repository, format, namespace, packageName, status)).build());
    }

    @POST
    @Path("/v1/package/version/assets")
    @Consumes(MediaType.WILDCARD)
    public Response listPackageVersionAssets(@Context HttpHeaders headers,
                                             @QueryParam("domain") String domain,
                                             @QueryParam("domain-owner") String domainOwner,
                                             @QueryParam("repository") String repository,
                                             @QueryParam("format") String format,
                                             @QueryParam("namespace") String namespace,
                                             @QueryParam("package") String packageName,
                                             @QueryParam("version") String version) {
        return handle(() -> Response.ok(service.listPackageVersionAssets(
                region(headers), domain, domainOwner, repository, format, namespace, packageName, version)).build());
    }

    @GET
    @Path("/v1/package/version/asset")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getPackageVersionAsset(@Context HttpHeaders headers,
                                           @QueryParam("domain") String domain,
                                           @QueryParam("domain-owner") String domainOwner,
                                           @QueryParam("repository") String repository,
                                           @QueryParam("format") String format,
                                           @QueryParam("namespace") String namespace,
                                           @QueryParam("package") String packageName,
                                           @QueryParam("version") String version,
                                           @QueryParam("asset") String asset) {
        return handle(() -> {
            CodeArtifactService.DownloadedAsset downloaded = service.getPackageVersionAsset(
                    region(headers), domain, domainOwner, repository, format, namespace, packageName, version, asset);
            return Response.ok(downloaded.content())
                    .type(MediaType.APPLICATION_OCTET_STREAM)
                    .header("X-AssetName", downloaded.name())
                    .header("X-PackageVersion", downloaded.version())
                    .header("X-PackageVersionRevision", downloaded.revision())
                    .build();
        });
    }

    @GET
    @Path("/v1/package/version/readme")
    @Consumes(MediaType.WILDCARD)
    public Response getPackageVersionReadme(@Context HttpHeaders headers,
                                            @QueryParam("domain") String domain,
                                            @QueryParam("domain-owner") String domainOwner,
                                            @QueryParam("repository") String repository,
                                            @QueryParam("format") String format,
                                            @QueryParam("namespace") String namespace,
                                            @QueryParam("package") String packageName,
                                            @QueryParam("version") String version) {
        return handle(() -> Response.ok(service.getPackageVersionReadme(
                region(headers), domain, domainOwner, repository, format, namespace, packageName, version)).build());
    }

    @POST
    @Path("/v1/package/version/dependencies")
    @Consumes(MediaType.WILDCARD)
    public Response listPackageVersionDependencies(@Context HttpHeaders headers,
                                                   @QueryParam("domain") String domain,
                                                   @QueryParam("domain-owner") String domainOwner,
                                                   @QueryParam("repository") String repository,
                                                   @QueryParam("format") String format,
                                                   @QueryParam("namespace") String namespace,
                                                   @QueryParam("package") String packageName,
                                                   @QueryParam("version") String version) {
        return handle(() -> Response.ok(service.listPackageVersionDependencies(
                region(headers), domain, domainOwner, repository, format, namespace, packageName, version)).build());
    }

    @POST
    @Path("/v1/package/versions/update_status")
    public Response updatePackageVersionsStatus(@Context HttpHeaders headers,
                                                @QueryParam("domain") String domain,
                                                @QueryParam("domain-owner") String domainOwner,
                                                @QueryParam("repository") String repository,
                                                @QueryParam("format") String format,
                                                @QueryParam("namespace") String namespace,
                                                @QueryParam("package") String packageName,
                                                String body) {
        return handle(() -> Response.ok(service.updatePackageVersionsStatus(
                region(headers), domain, domainOwner, repository, format, namespace, packageName, parse(body)))
                .build());
    }

    @POST
    @Path("/v1/package")
    public Response putPackageOriginConfiguration(@Context HttpHeaders headers,
                                                  @QueryParam("domain") String domain,
                                                  @QueryParam("domain-owner") String domainOwner,
                                                  @QueryParam("repository") String repository,
                                                  @QueryParam("format") String format,
                                                  @QueryParam("namespace") String namespace,
                                                  @QueryParam("package") String packageName,
                                                  String body) {
        return handle(() -> Response.ok(service.putPackageOriginConfiguration(
                region(headers), domain, domainOwner, repository, format, namespace, packageName, parse(body)))
                .build());
    }

    @POST
    @Path("/v1/package/versions/copy")
    public Response copyPackageVersions(@Context HttpHeaders headers,
                                        @QueryParam("domain") String domain,
                                        @QueryParam("domain-owner") String domainOwner,
                                        @QueryParam("source-repository") String sourceRepository,
                                        @QueryParam("destination-repository") String destinationRepository,
                                        @QueryParam("format") String format,
                                        @QueryParam("namespace") String namespace,
                                        @QueryParam("package") String packageName,
                                        String body) {
        return handle(() -> Response.ok(service.copyPackageVersions(
                region(headers), domain, domainOwner, sourceRepository, destinationRepository, format, namespace,
                packageName, parse(body))).build());
    }

    @POST
    @Path("/v1/package/versions/dispose")
    public Response disposePackageVersions(@Context HttpHeaders headers,
                                           @QueryParam("domain") String domain,
                                           @QueryParam("domain-owner") String domainOwner,
                                           @QueryParam("repository") String repository,
                                           @QueryParam("format") String format,
                                           @QueryParam("namespace") String namespace,
                                           @QueryParam("package") String packageName,
                                           String body) {
        return handle(() -> Response.ok(service.disposePackageVersions(
                region(headers), domain, domainOwner, repository, format, namespace, packageName, parse(body)))
                .build());
    }

    @POST
    @Path("/v1/package/versions/delete")
    public Response deletePackageVersions(@Context HttpHeaders headers,
                                          @QueryParam("domain") String domain,
                                          @QueryParam("domain-owner") String domainOwner,
                                          @QueryParam("repository") String repository,
                                          @QueryParam("format") String format,
                                          @QueryParam("namespace") String namespace,
                                          @QueryParam("package") String packageName,
                                          String body) {
        return handle(() -> Response.ok(service.deletePackageVersions(
                region(headers), domain, domainOwner, repository, format, namespace, packageName, parse(body)))
                .build());
    }

    @DELETE
    @Path("/v1/package")
    @Consumes(MediaType.WILDCARD)
    public Response deletePackage(@Context HttpHeaders headers,
                                  @QueryParam("domain") String domain,
                                  @QueryParam("domain-owner") String domainOwner,
                                  @QueryParam("repository") String repository,
                                  @QueryParam("format") String format,
                                  @QueryParam("namespace") String namespace,
                                  @QueryParam("package") String packageName) {
        return handle(() -> Response.ok(service.deletePackage(
                region(headers), domain, domainOwner, repository, format, namespace, packageName)).build());
    }

    @POST
    @Path("/v1/tags")
    @Consumes(MediaType.WILDCARD)
    public Response listTagsForResource(@Context HttpHeaders headers,
                                        @QueryParam("resourceArn") String resourceArn) {
        return handle(() -> Response.ok(service.listTagsForResource(region(headers), resourceArn)).build());
    }

    @POST
    @Path("/v1/tag")
    public Response tagResource(@Context HttpHeaders headers,
                                @QueryParam("resourceArn") String resourceArn,
                                String body) {
        return handle(() -> {
            service.tagResource(region(headers), resourceArn, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    @POST
    @Path("/v1/untag")
    public Response untagResource(@Context HttpHeaders headers,
                                  @QueryParam("resourceArn") String resourceArn,
                                  String body) {
        return handle(() -> {
            service.untagResource(region(headers), resourceArn, parse(body));
            return Response.ok(objectMapper.createObjectNode()).build();
        });
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response wrap(String field, Map<String, Object> value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(field, objectMapper.valueToTree(value));
        return Response.ok(response).build();
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

    private Response handle(Handler handler) {
        try {
            return handler.handle();
        } catch (AwsException exception) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("__type", exception.jsonType());
            node.put("message", exception.getMessage());
            if (exception.getExtendedData() != null) {
                for (Map.Entry<String, Object> entry : exception.getExtendedData().entrySet()) {
                    node.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
                }
            }
            return Response.status(exception.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", exception.jsonType())
                    .entity(node)
                    .build();
        }
    }

    @FunctionalInterface
    private interface Handler {
        Response handle();
    }
}
