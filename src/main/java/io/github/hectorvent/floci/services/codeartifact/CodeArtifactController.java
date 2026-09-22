package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AssetDownload;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationToken;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.DomainView;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PackageCoordinate;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.ResourcePolicy;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.VersionMutation;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/")
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

    // ---------------------------------------------------------------- domains

    @POST
    @Path("/v1/domain")
    public Response createDomain(@Context HttpHeaders headers, @QueryParam("domain") String domain, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        DomainView view = service.createDomain(region, domain, text(req, "encryptionKey"), readTagList(req.get("tags")));
        return ok(single("domain", domainDescription(view)));
    }

    @DELETE
    @Path("/v1/domain")
    public Response deleteDomain(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                  @QueryParam("domain-owner") String domainOwner) {
        String region = regionResolver.resolveRegion(headers);
        DomainView view = service.deleteDomain(region, domain, domainOwner);
        return ok(single("domain", domainDescription(view)));
    }

    @GET
    @Path("/v1/domain")
    public Response describeDomain(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                    @QueryParam("domain-owner") String domainOwner) {
        String region = regionResolver.resolveRegion(headers);
        DomainView view = service.describeDomain(region, domain, domainOwner);
        return ok(single("domain", domainDescription(view)));
    }

    @POST
    @Path("/v1/domains")
    public Response listDomains(@Context HttpHeaders headers, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        Integer maxResults = req.hasNonNull("maxResults") ? req.get("maxResults").asInt() : null;
        PaginatedResult<DomainView> page = service.listDomains(region, maxResults, text(req, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("domains");
        page.items().forEach(view -> items.add(domainSummary(view)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return ok(response);
    }

    @PUT
    @Path("/v1/domain/permissions/policy")
    public Response putDomainPermissionsPolicy(@Context HttpHeaders headers, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.putDomainPermissionsPolicy(region, text(req, "domain"),
                text(req, "domainOwner"), text(req, "policyDocument"), text(req, "policyRevision"));
        return ok(single("policy", resourcePolicy(policy)));
    }

    @GET
    @Path("/v1/domain/permissions/policy")
    public Response getDomainPermissionsPolicy(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                @QueryParam("domain-owner") String domainOwner) {
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.getDomainPermissionsPolicy(region, domain, domainOwner);
        return ok(single("policy", resourcePolicy(policy)));
    }

    @DELETE
    @Path("/v1/domain/permissions/policy")
    public Response deleteDomainPermissionsPolicy(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                   @QueryParam("domain-owner") String domainOwner,
                                                   @QueryParam("policy-revision") String policyRevision) {
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.deleteDomainPermissionsPolicy(region, domain, domainOwner, policyRevision);
        return ok(single("policy", resourcePolicy(policy)));
    }

    // ------------------------------------------------------------ repositories

    @POST
    @Path("/v1/repository")
    public Response createRepository(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                      @QueryParam("domain-owner") String domainOwner,
                                      @QueryParam("repository") String repository, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.createRepository(region, domain, domainOwner, repository,
                text(req, "description"), readUpstreams(req.get("upstreams")), readTagList(req.get("tags")));
        return ok(single("repository", repositoryDescription(r)));
    }

    @DELETE
    @Path("/v1/repository")
    public Response deleteRepository(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                      @QueryParam("domain-owner") String domainOwner,
                                      @QueryParam("repository") String repository) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.deleteRepository(region, domain, domainOwner, repository);
        return ok(single("repository", repositoryDescription(r)));
    }

    @GET
    @Path("/v1/repository")
    public Response describeRepository(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                        @QueryParam("domain-owner") String domainOwner,
                                        @QueryParam("repository") String repository) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.describeRepository(region, domain, domainOwner, repository);
        return ok(single("repository", repositoryDescription(r)));
    }

    @PUT
    @Path("/v1/repository")
    public Response updateRepository(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                      @QueryParam("domain-owner") String domainOwner,
                                      @QueryParam("repository") String repository, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.updateRepository(region, domain, domainOwner, repository,
                text(req, "description"), req.has("upstreams") ? readUpstreams(req.get("upstreams")) : null);
        return ok(single("repository", repositoryDescription(r)));
    }

    @POST
    @Path("/v1/repositories")
    public Response listRepositories(@Context HttpHeaders headers,
                                      @QueryParam("repository-prefix") String repositoryPrefix,
                                      @QueryParam("max-results") String maxResults,
                                      @QueryParam("next-token") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<CodeArtifactRepository> page = service.listRepositories(region, repositoryPrefix,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        return ok(repositorySummaryList(page));
    }

    @POST
    @Path("/v1/domain/repositories")
    public Response listRepositoriesInDomain(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                              @QueryParam("domain-owner") String domainOwner,
                                              @QueryParam("administrator-account") String administratorAccount,
                                              @QueryParam("repository-prefix") String repositoryPrefix,
                                              @QueryParam("max-results") String maxResults,
                                              @QueryParam("next-token") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<CodeArtifactRepository> page = service.listRepositoriesInDomain(region, domain, domainOwner,
                administratorAccount, repositoryPrefix, Pagination.parseMaxResults(maxResults, "ValidationException"),
                nextToken);
        return ok(repositorySummaryList(page));
    }

    @GET
    @Path("/v1/repository/endpoint")
    public Response getRepositoryEndpoint(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                           @QueryParam("domain-owner") String domainOwner,
                                           @QueryParam("repository") String repository,
                                           @QueryParam("format") String format,
                                           @QueryParam("endpointType") String endpointType) {
        String region = regionResolver.resolveRegion(headers);
        String endpoint = service.getRepositoryEndpoint(region, domain, domainOwner, repository, format,
                endpointType);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("repositoryEndpoint", endpoint);
        return ok(response);
    }

    @PUT
    @Path("/v1/repository/permissions/policy")
    public Response putRepositoryPermissionsPolicy(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                     @QueryParam("domain-owner") String domainOwner,
                                                     @QueryParam("repository") String repository, String body) {
        JsonNode req = readTree(body);
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.putRepositoryPermissionsPolicy(region, domain, domainOwner, repository,
                text(req, "policyDocument"), text(req, "policyRevision"));
        return ok(single("policy", resourcePolicy(policy)));
    }

    @GET
    @Path("/v1/repository/permissions/policy")
    public Response getRepositoryPermissionsPolicy(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                     @QueryParam("domain-owner") String domainOwner,
                                                     @QueryParam("repository") String repository) {
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.getRepositoryPermissionsPolicy(region, domain, domainOwner, repository);
        return ok(single("policy", resourcePolicy(policy)));
    }

    @DELETE
    @Path("/v1/repository/permissions/policies")
    public Response deleteRepositoryPermissionsPolicy(@Context HttpHeaders headers,
                                                        @QueryParam("domain") String domain,
                                                        @QueryParam("domain-owner") String domainOwner,
                                                        @QueryParam("repository") String repository,
                                                        @QueryParam("policy-revision") String policyRevision) {
        String region = regionResolver.resolveRegion(headers);
        ResourcePolicy policy = service.deleteRepositoryPermissionsPolicy(region, domain, domainOwner, repository,
                policyRevision);
        return ok(single("policy", resourcePolicy(policy)));
    }

    @POST
    @Path("/v1/repository/external-connection")
    public Response associateExternalConnection(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                  @QueryParam("domain-owner") String domainOwner,
                                                  @QueryParam("repository") String repository,
                                                  @QueryParam("external-connection") String externalConnection) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.associateExternalConnection(region, domain, domainOwner, repository,
                externalConnection);
        return ok(single("repository", repositoryDescription(r)));
    }

    @DELETE
    @Path("/v1/repository/external-connection")
    public Response disassociateExternalConnection(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                                     @QueryParam("domain-owner") String domainOwner,
                                                     @QueryParam("repository") String repository,
                                                     @QueryParam("external-connection") String externalConnection) {
        String region = regionResolver.resolveRegion(headers);
        CodeArtifactRepository r = service.disassociateExternalConnection(region, domain, domainOwner, repository,
                externalConnection);
        return ok(single("repository", repositoryDescription(r)));
    }

    @POST
    @Path("/v1/authorization-token")
    public Response getAuthorizationToken(@Context HttpHeaders headers, @QueryParam("domain") String domain,
                                           @QueryParam("domain-owner") String owner,
                                           @QueryParam("duration") String duration) {
        Long seconds = null;
        if (duration != null) {
            try {
                seconds = Long.parseLong(duration);
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationException", "duration must be an integer.", 400);
            }
        }
        AuthorizationToken token = service.getAuthorizationToken(regionResolver.resolveRegion(headers), domain,
                owner, seconds);
        return Response.ok(Map.of("authorizationToken", token.authorizationToken(), "expiration", token.expiration()))
                .build();
    }

    public static class PackageQuery {
        @QueryParam("domain") public String domain;
        @QueryParam("domain-owner") public String owner;
        @QueryParam("repository") public String repository;
        @QueryParam("format") public String format;
        @QueryParam("namespace") public String namespace;
        @QueryParam("package") public String name;
        @QueryParam("version") public String version;
        @QueryParam("max-results") public String maxResults;
        @QueryParam("next-token") public String nextToken;

        PackageCoordinate coordinate(String region) {
            return new PackageCoordinate(region, domain, owner, repository, format, namespace, name);
        }

        Integer pageSize() {
            return Pagination.parseMaxResults(maxResults, "ValidationException");
        }
    }

    @POST
    @Path("/v1/package/version/publish")
    @Consumes(MediaType.WILDCARD)
    public Response publishPackageVersion(@Context HttpHeaders headers, @BeanParam PackageQuery query,
                                           @QueryParam("asset") String asset,
                                           @QueryParam("unfinished") String unfinished,
                                           @HeaderParam("x-amz-content-sha256") String hash, byte[] body) {
        if (unfinished != null && !"true".equals(unfinished) && !"false".equals(unfinished)) {
            throw new AwsException("ValidationException", "unfinished must be a boolean.", 400);
        }
        return Response.ok(service.publishPackageVersion(query.coordinate(regionResolver.resolveRegion(headers)),
                query.version, asset, body, hash, "true".equals(unfinished))).build();
    }

    @GET
    @Path("/v1/package")
    public Response describePackage(@Context HttpHeaders headers, @BeanParam PackageQuery query) {
        return Response.ok(service.describePackage(query.coordinate(regionResolver.resolveRegion(headers)))).build();
    }

    @DELETE
    @Path("/v1/package")
    public Response deletePackage(@Context HttpHeaders headers, @BeanParam PackageQuery query) {
        return Response.ok(service.deletePackage(query.coordinate(regionResolver.resolveRegion(headers)))).build();
    }

    @POST
    @Path("/v1/package")
    public Response putPackageOriginConfiguration(@Context HttpHeaders headers, @BeanParam PackageQuery query,
                                                   String body) {
        JsonNode request = readTree(body);
        return Response.ok(service.putPackageOriginConfiguration(query.coordinate(regionResolver.resolveRegion(headers)),
                stringMap(request.get("restrictions")))).build();
    }

    @GET
    @Path("/v1/package/version")
    public Response describePackageVersion(@Context HttpHeaders headers, @BeanParam PackageQuery query) {
        return Response.ok(service.describePackageVersion(query.coordinate(regionResolver.resolveRegion(headers)),
                query.version)).build();
    }

    @POST
    @Path("/v1/packages")
    public Response listPackages(@Context HttpHeaders headers, @BeanParam PackageQuery query,
                                  @QueryParam("package-prefix") String prefix, @QueryParam("publish") String publish,
                                  @QueryParam("upstream") String upstream) {
        return Response.ok(service.listPackages(query.coordinate(regionResolver.resolveRegion(headers)), prefix,
                publish, upstream, query.pageSize(), query.nextToken)).build();
    }

    @POST
    @Path("/v1/package/versions")
    public Response listPackageVersions(@Context HttpHeaders headers, @BeanParam PackageQuery query,
                                         @QueryParam("status") String status, @QueryParam("originType") String originType,
                                         @QueryParam("sortBy") String sortBy) {
        return Response.ok(service.listPackageVersions(query.coordinate(regionResolver.resolveRegion(headers)), status,
                originType, sortBy, query.pageSize(), query.nextToken)).build();
    }

    @POST
    @Path("/v1/package/version/assets")
    public Response listPackageVersionAssets(@Context HttpHeaders headers, @BeanParam PackageQuery query) {
        return Response.ok(service.listPackageVersionAssets(query.coordinate(regionResolver.resolveRegion(headers)),
                query.version, query.pageSize(), query.nextToken)).build();
    }

    @GET
    @Path("/v1/package/version/asset")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getPackageVersionAsset(@Context HttpHeaders headers, @BeanParam PackageQuery query,
                                            @QueryParam("asset") String asset, @QueryParam("revision") String revision) {
        AssetDownload download = service.getPackageVersionAsset(query.coordinate(regionResolver.resolveRegion(headers)),
                query.version, asset, revision);
        return Response.ok(download.content(), MediaType.APPLICATION_OCTET_STREAM)
                .header("X-AssetName", download.name()).header("X-PackageVersion", download.version())
                .header("X-PackageVersionRevision", download.revision()).build();
    }

    @GET
    @Path("/v1/package/version/readme")
    public Response getPackageVersionReadme(@Context HttpHeaders headers, @BeanParam PackageQuery query) {
        return Response.ok(service.getPackageVersionReadme(query.coordinate(regionResolver.resolveRegion(headers)),
                query.version)).build();
    }

    @POST
    @Path("/v1/package/version/dependencies")
    public Response listPackageVersionDependencies(@Context HttpHeaders headers, @BeanParam PackageQuery query) {
        return Response.ok(service.listPackageVersionDependencies(query.coordinate(regionResolver.resolveRegion(headers)),
                query.version)).build();
    }

    @POST
    @Path("/v1/package/versions/update_status")
    public Response updatePackageVersionsStatus(@Context HttpHeaders headers, @BeanParam PackageQuery query, String body) {
        return Response.ok(service.mutatePackageVersions(query.coordinate(regionResolver.resolveRegion(headers)),
                "status", versionMutation(readTree(body)))).build();
    }

    @POST
    @Path("/v1/package/versions/dispose")
    public Response disposePackageVersions(@Context HttpHeaders headers, @BeanParam PackageQuery query, String body) {
        return Response.ok(service.mutatePackageVersions(query.coordinate(regionResolver.resolveRegion(headers)),
                "dispose", versionMutation(readTree(body)))).build();
    }

    @POST
    @Path("/v1/package/versions/delete")
    public Response deletePackageVersions(@Context HttpHeaders headers, @BeanParam PackageQuery query, String body) {
        return Response.ok(service.mutatePackageVersions(query.coordinate(regionResolver.resolveRegion(headers)),
                "delete", versionMutation(readTree(body)))).build();
    }

    @POST
    @Path("/v1/package/versions/copy")
    public Response copyPackageVersions(@Context HttpHeaders headers, @BeanParam PackageQuery query,
                                         @QueryParam("source-repository") String source,
                                         @QueryParam("destination-repository") String destination, String body) {
        PackageCoordinate coordinate = new PackageCoordinate(regionResolver.resolveRegion(headers), query.domain,
                query.owner, source, query.format, query.namespace, query.name);
        return Response.ok(service.copyPackageVersions(coordinate, destination, versionMutation(readTree(body)))).build();
    }

    private VersionMutation versionMutation(JsonNode request) {
        List<String> versions = null;
        if (request.hasNonNull("versions")) {
            if (!request.get("versions").isArray()) {
                throw new AwsException("ValidationException", "versions must be an array.", 400);
            }
            versions = new ArrayList<>();
            for (JsonNode version : request.get("versions")) {
                if (!version.isTextual()) {
                    throw new AwsException("ValidationException", "versions must contain strings.", 400);
                }
                versions.add(version.textValue());
            }
        }
        return new VersionMutation(versions, stringMap(request.get("versionRevisions")), text(request, "expectedStatus"),
                text(request, "targetStatus"), booleanValue(request, "allowOverwrite"),
                booleanValue(request, "includeFromUpstream"));
    }

    private boolean booleanValue(JsonNode request, String field) {
        if (request.hasNonNull(field) && !request.get(field).isBoolean()) {
            throw new AwsException("ValidationException", field + " must be a boolean.", 400);
        }
        return request.path(field).asBoolean(false);
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (!node.isObject()) {
            throw new AwsException("ValidationException", "Expected a string map.", 400);
        }
        node.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new AwsException("ValidationException", "Expected a string map.", 400);
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return result;
    }

    // -------------------------------------------------------------------- tags

    @POST
    @Path("/v1/tag")
    public Response tagResource(@QueryParam("resourceArn") String resourceArn, String body) {
        JsonNode req = readTree(body);
        service.tagResource(resourceArn, readTagList(req.get("tags")));
        return ok(objectMapper.createObjectNode());
    }

    @POST
    @Path("/v1/untag")
    public Response untagResource(@QueryParam("resourceArn") String resourceArn, String body) {
        JsonNode req = readTree(body);
        List<String> keys = new ArrayList<>();
        if (req.hasNonNull("tagKeys") && req.get("tagKeys").isArray()) {
            req.get("tagKeys").forEach(n -> keys.add(n.asText()));
        }
        service.untagResource(resourceArn, keys);
        return ok(objectMapper.createObjectNode());
    }

    // ListTagsForResource (POST /v1/tags?resourceArn=) is handled by V1TagsController via
    // CodeArtifactTagHandler; see their javadoc.

    // ----------------------------------------------------------------- helpers

    private ObjectNode domainDescription(DomainView view) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", view.domain().getArn());
        node.put("assetSizeBytes", 0L);
        node.put("createdTime", view.domain().getCreatedTime());
        node.put("encryptionKey", view.domain().getEncryptionKey());
        node.put("name", view.domain().getName());
        node.put("owner", view.domain().getOwner());
        node.put("repositoryCount", view.repositoryCount());
        // AWS's internal per-domain bucket naming is not publicly documented; this is a synthesized placeholder.
        node.put("s3BucketArn", "arn:aws:s3:::codeartifact-" + view.domain().getRegion() + "-" + view.domain().getOwner());
        node.put("status", "Active");
        return node;
    }

    private ObjectNode domainSummary(DomainView view) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", view.domain().getName());
        node.put("owner", view.domain().getOwner());
        node.put("arn", view.domain().getArn());
        node.put("status", "Active");
        node.put("createdTime", view.domain().getCreatedTime());
        node.put("encryptionKey", view.domain().getEncryptionKey());
        return node;
    }

    private ObjectNode repositoryDescription(CodeArtifactRepository r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("administratorAccount", r.getAdministratorAccount());
        node.put("arn", r.getArn());
        node.put("createdTime", r.getCreatedTime());
        if (r.getDescription() != null) {
            node.put("description", r.getDescription());
        }
        node.put("domainName", r.getDomainName());
        node.put("domainOwner", r.getDomainOwner());
        ArrayNode connections = node.putArray("externalConnections");
        for (ExternalConnection ec : r.getExternalConnections()) {
            ObjectNode c = objectMapper.createObjectNode();
            c.put("externalConnectionName", ec.getExternalConnectionName());
            c.put("packageFormat", ec.getPackageFormat());
            c.put("status", ec.getStatus());
            connections.add(c);
        }
        node.put("name", r.getName());
        ArrayNode upstreams = node.putArray("upstreams");
        for (String upstream : r.getUpstreams()) {
            ObjectNode u = objectMapper.createObjectNode();
            u.put("repositoryName", upstream);
            upstreams.add(u);
        }
        return node;
    }

    private ObjectNode repositorySummary(CodeArtifactRepository r) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", r.getName());
        node.put("administratorAccount", r.getAdministratorAccount());
        node.put("domainName", r.getDomainName());
        node.put("domainOwner", r.getDomainOwner());
        node.put("arn", r.getArn());
        if (r.getDescription() != null) {
            node.put("description", r.getDescription());
        }
        node.put("createdTime", r.getCreatedTime());
        return node;
    }

    private ObjectNode repositorySummaryList(PaginatedResult<CodeArtifactRepository> page) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("repositories");
        page.items().forEach(r -> items.add(repositorySummary(r)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    private ObjectNode resourcePolicy(ResourcePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceArn", policy.resourceArn());
        node.put("revision", policy.revision());
        node.put("document", policy.document());
        return node;
    }

    private ObjectNode single(String field, ObjectNode value) {
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set(field, value);
        return wrapper;
    }

    private Response ok(ObjectNode body) {
        return Response.ok(body).build();
    }

    private Map<String, String> readTagList(JsonNode tagsArray) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsArray == null || tagsArray.isNull()) {
            return tags;
        }
        if (!tagsArray.isArray()) {
            throw new AwsException("ValidationException", "tags must be an array of {key, value} objects.", 400);
        }
        tagsArray.forEach(n -> tags.put(text(n, "key"), text(n, "value")));
        return tags;
    }

    private List<String> readUpstreams(JsonNode upstreamsArray) {
        List<String> upstreams = new ArrayList<>();
        if (upstreamsArray == null || upstreamsArray.isNull()) {
            return upstreams;
        }
        if (!upstreamsArray.isArray()) {
            throw new AwsException("ValidationException", "upstreams must be an array of {repositoryName} objects.",
                    400);
        }
        upstreamsArray.forEach(n -> upstreams.add(text(n, "repositoryName")));
        return upstreams;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
