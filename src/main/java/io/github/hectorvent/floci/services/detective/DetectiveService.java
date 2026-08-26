package io.github.hectorvent.floci.services.detective;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.detective.model.DetectiveOrg;
import io.github.hectorvent.floci.services.detective.model.DetectiveOrg.Administrator;
import io.github.hectorvent.floci.services.detective.model.Graph;
import io.github.hectorvent.floci.services.detective.model.Investigation;
import io.github.hectorvent.floci.services.detective.model.Member;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Detective restJson1: one behavior graph per account/region, members,
 * investigations, data-source packages, and organization administrator APIs.
 */
@ApplicationScoped
public class DetectiveService implements TagHandler {

    static final String SERVICE = "detective";
    private static final String CORE = "DETECTIVE_CORE";
    private static final String EKS = "EKS_AUDIT";
    private static final String ASFF = "ASFF_SECURITYHUB_FINDING";
    private static final Set<String> PACKAGES = Set.of(CORE, EKS, ASFF);
    private static final Set<String> INVESTIGATION_STATES = Set.of("ACTIVE", "ARCHIVED");
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    private static final String NOT_A_MEMBER = "The account is not a member of this behavior graph.";

    private final StorageBackend<String, Graph> graphs;
    private final StorageBackend<String, DetectiveOrg> orgs;
    private final RegionResolver regionResolver;

