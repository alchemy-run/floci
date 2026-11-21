package io.floci.aws.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.floci.aws.services.datazone.DataZoneStore.denied;
import static io.floci.aws.services.datazone.DataZoneStore.invalid;
import static io.floci.aws.services.datazone.DataZoneStore.now;
import static io.floci.aws.services.datazone.DataZoneStore.oneOf;
import static io.floci.aws.services.datazone.DataZoneStore.optional;
import static io.floci.aws.services.datazone.DataZoneStore.present;
import static io.floci.aws.services.datazone.DataZoneStore.text;

/**
 * DataZone domains and what organizes them: domain units, user profiles, projects and their memberships,
 * notifications, data portal sign-in and tags.
 * <p>
 * A new domain is {@code CREATING} until {@code floci.services.datazone.transition-millis} has passed and then
 * {@code AVAILABLE}; it gets a root domain unit and a user profile for its creator, who owns both. A deleted domain is
 * {@code DELETING} and then gone, after which, as on AWS, every call naming it fails with
 * {@code AccessDeniedException}: DataZone authorizes a domain before it looks the domain up. The execution role must
 * exist and trust {@code datazone.amazonaws.com}.
 */
@ApplicationScoped
public class DataZoneService {

    static final String DOMAINS = "domains";
    static final String UNITS = "domain-units";
    static final String USERS = "user-profiles";
    static final String PROJECTS = "projects";
    static final String NOTIFICATIONS = "notifications";

    private final DataZoneStore store;
    private final Instance<IamService> iam;
    private final Instance<RequestContext> requestContext;
    private final long transitionMillis;

    @Inject
    public DataZoneService(DataZoneStore store, Instance<IamService> iam, Instance<RequestContext> requestContext,
                           EmulatorConfig config) {
        this.store = store;
        this.iam = iam;
        this.requestContext = requestContext;
        this.transitionMillis = config.services().datazone().transitionMillis();
    }

    // ---------------------------------------------------------------- domains

    public synchronized ObjectNode createDomain(ObjectNode input, String region) {
        String name = text(input, "name", 64);
        for (ObjectNode existing : store.domains(region)) {
            if (name.equals(existing.path("name").asText()) && !"DELETING".equals(existing.path("status").asText())) {
                throw DataZoneStore.conflict("A domain named " + name + " already exists.");
            }
        }
        String version = oneOf(input, "domainVersion", Set.of("V1", "V2"), "V1");
        String executionRole = role(text(input, "domainExecutionRole", 2048), "domainExecutionRole");
        if ("V2".equals(version)) {
            role(text(input, "serviceRole", 2048), "serviceRole");
        }
        String id = "dzd_" + store.id();
        double now = now();
        ObjectNode domain = store.object();
        domain.put("id", id);
        domain.put("name", name);
        if (present(input, "description")) {
            domain.put("description", input.get("description").asText());
        }
        domain.set("singleSignOn", singleSignOn(input.path("singleSignOn")));
        domain.put("domainExecutionRole", executionRole);
        domain.put("arn", store.arn(region, "domain/" + id));
        if (present(input, "kmsKeyIdentifier")) {
            domain.put("kmsKeyIdentifier", input.get("kmsKeyIdentifier").asText());
        }
        domain.put("status", transitionMillis <= 0 ? "AVAILABLE" : "CREATING");
        domain.put("portalUrl", "https://" + id + ".datazone." + region + "." + portalSuffix(region));
        domain.put("createdAt", now);
        domain.put("lastUpdatedAt", now);
        domain.set("tags", tags(input.path("tags")));
        domain.put("domainVersion", version);
        if (present(input, "serviceRole")) {
            domain.put("serviceRole", input.get("serviceRole").asText());
        }
        domain.put("managedAccountId", store.account());
        String rootUnit = store.id();
        domain.put("rootDomainUnitId", rootUnit);
        domain.putObject("_").put("region", region).put("readyAt", System.currentTimeMillis() + transitionMillis);
        store.putDomain(region, id, domain);

        ObjectNode creator = ensureProfile(region, id, callerPrincipal(), "IAM_ROLE");
        ObjectNode unit = store.object();
        unit.put("id", rootUnit);
        unit.put("domainId", id);
        unit.put("name", name);
        unit.put("description", "Root domain unit");
        unit.putArray("owners").addObject().putObject("user").put("userId", creator.path("id").asText());
        unit.put("createdAt", now);
        unit.put("lastUpdatedAt", now);
        unit.put("createdBy", creator.path("id").asText());
        store.put(region, id, UNITS, rootUnit, unit);
        ObjectNode response = domainView(domain);
        response.remove("createdAt");
        response.remove("lastUpdatedAt");
        response.remove("managedAccountId");
        return response;
    }

