package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datazone.model.Domain;
import io.github.hectorvent.floci.services.datazone.model.Environment;
import io.github.hectorvent.floci.services.datazone.model.EnvironmentBlueprintConfiguration;
import io.github.hectorvent.floci.services.datazone.model.Project;
import io.github.hectorvent.floci.services.datazone.model.UserProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Amazon DataZone restJson1 — domains, projects, environments, user profiles,
 * membership, environment blueprint configurations, inventory/catalog search,
 * subscriptions, and portal login URLs.
 *
 * <p>Missing domain-scoped resources surface as {@code AccessDeniedException}
 * (HTTP 403), matching AWS: DataZone evaluates domain-scoped authorization
 * before existence.
 */
@ApplicationScoped
public class DataZoneService implements TagHandler {

    static final String SERVICE = "datazone";

    private static final List<ManagedBlueprint> MANAGED_BLUEPRINTS = List.of(
            new ManagedBlueprint(
                    "11111111-1111-1111-1111-111111111111",
                    "DefaultDataLake",
                    "Default Data Lake blueprint",
                    "Amazon Web Services"),
            new ManagedBlueprint(
                    "22222222-2222-2222-2222-222222222222",
                    "DefaultDataWarehouse",
                    "Default Data Warehouse blueprint",
                    "Amazon Web Services"),
            new ManagedBlueprint(
                    "33333333-3333-3333-3333-333333333333",
                    "DefaultSageMaker",
                    "Default SageMaker blueprint",
                    "Amazon Web Services"));