    @Inject
    public DetectiveService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("detective", "detective-graphs.json",
                        new TypeReference<Map<String, Graph>>() {
                        }),
                storageFactory.create("detective", "detective-orgs.json",
                        new TypeReference<Map<String, DetectiveOrg>>() {
                        }),
                regionResolver);
    }

    DetectiveService(StorageBackend<String, Graph> graphs, RegionResolver regionResolver) {
        this(graphs, null, regionResolver);
    }

    DetectiveService(StorageBackend<String, Graph> graphs,
                     StorageBackend<String, DetectiveOrg> orgs,
                     RegionResolver regionResolver) {
        this.graphs = graphs;
        this.orgs = orgs;
        this.regionResolver = regionResolver;
    }

    public Optional<Graph> findGraph(String region) {
        return graphs.get(region);
    }

    public List<Graph> listGraphs(String region) {
        Optional<Graph> graph = graphs.get(region);
        return graph.map(List::of).orElseGet(List::of);
    }

    public synchronized Graph createGraph(String region, JsonNode request) {
        requireObject(request, "Request body");
        if (graphs.get(region).isPresent()) {
            throw new AwsException(
                    "ConflictException",
                    "A behavior graph already exists in this Region.",
                    409);
        }
        String now = now();
        String graphId = UUID.randomUUID().toString().replace("-", "");
        Graph graph = new Graph();
        graph.setGraphId(graphId);
        graph.setArn(graphArn(region, graphId));
        graph.setCreatedTime(now);
        graph.setTags(readTags(request.get("Tags")));
        graph.getDatasourcePackages().put(CORE, "STARTED");
        graph.getDatasourcePackages().put(EKS, "STOPPED");
        graph.getDatasourcePackages().put(ASFF, "STOPPED");
        graph.getDatasourceStartedAt().put(CORE, now);
        graphs.put(region, graph);
        return graph;
    }

    public synchronized void deleteGraph(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        graphs.delete(region);
        if (orgs != null) {
            orgs.get(region).ifPresent(org -> {
                org.getAdministrators().removeIf(admin -> graph.getArn().equals(admin.getGraphArn()));
                orgs.put(region, org);
            });
        }
    }

    public Graph requireGraph(String region, String graphArn) {
        String arn = requireGraphArn(graphArn);
        Graph graph = graphs.get(region).orElseThrow(() -> notFound(arn));
        if (!arn.equals(graph.getArn())) {
            throw notFound(arn);
        }
        return graph;
    }

    public List<Member> listMembers(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        return new ArrayList<>(graph.getMembers().values());
    }

    public GetMembersResult getMembers(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        List<String> accountIds = requireAccountIds(request);
        List<Member> members = new ArrayList<>();
        List<Unprocessed> unprocessed = new ArrayList<>();
        for (String accountId : accountIds) {
            Member member = graph.getMembers().get(accountId);
            if (member == null) {
                unprocessed.add(new Unprocessed(accountId, NOT_A_MEMBER));
            } else {
                members.add(member);
            }
        }
        return new GetMembersResult(members, unprocessed);
    }

    public synchronized GetMembersResult createMembers(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        JsonNode accounts = request.get("Accounts");
        if (accounts == null || !accounts.isArray() || accounts.isEmpty()) {
            throw validation("Accounts is a required parameter.");
        }
        String now = now();
        String admin = regionResolver.getAccountId();
        List<Member> created = new ArrayList<>();
        List<Unprocessed> unprocessed = new ArrayList<>();
        for (JsonNode account : accounts) {
            String accountId = textOrNull(account, "AccountId");
            String email = textOrNull(account, "EmailAddress");
            if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
                unprocessed.add(new Unprocessed(accountId == null ? "" : accountId, "AccountId is invalid."));
                continue;
            }
            if (accountId.equals(admin)) {
                unprocessed.add(new Unprocessed(accountId, "The administrator account cannot be invited."));
                continue;
            }
            if (graph.getMembers().containsKey(accountId)) {
                unprocessed.add(new Unprocessed(accountId, "The account is already a member of this behavior graph."));
                continue;
            }
            if (email == null || email.isBlank()) {
                unprocessed.add(new Unprocessed(accountId, "EmailAddress is a required parameter."));
                continue;
            }
            Member member = new Member();
            member.setAccountId(accountId);
            member.setEmailAddress(email);
            member.setGraphArn(graph.getArn());
            member.setAdministratorId(admin);
            member.setStatus("INVITED");
            member.setInvitationType("INVITATION");
            member.setInvitedTime(now);
            member.setUpdatedTime(now);
            graph.getMembers().put(accountId, member);
            created.add(member);
        }
        graphs.put(region, graph);
        return new GetMembersResult(created, unprocessed);
    }

    public synchronized DeleteMembersResult deleteMembers(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        List<String> accountIds = requireAccountIds(request);
        List<String> deleted = new ArrayList<>();
        List<Unprocessed> unprocessed = new ArrayList<>();
        for (String accountId : accountIds) {
            if (graph.getMembers().remove(accountId) != null) {
                deleted.add(accountId);
            } else {
                unprocessed.add(new Unprocessed(accountId, NOT_A_MEMBER));
            }
        }
        graphs.put(region, graph);
        return new DeleteMembersResult(deleted, unprocessed);
    }

    public synchronized void startMonitoringMember(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        String accountId = requireAccountId(requireText(request, "AccountId"));
        Member member = graph.getMembers().get(accountId);
        if (member == null) {
            throw notFound("Member account " + accountId + " was not found.");
        }
        member.setStatus("ENABLED");
        member.setUpdatedTime(now());
        graphs.put(region, graph);
    }

    public Map<String, DatasourcePackageIngestDetail> listDatasourcePackages(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        Map<String, DatasourcePackageIngestDetail> details = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : graph.getDatasourcePackages().entrySet()) {
            String startedAt = graph.getDatasourceStartedAt().get(entry.getKey());
            details.put(entry.getKey(), new DatasourcePackageIngestDetail(entry.getValue(), startedAt));
        }
        return details;
    }

    public synchronized void updateDatasourcePackages(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        JsonNode packages = request.get("DatasourcePackages");
        if (packages == null || !packages.isArray() || packages.isEmpty()) {
            throw validation("DatasourcePackages is a required parameter.");
        }
        String now = now();
        for (JsonNode item : packages) {
            if (item == null || !item.isTextual()) {
                throw validation("DatasourcePackages contains an invalid value.");
            }
            String name = item.asText();
            if (!PACKAGES.contains(name)) {
                throw validation("DatasourcePackages contains an invalid value.");
            }
            graph.getDatasourcePackages().put(name, "STARTED");
            graph.getDatasourceStartedAt().putIfAbsent(name, now);
        }
        graphs.put(region, graph);
    }

    public MemberDatasourcesResult batchGetGraphMemberDatasources(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        List<String> accountIds = requireAccountIds(request);
        List<MembershipDatasources> datasources = new ArrayList<>();
        List<Unprocessed> unprocessed = new ArrayList<>();
        for (String accountId : accountIds) {
            if (!graph.getMembers().containsKey(accountId)) {
                unprocessed.add(new Unprocessed(accountId, NOT_A_MEMBER));
            }
        }
        return new MemberDatasourcesResult(datasources, unprocessed);
    }

    public MembershipDatasourcesResult batchGetMembershipDatasources(String region, JsonNode request) {
        JsonNode arns = request.get("GraphArns");
        if (arns == null || !arns.isArray() || arns.isEmpty()) {
            throw validation("GraphArns is a required parameter.");
        }
        List<MembershipDatasources> datasources = new ArrayList<>();
        List<UnprocessedGraph> unprocessed = new ArrayList<>();
        for (JsonNode item : arns) {
            if (item == null || !item.isTextual() || item.asText().isBlank()) {
                throw validation("GraphArns contains an invalid value.");
            }
            String arn = item.asText();
            Optional<Graph> graph = graphs.get(region).filter(g -> arn.equals(g.getArn()));
            if (graph.isEmpty()) {
                unprocessed.add(new UnprocessedGraph(arn, "The account is not a member of this behavior graph."));
            }
        }
        return new MembershipDatasourcesResult(datasources, unprocessed);
    }

    public List<Investigation> listInvestigations(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        return new ArrayList<>(graph.getInvestigations().values());
    }

    public Investigation getInvestigation(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        String investigationId = requireText(request, "InvestigationId");
        Investigation investigation = graph.getInvestigations().get(investigationId);
        if (investigation == null) {
            throw notFound("Investigation " + investigationId + " was not found.");
        }
        return investigation;
    }

    public synchronized Investigation startInvestigation(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        String entityArn = requireText(request, "EntityArn");
        String scopeStart = requireText(request, "ScopeStartTime");
        String scopeEnd = requireText(request, "ScopeEndTime");
        String now = now();
        Investigation investigation = new Investigation();
        investigation.setInvestigationId(UUID.randomUUID().toString());
        investigation.setGraphArn(graph.getArn());
        investigation.setEntityArn(entityArn);
        investigation.setEntityType(entityType(entityArn));
        investigation.setStatus("SUCCESSFUL");
        investigation.setSeverity("INFORMATIONAL");
        investigation.setState("ACTIVE");
        investigation.setCreatedTime(now);
        investigation.setScopeStartTime(scopeStart);
        investigation.setScopeEndTime(scopeEnd);
        graph.getInvestigations().put(investigation.getInvestigationId(), investigation);
        graphs.put(region, graph);
        return investigation;
    }

    public synchronized void updateInvestigationState(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        String investigationId = requireText(request, "InvestigationId");
        String state = requireText(request, "State");
        if (!INVESTIGATION_STATES.contains(state)) {
            throw validation("State is invalid.");
        }
        Investigation investigation = graph.getInvestigations().get(investigationId);
        if (investigation == null) {
            throw notFound("Investigation " + investigationId + " was not found.");
        }
        investigation.setState(state);
        graphs.put(region, graph);
    }

    public List<Member> listInvitations(String region) {
        Graph graph = graphs.get(region).orElse(null);
        if (graph == null) {
            return List.of();
        }
        String account = regionResolver.getAccountId();
        Member member = graph.getMembers().get(account);
        if (member == null || !"INVITED".equals(member.getStatus())) {
            return List.of();
        }
        return List.of(member);
    }

    public synchronized void acceptInvitation(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        String account = regionResolver.getAccountId();
        Member member = graph.getMembers().get(account);
        if (member == null) {
            throw notFound("No invitation was found for this account.");
        }
        member.setStatus("ENABLED");
        member.setUpdatedTime(now());
        graphs.put(region, graph);
    }

    public synchronized void rejectInvitation(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        String account = regionResolver.getAccountId();
        if (graph.getMembers().remove(account) == null) {
            throw notFound("No invitation was found for this account.");
        }
        graphs.put(region, graph);
    }

    public synchronized void disassociateMembership(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        String account = regionResolver.getAccountId();
        if (graph.getMembers().remove(account) == null) {
            throw notFound("This account is not a member of the behavior graph.");
        }
        graphs.put(region, graph);
    }

    public boolean describeOrganizationConfiguration(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        return graph.isAutoEnable();
    }

    public synchronized void updateOrganizationConfiguration(String region, JsonNode request) {
        Graph graph = requireGraph(region, requireText(request, "GraphArn"));
        if (request.hasNonNull("AutoEnable")) {
            graph.setAutoEnable(request.get("AutoEnable").asBoolean());
            graphs.put(region, graph);
        }
    }

    public List<Administrator> listOrganizationAdminAccounts(String region) {
        if (orgs == null) {
            return List.of();
        }
        return orgs.get(region).map(DetectiveOrg::getAdministrators).orElse(List.of());
    }

    public synchronized void enableOrganizationAdminAccount(String region, JsonNode request) {
        String accountId = requireAccountId(requireText(request, "AccountId"));
        if (orgs == null) {
            return;
        }
        DetectiveOrg org = orgs.get(region).orElseGet(DetectiveOrg::new);
        boolean exists = org.getAdministrators().stream().anyMatch(admin -> accountId.equals(admin.getAccountId()));
        if (!exists) {
            Administrator administrator = new Administrator();
            administrator.setAccountId(accountId);
            administrator.setGraphArn(graphs.get(region).map(Graph::getArn).orElse(null));
            administrator.setDelegationTime(now());
            org.getAdministrators().add(administrator);
            orgs.put(region, org);
        }
    }

    public synchronized void disableOrganizationAdminAccount(String region) {
        if (orgs == null) {
            return;
        }
        orgs.delete(region);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Graph graph = requireGraph(region, arn);
        return graph.getTags() == null ? Map.of() : Map.copyOf(graph.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Graph graph = requireGraph(region, arn);
        Map<String, String> current = graph.getTags() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(graph.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        graph.setTags(current);
        graphs.put(region, graph);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Graph graph = requireGraph(region, arn);
        if (graph.getTags() != null && tagKeys != null) {
            tagKeys.forEach(graph.getTags()::remove);
        }
        graphs.put(region, graph);
    }

    private String graphArn(String region, String graphId) {
        return AwsArnUtils.Arn.of(SERVICE, region, regionResolver.getAccountId(), "graph:" + graphId).toString();
    }

    private static String requireGraphArn(String graphArn) {
        if (graphArn == null || graphArn.isBlank()) {
            throw validation("GraphArn is a required parameter.");
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(graphArn);
        } catch (IllegalArgumentException e) {
            throw validation("GraphArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service()) || !parsed.resource().startsWith("graph:")) {
            throw validation("GraphArn is invalid.");
        }
        return graphArn;
    }

    private static List<String> requireAccountIds(JsonNode request) {
        JsonNode ids = request.get("AccountIds");
        if (ids == null || !ids.isArray() || ids.isEmpty()) {
            throw validation("AccountIds is a required parameter.");
        }
        List<String> accountIds = new ArrayList<>();
        for (JsonNode item : ids) {
            if (item == null || !item.isTextual() || item.asText().isBlank()) {
                throw validation("AccountIds contains an invalid value.");
            }
            accountIds.add(requireAccountId(item.asText()));
        }
        return accountIds;
    }

    private static String requireAccountId(String accountId) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw validation("AccountId is invalid.");
        }
        return accountId;
    }

    private static String entityType(String entityArn) {
        if (entityArn.contains(":user/") || entityArn.contains(":user:")) {
            return "IAM_USER";
        }
        return "IAM_ROLE";
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw validation("Tags must be a map.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            tags.put(entry.getKey(), value == null || value.isNull() ? "" : value.asText());
        });
        return tags;
    }

    private static void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode node, String field) {
        requireObject(node, "Request body");
        String value = textOrNull(node, field);
        if (value == null) {
            throw validation(field + " is a required parameter.");
        }
        return value;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    public record Unprocessed(String accountId, String reason) {
    }

    public record UnprocessedGraph(String graphArn, String reason) {
    }

    public record GetMembersResult(List<Member> members, List<Unprocessed> unprocessed) {
    }

    public record DeleteMembersResult(List<String> accountIds, List<Unprocessed> unprocessed) {
    }

    public record MembershipDatasources(String accountId, String graphArn) {
    }

    public record MemberDatasourcesResult(List<MembershipDatasources> memberDatasources, List<Unprocessed> unprocessed) {
    }

    public record MembershipDatasourcesResult(
            List<MembershipDatasources> membershipDatasources, List<UnprocessedGraph> unprocessed) {
    }

    public record DatasourcePackageIngestDetail(String ingestState, String startedAt) {
    }
}
