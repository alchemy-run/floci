package io.floci.aws.services.auditmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.configservice.AwsConfigService;
import io.floci.aws.services.configservice.model.ConfigRule;
import io.floci.aws.services.configservice.model.EvaluationResult;
import io.floci.aws.services.configservice.model.EvaluationResultQualifier;
import io.floci.aws.services.organizations.model.OrganizationAccount;
import io.floci.aws.services.s3.PreSignedUrlGenerator;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.floci.aws.services.auditmanager.AuditManagerService.check;
import static io.floci.aws.services.auditmanager.AuditManagerService.copy;
import static io.floci.aws.services.auditmanager.AuditManagerService.now;
import static io.floci.aws.services.auditmanager.AuditManagerService.page;
import static io.floci.aws.services.auditmanager.AuditManagerService.validation;

/**
 * Audit Manager assessments: a snapshot of a framework's control sets and controls, scoped to accounts and
 * services, with process owners and delegations, control reviews and comments, the evidence collected for each
 * control in daily folders (imported manually, or collected from the AWS Config rules a control maps to), signed
 * assessment reports written to the assessment's S3 destination, change logs, notifications and insights.
 */
@ApplicationScoped
public class AuditManagerAssessments implements Resettable {
    private static final int MAX_ACTIVE_ASSESSMENTS = 100;
    private static final int MAX_MANUAL_EVIDENCE = 50;
    private static final long COLLECTION_INTERVAL_SECONDS = 30;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private final AccountAwareStorageBackend<ObjectNode> assessments;
    private final AccountAwareStorageBackend<ObjectNode> folders;
    private final AccountAwareStorageBackend<ObjectNode> evidence;
    private final AccountAwareStorageBackend<ObjectNode> reports;
    private final AccountAwareStorageBackend<ObjectNode> notifications;
    private final AccountAwareStorageBackend<ObjectNode> keys;
    private final AuditManagerService account;
    private final Instance<S3Service> s3;
    private final Instance<PreSignedUrlGenerator> presigner;
    private final Instance<AwsConfigService> configService;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;

    @Inject
    public AuditManagerAssessments(StorageFactory storageFactory, AuditManagerService account,
                                   Instance<S3Service> s3, Instance<PreSignedUrlGenerator> presigner,
                                   Instance<AwsConfigService> configService, EmulatorConfig config,
                                   ObjectMapper mapper) {
        this.assessments = AuditManagerService.store(storageFactory, "auditmanager-assessments.json");
        this.folders = AuditManagerService.store(storageFactory, "auditmanager-evidence-folders.json");
        this.evidence = AuditManagerService.store(storageFactory, "auditmanager-evidence.json");
        this.reports = AuditManagerService.store(storageFactory, "auditmanager-reports.json");
        this.notifications = AuditManagerService.store(storageFactory, "auditmanager-notifications.json");
        this.keys = AuditManagerService.store(storageFactory, "auditmanager-signing-keys.json");
        this.account = account;
        this.s3 = s3;
        this.presigner = presigner;
        this.configService = configService;
        this.config = config;
        this.mapper = mapper;
    }

    // ── Assessments ────────────────────────────────────────────────────────

    public synchronized ObjectNode create(JsonNode in, String region) {
        check("CreateAssessment", in);
        account.requireActive(region);
        if (active(region).size() >= MAX_ACTIVE_ASSESSMENTS) {
            throw new AwsException("ServiceQuotaExceededException", "The account has the maximum of "
                    + MAX_ACTIVE_ASSESSMENTS + " active assessments.", 402);
        }
        ObjectNode framework = account.renderFramework(account.requireFramework(in.path("frameworkId").asText(),
                region), region);
        ArrayNode roles = roles(in.path("roles"));
        ObjectNode destination = destination(in.path("assessmentReportsDestination"), region);
        ObjectNode scope = scope(in.path("scope"));
        String id = UUID.randomUUID().toString();
        double now = now();

        ObjectNode assessment = mapper.createObjectNode();
        assessment.put("arn", account.arn(region, "assessment/" + id));
        assessment.set("awsAccount", awsAccount(account.accountId()));
        ObjectNode metadata = assessment.putObject("metadata");
        metadata.put("name", in.path("name").asText());
        metadata.put("id", id);
        if (in.hasNonNull("description")) {
            metadata.put("description", in.path("description").asText());
        }
        copy(framework, metadata, "complianceType");
        metadata.put("status", "ACTIVE");
        metadata.set("assessmentReportsDestination", destination);
        metadata.set("scope", scope);
        metadata.set("roles", roles);
        metadata.putArray("delegations");
        metadata.put("creationTime", now);
        metadata.put("lastUpdated", now);

        ObjectNode snapshot = assessment.putObject("framework");
        snapshot.put("id", framework.path("id").asText());
        snapshot.put("arn", framework.path("arn").asText());
        ObjectNode frameworkMetadata = snapshot.putObject("metadata");
        copy(framework, frameworkMetadata, "name", "description", "logo", "complianceType");
        ArrayNode sets = snapshot.putArray("controlSets");
        for (JsonNode set : framework.path("controlSets")) {
            ObjectNode copied = sets.addObject();
            copied.put("id", set.path("name").asText());
            copied.put("status", "ACTIVE");
            copied.set("roles", roles.deepCopy());
            copied.putArray("delegations");
            ArrayNode controls = copied.putArray("controls");
            for (JsonNode control : set.path("controls")) {
                ObjectNode assessed = controls.addObject();
                assessed.put("id", control.path("id").asText());
                assessed.put("name", control.path("name").asText());
                copy(control, assessed, "description");
                assessed.put("status", "UNDER_REVIEW");
                boolean automated = false;
                Set<String> sources = new LinkedHashSet<>();
                for (JsonNode source : control.path("controlMappingSources")) {
                    automated |= !"MANUAL".equals(source.path("sourceType").asText());
                    sources.add(AuditManagerCatalog.sourceDisplayName(source.path("sourceType").asText()));
                }
                assessed.put("response", automated ? "AUTOMATE" : "MANUAL");
                assessed.putArray("comments");
                ArrayNode evidenceSources = assessed.putArray("evidenceSources");
                sources.forEach(evidenceSources::add);
                assessed.set("mappingSources", control.path("controlMappingSources").deepCopy());
            }
        }
        ObjectNode tags = assessment.putObject("tags");
        in.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        assessment.putArray("changeLogs");
        log(assessment, "ASSESSMENT", in.path("name").asText(), "CREATE", null, null);
        assessments.put(region + "/" + id, assessment);
        collect(assessment, region, true);
        return mapper.createObjectNode().set("assessment", render(require(id, region), region));
    }

    public synchronized ObjectNode get(String id, String region) {
        check("GetAssessment", null, "assessmentId", id);
        account.requireActive(region);
        ObjectNode assessment = require(id, region);
        collect(assessment, region, false);
        ObjectNode out = mapper.createObjectNode();
        out.set("assessment", render(assessment, region));
        JsonNode roles = assessment.path("metadata").path("roles");
        if (!roles.isEmpty()) {
            out.set("userRole", roles.get(0).deepCopy());
        }
        return out;
    }

    public synchronized ObjectNode update(String id, JsonNode in, String region) {
        check("UpdateAssessment", in, "assessmentId", id);
        account.requireActive(region);
        ObjectNode assessment = requireActiveAssessment(id, region);
        ObjectNode metadata = (ObjectNode) assessment.path("metadata");
        if (in.hasNonNull("assessmentName")) {
            metadata.put("name", in.path("assessmentName").asText());
        }
        if (in.has("assessmentDescription")) {
            if (in.hasNonNull("assessmentDescription")) {
                metadata.put("description", in.path("assessmentDescription").asText());
            } else {
                metadata.remove("description");
            }
        }
        metadata.set("scope", scope(in.path("scope")));
        if (in.hasNonNull("assessmentReportsDestination")) {
            metadata.set("assessmentReportsDestination", destination(in.path("assessmentReportsDestination"),
                    region));
        }
        if (in.hasNonNull("roles")) {
            ArrayNode roles = roles(in.path("roles"));
            metadata.set("roles", roles);
            for (JsonNode set : controlSets(assessment)) {
                ((ObjectNode) set).set("roles", roles.deepCopy());
            }
        }
        metadata.put("lastUpdated", now());
        log(assessment, "ASSESSMENT", metadata.path("name").asText(), "UPDATE_METADATA", null, null);
        save(assessment, region);
        return mapper.createObjectNode().set("assessment", render(assessment, region));
    }

