package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateFargateProfileRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.FargateProfile;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.UpdateAccessEntryRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateAddonRequest;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EKS REST-JSON controller.
 *
 * <p>
 * EKS uses standard HTTP verbs with JSON bodies — not JSON 1.1 (X-Amz-Target)
 * or Query protocol.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksController {

    private final EksService eksService;

    @Inject
    public EksController(EksService eksService) {
        this.eksService = eksService;
    }

    @POST
    @Path("/clusters")
    public Response createCluster(CreateClusterRequest request) {
        Cluster cluster = eksService.createCluster(request);
        return Response.ok(Map.of("cluster", cluster)).build();
    }

    @GET
    @Path("/clusters")
    public Response listClusters(@QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(eksService.page(eksService.listClusters(), maxResults, nextToken, "clusters")).build();
    }

    @GET
    @Path("/clusters/{name}")
    public Response describeCluster(@PathParam("name") String name) {
        Cluster cluster = eksService.describeCluster(name);
        return Response.ok(Map.of("cluster", cluster)).build();
    }

    @DELETE
    @Path("/clusters/{name}")
    public Response deleteCluster(@PathParam("name") String name) {
        Cluster cluster = eksService.deleteCluster(name);
        return Response.ok(Map.of("cluster", cluster)).build();
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
    public Response listFargateProfiles(@PathParam("name") String name,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(eksService.page(eksService.listFargateProfiles(name), maxResults, nextToken,
                "fargateProfileNames")).build();
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

    // Explicit routes so S3's path-style catch-all (@Path("/{bucket}/{key: .+}")) cannot
    // swallow them (issue #1754, same family as #1137).

    @POST
    @Path("/clusters/{name}/access-entries")
    public Response createAccessEntry(@PathParam("name") String name, CreateAccessEntryRequest request) {
        AccessEntry entry = eksService.createAccessEntry(name, request);
        return Response.ok(Map.of("accessEntry", entry)).build();
    }

    @GET
    @Path("/clusters/{name}/access-entries")
    public Response listAccessEntries(@PathParam("name") String name,
            @QueryParam("associatedPolicyArn") String associatedPolicyArn,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(eksService.page(
                eksService.listAccessEntries(name, associatedPolicyArn),
                maxResults, nextToken, "accessEntries")).build();
    }

    @GET
    @Path("/clusters/{name}/access-entries/{principalArn:.+}/access-policies")
    public Response listAssociatedAccessPolicies(@PathParam("name") String name,
            @PathParam("principalArn") String principalArn) {
        String decoded = decodeArn(principalArn);
        List<AccessEntry.AssociatedAccessPolicy> policies =
                eksService.listAssociatedAccessPolicies(name, decoded);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterName", name);
        body.put("principalArn", decoded);
        body.put("associatedAccessPolicies", policies);
        return Response.ok(body).build();
    }

    @POST
    @Path("/clusters/{name}/access-entries/{principalArn:.+}/access-policies")
    public Response associateAccessPolicy(@PathParam("name") String name,
            @PathParam("principalArn") String principalArn,
            AssociateAccessPolicyRequest request) {
        String decoded = decodeArn(principalArn);
        AccessEntry.AssociatedAccessPolicy associated =
                eksService.associateAccessPolicy(name, decoded, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterName", name);
        body.put("principalArn", decoded);
        body.put("associatedAccessPolicy", associated);
        return Response.ok(body).build();
    }

    @DELETE
    @Path("/clusters/{name}/access-entries/{principalArn:.+}/access-policies/{policyArn:.+}")
    public Response disassociateAccessPolicy(@PathParam("name") String name,
            @PathParam("principalArn") String principalArn,
            @PathParam("policyArn") String policyArn) {
        eksService.disassociateAccessPolicy(name, decodeArn(principalArn), decodeArn(policyArn));
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path("/clusters/{name}/access-entries/{principalArn:.+}")
    public Response describeAccessEntry(@PathParam("name") String name,
            @PathParam("principalArn") String principalArn) {
        AccessEntry entry = eksService.describeAccessEntry(name, decodeArn(principalArn));
        return Response.ok(Map.of("accessEntry", entry)).build();
    }

    @POST
    @Path("/clusters/{name}/access-entries/{principalArn:.+}")
    public Response updateAccessEntry(@PathParam("name") String name,
            @PathParam("principalArn") String principalArn,
            UpdateAccessEntryRequest request) {
        AccessEntry entry = eksService.updateAccessEntry(name, decodeArn(principalArn), request);
        return Response.ok(Map.of("accessEntry", entry)).build();
    }

    @DELETE
    @Path("/clusters/{name}/access-entries/{principalArn:.+}")
    public Response deleteAccessEntry(@PathParam("name") String name,
            @PathParam("principalArn") String principalArn) {
        eksService.deleteAccessEntry(name, decodeArn(principalArn));
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/clusters/{name}/addons")
    public Response createAddon(@PathParam("name") String name, CreateAddonRequest request) {
        Addon addon = eksService.createAddon(name, request);
        return Response.ok(Map.of("addon", addon)).build();
    }

    @GET
    @Path("/clusters/{name}/addons")
    public Response listAddons(@PathParam("name") String name,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(eksService.page(eksService.listAddons(name), maxResults, nextToken, "addons")).build();
    }

    @GET
    @Path("/clusters/{name}/addons/{addonName}")
    public Response describeAddon(@PathParam("name") String name,
            @PathParam("addonName") String addonName) {
        Addon addon = eksService.describeAddon(name, addonName);
        return Response.ok(Map.of("addon", addon)).build();
    }

    @POST
    @Path("/clusters/{name}/addons/{addonName}/update")
    public Response updateAddon(@PathParam("name") String name,
            @PathParam("addonName") String addonName,
            UpdateAddonRequest request) {
        return Response.ok(Map.of("update", eksService.updateAddon(name, addonName, request))).build();
    }

    @DELETE
    @Path("/clusters/{name}/addons/{addonName}")
    public Response deleteAddon(@PathParam("name") String name,
            @PathParam("addonName") String addonName) {
        Addon addon = eksService.deleteAddon(name, addonName);
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
        PodIdentityAssociation association = eksService.createPodIdentityAssociation(name, request);
        return Response.ok(Map.of("association", association)).build();
    }

    @GET
    @Path("/clusters/{name}/pod-identity-associations")
    public Response listPodIdentityAssociations(@PathParam("name") String name,
            @QueryParam("namespace") String namespace,
            @QueryParam("serviceAccount") String serviceAccount,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(eksService.listPodIdentityAssociations(name, namespace, serviceAccount,
                maxResults, nextToken)).build();
    }

    @GET
    @Path("/clusters/{name}/pod-identity-associations/{associationId}")
    public Response describePodIdentityAssociation(@PathParam("name") String name,
            @PathParam("associationId") String associationId) {
        return Response.ok(Map.of("association",
                eksService.describePodIdentityAssociation(name, associationId))).build();
    }

    @POST
    @Path("/clusters/{name}/pod-identity-associations/{associationId}")
    public Response updatePodIdentityAssociation(@PathParam("name") String name,
            @PathParam("associationId") String associationId,
            UpdatePodIdentityAssociationRequest request) {
        return Response.ok(Map.of("association",
                eksService.updatePodIdentityAssociation(name, associationId, request))).build();
    }

    @DELETE
    @Path("/clusters/{name}/pod-identity-associations/{associationId}")
    public Response deletePodIdentityAssociation(@PathParam("name") String name,
            @PathParam("associationId") String associationId) {
        return Response.ok(Map.of("association",
                eksService.deletePodIdentityAssociation(name, associationId))).build();
    }

    @POST
    @Path("/clusters/{name}/identity-provider-configs/describe")
    public Response describeIdentityProviderConfig(@PathParam("name") String name,
            Map<String, Object> request) {
        String type = null;
        String configName = null;
        if (request != null && request.get("identityProviderConfig") instanceof Map<?, ?> config) {
            Object typeValue = config.get("type");
            Object nameValue = config.get("name");
            type = typeValue == null ? null : typeValue.toString();
            configName = nameValue == null ? null : nameValue.toString();
        }
        return Response.ok(eksService.describeIdentityProviderConfig(name, type, configName)).build();
    }

    @POST
    @Path("/clusters/{name}/insights")
    public Response listInsights(@PathParam("name") String name, Map<String, Object> request) {
        return Response.ok(eksService.listInsights(name)).build();
    }

    @GET
    @Path("/clusters/{name}/insights/{id}")
    public Response describeInsight(@PathParam("name") String name, @PathParam("id") String id) {
        return Response.ok(eksService.describeInsight(name, id)).build();
    }

    @GET
    @Path("/clusters/{name}/insights-refresh")
    public Response describeInsightsRefresh(@PathParam("name") String name) {
        return Response.ok(eksService.describeInsightsRefresh(name)).build();
    }

    @POST
    @Path("/clusters/{name}/insights-refresh")
    public Response startInsightsRefresh(@PathParam("name") String name) {
        return Response.ok(eksService.startInsightsRefresh(name)).build();
    }

    @GET
    @Path("/clusters/{name}/updates")
    public Response listUpdates(@PathParam("name") String name,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(eksService.page(eksService.listUpdateIds(name), maxResults, nextToken, "updateIds")).build();
    }

    @GET
    @Path("/clusters/{name}/updates/{updateId}")
    public Response describeUpdate(@PathParam("name") String name, @PathParam("updateId") String updateId) {
        return Response.ok(eksService.describeUpdate(name, updateId)).build();
    }

    @GET
    @Path("/clusters/{name}/capabilities")
    public Response listCapabilities(@PathParam("name") String name) {
        return Response.ok(eksService.listCapabilities(name)).build();
    }

    @GET
    @Path("/clusters/{name}/capabilities/{capabilityName}")
    public Response describeCapability(@PathParam("name") String name,
            @PathParam("capabilityName") String capabilityName) {
        return Response.ok(eksService.describeCapability(name, capabilityName)).build();
    }

    // Account-scoped catalogs. Literal paths so S3's /{bucket} and /{bucket}/{key:.+}
    // catch-alls cannot swallow them (same family as #1137 / #1754).

    @GET
    @Path("/access-policies")
    public Response listAccessPolicies(@QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken) {
        return Response.ok(eksService.listAccessPolicies(maxResults, nextToken)).build();
    }

    @GET
    @Path("/cluster-versions")
    public Response describeClusterVersions(
            @QueryParam("clusterType") String clusterType,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken,
            @QueryParam("defaultOnly") Boolean defaultOnly,
            @QueryParam("includeAll") Boolean includeAll,
            @QueryParam("clusterVersions") List<String> clusterVersions,
            @QueryParam("status") String status,
            @QueryParam("versionStatus") String versionStatus) {
        return Response.ok(eksService.describeClusterVersions(defaultOnly, includeAll, clusterType,
                clusterVersions, maxResults, nextToken, status, versionStatus)).build();
    }

    @GET
    @Path("/addons/supported-versions")
    public Response describeAddonVersions(
            @QueryParam("kubernetesVersion") String kubernetesVersion,
            @QueryParam("maxResults") Integer maxResults,
            @QueryParam("nextToken") String nextToken,
            @QueryParam("addonName") String addonName,
            @QueryParam("types") List<String> types,
            @QueryParam("publishers") List<String> publishers,
            @QueryParam("owners") List<String> owners) {
        return Response.ok(eksService.describeAddonVersions(addonName, kubernetesVersion,
                maxResults, nextToken, types, publishers, owners)).build();
    }

    @GET
    @Path("/addons/configuration-schemas")
    public Response describeAddonConfiguration(
            @QueryParam("addonName") String addonName,
            @QueryParam("addonVersion") String addonVersion) {
        return Response.ok(eksService.describeAddonConfiguration(addonName, addonVersion)).build();
    }

    private static String decodeArn(String value) {
        if (value == null) {
            return null;
        }
        // Path labels arrive percent-encoded from the AWS SDK.
        String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        if (decoded.contains("%")) {
            decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
        }
        return decoded;
    }
}
