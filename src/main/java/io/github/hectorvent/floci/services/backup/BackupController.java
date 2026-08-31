package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.backup.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AWS Backup restJson1. Public AWS paths are {@code /backup-vaults}, {@code /backup/plans},
 * {@code /backup-jobs}, {@code /restore-jobs}, {@code /copy-jobs}, {@code /resources};
 * {@link BackupRoutingFilter} prefixes them so they do not collide
 * with S3 path-style routes. Tag APIs share {@code /tags/{arn}} and are dispatched by
 * {@code SharedTagsController}.
 */
@Path(BackupRoutingFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BackupController {

    private final BackupService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BackupController(BackupService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ── Vault ──────────────────────────────────────────────────────────────────

    @PUT
    @Path("/backup-vaults/{backupVaultName}")
    public Response createBackupVault(@Context HttpHeaders headers,
                                       @PathParam("backupVaultName") String vaultName,
                                       String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        String encryptionKeyArn  = textOrNull(req, "EncryptionKeyArn");
        String creatorRequestId  = textOrNull(req, "CreatorRequestId");
        Map<String, String> tags = readStringMap(req, "BackupVaultTags");

        BackupVault vault = service.createBackupVault(vaultName, encryptionKeyArn, creatorRequestId, tags, region);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupVaultName", vault.getBackupVaultName());
        out.put("BackupVaultArn", vault.getBackupVaultArn());
        out.put("CreationDate", vault.getCreationDate());
        return Response.status(200).entity(out).build();
    }

    @GET
    @Path("/backup-vaults/{backupVaultName}")
    @Consumes(MediaType.WILDCARD)
    public Response describeBackupVault(@Context HttpHeaders headers,
                                         @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.describeBackupVault(vaultName, region)).build();
    }

    @DELETE
    @Path("/backup-vaults/{backupVaultName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBackupVault(@Context HttpHeaders headers,
                                       @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteBackupVault(vaultName, region);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup-vaults")
    @Consumes(MediaType.WILDCARD)
    public Response listBackupVaults(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<BackupVault> vaults = service.listBackupVaults(region);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("BackupVaultList");
        vaults.forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    // Notification configuration is an optional, never-configured aspect of a vault in the
    // emulator. Per the AWS Backup API, GetBackupVaultNotifications returns
    // ResourceNotFoundException (HTTP 400) when no notification configuration exists for the
    // vault. We mirror that exact error contract so SDK clients see the documented
    // "not configured" signal rather than an empty 200 or a generic 400 they can't interpret.
    @GET
    @Path("/backup-vaults/{backupVaultName}/notification-configuration")
    @Consumes(MediaType.WILDCARD)
    public Response getBackupVaultNotifications(@PathParam("backupVaultName") String vaultName) {
        throw new AwsException("ResourceNotFoundException",
                "No notification configuration found for backup vault: " + vaultName, 400);
    }

    @GET
    @Path("/backup-vaults/{backupVaultName}/access-policy")
    @Consumes(MediaType.WILDCARD)
    public Response getBackupVaultAccessPolicy(@Context HttpHeaders headers,
                                                @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        BackupVault vault = service.requireVaultAccessPolicy(vaultName, region);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupVaultName", vault.getBackupVaultName());
        out.put("BackupVaultArn", vault.getBackupVaultArn());
        out.put("Policy", vault.getAccessPolicy());
        return Response.ok(out).build();
    }

    @PUT
    @Path("/backup-vaults/{backupVaultName}/access-policy")
    public Response putBackupVaultAccessPolicy(@Context HttpHeaders headers,
                                                @PathParam("backupVaultName") String vaultName,
                                                String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        String policy = textOrNull(req, "Policy");
        service.putBackupVaultAccessPolicy(vaultName, policy, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/backup-vaults/{backupVaultName}/access-policy")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBackupVaultAccessPolicy(@Context HttpHeaders headers,
                                                   @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteBackupVaultAccessPolicy(vaultName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    @PUT
    @Path("/backup/plans")
    public Response createBackupPlan(@Context HttpHeaders headers, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body);
        JsonNode planNode = req.path("BackupPlan");
        String planName = planNode.path("BackupPlanName").asText();
        List<BackupRule> rules = readRules(planNode.path("Rules"));
        String creatorRequestId = textOrNull(req, "CreatorRequestId");
        Map<String, String> tags = readStringMap(req, "BackupPlanTags");

        BackupPlan plan = service.createBackupPlan(planName, rules, creatorRequestId, tags, region);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupPlanId", plan.getBackupPlanId());
        out.put("BackupPlanArn", plan.getBackupPlanArn());
        out.put("CreationDate", plan.getCreationDate());
        out.put("VersionId", plan.getVersionId());
        return Response.status(200).entity(out).build();
    }

    @GET
    @Path("/backup/plans/{backupPlanId}")
    @Consumes(MediaType.WILDCARD)
    public Response getBackupPlan(@PathParam("backupPlanId") String planId) {
        BackupPlan plan = service.getBackupPlan(planId);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupPlanId", plan.getBackupPlanId());
        out.put("BackupPlanArn", plan.getBackupPlanArn());
        out.put("CreationDate", plan.getCreationDate());
        out.put("VersionId", plan.getVersionId());
        ObjectNode planBody = out.putObject("BackupPlan");
        planBody.put("BackupPlanName", plan.getBackupPlanName());
        planBody.set("Rules", objectMapper.valueToTree(plan.getRules()));
        return Response.ok(out).build();
    }

    @POST
    @Path("/backup/plans/{backupPlanId}")
    public Response updateBackupPlan(@PathParam("backupPlanId") String planId, String body) throws IOException {
        JsonNode req = objectMapper.readTree(body);
        JsonNode planNode = req.path("BackupPlan");
        String planName = planNode.has("BackupPlanName") ? planNode.path("BackupPlanName").asText() : null;
        List<BackupRule> rules = readRules(planNode.path("Rules"));

        BackupPlan plan = service.updateBackupPlan(planId, planName, rules);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupPlanId", plan.getBackupPlanId());
        out.put("BackupPlanArn", plan.getBackupPlanArn());
        out.put("VersionId", plan.getVersionId());
        return Response.ok(out).build();
    }

    @DELETE
    @Path("/backup/plans/{backupPlanId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBackupPlan(@PathParam("backupPlanId") String planId) {
        service.deleteBackupPlan(planId);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup/plans")
    @Consumes(MediaType.WILDCARD)
    public Response listBackupPlans() {
        List<BackupPlan> plans = service.listBackupPlans();
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("BackupPlansList");
        for (BackupPlan plan : plans) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("BackupPlanId", plan.getBackupPlanId());
            item.put("BackupPlanArn", plan.getBackupPlanArn());
            item.put("BackupPlanName", plan.getBackupPlanName());
            item.put("CreationDate", plan.getCreationDate());
            item.put("VersionId", plan.getVersionId());
            list.add(item);
        }
        return Response.ok(out).build();
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    @PUT
    @Path("/backup/plans/{backupPlanId}/selections")
    public Response createBackupSelection(@PathParam("backupPlanId") String planId,
                                           String body) throws IOException {
        JsonNode req = objectMapper.readTree(body);
        JsonNode selNode = req.path("BackupSelection");
        String selectionName    = selNode.path("SelectionName").asText();
        String iamRoleArn       = selNode.path("IamRoleArn").asText();
        List<String> resources  = readStringList(selNode.path("Resources"));
        List<String> notResources = readStringList(selNode.path("NotResources"));
        List<Condition> listOfTags = readConditions(selNode.path("ListOfTags"));
        String creatorRequestId = textOrNull(req, "CreatorRequestId");

        BackupSelection sel = service.createBackupSelection(planId, selectionName, iamRoleArn,
                resources, notResources, listOfTags, creatorRequestId);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("SelectionId", sel.getSelectionId());
        out.put("BackupPlanId", sel.getBackupPlanId());
        out.put("CreationDate", sel.getCreationDate());
        return Response.status(200).entity(out).build();
    }

    @GET
    @Path("/backup/plans/{backupPlanId}/selections/{selectionId}")
    @Consumes(MediaType.WILDCARD)
    public Response getBackupSelection(@PathParam("backupPlanId") String planId,
                                        @PathParam("selectionId") String selectionId) {
        BackupSelection sel = service.getBackupSelection(planId, selectionId);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupPlanId", sel.getBackupPlanId());
        out.put("SelectionId", sel.getSelectionId());
        out.put("CreationDate", sel.getCreationDate());
        ObjectNode selBody = out.putObject("BackupSelection");
        selBody.put("SelectionName", sel.getSelectionName());
        selBody.put("IamRoleArn", sel.getIamRoleArn());
        selBody.set("Resources", objectMapper.valueToTree(sel.getResources()));
        selBody.set("NotResources", objectMapper.valueToTree(sel.getNotResources()));
        selBody.set("ListOfTags", objectMapper.valueToTree(sel.getListOfTags()));
        return Response.ok(out).build();
    }

    @DELETE
    @Path("/backup/plans/{backupPlanId}/selections/{selectionId}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteBackupSelection(@PathParam("backupPlanId") String planId,
                                           @PathParam("selectionId") String selectionId) {
        service.deleteBackupSelection(planId, selectionId);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup/plans/{backupPlanId}/selections")
    @Consumes(MediaType.WILDCARD)
    public Response listBackupSelections(@PathParam("backupPlanId") String planId) {
        List<BackupSelection> selections = service.listBackupSelections(planId);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("BackupSelectionsList");
        for (BackupSelection sel : selections) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("SelectionId", sel.getSelectionId());
            item.put("SelectionName", sel.getSelectionName());
            item.put("BackupPlanId", sel.getBackupPlanId());
            item.put("IamRoleArn", sel.getIamRoleArn());
            item.put("CreationDate", sel.getCreationDate());
            list.add(item);
        }
        return Response.ok(out).build();
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    @PUT
    @Path("/backup-jobs")
    public Response startBackupJob(@Context HttpHeaders headers, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body);
        String vaultName   = req.path("BackupVaultName").asText();
        String resourceArn = req.path("ResourceArn").asText();
        String iamRoleArn  = req.path("IamRoleArn").asText();
        Lifecycle lifecycle = req.has("Lifecycle")
                ? objectMapper.treeToValue(req.path("Lifecycle"), Lifecycle.class)
                : null;

        BackupJob job = service.startBackupJob(vaultName, resourceArn, iamRoleArn, lifecycle, region);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupJobId", job.getBackupJobId());
        out.put("BackupVaultArn", job.getBackupVaultArn());
        out.put("CreationDate", job.getCreationDate());
        out.put("RecoveryPointArn", "");
        return Response.status(200).entity(out).build();
    }

    @GET
    @Path("/backup-jobs/{backupJobId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeBackupJob(@PathParam("backupJobId") String jobId) {
        return Response.ok(service.describeBackupJob(jobId)).build();
    }

    @POST
    @Path("/backup-jobs/{backupJobId}")
    @Consumes(MediaType.WILDCARD)
    public Response stopBackupJob(@PathParam("backupJobId") String jobId) {
        service.stopBackupJob(jobId);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup-jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listBackupJobs(@QueryParam("byBackupVaultName") String byVaultNameLegacy,
                                    @QueryParam("backupVaultName") String byVaultName,
                                    @QueryParam("byState") String byStateLegacy,
                                    @QueryParam("state") String byState,
                                    @QueryParam("byResourceArn") String byResourceArnLegacy,
                                    @QueryParam("resourceArn") String byResourceArn,
                                    @QueryParam("byResourceType") String byResourceTypeLegacy,
                                    @QueryParam("resourceType") String byResourceType) {
        List<BackupJob> jobs = service.listBackupJobs(
                firstNonBlank(byVaultName, byVaultNameLegacy),
                firstNonBlank(byState, byStateLegacy),
                firstNonBlank(byResourceArn, byResourceArnLegacy),
                firstNonBlank(byResourceType, byResourceTypeLegacy));
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("BackupJobs");
        jobs.forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    @GET
    @Path("/backup-vaults/{backupVaultName}/recovery-points/{recoveryPointArn: .+}/restore-metadata")
    @Consumes(MediaType.WILDCARD)
    public Response getRecoveryPointRestoreMetadata(@Context HttpHeaders headers,
                                                     @PathParam("backupVaultName") String vaultName,
                                                     @PathParam("recoveryPointArn") String recoveryPointArn) {
        String region = regionResolver.resolveRegion(headers);
        RecoveryPoint rp = service.describeRecoveryPoint(vaultName, recoveryPointArn, region);
        Map<String, String> metadata = service.getRecoveryPointRestoreMetadata(vaultName, recoveryPointArn, region);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupVaultArn", rp.getBackupVaultArn());
        out.put("RecoveryPointArn", rp.getRecoveryPointArn());
        out.put("ResourceType", rp.getResourceType());
        out.set("RestoreMetadata", objectMapper.valueToTree(metadata));
        return Response.ok(out).build();
    }

    @GET
    @Path("/backup-vaults/{backupVaultName}/recovery-points/{recoveryPointArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response describeRecoveryPoint(@Context HttpHeaders headers,
                                           @PathParam("backupVaultName") String vaultName,
                                           @PathParam("recoveryPointArn") String recoveryPointArn) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.describeRecoveryPoint(vaultName, recoveryPointArn, region)).build();
    }

    @GET
    @Path("/backup-vaults/{backupVaultName}/recovery-points")
    @Consumes(MediaType.WILDCARD)
    public Response listRecoveryPointsByBackupVault(@Context HttpHeaders headers,
                                                     @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        List<RecoveryPoint> points = service.listRecoveryPointsByBackupVault(vaultName, region);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("RecoveryPoints");
        points.forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    @DELETE
    @Path("/backup-vaults/{backupVaultName}/recovery-points/{recoveryPointArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteRecoveryPoint(@Context HttpHeaders headers,
                                         @PathParam("backupVaultName") String vaultName,
                                         @PathParam("recoveryPointArn") String recoveryPointArn) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteRecoveryPoint(vaultName, recoveryPointArn, region);
        return Response.noContent().build();
    }

    // ── Untag (POST /untag/{arn} with body — distinct from shared DELETE /tags pattern) ──

    @POST
    @Path("/untag/{resourceArn: .*}")
    public Response untagResource(@PathParam("resourceArn") String resourceArn,
                                   String body) throws IOException {
        JsonNode req = objectMapper.readTree(body);
        List<String> tagKeys = readStringList(req.path("TagKeyList"));
        service.untagResource(resourceArn, tagKeys);
        return Response.noContent().build();
    }

    // ── Supported resource types ───────────────────────────────────────────────

    @GET
    @Path("/supported-resource-types")
    @Consumes(MediaType.WILDCARD)
    public Response getSupportedResourceTypes() {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("ResourceTypes");
        service.getSupportedResourceTypes().forEach(list::add);
        return Response.ok(out).build();
    }

    // ── Restore jobs ───────────────────────────────────────────────────────────

    @PUT
    @Path("/restore-jobs")
    public Response startRestoreJob(@Context HttpHeaders headers, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        RestoreJob job = service.startRestoreJob(
                textOrNull(req, "RecoveryPointArn"),
                textOrNull(req, "IamRoleArn"),
                readStringMap(req, "Metadata"),
                textOrNull(req, "ResourceType"),
                region);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("RestoreJobId", job.getRestoreJobId());
        return Response.ok(out).build();
    }

    @GET
    @Path("/restore-jobs/{restoreJobId}/metadata")
    @Consumes(MediaType.WILDCARD)
    public Response getRestoreJobMetadata(@PathParam("restoreJobId") String jobId) {
        RestoreJob job = service.describeRestoreJob(jobId);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("RestoreJobId", job.getRestoreJobId());
        out.set("Metadata", objectMapper.valueToTree(service.getRestoreJobMetadata(jobId)));
        return Response.ok(out).build();
    }

    @PUT
    @Path("/restore-jobs/{restoreJobId}/validations")
    public Response putRestoreValidationResult(@PathParam("restoreJobId") String jobId, String body) throws IOException {
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        String status = textOrNull(req, "ValidationStatus");
        if (status == null || status.isBlank()) {
            throw new AwsException("MissingParameterValueException", "Missing parameter: ValidationStatus", 400);
        }
        service.putRestoreValidationResult(jobId, status, textOrNull(req, "ValidationStatusMessage"));
        return Response.ok().build();
    }

    @GET
    @Path("/restore-jobs/{restoreJobId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeRestoreJob(@PathParam("restoreJobId") String jobId) {
        return Response.ok(service.describeRestoreJob(jobId)).build();
    }

    @GET
    @Path("/restore-jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listRestoreJobs() {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("RestoreJobs");
        service.listRestoreJobs().forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    // ── Copy jobs ──────────────────────────────────────────────────────────────

    @PUT
    @Path("/copy-jobs")
    public Response startCopyJob(@Context HttpHeaders headers, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        CopyJob job = service.startCopyJob(
                textOrNull(req, "RecoveryPointArn"),
                textOrNull(req, "SourceBackupVaultName"),
                textOrNull(req, "DestinationBackupVaultArn"),
                textOrNull(req, "IamRoleArn"),
                region);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("CopyJobId", job.getCopyJobId());
        out.put("CreationDate", job.getCreationDate());
        return Response.ok(out).build();
    }

    @GET
    @Path("/copy-jobs/{copyJobId}")
    @Consumes(MediaType.WILDCARD)
    public Response describeCopyJob(@PathParam("copyJobId") String jobId) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set("CopyJob", objectMapper.valueToTree(service.describeCopyJob(jobId)));
        return Response.ok(out).build();
    }

    @GET
    @Path("/copy-jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listCopyJobs() {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("CopyJobs");
        service.listCopyJobs().forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    // ── Protected resources ────────────────────────────────────────────────────

    @GET
    @Path("/resources/{resourceArn: .+}/restore-jobs")
    @Consumes(MediaType.WILDCARD)
    public Response listRestoreJobsByProtectedResource(@PathParam("resourceArn") String resourceArn) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("RestoreJobs");
        service.listRestoreJobs().stream()
                .filter(j -> resourceArn.equals(j.getSourceResourceArn()))
                .forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    @GET
    @Path("/resources/{resourceArn: .+}/recovery-points")
    @Consumes(MediaType.WILDCARD)
    public Response listRecoveryPointsByResource(@PathParam("resourceArn") String resourceArn) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("RecoveryPoints");
        service.listRecoveryPointsByResource(resourceArn).forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    @GET
    @Path("/resources/{resourceArn: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response describeProtectedResource(@PathParam("resourceArn") String resourceArn) {
        return Response.ok(service.describeProtectedResource(resourceArn)).build();
    }

    @GET
    @Path("/resources")
    @Consumes(MediaType.WILDCARD)
    public Response listProtectedResources() {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("Results");
        service.listProtectedResources().forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readStringMap(JsonNode node, String field) {
        JsonNode mapNode = node.path(field);
        if (mapNode.isMissingNode() || mapNode.isNull()) {
            return new java.util.HashMap<>();
        }
        return objectMapper.convertValue(mapNode, Map.class);
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return result;
        }
        node.forEach(n -> result.add(n.asText()));
        return result;
    }

    private List<BackupRule> readRules(JsonNode rulesNode) throws IOException {
        List<BackupRule> rules = new ArrayList<>();
        if (rulesNode == null || rulesNode.isMissingNode() || !rulesNode.isArray()) {
            return rules;
        }
        for (JsonNode ruleNode : rulesNode) {
            rules.add(objectMapper.treeToValue(ruleNode, BackupRule.class));
        }
        return rules;
    }

    private List<Condition> readConditions(JsonNode conditionsNode) throws IOException {
        List<Condition> conditions = new ArrayList<>();
        if (conditionsNode == null || conditionsNode.isMissingNode() || !conditionsNode.isArray()) {
            return conditions;
        }
        for (JsonNode conditionNode : conditionsNode) {
            conditions.add(objectMapper.treeToValue(conditionNode, Condition.class));
        }
        return conditions;
    }
}