    /** Moves an assessment to {@code INACTIVE}, which stops evidence collection for good; it can't go back. */
    public synchronized ObjectNode updateStatus(String id, JsonNode in, String region) {
        check("UpdateAssessmentStatus", in, "assessmentId", id);
        account.requireActive(region);
        ObjectNode assessment = require(id, region);
        String status = in.path("status").asText();
        String current = assessment.path("metadata").path("status").asText();
        if (!status.equals(current)) {
            if ("ACTIVE".equals(status)) {
                throw validation("An inactive assessment can't be made active again.");
            }
            ObjectNode metadata = (ObjectNode) assessment.path("metadata");
            metadata.put("status", status);
            metadata.put("lastUpdated", now());
            log(assessment, "ASSESSMENT", metadata.path("name").asText(), status, null, null);
            save(assessment, region);
        }
        return mapper.createObjectNode().set("assessment", render(assessment, region));
    }

    public synchronized void delete(String id, String region) {
        check("DeleteAssessment", null, "assessmentId", id);
        account.requireActive(region);
        require(id, region);
        deleteData(region, id);
    }

    /** Deletes an assessment with its evidence folders, evidence, report records and notifications. */
    private void deleteData(String region, String id) {
        String prefix = region + "/" + id + "/";
        folders.keys().stream().filter(k -> k.startsWith(prefix)).toList().forEach(folders::delete);
        evidence.keys().stream().filter(k -> k.startsWith(prefix)).toList().forEach(evidence::delete);
        reports.keys().stream().filter(k -> k.startsWith(prefix)).toList().forEach(reports::delete);
        notifications.scan(k -> k.startsWith(region + "/")).stream()
                .filter(n -> id.equals(n.path("assessmentId").asText()))
                .forEach(n -> notifications.delete(region + "/" + n.path("id").asText()));
        assessments.delete(region + "/" + id);
    }

    /** Deletes every assessment in the Region, for a deregistration with the {@code ALL} policy. */
    public synchronized void deleteAll(String region) {
        assessments.keys().stream().filter(k -> k.startsWith(region + "/")).toList()
                .forEach(k -> deleteData(region, k.substring(region.length() + 1)));
    }