    private final StorageBackend<String, Domain> domains;
    private final StorageBackend<String, Project> projects;
    private final StorageBackend<String, Environment> environments;
    private final StorageBackend<String, UserProfile> userProfiles;
    private final StorageBackend<String, EnvironmentBlueprintConfiguration> blueprintConfigurations;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public DataZoneService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("datazone", "datazone-domains.json",
                        new TypeReference<Map<String, Domain>>() {
                        }),
                storageFactory.create("datazone", "datazone-projects.json",
                        new TypeReference<Map<String, Project>>() {
                        }),
                storageFactory.create("datazone", "datazone-environments.json",
                        new TypeReference<Map<String, Environment>>() {
                        }),
                storageFactory.create("datazone", "datazone-user-profiles.json",
                        new TypeReference<Map<String, UserProfile>>() {
                        }),
                storageFactory.create("datazone", "datazone-blueprint-configurations.json",
                        new TypeReference<Map<String, EnvironmentBlueprintConfiguration>>() {
                        }),
                regionResolver, objectMapper);
    }

    DataZoneService(
            StorageBackend<String, Domain> domains,
            StorageBackend<String, Project> projects,
            StorageBackend<String, Environment> environments,
            StorageBackend<String, UserProfile> userProfiles,
            StorageBackend<String, EnvironmentBlueprintConfiguration> blueprintConfigurations,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.domains = domains;
        this.projects = projects;
        this.environments = environments;
        this.userProfiles = userProfiles;
        this.blueprintConfigurations = blueprintConfigurations;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Domain createDomain(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String executionRole = requireText(request, "domainExecutionRole");
        for (Domain existing : domains.values()) {
            if (region.equals(existing.getRegion())
                    && name.equals(existing.getName())
                    && !"DELETED".equals(existing.getStatus())
                    && !"DELETING".equals(existing.getStatus())) {
                throw new AwsException("ConflictException",
                        "There is already a domain with the name " + name + ".", 409);
            }
        }
        long now = Instant.now().getEpochSecond();
        String id = "dzd_" + hex(12);
        String account = regionResolver.getAccountId();
        Domain domain = new Domain();
        domain.setId(id);
        domain.setArn(arn(region, account, id));
        domain.setName(name);
        domain.setDescription(optionalText(request, "description"));
        domain.setDomainExecutionRole(executionRole);
        domain.setServiceRole(optionalText(request, "serviceRole"));
        domain.setKmsKeyIdentifier(optionalText(request, "kmsKeyIdentifier"));
        String version = optionalText(request, "domainVersion");
        domain.setDomainVersion(version == null ? "V1" : version);
        domain.setStatus("AVAILABLE");
        domain.setPortalUrl("https://" + id + ".datazone." + region + ".on.aws");
        domain.setRootDomainUnitId("ddu_" + hex(12));
        domain.setRegion(region);
        domain.setAccountId(account);
        domain.setCreatedAt(now);
        domain.setLastUpdatedAt(now);
        if (request.has("singleSignOn") && request.get("singleSignOn").isObject()) {
            domain.setSingleSignOn(request.get("singleSignOn").deepCopy());
        }
        domain.setTags(readTags(request.get("tags")));
        domains.put(domainKey(region, id), domain);
        return domain;
    }

    public Domain getDomain(String region, String identifier) {
        return requireDomain(region, identifier);
    }

    public List<Domain> listDomains(String region, String status) {
        List<Domain> result = new ArrayList<>();
        for (Domain domain : domains.values()) {
            if (!region.equals(domain.getRegion())) {
                continue;
            }
            if (status != null && !status.isBlank()) {
                if (!status.equals(domain.getStatus())) {
                    continue;
                }
            } else if ("DELETED".equals(domain.getStatus()) || "DELETING".equals(domain.getStatus())) {
                continue;
            }
            result.add(domain);
        }
        result.sort(Comparator.comparing(Domain::getCreatedAt));
        return result;
    }

    public synchronized Domain updateDomain(String region, String identifier, JsonNode request) {
        requireObject(request, "Request body");
        Domain domain = requireDomain(region, identifier);
        if (request.has("name")) {
            domain.setName(requireText(request, "name"));
        }
        if (request.has("description")) {
            domain.setDescription(optionalText(request, "description"));
        }
        if (request.has("domainExecutionRole")) {
            domain.setDomainExecutionRole(requireText(request, "domainExecutionRole"));
        }
        if (request.has("serviceRole")) {
            domain.setServiceRole(optionalText(request, "serviceRole"));
        }
        if (request.has("singleSignOn") && request.get("singleSignOn").isObject()) {
            domain.setSingleSignOn(request.get("singleSignOn").deepCopy());
        }
        domain.setLastUpdatedAt(Instant.now().getEpochSecond());
        domains.put(domainKey(region, identifier), domain);
        return domain;
    }

    public synchronized Domain deleteDomain(String region, String identifier) {
        Domain domain = requireDomain(region, identifier);
        for (Project project : new ArrayList<>(projects.values())) {
            if (region.equals(project.getRegion()) && identifier.equals(project.getDomainId())) {
                projects.delete(projectKey(region, identifier, project.getId()));
            }
        }
        for (UserProfile profile : new ArrayList<>(userProfiles.values())) {
            if (region.equals(profile.getRegion()) && identifier.equals(profile.getDomainId())) {
                userProfiles.delete(profileKey(region, identifier, profile.getId()));
            }
        }
        for (Environment environment : new ArrayList<>(environments.values())) {
            if (region.equals(environment.getRegion()) && identifier.equals(environment.getDomainId())) {
                environments.delete(environmentKey(region, identifier, environment.getId()));
            }
        }
        domains.delete(domainKey(region, identifier));
        domain.setStatus("DELETING");
        return domain;
    }

    public synchronized Project createProject(String region, String domainId, JsonNode request) {
        Domain domain = requireDomain(region, domainId);
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        String now = Instant.now().toString();
        String id = hex(32);
        Project project = new Project();
        project.setId(id);
        project.setDomainId(domainId);
        project.setName(name);
        project.setDescription(optionalText(request, "description"));
        project.setProjectStatus("ACTIVE");
        project.setCreatedBy("dzu_" + hex(12));
        project.setCreatedAt(now);
        project.setLastUpdatedAt(now);
        project.setDomainUnitId(domain.getRootDomainUnitId());
        project.setRegion(region);
        project.setGlossaryTerms(stringList(request, "glossaryTerms"));
        projects.put(projectKey(region, domainId, id), project);
        return project;
    }

    public Project getProject(String region, String domainId, String identifier) {
        requireDomain(region, domainId);
        return requireProject(region, domainId, identifier);
    }

    public List<Project> listProjects(String region, String domainId, String name) {
        requireDomain(region, domainId);
        List<Project> result = new ArrayList<>();
        for (Project project : projects.values()) {
            if (!region.equals(project.getRegion()) || !domainId.equals(project.getDomainId())) {
                continue;
            }
            if ("DELETING".equals(project.getProjectStatus())) {
                continue;
            }
            if (name != null && !name.isBlank() && !name.equals(project.getName())) {
                continue;
            }
            result.add(project);
        }
        result.sort(Comparator.comparing(Project::getCreatedAt));
        return result;
    }

    public synchronized Project updateProject(
            String region, String domainId, String identifier, JsonNode request) {
        requireDomain(region, domainId);
        requireObject(request, "Request body");
        Project project = requireProject(region, domainId, identifier);
        if (request.has("name")) {
            project.setName(requireText(request, "name"));
        }
        if (request.has("description")) {
            project.setDescription(optionalText(request, "description"));
        }
        if (request.has("glossaryTerms")) {
            project.setGlossaryTerms(stringList(request, "glossaryTerms"));
        }
        project.setLastUpdatedAt(Instant.now().toString());
        projects.put(projectKey(region, domainId, identifier), project);
        return project;
    }

    public synchronized void deleteProject(String region, String domainId, String identifier) {
        requireDomain(region, domainId);
        requireProject(region, domainId, identifier);
        for (Environment environment : new ArrayList<>(environments.values())) {
            if (region.equals(environment.getRegion())
                    && domainId.equals(environment.getDomainId())
                    && identifier.equals(environment.getProjectId())) {
                environments.delete(environmentKey(region, domainId, environment.getId()));
            }
        }
        projects.delete(projectKey(region, domainId, identifier));
    }

    public synchronized Environment createEnvironment(String region, String domainId, JsonNode request) {
        requireDomain(region, domainId);
        requireObject(request, "Request body");
        String projectId = requireText(request, "projectIdentifier");
        requireProject(region, domainId, projectId);
        String name = requireText(request, "name");
        String now = Instant.now().toString();
        String id = hex(10);
        Environment environment = new Environment();
        environment.setId(id);
        environment.setDomainId(domainId);
        environment.setProjectId(projectId);
        environment.setName(name);
        environment.setDescription(optionalText(request, "description"));
        environment.setEnvironmentProfileId(optionalText(request, "environmentProfileIdentifier"));
        String blueprintId = optionalText(request, "environmentBlueprintIdentifier");
        environment.setEnvironmentBlueprintId(blueprintId == null ? "DefaultDataLake" : blueprintId);
        String accountId = optionalText(request, "environmentAccountIdentifier");
        environment.setAwsAccountId(accountId == null ? regionResolver.getAccountId() : accountId);
        String accountRegion = optionalText(request, "environmentAccountRegion");
        environment.setAwsAccountRegion(accountRegion == null ? region : accountRegion);
        environment.setProvider("Amazon DataZone");
        environment.setStatus("ACTIVE");
        environment.setCreatedBy("dzu_" + hex(12));
        environment.setCreatedAt(now);
        environment.setUpdatedAt(now);
        environment.setRegion(region);
        environment.setGlossaryTerms(stringList(request, "glossaryTerms"));
        if (request.has("userParameters") && request.get("userParameters").isArray()) {
            environment.setUserParameters(request.get("userParameters").deepCopy());
        }
        environments.put(environmentKey(region, domainId, id), environment);
        return environment;
    }

    public Environment getEnvironment(String region, String domainId, String identifier) {
        requireDomain(region, domainId);
        return requireEnvironment(region, domainId, identifier);
    }

    public List<Environment> listEnvironments(
            String region, String domainId, String projectId, String name, String status) {
        requireDomain(region, domainId);
        if (projectId == null || projectId.isBlank()) {
            throw new AwsException("ValidationException", "projectIdentifier is required.", 400);
        }
        requireProject(region, domainId, projectId);
        List<Environment> result = new ArrayList<>();
        for (Environment environment : environments.values()) {
            if (!region.equals(environment.getRegion())
                    || !domainId.equals(environment.getDomainId())
                    || !projectId.equals(environment.getProjectId())) {
                continue;
            }
            if ("DELETING".equals(environment.getStatus()) || "DELETED".equals(environment.getStatus())) {
                continue;
            }
            if (name != null && !name.isBlank() && !name.equals(environment.getName())) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(environment.getStatus())) {
                continue;
            }
            result.add(environment);
        }
        result.sort(Comparator.comparing(Environment::getCreatedAt));
        return result;
    }

    public synchronized Environment updateEnvironment(
            String region, String domainId, String identifier, JsonNode request) {
        requireDomain(region, domainId);
        requireObject(request, "Request body");
        Environment environment = requireEnvironment(region, domainId, identifier);
        if (request.has("name")) {
            environment.setName(requireText(request, "name"));
        }
        if (request.has("description")) {
            environment.setDescription(optionalText(request, "description"));
        }
        if (request.has("glossaryTerms")) {
            environment.setGlossaryTerms(stringList(request, "glossaryTerms"));
        }
        environment.setUpdatedAt(Instant.now().toString());
        environments.put(environmentKey(region, domainId, identifier), environment);
        return environment;
    }

    public synchronized void deleteEnvironment(String region, String domainId, String identifier) {
        requireDomain(region, domainId);
        requireEnvironment(region, domainId, identifier);
        environments.delete(environmentKey(region, domainId, identifier));
    }

    public synchronized UserProfile createUserProfile(String region, String domainId, JsonNode request) {
        requireDomain(region, domainId);
        requireObject(request, "Request body");
        String userIdentifier = requireText(request, "userIdentifier");
        String userType = optionalText(request, "userType");
        if (userType == null) {
            userType = "IAM_USER";
        }
        UserProfile existing = findUserProfile(region, domainId, userIdentifier, profileType(userType));
        if (existing != null) {
            throw new AwsException("ValidationException",
                    "User profile already exists for " + userIdentifier + ".", 400);
        }
        UserProfile profile = new UserProfile();
        profile.setId("dzu_" + hex(12));
        profile.setDomainId(domainId);
        profile.setType(profileType(userType));
        profile.setStatus("ASSIGNED");
        profile.setUserIdentifier(userIdentifier);
        profile.setSessionName(optionalText(request, "sessionName"));
        profile.setRegion(region);
        if ("IAM".equals(profile.getType())) {
            profile.setIamArn(userIdentifier);
            profile.setPrincipalId(principalId(userIdentifier));
        }
        userProfiles.put(profileKey(region, domainId, profile.getId()), profile);
        return profile;
    }

    public UserProfile getUserProfile(
            String region, String domainId, String userIdentifier, String type) {
        requireDomain(region, domainId);
        String decoded = decode(userIdentifier);
        UserProfile profile = findUserProfile(region, domainId, decoded, type);
        if (profile == null) {
            throw new AwsException("ResourceNotFoundException",
                    "User profile " + decoded + " was not found.", 404);
        }
        return profile;
    }

    public synchronized void createProjectMembership(
            String region, String domainId, String projectId, JsonNode request) {
        requireDomain(region, domainId);
        requireObject(request, "Request body");
        Project project = requireProject(region, domainId, projectId);
        JsonNode member = request.get("member");
        if (member == null || !member.isObject()) {
            throw new AwsException("ValidationException", "member is required.", 400);
        }
        String designation = requireText(request, "designation");
        String userIdentifier = optionalText(member, "userIdentifier");
        String groupIdentifier = optionalText(member, "groupIdentifier");
        if (userIdentifier == null && groupIdentifier == null) {
            throw new AwsException("ValidationException",
                    "member.userIdentifier or member.groupIdentifier is required.", 400);
        }
        for (Project.Membership existing : project.getMemberships()) {
            if (userIdentifier != null && userIdentifier.equals(existing.getUserIdentifier())) {
                throw new AwsException("ValidationException",
                        "User is already a member of the project.", 400);
            }
            if (groupIdentifier != null && groupIdentifier.equals(existing.getGroupIdentifier())) {
                throw new AwsException("ValidationException",
                        "Group is already a member of the project.", 400);
            }
        }
        Project.Membership membership = new Project.Membership();
        membership.setUserIdentifier(userIdentifier);
        membership.setGroupIdentifier(groupIdentifier);
        membership.setDesignation(designation);
        List<Project.Membership> memberships = new ArrayList<>(project.getMemberships());
        memberships.add(membership);
        project.setMemberships(memberships);
        projects.put(projectKey(region, domainId, projectId), project);
    }

    public ObjectNode search(String region, String domainId, JsonNode request) {
        requireDomain(region, domainId);
        requireObject(request, "Request body");
        requireText(request, "searchScope");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("items");
        response.put("totalMatchCount", 0);
        return response;
    }

    public ObjectNode searchListings(String region, String domainId) {
        requireDomain(region, domainId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("items");
        response.put("totalMatchCount", 0);
        return response;
    }

    public ObjectNode searchTypes(String region, String domainId) {
        requireDomain(region, domainId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("items");
        response.put("totalMatchCount", 0);
        return response;
    }

    public ObjectNode listSubscriptions(String region, String domainId) {
        requireDomain(region, domainId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("items");
        return response;
    }

    public ObjectNode listSubscriptionRequests(String region, String domainId) {
        requireDomain(region, domainId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("items");
        return response;
    }

    public ObjectNode listNotifications(String region, String domainId) {
        requireDomain(region, domainId);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("notifications");
        return response;
    }

    public ObjectNode getIamPortalLoginUrl(String region, String domainId) {
        Domain domain = requireDomain(region, domainId);
        UserProfile profile = null;
        for (UserProfile candidate : userProfiles.values()) {
            if (region.equals(candidate.getRegion())
                    && domainId.equals(candidate.getDomainId())
                    && "IAM".equals(candidate.getType())) {
                profile = candidate;
                break;
            }
        }
        if (profile == null) {
            profile = new UserProfile();
            profile.setId("dzu_" + hex(12));
            profile.setDomainId(domainId);
            profile.setType("IAM");
            profile.setStatus("ASSIGNED");
            profile.setRegion(region);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("authCodeUrl", domain.getPortalUrl() + "/auth?authCode=" + hex(16));
        response.put("userProfileId", profile.getId());
        return response;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireDomainByArn(region, arn).getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Domain domain = requireDomainByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(domain.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        domain.setTags(current);
        domain.setLastUpdatedAt(Instant.now().getEpochSecond());
        domains.put(domainKey(domain.getRegion(), domain.getId()), domain);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Domain domain = requireDomainByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(domain.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        domain.setTags(current);
        domain.setLastUpdatedAt(Instant.now().getEpochSecond());
        domains.put(domainKey(domain.getRegion(), domain.getId()), domain);
    }

    ObjectNode toDomain(Domain domain) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", domain.getId());
        if (domain.getRootDomainUnitId() != null) {
            node.put("rootDomainUnitId", domain.getRootDomainUnitId());
        }
        if (domain.getName() != null) {
            node.put("name", domain.getName());
        }
        if (domain.getDescription() != null) {
            node.put("description", domain.getDescription());
        }
        if (domain.getSingleSignOn() != null) {
            node.set("singleSignOn", domain.getSingleSignOn());
        }
        if (domain.getDomainExecutionRole() != null) {
            node.put("domainExecutionRole", domain.getDomainExecutionRole());
        }
        if (domain.getArn() != null) {
            node.put("arn", domain.getArn());
        }
        if (domain.getKmsKeyIdentifier() != null) {
            node.put("kmsKeyIdentifier", domain.getKmsKeyIdentifier());
        }
        if (domain.getStatus() != null) {
            node.put("status", domain.getStatus());
        }
        if (domain.getPortalUrl() != null) {
            node.put("portalUrl", domain.getPortalUrl());
        }
        node.put("createdAt", domain.getCreatedAt());
        node.put("lastUpdatedAt", domain.getLastUpdatedAt());
        putTags(node, domain.getTags());
        if (domain.getDomainVersion() != null) {
            node.put("domainVersion", domain.getDomainVersion());
        }
        if (domain.getServiceRole() != null) {
            node.put("serviceRole", domain.getServiceRole());
        }
        return node;
    }

    ObjectNode toDomainSummary(Domain domain) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", domain.getId());
        node.put("name", domain.getName());
        if (domain.getDescription() != null) {
            node.put("description", domain.getDescription());
        }
        node.put("arn", domain.getArn());
        node.put("managedAccountId", domain.getAccountId());
        node.put("status", domain.getStatus());
        if (domain.getPortalUrl() != null) {
            node.put("portalUrl", domain.getPortalUrl());
        }
        node.put("createdAt", domain.getCreatedAt());
        node.put("lastUpdatedAt", domain.getLastUpdatedAt());
        if (domain.getDomainVersion() != null) {
            node.put("domainVersion", domain.getDomainVersion());
        }
        return node;
    }

    ObjectNode toUpdateDomain(Domain domain) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", domain.getId());
        if (domain.getRootDomainUnitId() != null) {
            node.put("rootDomainUnitId", domain.getRootDomainUnitId());
        }
        if (domain.getDescription() != null) {
            node.put("description", domain.getDescription());
        }
        if (domain.getSingleSignOn() != null) {
            node.set("singleSignOn", domain.getSingleSignOn());
        }
        if (domain.getDomainExecutionRole() != null) {
            node.put("domainExecutionRole", domain.getDomainExecutionRole());
        }
        if (domain.getServiceRole() != null) {
            node.put("serviceRole", domain.getServiceRole());
        }
        if (domain.getName() != null) {
            node.put("name", domain.getName());
        }
        node.put("lastUpdatedAt", domain.getLastUpdatedAt());
        return node;
    }

    ObjectNode toProject(Project project) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("domainId", project.getDomainId());
        node.put("id", project.getId());
        node.put("name", project.getName());
        if (project.getDescription() != null) {
            node.put("description", project.getDescription());
        }
        if (project.getProjectStatus() != null) {
            node.put("projectStatus", project.getProjectStatus());
        }
        node.put("createdBy", project.getCreatedBy());
        if (project.getCreatedAt() != null) {
            node.put("createdAt", project.getCreatedAt());
        }
        if (project.getLastUpdatedAt() != null) {
            node.put("lastUpdatedAt", project.getLastUpdatedAt());
        }
        putStringArray(node, "glossaryTerms", project.getGlossaryTerms());
        if (project.getDomainUnitId() != null) {
            node.put("domainUnitId", project.getDomainUnitId());
        }
        return node;
    }

    ObjectNode toProjectSummary(Project project) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("domainId", project.getDomainId());
        node.put("id", project.getId());
        node.put("name", project.getName());
        if (project.getDescription() != null) {
            node.put("description", project.getDescription());
        }
        if (project.getProjectStatus() != null) {
            node.put("projectStatus", project.getProjectStatus());
        }
        node.put("createdBy", project.getCreatedBy());
        if (project.getCreatedAt() != null) {
            node.put("createdAt", project.getCreatedAt());
        }
        if (project.getLastUpdatedAt() != null) {
            node.put("updatedAt", project.getLastUpdatedAt());
        }
        if (project.getDomainUnitId() != null) {
            node.put("domainUnitId", project.getDomainUnitId());
        }
        return node;
    }

    ObjectNode toEnvironment(Environment environment) {
        ObjectNode node = toEnvironmentSummary(environment);
        putStringArray(node, "glossaryTerms", environment.getGlossaryTerms());
        return node;
    }

    ObjectNode toEnvironmentSummary(Environment environment) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("projectId", environment.getProjectId());
        node.put("id", environment.getId());
        node.put("domainId", environment.getDomainId());
        node.put("createdBy", environment.getCreatedBy());
        if (environment.getCreatedAt() != null) {
            node.put("createdAt", environment.getCreatedAt());
        }
        if (environment.getUpdatedAt() != null) {
            node.put("updatedAt", environment.getUpdatedAt());
        }
        node.put("name", environment.getName());
        if (environment.getDescription() != null) {
            node.put("description", environment.getDescription());
        }
        if (environment.getEnvironmentProfileId() != null) {
            node.put("environmentProfileId", environment.getEnvironmentProfileId());
        }
        if (environment.getAwsAccountId() != null) {
            node.put("awsAccountId", environment.getAwsAccountId());
        }
        if (environment.getAwsAccountRegion() != null) {
            node.put("awsAccountRegion", environment.getAwsAccountRegion());
        }
        node.put("provider", environment.getProvider());
        if (environment.getStatus() != null) {
            node.put("status", environment.getStatus());
        }
        if (environment.getEnvironmentBlueprintId() != null) {
            node.put("environmentBlueprintId", environment.getEnvironmentBlueprintId());
        }
        return node;
    }

    ObjectNode toUserProfile(UserProfile profile) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("domainId", profile.getDomainId());
        node.put("id", profile.getId());
        if (profile.getType() != null) {
            node.put("type", profile.getType());
        }
        if (profile.getStatus() != null) {
            node.put("status", profile.getStatus());
        }
        if ("IAM".equals(profile.getType())) {
            ObjectNode iam = node.putObject("details").putObject("iam");
            if (profile.getIamArn() != null) {
                iam.put("arn", profile.getIamArn());
            }
            if (profile.getPrincipalId() != null) {
                iam.put("principalId", profile.getPrincipalId());
            }
            if (profile.getSessionName() != null) {
                iam.put("sessionName", profile.getSessionName());
            }
        }
        return node;
    }

    private Domain requireDomain(String region, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "identifier is required.", 400);
        }
        return domains.get(domainKey(region, identifier)).orElseThrow(
                () -> accessDenied("GetDomain"));
    }

    private Project requireProject(String region, String domainId, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "identifier is required.", 400);
        }
        return projects.get(projectKey(region, domainId, identifier)).orElseThrow(
                () -> accessDenied("GetProject"));
    }

    private Environment requireEnvironment(String region, String domainId, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "identifier is required.", 400);
        }
        return environments.get(environmentKey(region, domainId, identifier)).orElseThrow(
                () -> new AwsException("ResourceNotFoundException",
                        "Environment " + identifier + " was not found.", 404));
    }

    private Domain requireDomainByArn(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid resource ARN.", 400);
        }
        if (!SERVICE.equals(parsed.service())) {
            throw new AwsException("ValidationException", "Invalid resource ARN.", 400);
        }
        String resource = parsed.resource();
        String lookupRegion = parsed.region() == null || parsed.region().isBlank() ? region : parsed.region();
        if (resource.startsWith("domain/")) {
            return requireDomain(lookupRegion, resource.substring("domain/".length()));
        }
        throw accessDenied("GetDomain");
    }

    private UserProfile findUserProfile(String region, String domainId, String userIdentifier, String type) {
        if (userIdentifier == null || userIdentifier.isBlank()) {
            return null;
        }
        UserProfile byId = userProfiles.get(profileKey(region, domainId, userIdentifier)).orElse(null);
        if (byId != null && (type == null || type.isBlank() || type.equals(byId.getType()))) {
            return byId;
        }
        for (UserProfile profile : userProfiles.values()) {
            if (!region.equals(profile.getRegion()) || !domainId.equals(profile.getDomainId())) {
                continue;
            }
            if (type != null && !type.isBlank() && !type.equals(profile.getType())) {
                continue;
            }
            if (userIdentifier.equals(profile.getId())
                    || userIdentifier.equals(profile.getUserIdentifier())
                    || userIdentifier.equals(profile.getIamArn())) {
                return profile;
            }
        }
        return null;
    }

    private void putTags(ObjectNode parent, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        ObjectNode node = parent.putObject("tags");
        tags.forEach(node::put);
    }

    private static void putStringArray(ObjectNode parent, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ArrayNode array = parent.putArray(field);
        values.forEach(array::add);
    }

    private static AwsException accessDenied(String operation) {
        return new AwsException("AccessDeniedException",
                "User is not permitted to perform operation: " + operation, 403);
    }

    private static void requireObject(JsonNode request, String name) {
        if (request == null || !request.isObject()) {
            throw new AwsException("ValidationException", name + " must be a JSON object.", 400);
        }
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return value;
    }

    private static String optionalText(JsonNode request, String field) {
        return textOrNull(request, field);
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        JsonNode node = request.get(field);
        if (!node.isTextual()) {
            throw new AwsException("ValidationException", field + " must be a string.", 400);
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static List<String> stringList(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new AwsException("ValidationException", field + " must be an array.", 400);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return tags;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && entry.getValue().isTextual()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static String profileType(String userType) {
        if (userType == null) {
            return "IAM";
        }
        return switch (userType) {
            case "SSO_USER" -> "SSO";
            default -> "IAM";
        };
    }

    private static String principalId(String arn) {
        int slash = arn.lastIndexOf('/');
        return slash >= 0 && slash < arn.length() - 1 ? arn.substring(slash + 1) : arn;
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String hex(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private static String arn(String region, String account, String domainId) {
        return "arn:aws:datazone:" + region + ":" + account + ":domain/" + domainId;
    }

    private static String domainKey(String region, String id) {
        return region + ":" + id;
    }

    private static String projectKey(String region, String domainId, String id) {
        return region + ":" + domainId + ":" + id;
    }

    private static String profileKey(String region, String domainId, String id) {
        return region + ":" + domainId + ":" + id;
    }

    private static String environmentKey(String region, String domainId, String id) {
        return region + ":" + domainId + ":env:" + id;
    }
}
