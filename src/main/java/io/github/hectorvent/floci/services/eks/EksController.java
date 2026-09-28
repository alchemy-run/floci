package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.AssociatedAccessPolicy;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateFargateProfileRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.DescribeIdentityProviderConfigRequest;
import io.github.hectorvent.floci.services.eks.model.FargateProfile;
import io.github.hectorvent.floci.services.eks.model.InsightSummary;
import io.github.hectorvent.floci.services.eks.model.ListInsightsRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.Update;
import io.github.hectorvent.floci.services.eks.model.UpdateAccessEntryRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateNodegroupConfigRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateNodegroupVersionRequest;
import io.github.hectorvent.floci.services.eks.model.UpdatePodIdentityAssociationRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * EKS REST-JSON controller.
 *
 * <p>
 * EKS uses standard HTTP verbs with JSON bodies - not JSON 1.1 (X-Amz-Target)
 * or Query protocol.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksController {

    private final EksService eksService;
    private final EksAccessEntryService accessEntries;
    private final EksPodIdentityAssociationService podIdentityAssociations;
    private final EksAddonService addons;
    private final EksCatalogService catalog;
    private final EksInsightsService insights;

    @Inject
    public EksController(EksService eksService, EksAccessEntryService accessEntries,
                         EksPodIdentityAssociationService podIdentityAssociations,
                         EksAddonService addons, EksCatalogService catalog, EksInsightsService insights) {
        this.eksService = eksService;
        this.accessEntries = accessEntries;
        this.podIdentityAssociations = podIdentityAssociations;
        this.addons = addons;
        this.catalog = catalog;
        this.insights = insights;
    }

    @GET
    @Path("/access-policies")
    public Response listAccessPolicies(@QueryParam("maxResults") String maxResults,
                                       @QueryParam("nextToken") String nextToken) {
        return Response.ok(catalog.listAccessPolicies(maxResults, nextToken)).build();
    }

    @GET
    @Path("/cluster-versions")
    public Response describeClusterVersions(@QueryParam("clusterType") String clusterType,
                                             @QueryParam("maxResults") String maxResults,
                                             @QueryParam("nextToken") String nextToken,
                                             @QueryParam("defaultOnly") String defaultOnly,
                                             @QueryParam("includeAll") String includeAll,
                                             @QueryParam("clusterVersions") List<String> clusterVersions,
                                             @QueryParam("status") String status,
                                             @QueryParam("versionStatus") String versionStatus) {
        return Response.ok(catalog.describeClusterVersions(clusterType, maxResults, nextToken,
                defaultOnly, includeAll, clusterVersions, status, versionStatus)).build();
    }

    @GET
    @Path("/addons/supported-versions")
    public Response describeAddonVersions(@QueryParam("addonName") String addonName,
                                         @QueryParam("kubernetesVersion") String kubernetesVersion,
                                         @QueryParam("types") List<String> types,
                                         @QueryParam("publishers") List<String> publishers,
                                         @QueryParam("owners") List<String> owners,
                                         @QueryParam("maxResults") String maxResults,
                                         @QueryParam("nextToken") String nextToken) {
        return Response.ok(catalog.describeAddonVersions(addonName, kubernetesVersion,
                types, publishers, owners, maxResults, nextToken)).build();
    }

    @GET
    @Path("/addons/configuration-schemas")
    public Response describeAddonConfiguration(@QueryParam("addonName") String addonName,
                                               @QueryParam("addonVersion") String addonVersion) {
        return Response.ok(catalog.describeAddonConfiguration(addonName, addonVersion)).build();
    }

    @POST
    @Path("/clusters")
    public Response createCluster(CreateClusterRequest request) {
        Cluster cluster = eksService.createCluster(request);
        return Response.ok(Map.of("cluster", toClusterResponse(cluster))).build();
    }

    @GET
    @Path("/clusters")
    public Response listClusters() {
        List<String> clusterNames = eksService.listClusters();
        return Response.ok(Map.of("clusters", clusterNames)).build();
    }

    @GET
    @Path("/clusters/{name}")
    public Response describeCluster(@PathParam("name") String name) {
        Cluster cluster = eksService.describeCluster(name);
        return Response.ok(Map.of("cluster", toClusterResponse(cluster))).build();
    }

    @DELETE
    @Path("/clusters/{name}")
    public Response deleteCluster(@PathParam("name") String name) {
        Cluster cluster = eksService.deleteCluster(name);
        return Response.ok(Map.of("cluster", toClusterResponse(cluster))).build();
    }

    /**
     * Sanitizes the cluster model before emitting it as an AWS API response.
     * explicitVersion is internal Floci metadata used for container image resolution
     * across restarts and must not be included on the wire. Clearing it on a copy
     * causes Jackson to omit the property due to @JsonInclude(NON_DEFAULT).
     */
    private Cluster toClusterResponse(Cluster cluster) {
        if (cluster == null || !cluster.isExplicitVersion()) {
            return cluster;
        }
        Cluster response = cluster.copy();
        response.setExplicitVersion(false);
        return response;
    }

    // Keep these concrete EKS resource paths declared explicitly so they outrank
    // the S3 catch-all route; see issue #1137.
    @POST
    @Path("/clusters/{name}/node-groups")
    public Response createNodeGroup(@PathParam("name") String name, CreateNodeGroupRequest request) {
        Nodegroup nodeGroup = eksService.createNodeGroup(name, request);
        return Response.ok(Map.of("nodegroup", nodeGroup)).build();
    }

    @GET
    @Path("/clusters/{name}/node-groups")
    public Response listNodeGroups(@PathParam("name") String name) {
        List<String> nodeGroupNames = eksService.listNodeGroups(name);
        return Response.ok(Map.of("nodegroups", nodeGroupNames)).build();
    }

    @GET
    @Path("/clusters/{name}/node-groups/{nodegroupName}")
    public Response describeNodeGroup(@PathParam("name") String name,
            @PathParam("nodegroupName") String nodegroupName) {
        Nodegroup nodeGroup = eksService.describeNodeGroup(name, nodegroupName);
        return Response.ok(Map.of("nodegroup", nodeGroup)).build();
    }

    @POST
    @Path("/clusters/{name}/node-groups/{nodegroupName}/update-config")
    public Response updateNodegroupConfig(@PathParam("name") String name,
            @PathParam("nodegroupName") String nodegroupName, UpdateNodegroupConfigRequest request) {
        Update update = eksService.updateNodegroupConfig(name, nodegroupName, request);
        return Response.ok(Map.of("update", update)).build();
    }

    @POST
    @Path("/clusters/{name}/node-groups/{nodegroupName}/update-version")
    public Response updateNodegroupVersion(@PathParam("name") String name,
            @PathParam("nodegroupName") String nodegroupName, UpdateNodegroupVersionRequest request) {
        Update update = eksService.updateNodegroupVersion(name, nodegroupName, request);
        return Response.ok(Map.of("update", update)).build();
    }

    @DELETE
    @Path("/clusters/{name}/node-groups/{nodegroupName}")
    public Response deleteNodeGroup(@PathParam("name") String name,
            @PathParam("nodegroupName") String nodegroupName) {
        Nodegroup nodeGroup = eksService.deleteNodeGroup(name, nodegroupName);
        return Response.ok(Map.of("nodegroup", nodeGroup)).build();
    }

    @POST
    @Path("/clusters/{name}/fargate-profiles")
    public Response createFargateProfile(@PathParam("name") String name, CreateFargateProfileRequest request) {
        FargateProfile profile = eksService.createFargateProfile(name, request);
        return Response.ok(Map.of("fargateProfile", profile)).build();
    }

    @GET
    @Path("/clusters/{name}/fargate-profiles")
    public Response listFargateProfiles(@PathParam("name") String name) {
        List<String> profileNames = eksService.listFargateProfiles(name);
        return Response.ok(Map.of("fargateProfileNames", profileNames)).build();
    }

    @GET
    @Path("/clusters/{name}/fargate-profiles/{fargateProfileName}")
    public Response describeFargateProfile(@PathParam("name") String name,
            @PathParam("fargateProfileName") String fargateProfileName) {
        FargateProfile profile = eksService.describeFargateProfile(name, fargateProfileName);
        return Response.ok(Map.of("fargateProfile", profile)).build();
    }

    @DELETE
    @Path("/clusters/{name}/fargate-profiles/{fargateProfileName}")
    public Response deleteFargateProfile(@PathParam("name") String name,
            @PathParam("fargateProfileName") String fargateProfileName) {
        FargateProfile profile = eksService.deleteFargateProfile(name, fargateProfileName);
        return Response.ok(Map.of("fargateProfile", profile)).build();
    }

    @POST
    @Path("/clusters/{name}/access-entries")
    public Response createAccessEntry(@PathParam("name") String name, CreateAccessEntryRequest request) {
        return Response.ok(Map.of("accessEntry", accessEntries.create(eksService.describeCluster(name), request))).build();
    }

    @GET
    @Path("/clusters/{name}/access-entries")
    public Response listAccessEntries(@PathParam("name") String name,
                                     @QueryParam("maxResults") String maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        EksAccessEntryService.Page page = accessEntries.list(eksService.describeCluster(name),
                Pagination.parseMaxResults(maxResults, "InvalidParameterException"), nextToken);
        return Response.ok(page.nextToken() == null ? Map.of("accessEntries", page.accessEntries())
                : Map.of("accessEntries", page.accessEntries(), "nextToken", page.nextToken())).build();
    }

    @GET
    @Path("/clusters/{name}/access-entries/{principalArn: .+}")
    public Response describeAccessEntry(@PathParam("name") String name, @PathParam("principalArn") String principalArn) {
        return Response.ok(Map.of("accessEntry", accessEntries.describe(eksService.describeCluster(name), principalArn))).build();
    }

    // Principal and policy ARNs contain '/', so their labels match greedily whether the client
    // percent-encodes them (%2F) or not.
    @POST
    @Path("/clusters/{name}/access-entries/{principalArn: .+}")
    public Response updateAccessEntry(@PathParam("name") String name, @PathParam("principalArn") String principalArn,
                                      UpdateAccessEntryRequest request) {
        return Response.ok(Map.of("accessEntry",
                accessEntries.update(eksService.describeCluster(name), principalArn, request))).build();
    }

    @GET
    @Path("/clusters/{name}/access-entries/{principalArn: .+}/access-policies")
    public Response listAssociatedAccessPolicies(@PathParam("name") String name,
                                                 @PathParam("principalArn") String principalArn,
                                                 @QueryParam("maxResults") String maxResults,
                                                 @QueryParam("nextToken") String nextToken) {
        Integer limit = Pagination.parseMaxResults(maxResults, "InvalidRequestException");
        PaginatedResult<AssociatedAccessPolicy> page = Pagination.paginate(
                accessEntries.listAssociatedAccessPolicies(eksService.describeCluster(name), principalArn),
                AssociatedAccessPolicy::policyArn, limit, nextToken, 100, "InvalidRequestException");
        return Response.ok(page.nextToken() == null
                ? Map.of("clusterName", name, "principalArn", principalArn,
                        "associatedAccessPolicies", page.items())
                : Map.of("clusterName", name, "principalArn", principalArn,
                        "associatedAccessPolicies", page.items(), "nextToken", page.nextToken())).build();
    }

    @POST
    @Path("/clusters/{name}/access-entries/{principalArn: .+}/access-policies")
    public Response associateAccessPolicy(@PathParam("name") String name,
                                          @PathParam("principalArn") String principalArn,
                                          AssociateAccessPolicyRequest request) {
        AssociatedAccessPolicy policy = accessEntries.associateAccessPolicy(eksService.describeCluster(name),
                principalArn, request);
        return Response.ok(Map.of("clusterName", name, "principalArn", principalArn,
                "associatedAccessPolicy", policy)).build();
    }

    @DELETE
    @Path("/clusters/{name}/access-entries/{principalArn: .+}/access-policies/{policyArn: .+}")
    public Response disassociateAccessPolicy(@PathParam("name") String name,
                                             @PathParam("principalArn") String principalArn,
                                             @PathParam("policyArn") String policyArn) {
        accessEntries.disassociateAccessPolicy(eksService.describeCluster(name), principalArn, policyArn);
        return Response.ok(Map.of()).build();
    }

    @DELETE
    @Path("/clusters/{name}/access-entries/{principalArn: .+}")
    public Response deleteAccessEntry(@PathParam("name") String name, @PathParam("principalArn") String principalArn) {
        accessEntries.delete(eksService.describeCluster(name), principalArn);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/clusters/{name}/addons")
    public Response createAddon(@PathParam("name") String name, CreateAddonRequest request) {
        Cluster cluster = eksService.describeCluster(name);
        Addon addon = addons.create(cluster, request);
        return Response.ok(Map.of("addon", addon)).build();
    }

    @GET
    @Path("/clusters/{name}/addons")
    public Response listAddons(@PathParam("name") String name,
                               @QueryParam("maxResults") String maxResults,
                               @QueryParam("nextToken") String nextToken) {
        Cluster cluster = eksService.describeCluster(name);
        EksAddonService.AddonNamesPage page = addons.list(cluster,
                Pagination.parseMaxResults(maxResults, "InvalidParameterException"), nextToken);
        return Response.ok(page.nextToken() == null
                ? Map.of("addons", page.addons())
                : Map.of("addons", page.addons(), "nextToken", page.nextToken())).build();
    }

    @GET
    @Path("/clusters/{name}/addons/{addonName}")
    public Response describeAddon(@PathParam("name") String name,
                                  @PathParam("addonName") String addonName) {
        Cluster cluster = eksService.describeCluster(name);
        Addon addon = addons.describe(cluster, addonName);
        return Response.ok(Map.of("addon", addon)).build();
    }

    @POST
    @Path("/clusters/{name}/addons/{addonName}/update")
    public Response updateAddon(@PathParam("name") String name,
                                @PathParam("addonName") String addonName,
                                UpdateAddonRequest request) {
        Cluster cluster = eksService.describeCluster(name);
        Update update = addons.update(cluster, addonName, request);
        return Response.ok(Map.of("update", update)).build();
    }

    @GET
    @Path("/clusters/{name}/updates")
    public Response listUpdates(@PathParam("name") String name,
                                @QueryParam("nodegroupName") String nodegroupName,
                                @QueryParam("addonName") String addonName,
                                @QueryParam("capabilityName") String capabilityName,
                                @QueryParam("maxResults") String maxResults,
                                @QueryParam("nextToken") String nextToken) {
        PaginatedResult<String> page = eksService.listUpdates(name, nodegroupName, addonName, capabilityName,
                Pagination.parseMaxResults(maxResults, "InvalidParameterException"), nextToken);
        return Response.ok(page.nextToken() == null
                ? Map.of("updateIds", page.items())
                : Map.of("updateIds", page.items(), "nextToken", page.nextToken())).build();
    }

    @POST
    @Path("/clusters/{name}/insights")
    public Response listInsights(@PathParam("name") String name, ListInsightsRequest request) {
        PaginatedResult<InsightSummary> page = insights.list(eksService.describeCluster(name), request);
        return Response.ok(page.nextToken() == null
                ? Map.of("insights", page.items())
                : Map.of("insights", page.items(), "nextToken", page.nextToken())).build();
    }

    @GET
    @Path("/clusters/{name}/insights/{id}")
    public Response describeInsight(@PathParam("name") String name, @PathParam("id") String id) {
        return Response.ok(Map.of("insight", insights.describe(eksService.describeCluster(name), id))).build();
    }

    @POST
    @Path("/clusters/{name}/insights-refresh")
    @Consumes(MediaType.WILDCARD)
    public Response startInsightsRefresh(@PathParam("name") String name) {
        return Response.ok(insights.startRefresh(eksService.describeCluster(name))).build();
    }

    @GET
    @Path("/clusters/{name}/insights-refresh")
    public Response describeInsightsRefresh(@PathParam("name") String name) {
        return Response.ok(insights.describeRefresh(eksService.describeCluster(name))).build();
    }

    @GET
    @Path("/clusters/{name}/capabilities")
    public Response listCapabilities(@PathParam("name") String name,
                                     @QueryParam("maxResults") String maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        Pagination.parseMaxResults(maxResults, "InvalidParameterException");
        eksService.describeCluster(name);
        return Response.ok(Map.of("capabilities", List.of())).build();
    }

    @GET
    @Path("/clusters/{name}/capabilities/{capabilityName}")
    public Response describeCapability(@PathParam("name") String name,
                                       @PathParam("capabilityName") String capabilityName) {
        eksService.describeCapability(name, capabilityName);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/clusters/{name}/identity-provider-configs/describe")
    public Response describeIdentityProviderConfig(@PathParam("name") String name,
                                                   DescribeIdentityProviderConfigRequest request) {
        DescribeIdentityProviderConfigRequest.IdentityProviderConfig config =
                request == null ? null : request.identityProviderConfig();
        eksService.describeIdentityProviderConfig(name, config == null ? null : config.type(),
                config == null ? null : config.name());
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path("/clusters/{name}/updates/{updateId}")
    public Response describeUpdate(@PathParam("name") String name,
                                   @PathParam("updateId") String updateId,
                                   @QueryParam("addonName") String addonName,
                                   @QueryParam("nodegroupName") String nodegroupName) {
        if (nodegroupName != null && !nodegroupName.isBlank()) {
            return Response.ok(Map.of("update",
                    eksService.describeNodegroupUpdate(name, nodegroupName, updateId))).build();
        }
        Cluster cluster = eksService.describeCluster(name);
        Update update = addons.describeUpdate(cluster, updateId, addonName);
        return Response.ok(Map.of("update", update)).build();
    }

    @DELETE
    @Path("/clusters/{name}/addons/{addonName}")
    public Response deleteAddon(@PathParam("name") String name,
                                @PathParam("addonName") String addonName,
                                @QueryParam("preserve") Boolean preserve) {
        Cluster cluster = eksService.describeCluster(name);
        Addon addon = addons.delete(cluster, addonName, Boolean.TRUE.equals(preserve));
        return Response.ok(Map.of("addon", addon)).build();
    }

    @GET
    @Path("/clusters/{name}/identity-provider-configs")
    public Response listIdentityProviderConfigs(@PathParam("name") String name) {
        eksService.describeCluster(name);
        return Response.ok(Map.of("identityProviderConfigs", List.of())).build();
    }

    @POST
    @Path("/clusters/{name}/pod-identity-associations")
    public Response createPodIdentityAssociation(@PathParam("name") String name,
                                                 CreatePodIdentityAssociationRequest request) {
        Cluster cluster = eksService.describeCluster(name);
        PodIdentityAssociation association = podIdentityAssociations.create(cluster, request);
        return Response.ok(Map.of("association", association)).build();
    }

    @GET
    @Path("/clusters/{name}/pod-identity-associations")
    public Response listPodIdentityAssociations(@PathParam("name") String name,
                                                @QueryParam("namespace") String namespace,
                                                @QueryParam("serviceAccount") String serviceAccount,
                                                @QueryParam("maxResults") String maxResults,
                                                @QueryParam("nextToken") String nextToken) {
        Cluster cluster = eksService.describeCluster(name);
        EksPodIdentityAssociationService.Page page = podIdentityAssociations.list(cluster, namespace, serviceAccount,
                Pagination.parseMaxResults(maxResults, "InvalidParameterException"), nextToken);
        return Response.ok(page.nextToken() == null
                ? Map.of("associations", page.associations())
                : Map.of("associations", page.associations(), "nextToken", page.nextToken())).build();
    }

    @GET
    @Path("/clusters/{name}/pod-identity-associations/{associationId}")
    public Response describePodIdentityAssociation(@PathParam("name") String name,
                                                   @PathParam("associationId") String associationId) {
        Cluster cluster = eksService.describeCluster(name);
        PodIdentityAssociation association = podIdentityAssociations.describe(cluster, associationId);
        return Response.ok(Map.of("association", association)).build();
    }

    @POST
    @Path("/clusters/{name}/pod-identity-associations/{associationId}")
    public Response updatePodIdentityAssociation(@PathParam("name") String name,
                                                 @PathParam("associationId") String associationId,
                                                 UpdatePodIdentityAssociationRequest request) {
        Cluster cluster = eksService.describeCluster(name);
        PodIdentityAssociation association = podIdentityAssociations.update(cluster, associationId, request);
        return Response.ok(Map.of("association", association)).build();
    }

    @DELETE
    @Path("/clusters/{name}/pod-identity-associations/{associationId}")
    public Response deletePodIdentityAssociation(@PathParam("name") String name,
                                                 @PathParam("associationId") String associationId) {
        Cluster cluster = eksService.describeCluster(name);
        PodIdentityAssociation association = podIdentityAssociations.delete(cluster, associationId);
        return Response.ok(Map.of("association", association)).build();
    }
}