    public synchronized ObjectNode list(String status, Integer maxResults, String nextToken, String region) {
        check("ListAssessments", null, "status", status, "maxResults", maxResults, "nextToken", nextToken);
        account.requireActive(region);
        List<ObjectNode> matching = all(region).stream()
                .filter(a -> status == null || status.equals(a.path("metadata").path("status").asText())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("assessmentMetadata");
        for (ObjectNode assessment : page(matching, maxResults, nextToken, out)) {
            ObjectNode item = list.addObject();
            copy(assessment.path("metadata"), item, "name", "id", "complianceType", "status", "roles", "delegations",
                    "creationTime", "lastUpdated");
        }
        return out;
    }

    // ── Control sets, controls and delegations ─────────────────────────────

    public synchronized ObjectNode updateControl(String id, String controlSetId, String controlId, JsonNode in,
                                                 String region) {
        check("UpdateAssessmentControl", in, "assessmentId", id, "controlSetId", controlSetId, "controlId",
                controlId);
        account.requireActive(region);
        ObjectNode assessment = requireActiveAssessment(id, region);
        ObjectNode control = requireControl(assessment, controlSetId, controlId);
        if (in.hasNonNull("controlStatus")) {
            String status = in.path("controlStatus").asText();
            if (!status.equals(control.path("status").asText())) {
                control.put("status", status);
                log(assessment, "CONTROL", control.path("name").asText(), status, controlSetId, controlId);
            }
        }
        if (in.hasNonNull("commentBody")) {
            control.withArray("comments").addObject().put("authorName", account.callerArn())
                    .put("commentBody", in.path("commentBody").asText()).put("postedDate", now());
            log(assessment, "CONTROL", control.path("name").asText(), "UPDATE_METADATA", controlSetId, controlId);
        }
        touch(assessment);
        save(assessment, region);
        return mapper.createObjectNode().set("control", renderControl(assessment, controlSetId, control, region));
    }

    /**
     * Sets a control set's review status. A delegate returning a set for review moves its delegations to
     * {@code UNDER_REVIEW}, and the process owner marking it {@code REVIEWED} completes them.
     */
    public synchronized ObjectNode updateControlSetStatus(String id, String controlSetId, JsonNode in,
                                                          String region) {
        check("UpdateAssessmentControlSetStatus", in, "assessmentId", id, "controlSetId", controlSetId);
        account.requireActive(region);
        ObjectNode assessment = requireActiveAssessment(id, region);
        ObjectNode set = requireControlSet(assessment, controlSetId);
        String status = in.path("status").asText();
        set.put("status", status);
        String delegationStatus = switch (status) {
            case "UNDER_REVIEW" -> "UNDER_REVIEW";
            case "REVIEWED" -> "COMPLETE";
            default -> null;
        };
        if (delegationStatus != null) {
            double now = now();
            for (JsonNode delegation : assessment.path("metadata").path("delegations")) {
                if (controlSetId.equals(delegation.path("controlSetId").asText())) {
                    ((ObjectNode) delegation).put("status", delegationStatus).put("lastUpdated", now);
                }
            }
            syncSetDelegations(assessment);
        }
        log(assessment, "CONTROL_SET", controlSetId, status, controlSetId, null);
        touch(assessment);
        save(assessment, region);
        return mapper.createObjectNode().set("controlSet", renderControlSet(assessment, set, region));
    }

    public synchronized ObjectNode createDelegations(String id, JsonNode in, String region) {
        check("BatchCreateDelegationByAssessment", in, "assessmentId", id);
        account.requireActive(region);
        ObjectNode assessment = requireActiveAssessment(id, region);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode created = out.putArray("delegations");
        ArrayNode errors = out.putArray("errors");
        ArrayNode delegations = (ArrayNode) assessment.path("metadata").path("delegations");
        String name = assessment.path("metadata").path("name").asText();
        for (JsonNode request : in.path("createDelegationRequests")) {
            String controlSetId = request.path("controlSetId").asText("");
            String roleArn = request.path("roleArn").asText("");
            String error = null;
            if (findControlSet(assessment, controlSetId).isEmpty()) {
                error = "The control set " + controlSetId + " isn't in the assessment.";
            } else if (!"RESOURCE_OWNER".equals(request.path("roleType").asText())) {
                error = "A control set is delegated to a RESOURCE_OWNER.";
            } else {
                try {
                    account.checkRole(roleArn);
                } catch (AwsException e) {
                    error = e.getMessage();
                }
            }
            if (error != null) {
                errors.addObject().<ObjectNode>set("createDelegationRequest", request.deepCopy())
                        .put("errorCode", "400").put("errorMessage", error);
                continue;
            }
            double now = now();
            ObjectNode delegation = mapper.createObjectNode();
            delegation.put("id", UUID.randomUUID().toString());
            delegation.put("assessmentName", name);
            delegation.put("assessmentId", id);
            delegation.put("status", "IN_PROGRESS");
            delegation.put("roleArn", roleArn);
            delegation.put("roleType", "RESOURCE_OWNER");
            delegation.put("creationTime", now);
            delegation.put("lastUpdated", now);
            delegation.put("controlSetId", controlSetId);
            if (request.hasNonNull("comment")) {
                delegation.put("comment", request.path("comment").asText());
            }
            delegation.put("createdBy", account.callerArn());
            delegations.add(delegation);
            created.add(delegation.deepCopy());
            requireControlSet(assessment, controlSetId).put("status", "UNDER_REVIEW");
            log(assessment, "DELEGATION", controlSetId, "CREATE", controlSetId, null);
            notification(region, assessment, controlSetId, "Control set " + controlSetId
                    + " of the assessment " + name + " was delegated to " + roleArn + " for review.");
        }
        syncSetDelegations(assessment);
        touch(assessment);
        save(assessment, region);
        return out;
    }

    public synchronized ObjectNode deleteDelegations(String id, JsonNode in, String region) {
        check("BatchDeleteDelegationByAssessment", in, "assessmentId", id);
        account.requireActive(region);
        ObjectNode assessment = require(id, region);
        ArrayNode delegations = (ArrayNode) assessment.path("metadata").path("delegations");
        ObjectNode out = mapper.createObjectNode();
        ArrayNode errors = out.putArray("errors");
        for (JsonNode requested : in.path("delegationIds")) {
            String delegationId = requested.asText();
            int index = -1;
            for (int i = 0; i < delegations.size(); i++) {
                if (delegationId.equals(delegations.get(i).path("id").asText())) {
                    index = i;
                }
            }
            if (index < 0) {
                errors.addObject().put("delegationId", delegationId).put("errorCode", "404")
                        .put("errorMessage", "The delegation " + delegationId + " doesn't exist.");
                continue;
            }
            String controlSetId = delegations.get(index).path("controlSetId").asText();
            delegations.remove(index);
            log(assessment, "DELEGATION", controlSetId, "DELETE", controlSetId, null);
        }
        syncSetDelegations(assessment);
        touch(assessment);
        save(assessment, region);
        return out;
    }

    /** Delegations across the Region's active assessments. */
    public synchronized ObjectNode delegations(Integer maxResults, String nextToken, String region) {
        check("GetDelegations", null, "maxResults", maxResults, "nextToken", nextToken);
        account.requireActive(region);
        List<ObjectNode> all = new ArrayList<>();
        for (ObjectNode assessment : active(region)) {
            for (JsonNode delegation : assessment.path("metadata").path("delegations")) {
                ObjectNode metadata = mapper.createObjectNode();
                copy(delegation, metadata, "id", "assessmentName", "assessmentId", "status", "roleArn",
                        "creationTime");
                metadata.put("controlSetName", delegation.path("controlSetId").asText());
                all.add(metadata);
            }
        }
        all.sort(Comparator.comparing(d -> -d.path("creationTime").asDouble()));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("delegations");
        page(all, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    public synchronized ObjectNode notifications(Integer maxResults, String nextToken, String region) {
        check("ListNotifications", null, "maxResults", maxResults, "nextToken", nextToken);
        account.requireActive(region);
        List<ObjectNode> all = notifications.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing(n -> -n.path("eventTime").asDouble())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("notifications");
        page(all, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    private void notification(String region, ObjectNode assessment, String controlSetId, String description) {
        ObjectNode note = mapper.createObjectNode();
        String id = UUID.randomUUID().toString();
        note.put("id", id);
        note.put("assessmentId", assessment.path("metadata").path("id").asText());
        note.put("assessmentName", assessment.path("metadata").path("name").asText());
        note.put("controlSetId", controlSetId);
        note.put("controlSetName", controlSetId);
        note.put("description", description);
        note.put("eventTime", now());
        note.put("source", "Delegation");
        notifications.put(region + "/" + id, note);
        account.notify(region, "AWS Audit Manager: control set delegated", description);
    }

    private void syncSetDelegations(ObjectNode assessment) {
        for (JsonNode set : controlSets(assessment)) {
            ArrayNode delegations = mapper.createArrayNode();
            for (JsonNode delegation : assessment.path("metadata").path("delegations")) {
                if (set.path("id").asText().equals(delegation.path("controlSetId").asText())) {
                    delegations.add(delegation.deepCopy());
                }
            }
            ((ObjectNode) set).set("delegations", delegations);
        }
    }

    // ── Evidence ───────────────────────────────────────────────────────────

    /**
     * Imports manual evidence into a control's folder for today: an S3 object of the account, a text response,
     * or a file uploaded through {@code GetEvidenceFileUploadUrl}. Each item that can't be imported is reported in
     * {@code errors}, and the rest are imported.
     */
    public synchronized ObjectNode importEvidence(String id, String controlSetId, String controlId, JsonNode in,
                                                  String region) {
        check("BatchImportEvidenceToAssessmentControl", in, "assessmentId", id, "controlSetId", controlSetId,
                "controlId", controlId);
        account.requireActive(region);
        ObjectNode assessment = requireActiveAssessment(id, region);
        ObjectNode control = requireControl(assessment, controlSetId, controlId);
        if (in.path("manualEvidence").size() > MAX_MANUAL_EVIDENCE) {
            throw validation("A request can import at most " + MAX_MANUAL_EVIDENCE + " manual evidence items.");
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode errors = out.putArray("errors");
        boolean imported = false;
        for (JsonNode item : in.path("manualEvidence")) {
            int given = (item.hasNonNull("s3ResourcePath") ? 1 : 0) + (item.hasNonNull("textResponse") ? 1 : 0)
                    + (item.hasNonNull("evidenceFileName") ? 1 : 0);
            String error = null;
            ObjectNode resource = mapper.createObjectNode();
            Map<String, String> attributes = new LinkedHashMap<>();
            if (given != 1) {
                error = "Each manual evidence item names exactly one of s3ResourcePath, textResponse and "
                        + "evidenceFileName.";
            } else if (item.hasNonNull("s3ResourcePath")) {
                String path = item.path("s3ResourcePath").asText();
                Optional<String[]> location = s3Location(path);
                if (location.isEmpty() || location.get()[1].isEmpty()) {
                    error = "The S3 path " + path + " doesn't name an object (s3://bucket/key).";
                } else if (!objectExists(location.get()[0], location.get()[1])) {
                    error = "The S3 object " + path + " doesn't exist or can't be read.";
                } else {
                    resource.put("arn", AwsArnUtils.Arn.global(account.partition(), "s3", "",
                            location.get()[0] + "/" + location.get()[1]).toString());
                    resource.put("value", path);
                    attributes.put("s3ResourcePath", path);
                }
            } else if (item.hasNonNull("textResponse")) {
                resource.put("value", item.path("textResponse").asText());
                attributes.put("textResponse", item.path("textResponse").asText());
            } else {
                String file = item.path("evidenceFileName").asText();
                Optional<ObjectNode> upload = account.uploadedFile(file, region);
                if (upload.isEmpty()) {
                    error = "The file " + file + " wasn't uploaded through GetEvidenceFileUploadUrl.";
                } else {
                    resource.put("value", upload.get().path("fileName").asText());
                    attributes.put("evidenceFileName", file);
                    attributes.put("fileSize", upload.get().path("size").asText());
                }
            }
            if (error != null) {
                errors.addObject().<ObjectNode>set("manualEvidence", item.deepCopy()).put("errorCode", "400")
                        .put("errorMessage", error);
                continue;
            }
            double now = now();
            ObjectNode folder = folder(assessment, controlSetId, control, LocalDate.now(ZoneOffset.UTC), "Manual",
                    region);
            ObjectNode record = mapper.createObjectNode();
            String evidenceId = UUID.randomUUID().toString();
            record.put("dataSource", "Manual");
            record.put("evidenceAwsAccountId", account.accountId());
            record.put("time", now);
            record.put("eventSource", AuditManagerService.SERVICE_PRINCIPAL);
            record.put("eventName", "BatchImportEvidenceToAssessmentControl");
            record.put("evidenceByType", "Manual");
            record.putArray("resourcesIncluded").add(resource);
            ObjectNode attrs = record.putObject("attributes");
            attributes.forEach(attrs::put);
            record.put("iamId", account.callerArn());
            record.put("awsAccountId", account.accountId());
            record.put("evidenceFolderId", folder.path("id").asText());
            record.put("id", evidenceId);
            record.put("assessmentReportSelection", "No");
            evidence.put(evidenceKey(region, id, folder.path("id").asText(), evidenceId), record);
            imported = true;
        }
        if (imported) {
            log(assessment, "CONTROL", control.path("name").asText(), "IMPORT_EVIDENCE", controlSetId, controlId);
            touch(assessment);
            save(assessment, region);
        }
        return out;
    }

    public synchronized ObjectNode foldersByAssessment(String id, Integer maxResults, String nextToken,
                                                       String region) {
        check("GetEvidenceFoldersByAssessment", null, "assessmentId", id, "maxResults", maxResults, "nextToken",
                nextToken);
        account.requireActive(region);
        ObjectNode assessment = require(id, region);
        collect(assessment, region, false);
        return folderPage(foldersOf(region, id), maxResults, nextToken, region);
    }

    public synchronized ObjectNode foldersByControl(String id, String controlSetId, String controlId,
                                                    Integer maxResults, String nextToken, String region) {
        check("GetEvidenceFoldersByAssessmentControl", null, "assessmentId", id, "controlSetId", controlSetId,
                "controlId", controlId, "maxResults", maxResults, "nextToken", nextToken);
        account.requireActive(region);
        ObjectNode assessment = require(id, region);
        requireControl(assessment, controlSetId, controlId);
        collect(assessment, region, false);
        return folderPage(foldersOf(region, id).stream()
                .filter(f -> controlSetId.equals(f.path("controlSetId").asText())
                        && controlId.equals(f.path("controlId").asText())).toList(), maxResults, nextToken, region);
    }

    public synchronized ObjectNode folder(String id, String controlSetId, String folderId, String region) {
        check("GetEvidenceFolder", null, "assessmentId", id, "controlSetId", controlSetId, "evidenceFolderId",
                folderId);
        account.requireActive(region);
        require(id, region);
        ObjectNode folder = requireFolder(region, id, controlSetId, folderId);
        return mapper.createObjectNode().set("evidenceFolder", renderFolder(folder, region));
    }

    public synchronized ObjectNode evidenceByFolder(String id, String controlSetId, String folderId,
                                                    Integer maxResults, String nextToken, String region) {
        check("GetEvidenceByEvidenceFolder", null, "assessmentId", id, "controlSetId", controlSetId,
                "evidenceFolderId", folderId, "maxResults", maxResults, "nextToken", nextToken);
        account.requireActive(region);
        require(id, region);
        requireFolder(region, id, controlSetId, folderId);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("evidence");
        page(evidenceIn(region, id, folderId), maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    public synchronized ObjectNode evidence(String id, String controlSetId, String folderId, String evidenceId,
                                            String region) {
        check("GetEvidence", null, "assessmentId", id, "controlSetId", controlSetId, "evidenceFolderId", folderId,
                "evidenceId", evidenceId);
        account.requireActive(region);
        require(id, region);
        requireFolder(region, id, controlSetId, folderId);
        ObjectNode record = evidence.get(evidenceKey(region, id, folderId, evidenceId)).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "The evidence " + evidenceId + " doesn't exist.",
                        404));
        return mapper.createObjectNode().set("evidence", record.deepCopy());
    }

    // ── Report selection ───────────────────────────────────────────────────

    public synchronized void selectFolder(String id, JsonNode in, boolean selected, String region) {
        check(selected ? "AssociateAssessmentReportEvidenceFolder" : "DisassociateAssessmentReportEvidenceFolder",
                in, "assessmentId", id);
        account.requireActive(region);
        require(id, region);
        String folderId = in.path("evidenceFolderId").asText();
        if (folders.get(region + "/" + id + "/" + folderId).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "The evidence folder " + folderId
                    + " doesn't exist.", 404);
        }
        for (ObjectNode record : evidenceIn(region, id, folderId)) {
            record.put("assessmentReportSelection", selected ? "Yes" : "No");
            evidence.put(evidenceKey(region, id, folderId, record.path("id").asText()), record);
        }
    }

    public synchronized ObjectNode selectEvidence(String id, JsonNode in, boolean selected, String region) {
        check(selected ? "BatchAssociateAssessmentReportEvidence" : "BatchDisassociateAssessmentReportEvidence", in,
                "assessmentId", id);
        account.requireActive(region);
        require(id, region);
        String folderId = in.path("evidenceFolderId").asText();
        if (folders.get(region + "/" + id + "/" + folderId).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "The evidence folder " + folderId
                    + " doesn't exist.", 404);
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode done = out.putArray("evidenceIds");
        ArrayNode errors = out.putArray("errors");
        for (JsonNode requested : in.path("evidenceIds")) {
            String evidenceId = requested.asText();
            String key = evidenceKey(region, id, folderId, evidenceId);
            Optional<ObjectNode> record = evidence.get(key);
            if (record.isEmpty()) {
                errors.addObject().put("evidenceId", evidenceId).put("errorCode", "404")
                        .put("errorMessage", "The evidence " + evidenceId + " isn't in the folder " + folderId + ".");
                continue;
            }
            record.get().put("assessmentReportSelection", selected ? "Yes" : "No");
            evidence.put(key, record.get());
            done.add(evidenceId);
        }
        return out;
    }

    // ── Reports ────────────────────────────────────────────────────────────

    /**
     * Generates an assessment report: a zip in the assessment's report destination holding the assessment's
     * summary (control sets, controls, their reviews and comments) and the evidence selected for the report, signed
     * with the account's Audit Manager key so {@code ValidateAssessmentReportIntegrity} can check it. A query
     * statement (an evidence finder search) selects the assessment's evidence in full, and needs evidence finder.
     */
    public synchronized ObjectNode createReport(String id, JsonNode in, String region) {
        check("CreateAssessmentReport", in, "assessmentId", id);
        account.requireActive(region);
        ObjectNode assessment = require(id, region);
        if (in.hasNonNull("queryStatement") && !account.evidenceFinderEnabled(region)) {
            throw validation("A queryStatement needs evidence finder, which isn't enabled in this account.");
        }
        String[] location = s3Location(assessment.path("metadata").path("assessmentReportsDestination")
                .path("destination").asText()).orElseThrow(() -> validation("The assessment has no S3 report "
                        + "destination."));
        if (s3.isResolvable() && !s3.get().bucketExists(location[0])) {
            throw validation("The report destination bucket " + location[0] + " doesn't exist.");
        }
        collect(assessment, region, false);
        String reportId = UUID.randomUUID().toString();
        String name = in.path("name").asText();
        boolean everything = in.hasNonNull("queryStatement");
        List<ObjectNode> selected = allEvidence(region, id).stream()
                .filter(e -> everything || "Yes".equals(e.path("assessmentReportSelection").asText())).toList();
        String file = "AssessmentReport_" + name.replaceAll("[^A-Za-z0-9._-]", "_") + "_" + reportId + ".zip";
        String key = location[1].isEmpty() ? file : location[1].replaceAll("/+$", "") + "/" + file;

        ObjectNode report = mapper.createObjectNode();
        report.put("id", reportId);
        report.put("name", name);
        if (in.hasNonNull("description")) {
            report.put("description", in.path("description").asText());
        }
        report.put("awsAccountId", account.accountId());
        report.put("assessmentId", id);
        report.put("assessmentName", assessment.path("metadata").path("name").asText());
        report.put("author", account.callerArn());
        report.put("creationTime", now());
        report.put("bucket", location[0]);
        report.put("key", key);
        try {
            byte[] zip = reportArchive(render(assessment, region), report, selected, region);
            Map<String, String> metadata = sign(zip);
            if (s3.isResolvable()) {
                s3.get().putObject(location[0], key, zip, "application/zip", metadata);
            }
            report.put("status", "COMPLETE");
        } catch (AwsException | IOException | GeneralSecurityException e) {
            report.put("status", "FAILED");
            report.put("failure", e.getMessage());
        }
        reports.put(region + "/" + id + "/" + reportId, report);
        log(assessment, "ASSESSMENT_REPORT", name, "CREATE", null, null);
        save(assessment, region);
        return mapper.createObjectNode().set("assessmentReport", renderReport(report, true));
    }

    public synchronized ObjectNode listReports(Integer maxResults, String nextToken, String region) {
        check("ListAssessmentReports", null, "maxResults", maxResults, "nextToken", nextToken);
        account.requireActive(region);
        List<ObjectNode> all = reports.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing(r -> -r.path("creationTime").asDouble())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("assessmentReports");
        page(all, maxResults, nextToken, out).forEach(r -> list.add(renderReport(r, false)));
        return out;
    }

    public synchronized ObjectNode reportUrl(String id, String reportId, String region) {
        check("GetAssessmentReportUrl", null, "assessmentId", id, "assessmentReportId", reportId);
        account.requireActive(region);
        ObjectNode report = requireReport(region, id, reportId);
        if (!"COMPLETE".equals(report.path("status").asText())) {
            throw validation("The assessment report is " + report.path("status").asText() + ", so it has no URL.");
        }
        String bucket = report.path("bucket").asText();
        String key = report.path("key").asText();
        String link = presigner.isResolvable()
                ? presigner.get().generatePresignedUrl(config.effectiveBaseUrl(), bucket, key, "GET", 900, region,
                        account.accountId())
                : config.effectiveBaseUrl() + "/" + bucket + "/" + key;
        ObjectNode out = mapper.createObjectNode();
        out.putObject("preSignedUrl").put("hyperlinkName", key.substring(key.lastIndexOf('/') + 1))
                .put("link", link);
        return out;
    }

    public synchronized void deleteReport(String id, String reportId, String region) {
        check("DeleteAssessmentReport", null, "assessmentId", id, "assessmentReportId", reportId);
        account.requireActive(region);
        ObjectNode report = requireReport(region, id, reportId);
        if ("COMPLETE".equals(report.path("status").asText()) && s3.isResolvable()) {
            try {
                s3.get().deleteObject(report.path("bucket").asText(), report.path("key").asText());
            } catch (AwsException e) {
                // The archive is already gone; the report record is deleted either way.
            }
        }
        reports.delete(region + "/" + id + "/" + reportId);
        assessments.get(region + "/" + id).ifPresent(assessment -> {
            log(assessment, "ASSESSMENT_REPORT", report.path("name").asText(), "DELETE", null, null);
            save(assessment, region);
        });
    }

    /** Checks an assessment report in S3 against the signature Audit Manager stored with it. */
    public synchronized ObjectNode validateReport(JsonNode in, String region) {
        check("ValidateAssessmentReportIntegrity", in);
        account.requireActive(region);
        String path = in.path("s3RelativePath").asText();
        String[] location = s3Location(path).filter(l -> !l[1].isEmpty()).orElseThrow(() -> validation(
                "s3RelativePath must name an object, as s3://bucket/key."));
        S3Object object;
        try {
            object = s3.get().getObject(location[0], location[1]);
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException", "The report " + path + " doesn't exist.", 404);
        }
        Map<String, String> metadata = object.getMetadata() == null ? Map.of() : object.getMetadata();
        ObjectNode out = mapper.createObjectNode();
        out.put("signatureAlgorithm", SIGNATURE_ALGORITHM);
        ArrayNode errors = out.putArray("validationErrors");
        String signature = metadata.get("auditmanager-signature");
        if (signature == null) {
            out.put("signatureValid", false);
            errors.add("The file has no Audit Manager signature, so it isn't an assessment report.");
            return out;
        }
        out.put("signatureDateTime", metadata.getOrDefault("auditmanager-signature-date", ""));
        out.put("signatureKeyId", metadata.getOrDefault("auditmanager-signature-key-id", ""));
        try {
            KeyPair pair = signingKey();
            if (!keyId(pair.getPublic()).equals(metadata.get("auditmanager-signature-key-id"))) {
                out.put("signatureValid", false);
                errors.add("The report was signed by a key of another account.");
                return out;
            }
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(pair.getPublic());
            verifier.update(object.getData());
            boolean valid = verifier.verify(Base64.getDecoder().decode(signature));
            out.put("signatureValid", valid);
            if (!valid) {
                errors.add("The report's content doesn't match its signature, so it was changed after Audit Manager "
                        + "created it.");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            out.put("signatureValid", false);
            errors.add("The report's signature can't be read: " + e.getMessage());
        }
        return out;
    }

    private byte[] reportArchive(ObjectNode assessment, ObjectNode report, List<ObjectNode> selected, String region)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ObjectNode summary = mapper.createObjectNode();
            summary.set("report", renderReport(report, true));
            summary.set("assessment", assessment);
            summary.put("evidenceCount", selected.size());
            zip.putNextEntry(new ZipEntry("AssessmentReport.json"));
            zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary));
            zip.closeEntry();
            for (ObjectNode record : selected) {
                ObjectNode folder = folders.get(region + "/" + report.path("assessmentId").asText() + "/"
                        + record.path("evidenceFolderId").asText()).orElse(mapper.createObjectNode());
                zip.putNextEntry(new ZipEntry("evidence/" + folder.path("controlSetId").asText("controlSet")
                        .replaceAll("[^A-Za-z0-9._-]", "_") + "/" + folder.path("controlName").asText("control")
                        .replaceAll("[^A-Za-z0-9._-]", "_") + "/" + record.path("id").asText() + ".json"));
                zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private Map<String, String> sign(byte[] data) throws GeneralSecurityException {
        KeyPair pair = signingKey();
        Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
        signer.initSign(pair.getPrivate());
        signer.update(data);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("auditmanager-signature", Base64.getEncoder().encodeToString(signer.sign()));
        metadata.put("auditmanager-signature-key-id", keyId(pair.getPublic()));
        metadata.put("auditmanager-signature-date", Instant.now().toString());
        return metadata;
    }

    /** The account's report-signing key pair, created on first use. */
    private KeyPair signingKey() throws GeneralSecurityException {
        Optional<ObjectNode> stored = keys.get("signing");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (stored.isPresent()) {
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(
                    stored.get().path("public").asText())));
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(
                    stored.get().path("private").asText())));
            return new KeyPair(publicKey, privateKey);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        keys.put("signing", mapper.createObjectNode()
                .put("public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                .put("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())));
        return pair;
    }

    private static String keyId(PublicKey key) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()), 0, 16);
    }

    private ObjectNode requireReport(String region, String id, String reportId) {
        return reports.get(region + "/" + id + "/" + reportId).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The assessment report " + reportId + " doesn't exist.", 404));
    }

    private ObjectNode renderReport(ObjectNode stored, boolean full) {
        ObjectNode out = mapper.createObjectNode();
        copy(stored, out, "id", "name", "description");
        if (full) {
            copy(stored, out, "awsAccountId");
        }
        copy(stored, out, "assessmentId", "assessmentName", "author", "status", "creationTime");
        return out;
    }

    // ── Change logs and insights ───────────────────────────────────────────

    public synchronized ObjectNode changeLogs(String id, String controlSetId, String controlId, Integer maxResults,
                                              String nextToken, String region) {
        check("GetChangeLogs", null, "assessmentId", id, "controlSetId", controlSetId, "controlId", controlId,
                "maxResults", maxResults, "nextToken", nextToken);
        account.requireActive(region);
        ObjectNode assessment = require(id, region);
        List<ObjectNode> matching = new ArrayList<>();
        for (JsonNode entry : assessment.path("changeLogs")) {
            if (controlSetId != null && !controlSetId.equals(entry.path("controlSetId").asText())) {
                continue;
            }
            if (controlId != null && !controlId.equals(entry.path("controlId").asText())) {
                continue;
            }
            ObjectNode log = mapper.createObjectNode();
            copy(entry, log, "objectType", "objectName", "action", "createdAt", "createdBy");
            matching.add(log);
        }
        matching.sort(Comparator.comparing(e -> -e.path("createdAt").asDouble()));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("changeLogs");
        page(matching, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    /** Evidence counts over the Region's active assessments: compliance checks that passed or failed, and the rest. */
    public synchronized ObjectNode insights(String region) {
        account.requireActive(region);
        ObjectNode insights = mapper.createObjectNode();
        List<ObjectNode> active = active(region);
        active.forEach(a -> collect(a, region, false));
        insights.put("activeAssessmentsCount", active.size());
        Tally tally = new Tally();
        for (ObjectNode assessment : active) {
            tally.add(assessment, region, null);
        }
        tally.write(insights, true);
        return mapper.createObjectNode().set("insights", insights);
    }

    public synchronized ObjectNode insightsByAssessment(String id, String region) {
        check("GetInsightsByAssessment", null, "assessmentId", id);
        account.requireActive(region);
        ObjectNode assessment = require(id, region);
        collect(assessment, region, false);
        Tally tally = new Tally();
        tally.add(assessment, region, null);
        ObjectNode insights = mapper.createObjectNode();
        tally.write(insights, true);
        return mapper.createObjectNode().set("insights", insights);
    }

    public synchronized ObjectNode domainInsights(Integer maxResults, String nextToken, String region) {
        check("ListControlDomainInsights", null, "maxResults", maxResults, "nextToken", nextToken);
        account.requireActive(region);
        List<ObjectNode> active = active(region);
        active.forEach(a -> collect(a, region, false));
        return domainPage(active, maxResults, nextToken, region);
    }

    public synchronized ObjectNode domainInsightsByAssessment(String id, Integer maxResults, String nextToken,
                                                              String region) {
        check("ListControlDomainInsightsByAssessment", null, "assessmentId", id, "maxResults", maxResults,
                "nextToken", nextToken);
        account.requireActive(region);
        ObjectNode assessment = require(id, region);
        collect(assessment, region, false);
        return domainPage(List.of(assessment), maxResults, nextToken, region);
    }

    public synchronized ObjectNode controlInsights(String domainId, Integer maxResults, String nextToken,
                                                   String region) {
        check("ListControlInsightsByControlDomain", null, "controlDomainId", domainId, "maxResults", maxResults,
                "nextToken", nextToken);
        account.requireActive(region);
        String domain = requireDomain(domainId);
        List<ObjectNode> active = active(region);
        active.forEach(a -> collect(a, region, false));
        Map<String, ObjectNode> byControl = new LinkedHashMap<>();
        for (ObjectNode assessment : active) {
            for (JsonNode set : controlSets(assessment)) {
                for (JsonNode control : set.path("controls")) {
                    if (!domain.equals(domainKey(control.path("id").asText()))) {
                        continue;
                    }
                    ObjectNode item = byControl.computeIfAbsent(control.path("id").asText(), k -> {
                        ObjectNode fresh = mapper.createObjectNode();
                        fresh.put("name", control.path("name").asText());
                        fresh.put("id", k);
                        fresh.set("evidenceInsights", emptyEvidenceInsights());
                        fresh.put("lastUpdated", now());
                        return fresh;
                    });
                    addEvidenceInsights((ObjectNode) item.path("evidenceInsights"),
                            evidenceOfControl(region, assessment, set.path("id").asText(), k(control)));
                }
            }
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("controlInsightsMetadata");
        page(new ArrayList<>(byControl.values()), maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    public synchronized ObjectNode assessmentControlInsights(String domainId, String id, Integer maxResults,
                                                             String nextToken, String region) {
        check("ListAssessmentControlInsightsByControlDomain", null, "controlDomainId", domainId, "assessmentId", id,
                "maxResults", maxResults, "nextToken", nextToken);
        account.requireActive(region);
        String domain = requireDomain(domainId);
        ObjectNode assessment = require(id, region);
        collect(assessment, region, false);
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode set : controlSets(assessment)) {
            for (JsonNode control : set.path("controls")) {
                if (!domain.equals(domainKey(control.path("id").asText()))) {
                    continue;
                }
                ObjectNode item = mapper.createObjectNode();
                item.put("name", control.path("name").asText());
                item.put("id", control.path("id").asText());
                ObjectNode counts = emptyEvidenceInsights();
                addEvidenceInsights(counts, evidenceOfControl(region, assessment, set.path("id").asText(),
                        k(control)));
                item.set("evidenceInsights", counts);
                item.put("controlSetName", set.path("id").asText());
                item.put("lastUpdated", now());
                items.add(item);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("controlInsightsByAssessment");
        page(items, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    private ObjectNode domainPage(List<ObjectNode> scope, Integer maxResults, String nextToken, String region) {
        Map<String, Tally> byDomain = new LinkedHashMap<>();
        for (ObjectNode assessment : scope) {
            for (JsonNode set : controlSets(assessment)) {
                for (JsonNode control : set.path("controls")) {
                    byDomain.computeIfAbsent(domainKey(control.path("id").asText()), k -> new Tally())
                            .addControl(evidenceOfControl(region, assessment, set.path("id").asText(), k(control)));
                }
            }
        }
        List<ObjectNode> items = new ArrayList<>();
        for (Map.Entry<String, Tally> entry : byDomain.entrySet()) {
            ObjectNode item = mapper.createObjectNode();
            Optional<AuditManagerCatalog.Domain> domain = AuditManagerCatalog.DOMAINS.stream()
                    .filter(d -> d.id().equals(entry.getKey())).findFirst();
            item.put("name", domain.map(AuditManagerCatalog.Domain::name).orElse("Uncategorized"));
            item.put("id", domain.map(d -> d.arn(account.partition())).orElse("UNCATEGORIZED"));
            item.put("controlsCountByNoncompliantEvidence", entry.getValue().noncompliantControls);
            item.put("totalControlsCount", entry.getValue().controls);
            ObjectNode counts = item.putObject("evidenceInsights");
            counts.put("noncompliantEvidenceCount", entry.getValue().noncompliant);
            counts.put("compliantEvidenceCount", entry.getValue().compliant);
            counts.put("inconclusiveEvidenceCount", entry.getValue().inconclusive);
            item.put("lastUpdated", now());
            items.add(item);
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("controlDomainInsights");
        page(items, maxResults, nextToken, out).forEach(list::add);
        return out;
    }

    private String requireDomain(String domainId) {
        if ("UNCATEGORIZED".equals(domainId)) {
            return "UNCATEGORIZED";
        }
        return AuditManagerCatalog.domain(account.partition(), domainId).map(AuditManagerCatalog.Domain::id)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "The control domain " + domainId
                        + " doesn't exist.", 404));
    }

    private static String domainKey(String controlId) {
        return AuditManagerService.domainOf(controlId).map(AuditManagerCatalog.Domain::id).orElse("UNCATEGORIZED");
    }

    /** Evidence counts by compliance check result, over one or more assessments' controls. */
    private final class Tally {
        int compliant;
        int noncompliant;
        int inconclusive;
        int controls;
        int noncompliantControls;

        void add(ObjectNode assessment, String region, String onlyDomain) {
            for (JsonNode set : controlSets(assessment)) {
                for (JsonNode control : set.path("controls")) {
                    if (onlyDomain == null || onlyDomain.equals(domainKey(control.path("id").asText()))) {
                        addControl(evidenceOfControl(region, assessment, set.path("id").asText(), k(control)));
                    }
                }
            }
        }

        void addControl(List<ObjectNode> records) {
            controls++;
            boolean failing = false;
            for (ObjectNode record : records) {
                switch (complianceOf(record)) {
                    case "COMPLIANT" -> compliant++;
                    case "NON_COMPLIANT" -> {
                        noncompliant++;
                        failing = true;
                    }
                    default -> inconclusive++;
                }
            }
            if (failing) {
                noncompliantControls++;
            }
        }

        void write(ObjectNode out, boolean controlCounts) {
            out.put("noncompliantEvidenceCount", noncompliant);
            out.put("compliantEvidenceCount", compliant);
            out.put("inconclusiveEvidenceCount", inconclusive);
            if (controlCounts) {
                out.put("assessmentControlsCountByNoncompliantEvidence", noncompliantControls);
                out.put("totalAssessmentControlsCount", controls);
            }
            out.put("lastUpdated", now());
        }
    }

    private ObjectNode emptyEvidenceInsights() {
        return mapper.createObjectNode().put("noncompliantEvidenceCount", 0).put("compliantEvidenceCount", 0)
                .put("inconclusiveEvidenceCount", 0);
    }

    private static void addEvidenceInsights(ObjectNode counts, List<ObjectNode> records) {
        for (ObjectNode record : records) {
            String field = switch (complianceOf(record)) {
                case "COMPLIANT" -> "compliantEvidenceCount";
                case "NON_COMPLIANT" -> "noncompliantEvidenceCount";
                default -> "inconclusiveEvidenceCount";
            };
            counts.put(field, counts.path(field).asInt() + 1);
        }
    }

    /** A compliance check's result as insights count it: {@code COMPLIANT}, {@code NON_COMPLIANT} or inconclusive. */
    private static String complianceOf(ObjectNode record) {
        return switch (record.path("complianceCheck").asText("")) {
            case "COMPLIANT", "PASSED" -> "COMPLIANT";
            case "NON_COMPLIANT", "FAILED" -> "NON_COMPLIANT";
            default -> "INCONCLUSIVE";
        };
    }

    // ── Collection ─────────────────────────────────────────────────────────

    /**
     * Collects compliance check evidence from AWS Config for an active assessment's controls that map to a Config
     * rule: each evaluation result of a rule whose managed rule identifier is the source's keyword (or, for a
     * {@code Custom_<name>} keyword, of the custom rule with that name) becomes evidence, filed in the control's
     * folder for the day the result was recorded. Runs when the assessment is created, and again when its evidence
     * or insights are read, at most every 30 seconds.
     */
    private void collect(ObjectNode assessment, String region, boolean force) {
        if (!configService.isResolvable() || !"ACTIVE".equals(assessment.path("metadata").path("status").asText())) {
            return;
        }
        double now = now();
        if (!force && now - assessment.path("lastCollected").asDouble(0) < COLLECTION_INTERVAL_SECONDS) {
            return;
        }
        String id = assessment.path("metadata").path("id").asText();
        List<ConfigRule> rules;
        try {
            rules = configService.get().describeConfigRules(region, null);
        } catch (AwsException e) {
            rules = List.of();
        }
        for (JsonNode set : controlSets(assessment)) {
            for (JsonNode control : set.path("controls")) {
                if ("INACTIVE".equals(control.path("status").asText())) {
                    continue;
                }
                for (JsonNode source : control.path("mappingSources")) {
                    if (!"AWS_Config".equals(source.path("sourceType").asText())) {
                        continue;
                    }
                    String keyword = source.path("sourceKeyword").path("keywordValue").asText("");
                    for (ConfigRule rule : rules) {
                        boolean matches = rule.source() != null && keyword.equals(rule.source().sourceIdentifier())
                                || keyword.equals("Custom_" + rule.configRuleName());
                        if (matches) {
                            collectRule(assessment, region, set.path("id").asText(), (ObjectNode) control, rule,
                                    source.path("sourceName").asText());
                        }
                    }
                }
            }
        }
        assessment.put("lastCollected", now);
        assessments.put(region + "/" + id, assessment);
    }

    private void collectRule(ObjectNode assessment, String region, String controlSetId, ObjectNode control,
                             ConfigRule rule, String sourceName) {
        String id = assessment.path("metadata").path("id").asText();
        String token = null;
        do {
            AwsConfigService.Paged<EvaluationResult> page;
            try {
                page = configService.get().getComplianceDetailsByConfigRule(region, rule.configRuleName(), null, 100,
                        token);
            } catch (AwsException e) {
                return;
            }
            for (EvaluationResult result : page.items()) {
                EvaluationResultQualifier qualifier = result.evaluationResultIdentifier() == null ? null
                        : result.evaluationResultIdentifier().evaluationResultQualifier();
                if (qualifier == null) {
                    continue;
                }
                long recorded = result.resultRecordedTime() == null ? (long) now() : result.resultRecordedTime();
                String evidenceId = AuditManagerCatalog.nameId("evidence:" + id + "/" + controlSetId + "/"
                        + control.path("id").asText() + "/" + rule.configRuleName() + "/" + qualifier.resourceType()
                        + "/" + qualifier.resourceId() + "/" + recorded + "/" + result.complianceType());
                ObjectNode folder = folder(assessment, controlSetId, control,
                        LocalDate.ofInstant(Instant.ofEpochSecond(recorded), ZoneOffset.UTC), "AWS Config", region);
                String key = evidenceKey(region, id, folder.path("id").asText(), evidenceId);
                if (evidence.get(key).isPresent()) {
                    continue;
                }
                ObjectNode record = mapper.createObjectNode();
                record.put("dataSource", "AWS Config");
                record.put("evidenceAwsAccountId", account.accountId());
                record.put("time", (double) recorded);
                record.put("eventSource", "config.amazonaws.com"); // partition-literal: CloudTrail event source names are the same in every partition
                record.put("eventName", rule.configRuleName());
                record.put("evidenceByType", "Compliance check");
                ObjectNode resource = record.putArray("resourcesIncluded").addObject();
                resource.put("value", qualifier.resourceType() + "/" + qualifier.resourceId());
                resource.put("complianceCheck", result.complianceType());
                ObjectNode attributes = record.putObject("attributes");
                attributes.put("configRuleName", rule.configRuleName());
                attributes.put("sourceName", sourceName);
                attributes.put("resourceType", qualifier.resourceType());
                attributes.put("resourceId", qualifier.resourceId());
                if (result.annotation() != null) {
                    attributes.put("annotation", result.annotation());
                }
                record.put("iamId", account.callerArn());
                record.put("complianceCheck", result.complianceType());
                record.put("awsAccountId", account.accountId());
                record.put("evidenceFolderId", folder.path("id").asText());
                record.put("id", evidenceId);
                record.put("assessmentReportSelection", "No");
                evidence.put(key, record);
            }
            token = page.nextToken();
        } while (token != null);
    }

    // ── Folders ────────────────────────────────────────────────────────────

    /** The folder for a control's evidence from one data source on one day, created on first use. */
    private ObjectNode folder(ObjectNode assessment, String controlSetId, JsonNode control, LocalDate day,
                              String dataSource, String region) {
        String id = assessment.path("metadata").path("id").asText();
        String folderId = AuditManagerCatalog.nameId("folder:" + id + "/" + controlSetId + "/"
                + control.path("id").asText() + "/" + day + "/" + dataSource);
        String key = region + "/" + id + "/" + folderId;
        return folders.get(key).orElseGet(() -> {
            ObjectNode folder = mapper.createObjectNode();
            folder.put("name", day.toString());
            folder.put("date", (double) day.atStartOfDay(ZoneOffset.UTC).toEpochSecond());
            folder.put("assessmentId", id);
            folder.put("controlSetId", controlSetId);
            folder.put("controlId", control.path("id").asText());
            folder.put("id", folderId);
            folder.put("dataSource", dataSource);
            folder.put("author", "Manual".equals(dataSource) ? account.callerArn() : "AWS Audit Manager");
            folder.put("controlName", control.path("name").asText());
            folders.put(key, folder);
            return folder;
        });
    }

    private ObjectNode folderPage(List<ObjectNode> all, Integer maxResults, String nextToken, String region) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("evidenceFolders");
        page(all, maxResults, nextToken, out).forEach(f -> list.add(renderFolder(f, region)));
        return out;
    }

    private List<ObjectNode> foldersOf(String region, String id) {
        return folders.scan(k -> k.startsWith(region + "/" + id + "/")).stream()
                .sorted(Comparator.comparing((ObjectNode f) -> -f.path("date").asDouble())
                        .thenComparing(f -> f.path("controlName").asText())).toList();
    }

    private ObjectNode requireFolder(String region, String id, String controlSetId, String folderId) {
        return folders.get(region + "/" + id + "/" + folderId)
                .filter(f -> controlSetId.equals(f.path("controlSetId").asText()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "The evidence folder " + folderId
                        + " doesn't exist in the control set " + controlSetId + ".", 404));
    }

    private ObjectNode renderFolder(ObjectNode stored, String region) {
        ObjectNode folder = stored.deepCopy();
        List<ObjectNode> records = evidenceIn(region, stored.path("assessmentId").asText(),
                stored.path("id").asText());
        int selected = 0;
        int resources = 0;
        int configuration = 0;
        int manual = 0;
        int checks = 0;
        int issues = 0;
        int activity = 0;
        Set<String> services = new LinkedHashSet<>();
        for (ObjectNode record : records) {
            selected += "Yes".equals(record.path("assessmentReportSelection").asText()) ? 1 : 0;
            resources += record.path("resourcesIncluded").size();
            switch (record.path("evidenceByType").asText()) {
                case "Manual" -> manual++;
                case "Compliance check" -> {
                    checks++;
                    issues += "NON_COMPLIANT".equals(complianceOf(record)) ? 1 : 0;
                }
                case "Configuration data" -> configuration++;
                case "User activity" -> activity++;
                default -> {
                }
            }
            if (!"Manual".equals(record.path("dataSource").asText())) {
                services.add(record.path("eventSource").asText());
            }
        }
        folder.put("totalEvidence", records.size());
        folder.put("assessmentReportSelectionCount", selected);
        folder.put("evidenceResourcesIncludedCount", resources);
        folder.put("evidenceByTypeConfigurationDataCount", configuration);
        folder.put("evidenceByTypeManualCount", manual);
        folder.put("evidenceByTypeComplianceCheckCount", checks);
        folder.put("evidenceByTypeComplianceCheckIssuesCount", issues);
        folder.put("evidenceByTypeUserActivityCount", activity);
        folder.put("evidenceAwsServiceSourceCount", services.size());
        return folder;
    }

    private static String evidenceKey(String region, String assessmentId, String folderId, String evidenceId) {
        return region + "/" + assessmentId + "/" + folderId + "/" + evidenceId;
    }

    private List<ObjectNode> evidenceIn(String region, String id, String folderId) {
        return evidence.scan(k -> k.startsWith(region + "/" + id + "/" + folderId + "/")).stream()
                .sorted(Comparator.comparing(e -> -e.path("time").asDouble())).toList();
    }

    private List<ObjectNode> allEvidence(String region, String id) {
        return evidence.scan(k -> k.startsWith(region + "/" + id + "/"));
    }

    private List<ObjectNode> evidenceOfControl(String region, ObjectNode assessment, String controlSetId,
                                               String controlId) {
        String id = assessment.path("metadata").path("id").asText();
        Set<String> folderIds = new LinkedHashSet<>();
        for (ObjectNode folder : foldersOf(region, id)) {
            if (controlSetId.equals(folder.path("controlSetId").asText())
                    && controlId.equals(folder.path("controlId").asText())) {
                folderIds.add(folder.path("id").asText());
            }
        }
        List<ObjectNode> out = new ArrayList<>();
        for (String folderId : folderIds) {
            out.addAll(evidenceIn(region, id, folderId));
        }
        return out;
    }

    private static String k(JsonNode control) {
        return control.path("id").asText();
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    /** An assessment as the API returns it, with its evidence counts. */
    ObjectNode render(ObjectNode stored, String region) {
        ObjectNode assessment = stored.deepCopy();
        assessment.remove("changeLogs");
        assessment.remove("lastCollected");
        ArrayNode sets = mapper.createArrayNode();
        for (JsonNode set : controlSets(stored)) {
            sets.add(renderControlSet(stored, (ObjectNode) set, region));
        }
        ((ObjectNode) assessment.path("framework")).set("controlSets", sets);
        return assessment;
    }

    private ObjectNode renderControlSet(ObjectNode assessment, ObjectNode set, String region) {
        ObjectNode out = set.deepCopy();
        ArrayNode controls = mapper.createArrayNode();
        int system = 0;
        int manual = 0;
        for (JsonNode control : set.path("controls")) {
            for (ObjectNode record : evidenceOfControl(region, assessment, set.path("id").asText(), k(control))) {
                if ("Manual".equals(record.path("dataSource").asText())) {
                    manual++;
                } else {
                    system++;
                }
            }
            controls.add(renderControl(assessment, set.path("id").asText(), (ObjectNode) control, region));
        }
        out.set("controls", controls);
        out.put("systemEvidenceCount", system);
        out.put("manualEvidenceCount", manual);
        return out;
    }

    private ObjectNode renderControl(ObjectNode assessment, String controlSetId, ObjectNode control, String region) {
        ObjectNode out = control.deepCopy();
        out.remove("mappingSources");
        List<ObjectNode> records = evidenceOfControl(region, assessment, controlSetId, k(control));
        out.put("evidenceCount", records.size());
        out.put("assessmentReportEvidenceCount", (int) records.stream()
                .filter(r -> "Yes".equals(r.path("assessmentReportSelection").asText())).count());
        return out;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ObjectNode require(String id, String region) {
        return assessments.get(region + "/" + id).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The assessment " + id + " doesn't exist.", 404));
    }

    private ObjectNode requireActiveAssessment(String id, String region) {
        ObjectNode assessment = require(id, region);
        if (!"ACTIVE".equals(assessment.path("metadata").path("status").asText())) {
            throw validation("The assessment " + id + " is inactive, so it can't be changed.");
        }
        return assessment;
    }

    private static Iterable<JsonNode> controlSets(ObjectNode assessment) {
        return assessment.path("framework").path("controlSets");
    }

    private Optional<ObjectNode> findControlSet(ObjectNode assessment, String controlSetId) {
        for (JsonNode set : controlSets(assessment)) {
            if (set.path("id").asText().equals(controlSetId)) {
                return Optional.of((ObjectNode) set);
            }
        }
        return Optional.empty();
    }

    private ObjectNode requireControlSet(ObjectNode assessment, String controlSetId) {
        return findControlSet(assessment, controlSetId).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The control set " + controlSetId + " isn't in the assessment.", 404));
    }

    private ObjectNode requireControl(ObjectNode assessment, String controlSetId, String controlId) {
        for (JsonNode control : requireControlSet(assessment, controlSetId).path("controls")) {
            if (controlId.equals(control.path("id").asText())) {
                return (ObjectNode) control;
            }
        }
        throw new AwsException("ResourceNotFoundException", "The control " + controlId + " isn't in the control set "
                + controlSetId + ".", 404);
    }

    private List<ObjectNode> all(String region) {
        return assessments.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing(a -> a.path("metadata").path("creationTime").asDouble())).toList();
    }

    private List<ObjectNode> active(String region) {
        return all(region).stream().filter(a -> "ACTIVE".equals(a.path("metadata").path("status").asText()))
                .toList();
    }

    private void save(ObjectNode assessment, String region) {
        assessments.put(region + "/" + assessment.path("metadata").path("id").asText(), assessment);
    }

    private static void touch(ObjectNode assessment) {
        ((ObjectNode) assessment.path("metadata")).put("lastUpdated", now());
    }

    private void log(ObjectNode assessment, String objectType, String objectName, String action, String controlSetId,
                     String controlId) {
        ObjectNode entry = assessment.withArray("changeLogs").addObject();
        entry.put("objectType", objectType);
        entry.put("objectName", objectName);
        entry.put("action", action);
        entry.put("createdAt", now());
        entry.put("createdBy", account.callerArn());
        if (controlSetId != null) {
            entry.put("controlSetId", controlSetId);
        }
        if (controlId != null) {
            entry.put("controlId", controlId);
        }
    }

    private ArrayNode roles(JsonNode requested) {
        ArrayNode out = mapper.createArrayNode();
        boolean owner = false;
        for (JsonNode role : requested) {
            account.checkRole(role.path("roleArn").asText());
            owner |= "PROCESS_OWNER".equals(role.path("roleType").asText());
            out.add(role.deepCopy());
        }
        if (!owner) {
            throw validation("An assessment needs at least one PROCESS_OWNER role.");
        }
        return out;
    }

    private ObjectNode destination(JsonNode requested, String region) {
        String destination = requested.path("destination").asText("");
        if (destination.isEmpty()) {
            destination = account.settings(region).path("defaultAssessmentReportsDestination").path("destination")
                    .asText("");
        }
        String[] location = s3Location(destination).orElseThrow(() -> validation(
                "assessmentReportsDestination must be an S3 URI (s3://bucket/prefix)."));
        if (s3.isResolvable() && !s3.get().bucketExists(location[0])) {
            throw validation("The assessment report destination bucket " + location[0] + " doesn't exist.");
        }
        return mapper.createObjectNode().put("destinationType", requested.path("destinationType").asText("S3"))
                .put("destination", destination);
    }

    /**
     * The scope as stored: each account with the email and name Organizations knows it by, and only accounts of
     * the caller's organization (or the caller itself).
     */
    private ObjectNode scope(JsonNode requested) {
        ObjectNode scope = mapper.createObjectNode();
        ArrayNode accounts = scope.putArray("awsAccounts");
        String caller = account.accountId();
        Optional<String> organization = account.organizationOf(caller).map(o -> o.getId());
        for (JsonNode requestedAccount : requested.path("awsAccounts")) {
            String id = requestedAccount.path("id").asText("");
            boolean member = id.equals(caller) || organization.isPresent()
                    && organization.equals(account.organizationOf(id).map(o -> o.getId()));
            if (!member) {
                throw validation("The account " + id + " isn't this account or an account of its organization.");
            }
            accounts.add(awsAccount(id));
        }
        ArrayNode services = scope.putArray("awsServices");
        for (JsonNode service : requested.path("awsServices")) {
            String name = service.path("serviceName").asText("");
            if (AuditManagerCatalog.SERVICES.stream().noneMatch(s -> s.name().equals(name))) {
                throw validation("The service " + name + " isn't in scope for Audit Manager; see "
                        + "GetServicesInScope.");
            }
            services.addObject().put("serviceName", name);
        }
        return scope;
    }

    private ObjectNode awsAccount(String id) {
        ObjectNode out = mapper.createObjectNode().put("id", id);
        Optional<OrganizationAccount> known = account.organizationAccount(id);
        known.ifPresent(a -> {
            if (a.getEmail() != null) {
                out.put("emailAddress", a.getEmail());
            }
            if (a.getName() != null) {
                out.put("name", a.getName());
            }
        });
        return out;
    }

    /** The bucket and key of an {@code s3://bucket/key} URI. */
    private static Optional<String[]> s3Location(String uri) {
        if (uri == null || !uri.toLowerCase().startsWith("s3://")) {
            return Optional.empty();
        }
        String rest = uri.substring(5);
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String key = slash < 0 ? "" : rest.substring(slash + 1);
        return bucket.isEmpty() ? Optional.empty() : Optional.of(new String[] {bucket, key});
    }

    private boolean objectExists(String bucket, String key) {
        if (!s3.isResolvable()) {
            return true;
        }
        try {
            s3.get().headObject(bucket, key);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    /** The tags of an assessment an ARN names, for the shared tag routes. */
    Optional<AuditManagerService.Tagged> tagged(String arn, String region) {
        String resource = AwsArnUtils.parse(arn).resource();
        if (!resource.startsWith("assessment/")) {
            return Optional.empty();
        }
        String key = region + "/" + resource.substring("assessment/".length());
        return assessments.get(key).map(a -> new AuditManagerService.Tagged(assessments, key, a));
    }

    @Override
    public void clear() {
        assessments.clear();
        folders.clear();
        evidence.clear();
        reports.clear();
        notifications.clear();
        keys.clear();
    }

}