    public synchronized ObjectNode getDomain(ObjectNode input, String region) {
        return domainView(domain(region, text(input, "identifier", 64), "GetDomain"));
    }

    public synchronized ObjectNode updateDomain(ObjectNode input, String region) {
        ObjectNode domain = available(region, text(input, "identifier", 64), "UpdateDomain");
        if (present(input, "name")) {
            domain.put("name", input.get("name").asText());
        }
        if (present(input, "description")) {
            domain.put("description", input.get("description").asText());
        }
        if (present(input, "singleSignOn")) {
            domain.set("singleSignOn", singleSignOn(input.get("singleSignOn")));
        }
        if (present(input, "domainExecutionRole")) {
            domain.put("domainExecutionRole", role(input.get("domainExecutionRole").asText(), "domainExecutionRole"));
        }
        if (present(input, "serviceRole")) {
            domain.put("serviceRole", role(input.get("serviceRole").asText(), "serviceRole"));
        }
        domain.put("lastUpdatedAt", now());
        store.putDomain(region, domain.path("id").asText(), domain);
        return domainView(domain, "id", "rootDomainUnitId", "description", "singleSignOn", "domainExecutionRole",
                "lastUpdatedAt", "name", "serviceRole");
    }

    public synchronized ObjectNode deleteDomain(ObjectNode input, String region) {
        ObjectNode domain = domain(region, text(input, "identifier", 64), "DeleteDomain");
        String id = domain.path("id").asText();
        if ("DELETING".equals(domain.path("status").asText())) {
            throw DataZoneStore.conflict("Domain " + id + " is already being deleted.");
        }
        if (!input.path("skipDeletionCheck").asBoolean(false)) {
            for (String kind : List.of(PROJECTS, DataZoneEnvironments.PROFILES, DataZoneEnvironments.ENVIRONMENTS)) {
                if (!store.list(region, id, kind).isEmpty()) {
                    throw DataZoneStore.conflict("Domain " + id + " still has " + kind + "; delete them or set "
                            + "skipDeletionCheck.");
                }
            }
        }
        domain.put("status", "DELETING");
        ((ObjectNode) domain.get("_")).put("readyAt", System.currentTimeMillis() + transitionMillis);
        store.putDomain(region, id, domain);
        settle(region, domain);
        return store.object().put("status", "DELETING");
    }

