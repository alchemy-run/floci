package io.floci.aws.services.auditmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.organizations.model.Organization;
import io.floci.aws.services.organizations.model.OrganizationAccount;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Audit Manager's account surface: registration, settings and the delegated administrator; the control
 * library (the catalog's Core controls and the account's custom controls, each mapped to its evidence sources);
 * framework libraries (the catalog's standard framework and custom frameworks built from controls), shared with
 * another account and Region as a replicated copy; and the upload URLs manual evidence files go through.
 * Until an account registers in a Region, every operation but {@code GetAccountStatus} and
 * {@code RegisterAccount} answers {@code AccessDeniedException}, as AWS does.
 */
@ApplicationScoped
public class AuditManagerService implements Resettable {
    static final String SERVICE_PRINCIPAL = "auditmanager.amazonaws.com"; // partition-literal: the service principal name AWS uses in every partition
    static final String SETUP_MESSAGE = "Please complete AWS Audit Manager setup from home page to enable this "
            + "action in this account.";
    private static final int MAX_CUSTOM_CONTROLS = 500;
    private static final int MAX_CUSTOM_FRAMEWORKS = 100;
    private static final Duration SHARE_LIFETIME = Duration.ofDays(120);

    private static final RequestConstraintCheck SHAPES = RequestConstraintCheck.load(
            "aws/auditmanager-request-shapes.json", "Audit Manager request shapes",
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return validation(path + " must be a " + type + ".");
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return validation(path + " is outside the allowed bounds" + (min == null ? "" : " (min " + min
                            + ")") + (max == null ? "" : " (max " + max + ")") + ".");
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return validation(path + " does not match the pattern " + pattern + ".");
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return validation(path + " must be one of " + values + ".");
                }

                @Override
                public AwsException required(String path) {
                    return validation(path + " is required.");
                }
            });

    private final AccountAwareStorageBackend<ObjectNode> accounts;
    private final AccountAwareStorageBackend<ObjectNode> controls;
    private final AccountAwareStorageBackend<ObjectNode> frameworks;
    private final AccountAwareStorageBackend<ObjectNode> shares;
    private final AccountAwareStorageBackend<ObjectNode> uploads;
    private final Instance<IamService> iam;
    private final Instance<KmsService> kms;
    private final Instance<SnsService> sns;
    private final Instance<OrganizationsService> organizations;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;

    @Inject
    public AuditManagerService(StorageFactory storageFactory, Instance<IamService> iam, Instance<KmsService> kms,
                               Instance<SnsService> sns, Instance<OrganizationsService> organizations,
                               RegionResolver regionResolver, EmulatorConfig config, ObjectMapper mapper) {
        this.accounts = store(storageFactory, "auditmanager-accounts.json");
        this.controls = store(storageFactory, "auditmanager-controls.json");
        this.frameworks = store(storageFactory, "auditmanager-frameworks.json");
        this.shares = store(storageFactory, "auditmanager-framework-shares.json");
        this.uploads = store(storageFactory, "auditmanager-evidence-uploads.json");
        this.iam = iam;
        this.kms = kms;
        this.sns = sns;
        this.organizations = organizations;
        this.regionResolver = regionResolver;
        this.config = config;
        this.mapper = mapper;
    }

    static AccountAwareStorageBackend<ObjectNode> store(StorageFactory factory, String file) {
        return factory.create("auditmanager", file, new TypeReference<Map<String, ObjectNode>>() {});
    }

    /** Checks a request against the model; {@code members} alternates the names and values of URI and query members. */
    static void check(String operation, JsonNode request, Object... members) {
        ObjectNode checked = request != null && request.isObject() ? ((ObjectNode) request).deepCopy()
                : new ObjectMapper().createObjectNode();
        for (int i = 0; i + 1 < members.length; i += 2) {
            Object value = members[i + 1];
            if (value instanceof Integer number) {
                checked.put((String) members[i], number);
            } else if (value instanceof List<?> list) {
                ArrayNode array = checked.putArray((String) members[i]);
                list.forEach(item -> array.add(String.valueOf(item)));
            } else if (value != null) {
                checked.put((String) members[i], value.toString());
            }
        }
        SHAPES.check(operation, checked);
    }

    // ── Account ────────────────────────────────────────────────────────────

    public synchronized ObjectNode accountStatus(String region) {
        return mapper.createObjectNode().put("status", account(region).path("status").asText("INACTIVE"));
    }

    public synchronized ObjectNode register(JsonNode in, String region) {
        check("RegisterAccount", in);
        ObjectNode account = account(region);
        if (in.hasNonNull("kmsKey")) {
            account.withObject("settings").put("kmsKey", checkKmsKey(in.path("kmsKey").asText(), region));
        }
        if (in.hasNonNull("delegatedAdminAccount")) {
            registerAdmin(in.path("delegatedAdminAccount").asText());
        }
        account.put("status", "ACTIVE");
        accounts.put(region, account);
        return mapper.createObjectNode().put("status", "ACTIVE");
    }

    /**
     * Deregisters the account in the Region. With the {@code ALL} deregistration policy, its assessments, custom
     * controls and frameworks, evidence and reports are deleted; with {@code DEFAULT} they are kept for when the
     * account registers again.
     */
    public synchronized ObjectNode deregister(String region, Runnable deleteAssessmentData) {
        requireActive(region);
        ObjectNode account = account(region);
        if ("ALL".equals(account.path("settings").path("deregistrationPolicy").path("deleteResources").asText())) {
            deleteAssessmentData.run();
            controls.keys().stream().filter(k -> k.startsWith(region + "/")).toList().forEach(controls::delete);
            frameworks.keys().stream().filter(k -> k.startsWith(region + "/")).toList().forEach(frameworks::delete);
        }
        account.put("status", "INACTIVE");
        accounts.put(region, account);
        return mapper.createObjectNode().put("status", "INACTIVE");
    }

    /** Fails with AWS's {@code AccessDeniedException} until the account has registered in the Region. */
    public void requireActive(String region) {
        if (!"ACTIVE".equals(account(region).path("status").asText())) {
            throw new AwsException("AccessDeniedException", SETUP_MESSAGE, 403);
        }
    }

    boolean isActive(String account, String region) {
        return accounts.getForAccount(account, region).map(a -> "ACTIVE".equals(a.path("status").asText()))
                .orElse(false);
    }

    private ObjectNode account(String region) {
        return accounts.get(region).orElseGet(() -> {
            ObjectNode fresh = mapper.createObjectNode();
            fresh.put("status", "INACTIVE");
            return fresh;
        });
    }

    // ── Organization admin ─────────────────────────────────────────────────

    public synchronized ObjectNode registerOrganizationAdmin(JsonNode in, String region) {
        check("RegisterOrganizationAdminAccount", in);
        requireActive(region);
        String admin = in.path("adminAccountId").asText();
        Organization organization = registerAdmin(admin);
        return mapper.createObjectNode().put("adminAccountId", admin).put("organizationId", organization.getId());
    }

    public synchronized void deregisterOrganizationAdmin(JsonNode in, String region) {
        check("DeregisterOrganizationAdminAccount", in);
        requireActive(region);
        String caller = regionResolver.getAccountId();
        Organization organization = organizationOf(caller).orElseThrow(() -> validation(
                "The account isn't a member of an organization in AWS Organizations."));
        String admin = in.hasNonNull("adminAccountId") ? in.path("adminAccountId").asText()
                : delegatedAdmin(organization).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The organization has no delegated administrator for Audit Manager.", 404));
        try {
            organizations.get().deregisterDelegatedAdministrator(caller, admin, SERVICE_PRINCIPAL);
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException", "The account " + admin + " isn't the delegated "
                    + "administrator for Audit Manager: " + e.getMessage(), 404);
        }
    }

    public synchronized ObjectNode organizationAdmin(String region) {
        requireActive(region);
        Organization organization = organizationOf(regionResolver.getAccountId()).orElseThrow(() -> validation(
                "The account isn't a member of an organization in AWS Organizations."));
        String admin = delegatedAdmin(organization).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The organization has no delegated administrator for Audit Manager.", 404));
        return mapper.createObjectNode().put("adminAccountId", admin).put("organizationId", organization.getId());
    }

    private Organization registerAdmin(String admin) {
        String caller = regionResolver.getAccountId();
        Organization organization = organizationOf(caller).orElseThrow(() -> validation(
                "The account isn't a member of an organization in AWS Organizations."));
        if (!caller.equals(organization.getMasterAccountId())) {
            throw new AwsException("AccessDeniedException", "Only the management account of the organization can "
                    + "register a delegated administrator for Audit Manager.", 403);
        }
        try {
            organizations.get().registerDelegatedAdministrator(caller, admin, SERVICE_PRINCIPAL);
        } catch (AwsException e) {
            if (!"AccountAlreadyRegisteredException".equals(e.getErrorCode())) {
                throw validation(e.getMessage());
            }
        }
        return organization;
    }

    private Optional<String> delegatedAdmin(Organization organization) {
        return organizations.get().listDelegatedAdministrators(organization.getMasterAccountId(), SERVICE_PRINCIPAL)
                .stream().map(OrganizationAccount::getId).findFirst();
    }

    Optional<Organization> organizationOf(String account) {
        if (!organizations.isResolvable()) {
            return Optional.empty();
        }
        try {
            return Optional.of(organizations.get().describeOrganization(account));
        } catch (AwsException e) {
            return Optional.empty();
        }
    }

    Optional<OrganizationAccount> organizationAccount(String account) {
        return organizations.isResolvable() ? organizations.get().findAccountForPortal(account) : Optional.empty();
    }

    // ── Settings ───────────────────────────────────────────────────────────

    public synchronized ObjectNode getSettings(String attribute, String region) {
        check("GetSettings", null, "attribute", attribute);
        requireActive(region);
        ObjectNode all = settings(region);
        ObjectNode out = mapper.createObjectNode();
        switch (attribute) {
            case "ALL" -> out.setAll(all);
            case "IS_AWS_ORG_ENABLED" -> out.set("isAwsOrgEnabled", all.get("isAwsOrgEnabled"));
            case "SNS_TOPIC" -> copy(all, out, "snsTopic");
            case "DEFAULT_ASSESSMENT_REPORTS_DESTINATION" -> copy(all, out, "defaultAssessmentReportsDestination");
            case "DEFAULT_PROCESS_OWNERS" -> copy(all, out, "defaultProcessOwners");
            case "EVIDENCE_FINDER_ENABLEMENT" -> copy(all, out, "evidenceFinderEnablement");
            case "DEREGISTRATION_POLICY" -> copy(all, out, "deregistrationPolicy");
            case "DEFAULT_EXPORT_DESTINATION" -> copy(all, out, "defaultExportDestination");
            default -> throw validation("attribute isn't a settings attribute.");
        }
        return mapper.createObjectNode().set("settings", out);
    }

    public synchronized ObjectNode updateSettings(JsonNode in, String region) {
        check("UpdateSettings", in);
        requireActive(region);
        ObjectNode account = account(region);
        ObjectNode stored = account.withObject("settings");
        if (in.has("snsTopic")) {
            String topic = in.path("snsTopic").asText("");
            if (topic.isEmpty()) {
                stored.remove("snsTopic");
            } else {
                if (sns.isResolvable() && !sns.get().topicExists(topic, region)) {
                    throw validation("The SNS topic " + topic + " doesn't exist.");
                }
                stored.put("snsTopic", topic);
            }
        }
        if (in.has("defaultAssessmentReportsDestination")) {
            stored.set("defaultAssessmentReportsDestination",
                    destination(in.path("defaultAssessmentReportsDestination")));
        }
        if (in.has("defaultProcessOwners")) {
            for (JsonNode role : in.path("defaultProcessOwners")) {
                checkRole(role.path("roleArn").asText());
            }
            stored.set("defaultProcessOwners", in.path("defaultProcessOwners").deepCopy());
        }
        if (in.hasNonNull("kmsKey")) {
            stored.put("kmsKey", checkKmsKey(in.path("kmsKey").asText(), region));
        }
        if (in.hasNonNull("evidenceFinderEnabled")) {
            ObjectNode finder = mapper.createObjectNode();
            if (in.path("evidenceFinderEnabled").asBoolean()) {
                String store = stored.path("evidenceFinderEnablement").path("eventDataStoreArn").asText(null);
                finder.put("eventDataStoreArn", store != null ? store : AwsArnUtils.Arn.of("cloudtrail", region,
                        regionResolver.getAccountId(), "eventdatastore/" + UUID.randomUUID()).toString());
                finder.put("enablementStatus", "ENABLED");
                finder.put("backfillStatus", "COMPLETED");
            } else {
                finder.put("enablementStatus", "DISABLED");
            }
            stored.set("evidenceFinderEnablement", finder);
        }
        if (in.has("deregistrationPolicy")) {
            stored.set("deregistrationPolicy", in.path("deregistrationPolicy").deepCopy());
        }
        if (in.has("defaultExportDestination")) {
            stored.set("defaultExportDestination", destination(in.path("defaultExportDestination")));
        }
        accounts.put(region, account);
        return mapper.createObjectNode().set("settings", settings(region));
    }

    /** The account's settings in the Region, with AWS's defaults for what was never set. */
    ObjectNode settings(String region) {
        ObjectNode stored = account(region).path("settings").isObject()
                ? (ObjectNode) account(region).path("settings").deepCopy() : mapper.createObjectNode();
        ObjectNode out = mapper.createObjectNode();
        out.put("isAwsOrgEnabled", organizationOf(regionResolver.getAccountId()).isPresent());
        copy(stored, out, "snsTopic");
        copy(stored, out, "defaultAssessmentReportsDestination");
        out.set("defaultProcessOwners", stored.path("defaultProcessOwners").isArray()
                ? stored.get("defaultProcessOwners") : mapper.createArrayNode());
        out.put("kmsKey", stored.path("kmsKey").asText("DEFAULT"));
        out.set("evidenceFinderEnablement", stored.path("evidenceFinderEnablement").isObject()
                ? stored.get("evidenceFinderEnablement")
                : mapper.createObjectNode().put("enablementStatus", "DISABLED"));
        out.set("deregistrationPolicy", stored.path("deregistrationPolicy").isObject()
                ? stored.get("deregistrationPolicy") : mapper.createObjectNode().put("deleteResources", "DEFAULT"));
        copy(stored, out, "defaultExportDestination");
        return out;
    }

    boolean evidenceFinderEnabled(String region) {
        return "ENABLED".equals(settings(region).path("evidenceFinderEnablement").path("enablementStatus").asText());
    }

    /** Publishes an Audit Manager notification to the topic in the account's settings, if it has one. */
    void notify(String region, String subject, String message) {
        String topic = settings(region).path("snsTopic").asText(null);
        if (topic != null && sns.isResolvable() && sns.get().topicExists(topic, region)) {
            sns.get().publish(topic, null, message, subject, region);
        }
    }

    private ObjectNode destination(JsonNode requested) {
        String destination = requested.path("destination").asText("");
        if (!destination.toLowerCase().startsWith("s3://")) {
            throw validation("The destination must be an S3 URI (s3://bucket/prefix).");
        }
        return mapper.createObjectNode().put("destinationType", requested.path("destinationType").asText("S3"))
                .put("destination", destination);
    }

    private String checkKmsKey(String key, String region) {
        if (!"DEFAULT".equals(key) && kms.isResolvable() && kms.get().findKeyArn(key, region).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "The KMS key " + key + " doesn't exist.", 404);
        }
        return key;
    }

    /** Fails with {@code ValidationException} unless the IAM role exists. */
    void checkRole(String roleArn) {
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !roleArn.contains(":role/")) {
            throw validation("The ARN " + roleArn + " isn't the ARN of an IAM role.");
        }
        if (!iam.isResolvable()) {
            return;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        if (iam.get().findRole(arn.accountId(), name).isEmpty()) {
            throw validation("The IAM role " + roleArn + " doesn't exist.");
        }
    }

    // ── Controls ───────────────────────────────────────────────────────────

    public synchronized ObjectNode createControl(JsonNode in, String region) {
        check("CreateControl", in);
        requireActive(region);
        String name = in.path("name").asText();
        if (customControls(region).stream().anyMatch(c -> name.equals(c.path("name").asText()))) {
            throw validation("A custom control named " + name + " already exists.");
        }
        if (customControls(region).size() >= MAX_CUSTOM_CONTROLS) {
            throw new AwsException("ServiceQuotaExceededException", "The account has the maximum of "
                    + MAX_CUSTOM_CONTROLS + " custom controls.", 402);
        }
        ObjectNode control = mapper.createObjectNode();
        String id = UUID.randomUUID().toString();
        double now = now();
        control.put("arn", arn(region, "control/" + id));
        control.put("id", id);
        control.put("type", "Custom");
        control.put("name", name);
        copyText(in, control, "description", "testingInformation", "actionPlanTitle", "actionPlanInstructions");
        control.set("controlMappingSources", mappingSources(in.path("controlMappingSources")));
        control.put("createdAt", now);
        control.put("lastUpdatedAt", now);
        control.put("createdBy", callerArn());
        control.put("lastUpdatedBy", callerArn());
        control.put("state", "ACTIVE");
        ObjectNode tags = control.putObject("tags");
        in.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        controls.put(region + "/" + id, control);
        return mapper.createObjectNode().set("control", renderControl(control));
    }

    public synchronized ObjectNode getControl(String id, String region) {
        check("GetControl", null, "controlId", id);
        requireActive(region);
        return mapper.createObjectNode().set("control", renderControl(requireControl(id, region)));
    }

    public synchronized ObjectNode updateControl(String id, JsonNode in, String region) {
        check("UpdateControl", in, "controlId", id);
        requireActive(region);
        ObjectNode control = requireCustomControl(id, region);
        String name = in.path("name").asText();
        if (customControls(region).stream().anyMatch(c -> name.equals(c.path("name").asText())
                && !id.equals(c.path("id").asText()))) {
            throw validation("A custom control named " + name + " already exists.");
        }
        control.put("name", name);
        for (String field : List.of("description", "testingInformation", "actionPlanTitle",
                "actionPlanInstructions")) {
            if (in.hasNonNull(field)) {
                control.put(field, in.path(field).asText());
            } else {
                control.remove(field);
            }
        }
        control.set("controlMappingSources", mappingSources(in.path("controlMappingSources")));
        control.put("lastUpdatedAt", now());
        control.put("lastUpdatedBy", callerArn());
        controls.put(region + "/" + id, control);
        return mapper.createObjectNode().set("control", renderControl(control));
    }

    /**
     * Deletes a custom control, and takes it out of every custom framework it is part of, as AWS does; the
     * assessments built from those frameworks keep their copy.
     */
    public synchronized void deleteControl(String id, String region) {
        check("DeleteControl", null, "controlId", id);
        requireActive(region);
        requireCustomControl(id, region);
        controls.delete(region + "/" + id);
        for (ObjectNode framework : customFrameworks(region)) {
            boolean changed = false;
            for (JsonNode set : framework.path("controlSets")) {
                ArrayNode kept = mapper.createArrayNode();
                for (JsonNode control : set.path("controls")) {
                    if (id.equals(control.path("id").asText())) {
                        changed = true;
                    } else {
                        kept.add(control);
                    }
                }
                ((ObjectNode) set).set("controls", kept);
            }
            if (changed) {
                frameworks.put(region + "/" + framework.path("id").asText(), framework);
            }
        }
    }

    public synchronized ObjectNode listControls(String type, String catalogId, Integer maxResults, String nextToken,
                                                String region) {
        check("ListControls", null, "controlType", type, "controlCatalogId", catalogId, "maxResults", maxResults,
                "nextToken", nextToken);
        requireActive(region);
        if (catalogId != null && !"Core".equals(type)) {
            throw validation("controlCatalogId applies only to Core controls.");
        }
        List<ObjectNode> matching = switch (type) {
            case "Custom" -> customControls(region).stream().map(this::renderControl).toList();
            case "Core" -> AuditManagerCatalog.STANDARD_CONTROLS.stream()
                    .filter(c -> catalogId == null || "UNCATEGORIZED".equals(catalogId) && c.domain() == null
                            || AuditManagerCatalog.domainByName(c.domain())
                                    .map(d -> d.id().equals(catalogId) || d.arn(partition()).equals(catalogId))
                                    .orElse(false))
                    .map(c -> renderControl(coreControl(c, region))).toList();
            default -> List.of();
        };
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("controlMetadataList");
        for (ObjectNode control : page(matching, maxResults, nextToken, out)) {
            ObjectNode metadata = list.addObject();
            copy(control, metadata, "arn", "id", "name", "controlSources", "createdAt", "lastUpdatedAt");
        }
        return out;
    }

    /** A control by id: a custom control of the account or one of the catalog's Core controls. */
    public Optional<ObjectNode> findControl(String id, String region) {
        Optional<ObjectNode> custom = controls.get(region + "/" + id);
        if (custom.isPresent()) {
            return custom;
        }
        return AuditManagerCatalog.standardControl(id).map(c -> coreControl(c, region));
    }

    ObjectNode requireControl(String id, String region) {
        return findControl(id, region).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The control " + id + " doesn't exist.", 404));
    }

    /** The control domain a control belongs to: its catalog domain, or none for a custom control. */
    static Optional<AuditManagerCatalog.Domain> domainOf(String controlId) {
        return AuditManagerCatalog.standardControl(controlId).flatMap(c -> AuditManagerCatalog.domainByName(
                c.domain()));
    }

    private ObjectNode requireCustomControl(String id, String region) {
        if (AuditManagerCatalog.standardControl(id).isPresent()) {
            throw validation("The control " + id + " is a Core control, which can't be changed or deleted.");
        }
        return controls.get(region + "/" + id).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The control " + id + " doesn't exist.", 404));
    }

    private List<ObjectNode> customControls(String region) {
        return controls.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing(c -> c.path("createdAt").asDouble())).toList();
    }

    private ObjectNode coreControl(AuditManagerCatalog.StandardControl c, String region) {
        ObjectNode control = mapper.createObjectNode();
        control.put("arn", AwsArnUtils.Arn.of("auditmanager", region, "", "control/" + c.id()).toString());
        control.put("id", c.id());
        control.put("type", "Core");
        control.put("name", c.name());
        control.put("description", c.description());
        control.put("testingInformation", c.testingInformation());
        ArrayNode sources = control.putArray("controlMappingSources");
        ObjectNode source = sources.addObject();
        source.put("sourceId", AuditManagerCatalog.nameId("source:" + c.id()));
        if ("MANUAL".equals(c.sourceType())) {
            source.put("sourceName", "Manual evidence");
            source.put("sourceSetUpOption", "Procedural_Controls_Mapping");
            source.put("sourceType", "MANUAL");
        } else {
            source.put("sourceName", c.keyword());
            source.put("sourceSetUpOption", "System_Controls_Mapping");
            source.put("sourceType", c.sourceType());
            source.putObject("sourceKeyword").put("keywordInputType", "SELECT_FROM_LIST")
                    .put("keywordValue", c.keyword());
        }
        control.put("createdAt", 0);
        control.put("lastUpdatedAt", 0);
        control.put("createdBy", "System");
        control.put("lastUpdatedBy", "System");
        control.put("state", "ACTIVE");
        control.putObject("tags");
        return control;
    }

    /** A control as the API returns it, with its {@code controlSources} summary. */
    ObjectNode renderControl(ObjectNode stored) {
        ObjectNode control = stored.deepCopy();
        control.put("controlSources", sourcesSummary(control.path("controlMappingSources")));
        return control;
    }

    static String sourcesSummary(JsonNode mappingSources) {
        Set<String> names = new LinkedHashSet<>();
        mappingSources.forEach(s -> names.add(AuditManagerCatalog.sourceDisplayName(s.path("sourceType").asText())));
        return String.join(", ", names);
    }

    private ArrayNode mappingSources(JsonNode requested) {
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode source : requested) {
            String type = source.path("sourceType").asText("");
            String setUp = source.path("sourceSetUpOption").asText("");
            boolean manual = "MANUAL".equals(type);
            if (type.isEmpty() || setUp.isEmpty()) {
                throw validation("Every control mapping source needs a sourceType and a sourceSetUpOption.");
            }
            if (manual != "Procedural_Controls_Mapping".equals(setUp)) {
                throw validation("A MANUAL source uses Procedural_Controls_Mapping, and an automated source "
                        + "(" + type + ") System_Controls_Mapping.");
            }
            String keyword = source.path("sourceKeyword").path("keywordValue").asText("");
            if (!manual && keyword.isEmpty()) {
                throw validation("The " + type + " source " + source.path("sourceName").asText()
                        + " needs a sourceKeyword.");
            }
            if (!manual && List.of("AWS_Config", "AWS_Security_Hub").contains(type)
                    && "SELECT_FROM_LIST".equals(source.path("sourceKeyword").path("keywordInputType").asText())
                    && !AuditManagerCatalog.KEYWORDS.get(type).contains(keyword)
                    && !keyword.startsWith("Custom_")) {
                throw validation("The keyword " + keyword + " isn't one of the keywords of " + type
                        + "; see ListKeywordsForDataSource.");
            }
            if ("AWS_API_Call".equals(type) && !source.hasNonNull("sourceFrequency")) {
                throw validation("An AWS_API_Call source needs a sourceFrequency.");
            }
            ObjectNode mapped = source.deepCopy();
            if (!mapped.hasNonNull("sourceId")) {
                mapped.put("sourceId", UUID.randomUUID().toString());
            }
            out.add(mapped);
        }
        if (out.isEmpty()) {
            throw validation("A control needs at least one control mapping source.");
        }
        return out;
    }

    // ── Frameworks ─────────────────────────────────────────────────────────

    public synchronized ObjectNode createFramework(JsonNode in, String region) {
        check("CreateAssessmentFramework", in);
        requireActive(region);
        if (customFrameworks(region).size() >= MAX_CUSTOM_FRAMEWORKS) {
            throw new AwsException("ServiceQuotaExceededException", "The account has the maximum of "
                    + MAX_CUSTOM_FRAMEWORKS + " custom frameworks.", 402);
        }
        ObjectNode framework = newFramework(in.path("name").asText(), in.path("description").asText(null),
                in.path("complianceType").asText(null), controlSets(in.path("controlSets"), false, region), region);
        in.path("tags").fields().forEachRemaining(e -> framework.withObject("tags").put(e.getKey(),
                e.getValue().asText()));
        frameworks.put(region + "/" + framework.path("id").asText(), framework);
        return mapper.createObjectNode().set("framework", renderFramework(framework, region));
    }

    private ObjectNode newFramework(String name, String description, String complianceType, ArrayNode sets,
                                    String region) {
        if (customFrameworks(region).stream().anyMatch(f -> name.equals(f.path("name").asText()))) {
            throw validation("A custom framework named " + name + " already exists.");
        }
        ObjectNode framework = mapper.createObjectNode();
        String id = UUID.randomUUID().toString();
        double now = now();
        framework.put("arn", arn(region, "assessmentFramework/" + id));
        framework.put("id", id);
        framework.put("name", name);
        framework.put("type", "Custom");
        if (complianceType != null) {
            framework.put("complianceType", complianceType);
        }
        if (description != null) {
            framework.put("description", description);
        }
        framework.set("controlSets", sets);
        framework.put("createdAt", now);
        framework.put("lastUpdatedAt", now);
        framework.put("createdBy", callerArn());
        framework.put("lastUpdatedBy", callerArn());
        framework.putObject("tags");
        return framework;
    }

    public synchronized ObjectNode getFramework(String id, String region) {
        check("GetAssessmentFramework", null, "frameworkId", id);
        requireActive(region);
        return mapper.createObjectNode().set("framework", renderFramework(requireFramework(id, region), region));
    }

    public synchronized ObjectNode updateFramework(String id, JsonNode in, String region) {
        check("UpdateAssessmentFramework", in, "frameworkId", id);
        requireActive(region);
        ObjectNode framework = requireCustomFramework(id, region);
        String name = in.path("name").asText();
        if (customFrameworks(region).stream().anyMatch(f -> name.equals(f.path("name").asText())
                && !id.equals(f.path("id").asText()))) {
            throw validation("A custom framework named " + name + " already exists.");
        }
        framework.put("name", name);
        for (String field : List.of("description", "complianceType")) {
            if (in.hasNonNull(field)) {
                framework.put(field, in.path(field).asText());
            } else {
                framework.remove(field);
            }
        }
        framework.set("controlSets", controlSets(in.path("controlSets"), true, region));
        framework.put("lastUpdatedAt", now());
        framework.put("lastUpdatedBy", callerArn());
        frameworks.put(region + "/" + id, framework);
        return mapper.createObjectNode().set("framework", renderFramework(framework, region));
    }

    public synchronized void deleteFramework(String id, String region) {
        check("DeleteAssessmentFramework", null, "frameworkId", id);
        requireActive(region);
        requireCustomFramework(id, region);
        frameworks.delete(region + "/" + id);
    }

    public synchronized ObjectNode listFrameworks(String type, Integer maxResults, String nextToken, String region) {
        check("ListAssessmentFrameworks", null, "frameworkType", type, "maxResults", maxResults, "nextToken",
                nextToken);
        requireActive(region);
        List<ObjectNode> matching = "Standard".equals(type) ? List.of(sampleFramework(region))
                : customFrameworks(region);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("frameworkMetadataList");
        for (ObjectNode framework : page(matching, maxResults, nextToken, out)) {
            ObjectNode metadata = list.addObject();
            copy(framework, metadata, "arn", "id", "type", "name", "description", "logo", "complianceType",
                    "createdAt", "lastUpdatedAt");
            int controlCount = 0;
            for (JsonNode set : framework.path("controlSets")) {
                controlCount += set.path("controls").size();
            }
            metadata.put("controlsCount", controlCount);
            metadata.put("controlSetsCount", framework.path("controlSets").size());
        }
        return out;
    }

    /** A framework by id: a custom framework of the account or the catalog's standard framework. */
    public Optional<ObjectNode> findFramework(String id, String region) {
        if (AuditManagerCatalog.SAMPLE_FRAMEWORK_ID.equals(id)) {
            return Optional.of(sampleFramework(region));
        }
        return frameworks.get(region + "/" + id);
    }

    ObjectNode requireFramework(String id, String region) {
        return findFramework(id, region).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The framework " + id + " doesn't exist.", 404));
    }

    private ObjectNode requireCustomFramework(String id, String region) {
        if (AuditManagerCatalog.SAMPLE_FRAMEWORK_ID.equals(id)) {
            throw validation("The framework " + id + " is a standard framework, which can't be changed or "
                    + "deleted.");
        }
        return frameworks.get(region + "/" + id).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The framework " + id + " doesn't exist.", 404));
    }

    private List<ObjectNode> customFrameworks(String region) {
        return frameworks.scan(k -> k.startsWith(region + "/")).stream()
                .sorted(Comparator.comparing(f -> f.path("createdAt").asDouble())).toList();
    }

    private ObjectNode sampleFramework(String region) {
        ObjectNode framework = mapper.createObjectNode();
        framework.put("arn", AwsArnUtils.Arn.of("auditmanager", region, "",
                "assessmentFramework/" + AuditManagerCatalog.SAMPLE_FRAMEWORK_ID).toString());
        framework.put("id", AuditManagerCatalog.SAMPLE_FRAMEWORK_ID);
        framework.put("name", AuditManagerCatalog.SAMPLE_FRAMEWORK_NAME);
        framework.put("type", "Standard");
        framework.put("complianceType", "Sample");
        framework.put("description", AuditManagerCatalog.SAMPLE_FRAMEWORK_DESCRIPTION);
        ArrayNode sets = framework.putArray("controlSets");
        for (AuditManagerCatalog.Domain domain : AuditManagerCatalog.DOMAINS) {
            ObjectNode set = sets.addObject();
            set.put("id", AuditManagerCatalog.nameId("controlset:" + domain.name()));
            set.put("name", domain.name());
            ArrayNode members = set.putArray("controls");
            AuditManagerCatalog.STANDARD_CONTROLS.stream().filter(c -> domain.name().equals(c.domain()))
                    .forEach(c -> members.addObject().put("id", c.id()));
        }
        framework.put("createdAt", 0);
        framework.put("lastUpdatedAt", 0);
        framework.put("createdBy", "System");
        framework.put("lastUpdatedBy", "System");
        framework.putObject("tags");
        return framework;
    }

    /** A framework as the API returns it: each control set's controls in full, and the sources they draw on. */
    ObjectNode renderFramework(ObjectNode stored, String region) {
        ObjectNode framework = stored.deepCopy();
        Set<String> sources = new LinkedHashSet<>();
        for (JsonNode set : framework.path("controlSets")) {
            ArrayNode resolved = mapper.createArrayNode();
            for (JsonNode member : set.path("controls")) {
                findControl(member.path("id").asText(), region).ifPresent(control -> {
                    ObjectNode rendered = renderControl(control);
                    sources.addAll(List.of(rendered.path("controlSources").asText().split(", ")));
                    resolved.add(rendered);
                });
            }
            ((ObjectNode) set).set("controls", resolved);
        }
        sources.remove("");
        framework.put("controlSources", String.join(", ", sources));
        return framework;
    }

    private ArrayNode controlSets(JsonNode requested, boolean update, String region) {
        ArrayNode out = mapper.createArrayNode();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode set : requested) {
            String name = set.path("name").asText();
            if (!names.add(name)) {
                throw validation("The framework has more than one control set named " + name + ".");
            }
            ObjectNode stored = out.addObject();
            stored.put("id", update && set.hasNonNull("id") ? set.path("id").asText() : UUID.randomUUID().toString());
            stored.put("name", name);
            ArrayNode members = stored.putArray("controls");
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode control : set.path("controls")) {
                String id = control.path("id").asText();
                requireControl(id, region);
                if (ids.add(id)) {
                    members.addObject().put("id", id);
                }
            }
            if (members.isEmpty()) {
                throw validation("The control set " + name + " needs at least one control.");
            }
        }
        if (out.isEmpty()) {
            throw validation("A framework needs at least one control set.");
        }
        return out;
    }

    // ── Framework sharing ──────────────────────────────────────────────────

    /**
     * Shares a custom framework with another account and Region: a snapshot of the framework and its custom
     * controls is taken now, and the recipient sees the request as {@code RECEIVED} until it accepts (a copy is
     * created in its library), declines, or the request expires or is revoked.
     */
    public synchronized ObjectNode startShare(String frameworkId, JsonNode in, String region) {
        check("StartAssessmentFrameworkShare", in, "frameworkId", frameworkId);
        requireActive(region);
        ObjectNode framework = requireCustomFramework(frameworkId, region);
        String source = regionResolver.getAccountId();
        String destination = in.path("destinationAccount").asText();
        String destinationRegion = in.path("destinationRegion").asText();
        if (destination.equals(source) && destinationRegion.equals(region)) {
            throw validation("A framework can't be shared with the account and Region it's in.");
        }
        if (!AwsPartitions.isPublishedRegion(destinationRegion)
                || !AwsPartitions.forRegionOrCommercial(destinationRegion).id().equals(partition())) {
            throw validation("destinationRegion " + destinationRegion + " isn't a Region of this partition.");
        }
        ObjectNode rendered = renderFramework(framework, region);
        ObjectNode request = mapper.createObjectNode();
        String id = UUID.randomUUID().toString();
        double now = now();
        request.put("id", id);
        request.put("frameworkId", frameworkId);
        request.put("frameworkName", framework.path("name").asText());
        copy(framework, request, "complianceType");
        if (framework.hasNonNull("description")) {
            request.put("frameworkDescription", framework.path("description").asText());
        }
        request.put("status", "ACTIVE");
        request.put("sourceAccount", source);
        request.put("destinationAccount", destination);
        request.put("destinationRegion", destinationRegion);
        request.put("expirationTime", now + SHARE_LIFETIME.toSeconds());
        request.put("creationTime", now);
        request.put("lastUpdated", now);
        if (in.hasNonNull("comment")) {
            request.put("comment", in.path("comment").asText());
        }
        int standard = 0;
        int custom = 0;
        for (JsonNode set : rendered.path("controlSets")) {
            for (JsonNode control : set.path("controls")) {
                if ("Custom".equals(control.path("type").asText())) {
                    custom++;
                } else {
                    standard++;
                }
            }
        }
        request.put("standardControlsCount", standard);
        request.put("customControlsCount", custom);
        request.put("sourceRegion", region);
        request.set("snapshot", rendered);
        shares.put("SENT/" + region + "/" + id, request);
        shares.putForAccount(destination, "RECEIVED/" + destinationRegion + "/" + id, request.deepCopy());
        return mapper.createObjectNode().set("assessmentFrameworkShareRequest", renderShare(request));
    }

    public synchronized ObjectNode listShares(String type, Integer maxResults, String nextToken, String region) {
        check("ListAssessmentFrameworkShareRequests", null, "requestType", type, "maxResults", maxResults,
                "nextToken", nextToken);
        requireActive(region);
        List<ObjectNode> matching = shares.scan(k -> k.startsWith(type + "/" + region + "/")).stream()
                .map(this::expire).sorted(Comparator.comparing(r -> r.path("creationTime").asDouble())).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("assessmentFrameworkShareRequests");
        page(matching, maxResults, nextToken, out).forEach(r -> list.add(renderShare(r)));
        return out;
    }

    public synchronized ObjectNode updateShare(String id, JsonNode in, String region) {
        check("UpdateAssessmentFrameworkShare", in, "requestId", id);
        requireActive(region);
        String type = in.path("requestType").asText();
        String action = in.path("action").asText();
        ObjectNode request = expire(requireShare(type, id, region));
        String status = request.path("status").asText();
        String next;
        if ("REVOKE".equals(action)) {
            if (!"SENT".equals(type)) {
                throw validation("Only the sender of a share request can revoke it.");
            }
            if (!"ACTIVE".equals(status)) {
                throw validation("The share request is " + status + ", so it can't be revoked.");
            }
            next = "REVOKED";
        } else {
            if (!"RECEIVED".equals(type)) {
                throw validation("Only the recipient of a share request can " + action.toLowerCase() + " it.");
            }
            if (!"ACTIVE".equals(status)) {
                throw validation("The share request is " + status + ", so it can't be " + ("ACCEPT".equals(action)
                        ? "accepted." : "declined."));
            }
            if ("ACCEPT".equals(action)) {
                replicate(request.path("snapshot"), region);
                next = "SHARED";
            } else {
                next = "DECLINED";
            }
        }
        setShareStatus(request, next);
        return mapper.createObjectNode().set("assessmentFrameworkShareRequest",
                renderShare(requireShare(type, id, region)));
    }

    public synchronized void deleteShare(String id, String type, String region) {
        check("DeleteAssessmentFrameworkShare", null, "requestId", id, "requestType", type);
        requireActive(region);
        ObjectNode request = expire(requireShare(type, id, region));
        if ("SENT".equals(type) && "ACTIVE".equals(request.path("status").asText())) {
            setShareStatus(request, "REVOKED");
        }
        shares.delete(type + "/" + region + "/" + id);
    }

    /** Copies a shared framework into this account's library, with new copies of its custom controls. */
    private void replicate(JsonNode snapshot, String region) {
        ArrayNode sets = mapper.createArrayNode();
        for (JsonNode set : snapshot.path("controlSets")) {
            ObjectNode copy = sets.addObject();
            copy.put("id", UUID.randomUUID().toString());
            copy.put("name", set.path("name").asText());
            ArrayNode members = copy.putArray("controls");
            for (JsonNode control : set.path("controls")) {
                if (!"Custom".equals(control.path("type").asText())) {
                    members.addObject().put("id", control.path("id").asText());
                    continue;
                }
                ObjectNode replica = ((ObjectNode) control).deepCopy();
                String newId = UUID.randomUUID().toString();
                double now = now();
                replica.put("id", newId);
                replica.put("arn", arn(region, "control/" + newId));
                replica.remove("controlSources");
                replica.put("createdAt", now);
                replica.put("lastUpdatedAt", now);
                replica.put("createdBy", callerArn());
                replica.put("lastUpdatedBy", callerArn());
                replica.putObject("tags");
                controls.put(region + "/" + newId, replica);
                members.addObject().put("id", newId);
            }
        }
        ObjectNode framework = newFramework(snapshot.path("name").asText(), snapshot.path("description").asText(null),
                snapshot.path("complianceType").asText(null), sets, region);
        frameworks.put(region + "/" + framework.path("id").asText(), framework);
    }

    private ObjectNode requireShare(String type, String id, String region) {
        return shares.get(type + "/" + region + "/" + id).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The share request " + id + " doesn't exist.", 404));
    }

    /** Records a status on both the sender's and the recipient's copy of a share request. */
    private void setShareStatus(ObjectNode request, String status) {
        double now = now();
        String id = request.path("id").asText();
        String sentKey = "SENT/" + request.path("sourceRegion").asText() + "/" + id;
        String receivedKey = "RECEIVED/" + request.path("destinationRegion").asText() + "/" + id;
        for (Map.Entry<String, String> side : Map.of(request.path("sourceAccount").asText(), sentKey,
                request.path("destinationAccount").asText(), receivedKey).entrySet()) {
            shares.getForAccount(side.getKey(), side.getValue()).ifPresent(copy -> {
                copy.put("status", status);
                copy.put("lastUpdated", now);
                shares.putForAccount(side.getKey(), side.getValue(), copy);
            });
        }
    }

    private ObjectNode expire(ObjectNode request) {
        if ("ACTIVE".equals(request.path("status").asText())
                && request.path("expirationTime").asDouble() < now()) {
            setShareStatus(request, "EXPIRED");
            request.put("status", "EXPIRED");
        }
        return request;
    }

    private ObjectNode renderShare(ObjectNode stored) {
        ObjectNode out = stored.deepCopy();
        out.remove("snapshot");
        out.remove("sourceRegion");
        return out;
    }

    // ── Evidence uploads ───────────────────────────────────────────────────

    /**
     * A URL to PUT a manual evidence file to, and the {@code evidenceFileName} that imports it once it's there.
     * The file is held by Floci rather than in an S3 bucket of the account, as AWS holds it in a bucket of its own.
     */
    public synchronized ObjectNode uploadUrl(String fileName, String region) {
        check("GetEvidenceFileUploadUrl", null, "fileName", fileName);
        requireActive(region);
        String token = UUID.randomUUID().toString().replace("-", "");
        String evidenceFileName = token + "_" + fileName;
        ObjectNode upload = mapper.createObjectNode();
        upload.put("fileName", fileName);
        upload.put("evidenceFileName", evidenceFileName);
        upload.put("token", token);
        upload.put("createdAt", now());
        uploads.put(region + "/" + evidenceFileName, upload);
        return mapper.createObjectNode().put("evidenceFileName", evidenceFileName)
                .put("uploadUrl", config.effectiveBaseUrl() + AuditManagerUploadController.PATH + "/" + token);
    }

    /** Stores the body of a PUT to an upload URL; false when the URL is unknown. */
    public synchronized boolean acceptUpload(String token, byte[] body) {
        for (AccountAwareStorageBackend.AccountEntry<ObjectNode> entry
                : uploads.scanAllAccountEntries(k -> k.contains("/" + token + "_"))) {
            ObjectNode upload = entry.value();
            upload.put("data", Base64.getEncoder().encodeToString(body));
            upload.put("size", body.length);
            upload.put("uploadedAt", now());
            uploads.putForAccount(entry.accountId(), entry.key(), upload);
            return true;
        }
        return false;
    }

    /** A manual evidence file uploaded through {@link #uploadUrl}, if it has been. */
    Optional<ObjectNode> uploadedFile(String evidenceFileName, String region) {
        return uploads.get(region + "/" + evidenceFileName).filter(u -> u.has("data"));
    }

    // ── Keywords and services ──────────────────────────────────────────────

    public ObjectNode keywords(String source, Integer maxResults, String nextToken, String region) {
        check("ListKeywordsForDataSource", null, "source", source, "maxResults", maxResults, "nextToken", nextToken);
        requireActive(region);
        List<ObjectNode> all = AuditManagerCatalog.KEYWORDS.getOrDefault(source, List.of()).stream()
                .map(k -> mapper.createObjectNode().put("k", k)).toList();
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("keywords");
        page(all, maxResults, nextToken, out).forEach(k -> list.add(k.path("k").asText()));
        return out;
    }

    public ObjectNode servicesInScope(String region) {
        requireActive(region);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode list = out.putArray("serviceMetadata");
        for (AuditManagerCatalog.Service service : AuditManagerCatalog.SERVICES) {
            list.addObject().put("name", service.name()).put("displayName", service.displayName())
                    .put("description", service.description()).put("category", service.category());
        }
        return out;
    }

    // ── Tags ───────────────────────────────────────────────────────────────

    /** The stored control or framework an ARN names, for tagging; empty for any other ARN. */
    Optional<Tagged> tagged(String arn, String region) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        if (resource.startsWith("control/")) {
            String key = region + "/" + resource.substring("control/".length());
            return controls.get(key).map(c -> new Tagged(controls, key, c));
        }
        if (resource.startsWith("assessmentFramework/")) {
            String key = region + "/" + resource.substring("assessmentFramework/".length());
            return frameworks.get(key).map(f -> new Tagged(frameworks, key, f));
        }
        return Optional.empty();
    }

    record Tagged(AccountAwareStorageBackend<ObjectNode> store, String key, ObjectNode resource) {
        void save() {
            store.put(key, resource);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    String callerArn() {
        return AwsArnUtils.Arn.global(partition(), "iam", regionResolver.getAccountId(), "root").toString();
    }

    String partition() {
        return regionResolver.getPartition();
    }

    String accountId() {
        return regionResolver.getAccountId();
    }

    String arn(String region, String resource) {
        return AwsArnUtils.Arn.of("auditmanager", region, regionResolver.getAccountId(), resource).toString();
    }

    static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    /** Pages {@code all} by {@code maxResults} (at most 1000) and an index token, setting {@code nextToken}. */
    static <T> List<T> page(List<T> all, Integer maxResults, String nextToken, ObjectNode out) {
        int max = maxResults == null ? 1000 : maxResults;
        if (max < 1 || max > 1000) {
            throw validation("maxResults must be between 1 and 1000.");
        }
        int start = 0;
        if (nextToken != null && !nextToken.isEmpty()) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw validation("The nextToken isn't valid.");
            }
            if (start < 0 || start > all.size()) {
                throw validation("The nextToken isn't valid.");
            }
        }
        int end = Math.min(all.size(), start + max);
        if (end < all.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return new ArrayList<>(all.subList(start, end));
    }

    static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.set(field, from.get(field).deepCopy());
            }
        }
    }

    private static void copyText(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field)) {
                to.put(field, from.path(field).asText());
            }
        }
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    @Override
    public void clear() {
        accounts.clear();
        controls.clear();
        frameworks.clear();
        shares.clear();
        uploads.clear();
    }
}
