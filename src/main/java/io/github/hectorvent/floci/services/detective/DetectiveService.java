package io.github.hectorvent.floci.services.detective;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailEventService;
import io.github.hectorvent.floci.services.detective.model.DetectiveMember;
import io.github.hectorvent.floci.services.detective.model.DetectiveState;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class DetectiveService implements Resettable {
    private static final int MAX_MEMBERS = 1200;
    private static final String ACCEPTED_BUT_DISABLED = "ACCEPTED_BUT_DISABLED";
    private static final String ENABLED = "ENABLED";

    private final AccountAwareStorageBackend<DetectiveState> states;
    private final RegionResolver regionResolver;
    private final OrganizationsService organizationsService;
    private final CloudTrailEventService cloudTrailEvents;
    private final ObjectMapper objectMapper;
    private final boolean cloudTrailEnabled;

    @Inject
    public DetectiveService(StorageFactory storageFactory, RegionResolver regionResolver,
                            OrganizationsService organizationsService, CloudTrailEventService cloudTrailEvents,
                            ObjectMapper objectMapper, EmulatorConfig config) {
        this(storageFactory.create("detective", "detective-state.json",
                new TypeReference<Map<String, DetectiveState>>() {}), regionResolver, organizationsService,
                cloudTrailEvents, objectMapper, config.services().cloudtrail().enabled());
    }

    DetectiveService(AccountAwareStorageBackend<DetectiveState> states, RegionResolver regionResolver,
                     OrganizationsService organizationsService, CloudTrailEventService cloudTrailEvents,
                     ObjectMapper objectMapper, boolean cloudTrailEnabled) {
        this.states = states;
        this.regionResolver = regionResolver;
        this.organizationsService = organizationsService;
        this.cloudTrailEvents = cloudTrailEvents;
        this.objectMapper = objectMapper;
        this.cloudTrailEnabled = cloudTrailEnabled;
    }

    public synchronized DetectiveState state(String region) {
        DetectiveState state = states.get(region).orElseGet(DetectiveState::new);
        if (state.isGraph() && state.getGraphArn() == null) {
            // Preserve the identity used by pre-lifecycle persisted graphs.
            state.setGraphArn(graphArnForAccount(regionResolver.getAccountId(), region,
                    "00000000000000000000000000000001"));
            states.put(region, state);
        }
        return state;
    }

    public String graphArn(String region) {
        return requireGraph(region).getGraphArn();
    }

    public synchronized String createGraph(String region, Map<String, String> tags) {
        Map<String, String> validated = validateTags(tags);
        DetectiveState state = state(region);
        if (state.isGraph()) {
            throw new AwsException("ConflictException", "A behavior graph already exists for this account.", 409);
        }
        initializeGraph(state, regionResolver.getAccountId(), region);
        state.setTags(new LinkedHashMap<>(validated));
        states.put(region, state);
        return state.getGraphArn();
    }

    public synchronized void deleteGraph(String region, String arn) {
        requireGraphArn(region, arn);
        DetectiveState state = requireGraph(region);
        state.setGraph(false);
        state.setGraphArn(null);
        state.setCreatedTime(null);
        state.setTags(new LinkedHashMap<>());
        state.getMembers().clear();
        state.setCoreCollectionStartTime(null);
        state.setCoreIngestState(null);
        state.getCoreIngestStateChanges().clear();
        state.getCoreEvents().clear();
        state.setAutoEnable(false);
        states.put(region, state);
    }

    public synchronized ObjectNode listDatasourcePackages(String region, String arn) {
        requireGraphArn(region, arn);
        DetectiveState state = requireGraph(region);
        Instant now = Instant.now();
        String startTime = state.getCoreCollectionStartTime();
        if (startTime == null) {
            startTime = state.getCreatedTime() == null ? now.toString() : state.getCreatedTime();
        }
        if (cloudTrailEnabled) {
            Map<String, String> collected = collectCoreEvents(region, Instant.parse(startTime), now);
            state.getCoreEvents().putAll(collected);
        }
        String ingestState = cloudTrailEnabled ? "STARTED" : "DISABLED";
        if (!ingestState.equals(state.getCoreIngestState())) {
            state.setCoreIngestState(ingestState);
            state.getCoreIngestStateChanges().put(ingestState, now.toString());
        }
        state.setCoreCollectionStartTime(startTime);
        states.put(region, state);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode core = response.putObject("DatasourcePackages").putObject("DETECTIVE_CORE");
        core.put("DatasourcePackageIngestState", state.getCoreIngestState());
        ObjectNode changes = core.putObject("LastIngestStateChange");
        state.getCoreIngestStateChanges().forEach((status, timestamp) ->
                changes.putObject(status).put("Timestamp", timestamp));
        return response;
    }

    private Map<String, String> collectCoreEvents(String region, Instant start, Instant end) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("StartTime", start.toEpochMilli() / 1000.0);
        request.put("EndTime", end.toEpochMilli() / 1000.0);
        request.put("MaxResults", 50);
        Map<String, String> collected = new LinkedHashMap<>();
        Set<String> tokens = new HashSet<>();
        String account = regionResolver.getAccountId();
        // Materialize local management events on demand, without claiming an investigation result.
        while (true) {
            ObjectNode page;
            try {
                page = cloudTrailEvents.lookup(request, region);
            } catch (AwsException e) {
                throw new AwsException("InternalServerException", "Unable to collect local CloudTrail events: "
                        + e.getErrorCode() + ".", 500);
            }
            if (page == null || !page.path("Events").isArray()) {
                throw new AwsException("InternalServerException", "Invalid local CloudTrail event page.", 500);
            }
            for (JsonNode row : page.path("Events")) {
                try {
                    JsonNode event = objectMapper.readTree(row.path("CloudTrailEvent").asText());
                    if (event == null || !event.isObject()) {
                        throw new AwsException("InternalServerException", "Invalid local CloudTrail event data.", 500);
                    }
                    if (!account.equals(event.path("recipientAccountId").asText())
                            || !region.equals(event.path("awsRegion").asText())
                            || !"Management".equals(event.path("eventCategory").asText())) {
                        continue;
                    }
                    Instant time = Instant.parse(event.path("eventTime").asText());
                    String id = event.path("eventID").asText();
                    if (id.isBlank() || !id.equals(row.path("EventId").asText())) {
                        throw new AwsException("InternalServerException", "Invalid local CloudTrail event identity.", 500);
                    }
                    if (!time.isBefore(start) && !time.isAfter(end)) {
                        collected.put(id, event.toString());
                    }
                } catch (JsonProcessingException | DateTimeParseException e) {
                    throw new AwsException("InternalServerException", "Invalid local CloudTrail event data.", 500);
                }
            }
            if (!page.hasNonNull("NextToken")) {
                return collected;
            }
            String token = page.path("NextToken").asText();
            if (token.isBlank() || !tokens.add(token)) {
                throw new AwsException("InternalServerException", "Invalid local CloudTrail pagination cursor.", 500);
            }
            request.put("NextToken", token);
        }
    }

    public synchronized Map<String, String> listTags(String region, String arn) {
        requireGraphArn(region, arn);
        return Map.copyOf(requireGraph(region).getTags());
    }

    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        requireGraphArn(region, arn);
        DetectiveState state = requireGraph(region);
        Map<String, String> updated = new LinkedHashMap<>(state.getTags());
        updated.putAll(validateTags(tags));
        state.setTags(new LinkedHashMap<>(validateTags(updated)));
        states.put(region, state);
    }

    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        requireGraphArn(region, arn);
        if (tagKeys == null || tagKeys.isEmpty() || tagKeys.size() > 50) {
            throw new AwsException("ValidationException", "tagKeys must contain between 1 and 50 keys.", 400);
        }
        for (String key : tagKeys) {
            validateTagKey(key);
        }
        DetectiveState state = requireGraph(region);
        tagKeys.forEach(state.getTags()::remove);
        states.put(region, state);
    }

    private static Map<String, String> validateTags(Map<String, String> tags) {
        if (tags == null || tags.size() > 50) {
            throw new AwsException("ValidationException", "Tags must contain at most 50 entries.", 400);
        }
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            validateTagKey(entry.getKey());
            if (entry.getValue() == null || entry.getValue().length() > 256) {
                throw new AwsException("ValidationException", "Tag values must contain at most 256 characters.", 400);
            }
        }
        return tags;
    }

    private static void validateTagKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 128) {
            throw new AwsException("ValidationException", "Tag keys must contain 1 to 128 characters.", 400);
        }
    }

    private static void initializeGraph(DetectiveState state, String accountId, String region) {
        state.setGraph(true);
        state.setGraphArn(graphArnForAccount(accountId, region, UUID.randomUUID().toString().replace("-", "")));
        state.setCreatedTime(Instant.now().toString());
    }

    public synchronized void enableAdmin(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        var organization = organizationsService.describeOrganization(callerAccountId);
        if (!callerAccountId.equals(organization.getMasterAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "Only the organization management account can designate the Detective administrator account.", 403);
        }
        boolean accountInOrganization = organizationsService.listAccounts(callerAccountId).stream()
                .anyMatch(account -> accountId.equals(account.getId()));
        if (!accountInOrganization) {
            throw new AwsException("ValidationException",
                    "AccountId must identify an account in the organization.", 400);
        }
        DetectiveState management = state(region);
        if (management.getAdminAccountId() != null && !management.getAdminAccountId().equals(accountId)) {
            throw new AwsException("ConflictException",
                    "A different Detective administrator account is already configured.", 409);
        }
        management.setAdminAccountId(accountId);
        states.put(region, management);

        DetectiveState delegated = states.getForAccount(accountId, region).orElseGet(DetectiveState::new);
        delegated.setAdminAccountId(accountId);
        if (!delegated.isGraph()) {
            initializeGraph(delegated, accountId, region);
        }
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized void updateOrganizationConfiguration(String region, String graphArn, Boolean autoEnable) {
        requireGraphArn(region, graphArn);
        DetectiveState state = requireGraph(region);
        if (autoEnable != null) {
            state.setAutoEnable(autoEnable);
            states.put(region, state);
        }
    }

    public synchronized DetectiveMember createMember(String region, String graphArn,
                                                      String accountId, String emailAddress) {
        requireGraphArn(region, graphArn);
        requireAccountId(accountId);
        DetectiveState state = requireGraph(region);
        if (state.getMembers().containsKey(accountId)) {
            throw new AwsException("ConflictException",
                    "The account is already a member of the behavior graph.", 409);
        }
        if (state.getMembers().size() >= MAX_MEMBERS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The behavior graph member quota has been exceeded.", 402);
        }
        boolean organizationMember = regionResolver.getAccountId().equals(state.getAdminAccountId())
                && organizationsService.listAccounts(regionResolver.getAccountId()).stream()
                .anyMatch(account -> accountId.equals(account.getId()));
        if (!organizationMember && (emailAddress == null || emailAddress.isBlank()
                || emailAddress.indexOf('@') <= 0 || emailAddress.endsWith("@"))) {
            throw new AwsException("ValidationException", "EmailAddress is required for invited members.", 400);
        }
        DetectiveMember member = new DetectiveMember(accountId, emailAddress,
                organizationMember ? ACCEPTED_BUT_DISABLED : "INVITED");
        member.setInvitationType(organizationMember ? "ORGANIZATION" : "INVITATION");
        member.setInvitedTime(Instant.now().toString());
        state.getMembers().put(accountId, member);
        states.put(region, state);
        return member;
    }

    public synchronized void validateCreateMembers(String region, String graphArn, List<String> accountIds) {
        requireGraphArn(region, graphArn);
        DetectiveState state = requireGraph(region);
        int newMembers = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String accountId : accountIds) {
            requireAccountId(accountId);
            if (seen.add(accountId) && !state.getMembers().containsKey(accountId)) {
                newMembers++;
            }
        }
        if (state.getMembers().size() + newMembers > MAX_MEMBERS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The behavior graph member quota has been exceeded.", 402);
        }
    }

    public synchronized DetectiveMember startMonitoring(String region, String accountId, String graphArn) {
        requireGraphArn(region, graphArn);
        requireAccountId(accountId);
        DetectiveState state = requireGraph(region);
        DetectiveMember member = state.getMembers().get(accountId);
        if (member == null) {
            throw new AwsException("ResourceNotFoundException", "Member account not found.", 404);
        }
        if (ENABLED.equals(member.getStatus())) {
            throw new AwsException("ConflictException",
                    "The member account already has ENABLED status.", 409);
        }
        if (!ACCEPTED_BUT_DISABLED.equals(member.getStatus())) {
            throw new AwsException("ConflictException",
                    "The member account is not in a state that can start monitoring.", 409);
        }
        throw unsupported("member data ingestion");
    }

    public List<DetectiveMember> listMembers(String region, String graphArn) {
        requireGraphArn(region, graphArn);
        return requireGraph(region).getMembers().values().stream()
                .sorted(java.util.Comparator.comparing(DetectiveMember::getAccountId))
                .toList();
    }

    public Map<String, DetectiveMember> getMembers(String region, String graphArn, List<String> accountIds) {
        requireGraphArn(region, graphArn);
        if (accountIds == null || accountIds.isEmpty() || accountIds.size() > 50) {
            throw new AwsException("ValidationException", "AccountIds must contain between 1 and 50 accounts.", 400);
        }
        accountIds.forEach(DetectiveService::requireAccountId);
        Map<String, DetectiveMember> members = requireGraph(region).getMembers();
        Map<String, DetectiveMember> result = new LinkedHashMap<>();
        for (String accountId : accountIds) {
            if (members.containsKey(accountId)) {
                result.put(accountId, members.get(accountId));
            }
        }
        return result;
    }

    public List<Invitation> listInvitations(String region) {
        String caller = regionResolver.getAccountId();
        return states.scanAllAccountEntries(region::equals).stream()
                .filter(entry -> entry.value().isGraph() && !entry.accountId().equals(caller))
                .filter(entry -> {
                    DetectiveMember member = entry.value().getMembers().get(caller);
                    return member != null && "INVITED".equals(member.getStatus());
                })
                .map(entry -> new Invitation(entry.value().getGraphArn(), entry.accountId(),
                        entry.value().getMembers().get(caller)))
                .sorted(java.util.Comparator.comparing(Invitation::graphArn))
                .toList();
    }

    public record Invitation(String graphArn, String administratorId, DetectiveMember member) {}

    static AwsException unsupported(String operation) {
        return new AwsException("ValidationException", "Floci does not support Detective " + operation + ".", 400);
    }

    @Override
    public void clear() {
        states.clear();
    }

    public DetectiveState requireGraph(String region) {
        DetectiveState state = state(region);
        if (!state.isGraph()) {
            throw new AwsException("ResourceNotFoundException", "Behavior graph not found.", 404);
        }
        return state;
    }

    public void requireGraphArn(String region, String graphArn) {
        if (graphArn == null || !graphArn.matches("arn:aws:detective:[a-z0-9-]+:\\d{12}:graph:[a-f0-9]{32}")) {
            throw new AwsException("ValidationException", "GraphArn must be a valid Detective graph ARN.", 400);
        }
        String prefix = "arn:aws:detective:" + region + ":" + regionResolver.getAccountId() + ":graph:";
        if (!graphArn.startsWith(prefix) || !graphArn.equals(requireGraph(region).getGraphArn())) {
            throw new AwsException("ResourceNotFoundException", "Behavior graph not found.", 404);
        }
    }

    private static String graphArnForAccount(String accountId, String region, String id) {
        return "arn:aws:detective:" + region + ":" + accountId + ":graph:" + id;
    }

    private static void requireAccountId(String accountId) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new AwsException("ValidationException", "AccountId must be a 12 digit account ID.", 400);
        }
    }
}