    public synchronized ObjectNode listDomains(ObjectNode input, String region) {
        String status = optional(input, "status");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode domain : store.domains(region)) {
            ObjectNode settled = settle(region, domain);
            if (settled == null || (status != null && !status.equals(settled.path("status").asText()))) {
                continue;
            }
            items.add(store.view(settled, "id", "name", "description", "arn", "managedAccountId", "status",
                    "portalUrl", "createdAt", "lastUpdatedAt", "domainVersion"));
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    /** A domain the caller may use: one that exists and is not deleted, as AWS authorizes it. */
    ObjectNode domain(String region, String id, String operation) {
        ObjectNode domain = store.domain(region, id).map(value -> settle(region, value)).orElse(null);
        if (domain == null) {
            throw denied("User is not permitted to perform operation: " + operation);
        }
        return domain;
    }

    ObjectNode available(String region, String id, String operation) {
        ObjectNode domain = domain(region, id, operation);
        if (!"AVAILABLE".equals(domain.path("status").asText())) {
            throw DataZoneStore.conflict("Domain " + id + " is " + domain.path("status").asText() + ".");
        }
        return domain;
    }

    /** Applies elapsed transitions: CREATING becomes AVAILABLE, and a DELETING domain is removed with its contents. */
    private ObjectNode settle(String region, ObjectNode domain) {
        if (System.currentTimeMillis() < domain.path("_").path("readyAt").asLong()) {
            return domain;
        }
        String id = domain.path("id").asText();
        switch (domain.path("status").asText()) {
            case "CREATING" -> {
                domain.put("status", "AVAILABLE");
                store.putDomain(region, id, domain);
            }
            case "DELETING" -> {
                store.deleteDomainContents(region, id);
                store.deleteDomain(region, id);
                return null;
            }
            default -> {
            }
        }
        return domain;
    }

    private ObjectNode domainView(ObjectNode domain, String... fields) {
        return fields.length == 0 ? store.view(domain) : store.view(domain, fields);
    }

    private ObjectNode singleSignOn(JsonNode input) {
        ObjectNode sso = store.object();
        String type = oneOf(input, "type", Set.of("IAM_IDC", "DISABLED"), "DISABLED");
        sso.put("type", type);
        if ("IAM_IDC".equals(type)) {
            sso.put("userAssignment", oneOf(input, "userAssignment", Set.of("AUTOMATIC", "MANUAL"), "MANUAL"));
            if (present(input, "idcInstanceArn")) {
                sso.put("idcInstanceArn", input.get("idcInstanceArn").asText());
            }
        }
        return sso;
    }

    /** The role must exist and trust {@code datazone.amazonaws.com}, or DataZone cannot assume it. */
    String role(String roleArn, String field) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must be an IAM role ARN.");
        }
        if (!"iam".equals(arn.service()) || !arn.resource().startsWith("role/")) {
            throw invalid(field + " must be an IAM role ARN.");
        }
        if (iam.isResolvable() && arn.accountId().equals(store.account())) {
            IamRole role;
            try {
                role = iam.get().getRole(arn.resource().substring(arn.resource().lastIndexOf('/') + 1));
            } catch (AwsException e) {
                throw invalid("Unable to assume role " + roleArn + ": the role does not exist.");
            }
            String trust = role.getAssumeRolePolicyDocument() == null ? ""
                    : URLDecoder.decode(role.getAssumeRolePolicyDocument(), StandardCharsets.UTF_8);
            if (!trust.contains("datazone.amazonaws.com")) { // partition-literal: the DataZone service principal
                throw invalid("Unable to assume role " + roleArn + ": its trust policy does not trust DataZone.");
            }
        }
        return roleArn;
    }

    // ---------------------------------------------------------------- domain units

    public synchronized ObjectNode createDomainUnit(ObjectNode input, String region) {
        ObjectNode domain = available(region, text(input, "domainIdentifier", 64), "CreateDomainUnit");
        String domainId = domain.path("id").asText();
        String parent = text(input, "parentDomainUnitIdentifier", 256);
        store.require(region, domainId, UNITS, parent, "Domain unit");
        String id = store.id();
        double now = now();
        ObjectNode unit = store.object();
        unit.put("id", id);
        unit.put("domainId", domainId);
        unit.put("name", text(input, "name", 128));
        unit.put("parentDomainUnitId", parent);
        if (present(input, "description")) {
            unit.put("description", input.get("description").asText());
        }
        ObjectNode creator = ensureProfile(region, domainId, callerPrincipal(), "IAM_ROLE");
        unit.putArray("owners").addObject().putObject("user").put("userId", creator.path("id").asText());
        unit.putArray("ancestorDomainUnitIds").add(parent);
        unit.put("createdAt", now);
        unit.put("lastUpdatedAt", now);
        unit.put("createdBy", creator.path("id").asText());
        store.put(region, domainId, UNITS, id, unit);
        return store.view(unit);
    }

    public synchronized ObjectNode getDomainUnit(ObjectNode input, String region) {
        String domainId = domain(region, text(input, "domainIdentifier", 64), "GetDomainUnit").path("id").asText();
        ObjectNode unit = store.view(store.require(region, domainId, UNITS, text(input, "identifier", 256),
                "Domain unit"));
        unit.remove("ancestorDomainUnitIds");
        return unit;
    }

    public synchronized ObjectNode updateDomainUnit(ObjectNode input, String region) {
        String domainId = available(region, text(input, "domainIdentifier", 64), "UpdateDomainUnit").path("id")
                .asText();
        String id = text(input, "identifier", 256);
        ObjectNode unit = store.require(region, domainId, UNITS, id, "Domain unit");
        if (present(input, "name")) {
            unit.put("name", input.get("name").asText());
        }
        if (present(input, "description")) {
            unit.put("description", input.get("description").asText());
        }
        unit.put("lastUpdatedAt", now());
        store.put(region, domainId, UNITS, id, unit);
        return store.view(unit);
    }

    public synchronized ObjectNode deleteDomainUnit(ObjectNode input, String region) {
        ObjectNode domain = available(region, text(input, "domainIdentifier", 64), "DeleteDomainUnit");
        String domainId = domain.path("id").asText();
        String id = text(input, "identifier", 256);
        store.require(region, domainId, UNITS, id, "Domain unit");
        if (id.equals(domain.path("rootDomainUnitId").asText())) {
            throw invalid("The root domain unit cannot be deleted.");
        }
        for (ObjectNode unit : store.list(region, domainId, UNITS)) {
            if (id.equals(unit.path("parentDomainUnitId").asText())) {
                throw DataZoneStore.conflict("Domain unit " + id + " has child domain units.");
            }
        }
        for (ObjectNode project : store.list(region, domainId, PROJECTS)) {
            if (id.equals(project.path("domainUnitId").asText())) {
                throw DataZoneStore.conflict("Domain unit " + id + " still holds project "
                        + project.path("id").asText() + ".");
            }
        }
        store.delete(region, domainId, UNITS, id);
        return store.object();
    }

    public synchronized ObjectNode listDomainUnitsForParent(ObjectNode input, String region) {
        String domainId = domain(region, text(input, "domainIdentifier", 64), "ListDomainUnitsForParent").path("id")
                .asText();
        String parent = text(input, "parentDomainUnitIdentifier", 256);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode unit : store.list(region, domainId, UNITS)) {
            if (parent.equals(unit.path("parentDomainUnitId").asText())) {
                items.add(store.view(unit, "name", "id"));
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    /** The data portal's DNS suffix: {@code on.aws} in the commercial partition, {@code on.<dnsSuffix>} elsewhere. */
    private static String portalSuffix(String region) {
        String dnsSuffix = AwsRegions.dnsSuffixFor(region);
        String commercial = "amazonaws.com"; // partition-literal: the commercial suffix the portal shortens to on.aws
        return commercial.equals(dnsSuffix) ? "on.aws" : "on." + dnsSuffix;
    }

    // ---------------------------------------------------------------- user profiles

    /** The caller's IAM principal: a role session as its role, as DataZone identifies IAM users. */
    String callerPrincipal() {
        String accessKey = requestContext.isResolvable() ? requestContext.get().getAccessKeyId() : null;
        String arn = accessKey != null && iam.isResolvable() ? iam.get().resolveCallerArn(accessKey).orElse(null)
                : null;
        if (arn == null) {
            return store.regions().buildGlobalArn("iam", store.account(), "root");
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if ("sts".equals(parsed.service()) && parsed.resource().startsWith("assumed-role/")) {
                String role = parsed.resource().substring("assumed-role/".length());
                role = role.contains("/") ? role.substring(0, role.indexOf('/')) : role;
                return AwsArnUtils.Arn.global(parsed.partition(), "iam", parsed.accountId(), "role/" + role)
                        .toString();
            }
        } catch (IllegalArgumentException e) {
            return arn;
        }
        return arn;
    }

    ObjectNode ensureProfile(String region, String domainId, String principalArn, String userType) {
        Optional<ObjectNode> existing = profileFor(region, domainId, principalArn);
        if (existing.isPresent()) {
            return existing.get();
        }
        String id = store.id();
        ObjectNode profile = store.object();
        profile.put("domainId", domainId);
        profile.put("id", id);
        boolean sso = "SSO_USER".equals(userType);
        profile.put("type", sso ? "SSO" : "IAM");
        profile.put("status", sso ? "ASSIGNED" : "ACTIVATED");
        ObjectNode details = profile.putObject("details");
        if (sso) {
            details.putObject("sso").put("username", principalArn).put("firstName", principalArn)
                    .put("lastName", "");
        } else {
            details.putObject("iam").put("arn", principalArn).put("principalId", "AROA" + store.id(17)
                    .toUpperCase());
        }
        profile.put("createdAt", now());
        profile.putObject("_").put("identifier", principalArn);
        store.put(region, domainId, USERS, id, profile);
        return profile;
    }

    Optional<ObjectNode> profileFor(String region, String domainId, String identifier) {
        for (ObjectNode profile : store.list(region, domainId, USERS)) {
            if (identifier.equals(profile.path("id").asText())
                    || identifier.equals(profile.path("_").path("identifier").asText())) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    public synchronized ObjectNode createUserProfile(ObjectNode input, String region) {
        String domainId = available(region, text(input, "domainIdentifier", 64), "CreateUserProfile").path("id")
                .asText();
        String identifier = text(input, "userIdentifier", 1024);
        String userType = oneOf(input, "userType", Set.of("IAM_USER", "IAM_ROLE", "SSO_USER", "IAM_ROLE_SESSION"),
                AwsArnUtils.isArn(identifier) ? "IAM_ROLE" : "SSO_USER");
        if (profileFor(region, domainId, identifier).isPresent()) {
            throw invalid("A user profile for " + identifier + " already exists in domain " + domainId + ".");
        }
        if (!"SSO_USER".equals(userType)) {
            try {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(identifier);
                if (!"iam".equals(arn.service()) && !"sts".equals(arn.service())) {
                    throw invalid("userIdentifier must be an IAM user or role ARN.");
                }
            } catch (IllegalArgumentException e) {
                throw invalid("userIdentifier must be an IAM user or role ARN.");
            }
        }
        return profileView(ensureProfile(region, domainId, identifier, userType));
    }

    public synchronized ObjectNode getUserProfile(ObjectNode input, String region) {
        String domainId = domain(region, text(input, "domainIdentifier", 64), "GetUserProfile").path("id").asText();
        String identifier = text(input, "userIdentifier", 1024);
        ObjectNode profile = profileFor(region, domainId, identifier)
                .orElseThrow(() -> DataZoneStore.notFound("User profile " + identifier + " was not found."));
        String type = optional(input, "type");
        if (type != null && !type.equals(profile.path("type").asText())) {
            throw DataZoneStore.notFound("User profile " + identifier + " is not of type " + type + ".");
        }
        return profileView(profile);
    }

    public synchronized ObjectNode updateUserProfile(ObjectNode input, String region) {
        String domainId = available(region, text(input, "domainIdentifier", 64), "UpdateUserProfile").path("id")
                .asText();
        String identifier = text(input, "userIdentifier", 1024);
        ObjectNode profile = profileFor(region, domainId, identifier)
                .orElseThrow(() -> DataZoneStore.notFound("User profile " + identifier + " was not found."));
        String status = oneOf(input, "status", Set.of("ASSIGNED", "NOT_ASSIGNED", "ACTIVATED", "DEACTIVATED"), null);
        profile.put("status", status);
        store.put(region, domainId, USERS, profile.path("id").asText(), profile);
        return profileView(profile);
    }

    public synchronized ObjectNode searchUserProfiles(ObjectNode input, String region) {
        String domainId = domain(region, text(input, "domainIdentifier", 64), "SearchUserProfiles").path("id")
                .asText();
        String type = oneOf(input, "userType", Set.of("SSO_USER", "DATAZONE_USER", "DATAZONE_SSO_USER",
                "DATAZONE_IAM_USER"), null);
        String search = optional(input, "searchText");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode profile : store.list(region, domainId, USERS)) {
            String profileType = profile.path("type").asText();
            boolean matchesType = switch (type) {
                case "DATAZONE_IAM_USER" -> "IAM".equals(profileType);
                case "DATAZONE_SSO_USER", "SSO_USER" -> "SSO".equals(profileType);
                default -> true;
            };
            if (matchesType && (search == null || profile.path("_").path("identifier").asText().toLowerCase()
                    .contains(search.toLowerCase()))) {
                items.add(profileView(profile));
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    private ObjectNode profileView(ObjectNode profile) {
        ObjectNode view = store.view(profile);
        view.remove("createdAt");
        return view;
    }

    public synchronized ObjectNode getIamPortalLoginUrl(ObjectNode input, String region) {
        ObjectNode domain = available(region, text(input, "domainIdentifier", 64), "GetIamPortalLoginUrl");
        ObjectNode profile = ensureProfile(region, domain.path("id").asText(), callerPrincipal(), "IAM_ROLE");
        return store.object()
                .put("authCodeUrl", domain.path("portalUrl").asText() + "/sso/login?authCode=" + store.id(32))
                .put("userProfileId", profile.path("id").asText());
    }

    // ---------------------------------------------------------------- projects

    public synchronized ObjectNode createProject(ObjectNode input, String region) {
        ObjectNode domain = available(region, text(input, "domainIdentifier", 64), "CreateProject");
        String domainId = domain.path("id").asText();
        String name = text(input, "name", 64);
        if (!name.matches("^[\\w -]+$")) {
            throw invalid("Project names may contain letters, digits, spaces, underscores and hyphens.");
        }
        for (ObjectNode existing : store.list(region, domainId, PROJECTS)) {
            if (name.equals(existing.path("name").asText())) {
                throw DataZoneStore.conflict("A project named " + name + " already exists in domain " + domainId
                        + ".");
            }
        }
        String unit = present(input, "domainUnitId") ? input.get("domainUnitId").asText()
                : domain.path("rootDomainUnitId").asText();
        store.require(region, domainId, UNITS, unit, "Domain unit");
        if (present(input, "projectProfileId")) {
            store.require(region, domainId, "project-profiles", input.get("projectProfileId").asText(),
                    "Project profile");
        }
        ObjectNode creator = ensureProfile(region, domainId, callerPrincipal(), "IAM_ROLE");
        String id = store.id();
        double now = now();
        ObjectNode project = store.object();
        project.put("domainId", domainId);
        project.put("id", id);
        project.put("name", name);
        if (present(input, "description")) {
            project.put("description", input.get("description").asText());
        }
        project.put("projectStatus", "ACTIVE");
        project.putArray("failureReasons");
        project.put("createdBy", creator.path("id").asText());
        project.put("createdAt", now);
        project.put("lastUpdatedAt", now);
        project.set("resourceTags", resourceTags(input.path("resourceTags")));
        if (input.has("glossaryTerms")) {
            project.set("glossaryTerms", input.get("glossaryTerms").deepCopy());
        }
        project.put("domainUnitId", unit);
        for (String field : List.of("projectProfileId", "projectCategory")) {
            if (present(input, field)) {
                project.put(field, input.get(field).asText());
            }
        }
        if (input.has("userParameters")) {
            project.set("userParameters", input.get("userParameters").deepCopy());
        }
        ArrayNode members = project.putObject("_").put("region", region).putArray("members");
        members.addObject().put("userId", creator.path("id").asText()).put("designation", "PROJECT_OWNER");
        for (JsonNode assignment : input.path("membershipAssignments")) {
            members.add(membership(region, domainId, assignment.path("member"), assignment.path("designation")
                    .asText("PROJECT_CONTRIBUTOR")));
        }
        store.put(region, domainId, PROJECTS, id, project);
        return store.view(project);
    }

    public synchronized ObjectNode getProject(ObjectNode input, String region) {
        String domainId = domain(region, text(input, "domainIdentifier", 64), "GetProject").path("id").asText();
        return store.view(project(region, domainId, text(input, "identifier", 64)));
    }

    ObjectNode project(String region, String domainId, String id) {
        return store.require(region, domainId, PROJECTS, id, "Project");
    }

    public synchronized ObjectNode updateProject(ObjectNode input, String region) {
        String domainId = available(region, text(input, "domainIdentifier", 64), "UpdateProject").path("id")
                .asText();
        ObjectNode project = project(region, domainId, text(input, "identifier", 64));
        if (present(input, "name")) {
            String name = input.get("name").asText();
            for (ObjectNode existing : store.list(region, domainId, PROJECTS)) {
                if (name.equals(existing.path("name").asText())
                        && !existing.path("id").asText().equals(project.path("id").asText())) {
                    throw DataZoneStore.conflict("A project named " + name + " already exists.");
                }
            }
            project.put("name", name);
        }
        if (present(input, "description")) {
            project.put("description", input.get("description").asText());
        }
        if (input.has("resourceTags")) {
            project.set("resourceTags", resourceTags(input.get("resourceTags")));
        }
        if (input.has("glossaryTerms")) {
            project.set("glossaryTerms", input.get("glossaryTerms").deepCopy());
        }
        if (present(input, "domainUnitId")) {
            store.require(region, domainId, UNITS, input.get("domainUnitId").asText(), "Domain unit");
            project.put("domainUnitId", input.get("domainUnitId").asText());
        }
        if (input.has("userParameters")) {
            project.set("userParameters", input.get("userParameters").deepCopy());
        }
        if (input.has("environmentDeploymentDetails")) {
            project.set("environmentDeploymentDetails", input.get("environmentDeploymentDetails").deepCopy());
        }
        project.put("lastUpdatedAt", now());
        store.put(region, domainId, PROJECTS, project.path("id").asText(), project);
        return store.view(project);
    }

    public synchronized ObjectNode deleteProject(ObjectNode input, String region) {
        String domainId = available(region, text(input, "domainIdentifier", 64), "DeleteProject").path("id")
                .asText();
        String id = text(input, "identifier", 64);
        project(region, domainId, id);
        if (!input.path("skipDeletionCheck").asBoolean(false)) {
            for (ObjectNode environment : store.list(region, domainId, DataZoneEnvironments.ENVIRONMENTS)) {
                if (id.equals(environment.path("projectId").asText())) {
                    throw DataZoneStore.conflict("Project " + id + " still has environment "
                            + environment.path("id").asText() + ".");
                }
            }
            for (ObjectNode asset : store.list(region, domainId, DataZoneCatalog.ASSETS)) {
                if (id.equals(asset.path("owningProjectId").asText())) {
                    throw DataZoneStore.conflict("Project " + id + " still owns asset " + asset.path("id").asText()
                            + ".");
                }
            }
        }
        store.delete(region, domainId, PROJECTS, id);
        return store.object();
    }

    public synchronized ObjectNode listProjects(ObjectNode input, String region) {
        String domainId;
        try {
            domainId = domain(region, text(input, "domainIdentifier", 64), "ListProjects").path("id").asText();
        } catch (AwsException e) {
            throw DataZoneStore.notFound("Domain " + optional(input, "domainIdentifier") + " was not found.");
        }
        String name = optional(input, "name");
        String user = optional(input, "userIdentifier");
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode project : store.list(region, domainId, PROJECTS)) {
            if (name != null && !name.equals(project.path("name").asText())) {
                continue;
            }
            if (user != null) {
                String profileId = profileFor(region, domainId, user).map(value -> value.path("id").asText())
                        .orElse(user);
                boolean member = false;
                for (JsonNode m : project.path("_").path("members")) {
                    member |= profileId.equals(m.path("userId").asText());
                }
                if (!member) {
                    continue;
                }
            }
            ObjectNode summary = store.view(project, "domainId", "id", "name", "description", "projectStatus",
                    "failureReasons", "createdBy", "createdAt", "domainUnitId", "projectCategory");
            summary.put("updatedAt", project.path("lastUpdatedAt").asDouble());
            items.add(summary);
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    private ArrayNode resourceTags(JsonNode input) {
        ArrayNode tags = store.array();
        if (input != null && input.isObject()) {
            input.properties().forEach(entry -> tags.addObject().put("key", entry.getKey())
                    .put("value", entry.getValue().asText()).put("source", "PROJECT"));
        }
        return tags;
    }

    // ---------------------------------------------------------------- memberships

    private ObjectNode membership(String region, String domainId, JsonNode member, String designation) {
        ObjectNode entry = store.object();
        if (member.hasNonNull("userIdentifier")) {
            String identifier = member.get("userIdentifier").asText();
            ObjectNode profile = profileFor(region, domainId, identifier)
                    .orElseGet(() -> AwsArnUtils.isArn(identifier)
                            ? ensureProfile(region, domainId, identifier, "IAM_ROLE")
                            : null);
            if (profile == null) {
                throw DataZoneStore.notFound("User profile " + identifier + " was not found.");
            }
            entry.put("userId", profile.path("id").asText());
        } else if (member.hasNonNull("groupIdentifier")) {
            entry.put("groupId", member.get("groupIdentifier").asText());
        } else {
            throw invalid("member must name a userIdentifier or a groupIdentifier.");
        }
        entry.put("designation", designation);
        return entry;
    }

    public synchronized ObjectNode createProjectMembership(ObjectNode input, String region) {
        String domainId = available(region, text(input, "domainIdentifier", 64), "CreateProjectMembership")
                .path("id").asText();
        ObjectNode project = project(region, domainId, text(input, "projectIdentifier", 64));
        String designation = oneOf(input, "designation", Set.of("PROJECT_OWNER", "PROJECT_CONTRIBUTOR",
                "PROJECT_CATALOG_VIEWER", "PROJECT_CATALOG_CONSUMER", "PROJECT_CATALOG_STEWARD"), null);
        ObjectNode member = membership(region, domainId, input.path("member"), designation);
        ArrayNode members = (ArrayNode) project.path("_").path("members");
        for (int index = members.size() - 1; index >= 0; index--) {
            JsonNode existing = members.get(index);
            if (existing.path("userId").asText("").equals(member.path("userId").asText("-"))
                    || existing.path("groupId").asText("").equals(member.path("groupId").asText("-"))) {
                members.remove(index);
            }
        }
        members.add(member);
        store.put(region, domainId, PROJECTS, project.path("id").asText(), project);
        return store.object();
    }

    public synchronized ObjectNode deleteProjectMembership(ObjectNode input, String region) {
        String domainId = available(region, text(input, "domainIdentifier", 64), "DeleteProjectMembership")
                .path("id").asText();
        ObjectNode project = project(region, domainId, text(input, "projectIdentifier", 64));
        JsonNode member = input.path("member");
        String userId = member.hasNonNull("userIdentifier") ? profileFor(region, domainId,
                member.get("userIdentifier").asText()).map(value -> value.path("id").asText())
                .orElse(member.get("userIdentifier").asText()) : null;
        String groupId = optional(member, "groupIdentifier");
        ArrayNode members = (ArrayNode) project.path("_").path("members");
        boolean removed = false;
        for (int index = members.size() - 1; index >= 0; index--) {
            JsonNode existing = members.get(index);
            if ((userId != null && userId.equals(existing.path("userId").asText()))
                    || (groupId != null && groupId.equals(existing.path("groupId").asText()))) {
                members.remove(index);
                removed = true;
            }
        }
        if (!removed) {
            throw DataZoneStore.notFound("The member is not part of project " + project.path("id").asText() + ".");
        }
        store.put(region, domainId, PROJECTS, project.path("id").asText(), project);
        return store.object();
    }

    public synchronized ObjectNode listProjectMemberships(ObjectNode input, String region) {
        String domainId = domain(region, text(input, "domainIdentifier", 64), "ListProjectMemberships").path("id")
                .asText();
        ObjectNode project = project(region, domainId, text(input, "projectIdentifier", 64));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode member : project.path("_").path("members")) {
            ObjectNode entry = store.object();
            ObjectNode details = entry.putObject("memberDetails");
            if (member.hasNonNull("userId")) {
                details.putObject("user").put("userId", member.get("userId").asText());
            } else {
                details.putObject("group").put("groupId", member.path("groupId").asText());
            }
            entry.put("designation", member.path("designation").asText());
            items.add(entry);
        }
        return store.page(items, input, "members", item -> item.toString());
    }

    boolean isMember(String region, String domainId, String projectId, String profileId) {
        return store.get(region, domainId, PROJECTS, projectId).map(project -> {
            for (JsonNode member : project.path("_").path("members")) {
                if (profileId.equals(member.path("userId").asText())) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    /** The caller's user profile in the domain, created on first use as DataZone does for IAM principals. */
    String callerProfileId(String region, String domainId) {
        return ensureProfile(region, domainId, callerPrincipal(), "IAM_ROLE").path("id").asText();
    }

    /** DataZone authorizes project-scoped catalog calls by the caller's membership of the project. */
    String requireMember(String region, String domainId, String projectId, String operation) {
        project(region, domainId, projectId);
        String profileId = callerProfileId(region, domainId);
        if (!isMember(region, domainId, projectId, profileId)) {
            throw denied("User is not permitted to perform operation: " + operation);
        }
        return profileId;
    }

    // ---------------------------------------------------------------- notifications

    /** Records a notification in the domain, as DataZone tells project members about tasks and events. */
    void notify(String region, String domainId, String type, String projectId, String role, String title,
                String message, Map<String, String> metadata) {
        String id = store.id();
        double now = now();
        ObjectNode notification = store.object();
        notification.put("identifier", id);
        notification.put("domainIdentifier", domainId);
        notification.put("type", type);
        ObjectNode topic = notification.putObject("topic");
        topic.put("subject", title);
        topic.putObject("resource").put("type", "PROJECT").put("id", projectId);
        topic.put("role", role);
        notification.put("title", title);
        notification.put("message", message);
        notification.put("status", "ACTIVE");
        notification.put("actionLink", store.domain(region, domainId).map(value -> value.path("portalUrl").asText())
                .orElse(""));
        notification.put("creationTimestamp", now);
        notification.put("lastUpdatedTimestamp", now);
        ObjectNode meta = notification.putObject("metadata");
        metadata.forEach(meta::put);
        notification.put("createdAt", now);
        store.put(region, domainId, NOTIFICATIONS, id, notification);
    }

    public synchronized ObjectNode listNotifications(ObjectNode input, String region) {
        String domainId = domain(region, text(input, "domainIdentifier", 64), "ListNotifications").path("id")
                .asText();
        String type = oneOf(input, "type", Set.of("TASK", "EVENT"), null);
        String status = optional(input, "taskStatus");
        Set<String> subjects = new HashSet<>();
        input.path("subjects").forEach(subject -> subjects.add(subject.asText()));
        double after = input.path("afterTimestamp").asDouble(0);
        double before = input.path("beforeTimestamp").asDouble(Double.MAX_VALUE);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode notification : store.list(region, domainId, NOTIFICATIONS)) {
            double at = notification.path("creationTimestamp").asDouble();
            if (!type.equals(notification.path("type").asText()) || at < after || at > before
                    || (status != null && !status.equals(notification.path("status").asText()))
                    || (!subjects.isEmpty() && !subjects.contains(notification.path("topic").path("subject")
                            .asText()))) {
                continue;
            }
            ObjectNode view = store.view(notification);
            view.remove("createdAt");
            items.add(view);
        }
        return store.page(items, input, "notifications", item -> item.path("identifier").asText());
    }

    // ---------------------------------------------------------------- tags

    public synchronized ObjectNode listTagsForResource(ObjectNode input, String region) {
        ObjectNode response = store.object();
        response.set("tags", taggable(text(input, "resourceArn", 2048)).path("tags").deepCopy());
        return response;
    }

    public synchronized ObjectNode tagResource(ObjectNode input, String region) {
        ObjectNode domain = taggable(text(input, "resourceArn", 2048));
        ((ObjectNode) domain.get("tags")).setAll(tags(input.path("tags")));
        store.putDomain(domain.path("_").path("region").asText(), domain.path("id").asText(), domain);
        return store.object();
    }

    public synchronized ObjectNode untagResource(ObjectNode input, String region) {
        ObjectNode domain = taggable(text(input, "resourceArn", 2048));
        ObjectNode tags = (ObjectNode) domain.get("tags");
        input.path("tagKeys").forEach(key -> tags.remove(key.asText()));
        store.putDomain(domain.path("_").path("region").asText(), domain.path("id").asText(), domain);
        return store.object();
    }

    /** Domains are DataZone's taggable resource. */
    private ObjectNode taggable(String resourceArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid resourceArn " + resourceArn + ".");
        }
        if (!"datazone".equals(arn.service()) || !arn.resource().startsWith("domain/")) {
            throw invalid("Only DataZone domains can be tagged.");
        }
        ObjectNode domain = domain(arn.region(), arn.resource().substring("domain/".length()), "TagResource");
        if (!domain.has("tags")) {
            domain.putObject("tags");
        }
        return domain;
    }

    ObjectNode tags(JsonNode input) {
        ObjectNode tags = store.object();
        if (input != null && input.isObject()) {
            input.properties().forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        }
        return tags;
    }
}
