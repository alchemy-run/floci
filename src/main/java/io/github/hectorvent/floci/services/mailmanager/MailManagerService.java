package io.github.hectorvent.floci.services.mailmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.mailmanager.model.MailManagerAddressList;
import io.github.hectorvent.floci.services.mailmanager.model.MailManagerArchive;
import io.github.hectorvent.floci.services.mailmanager.model.MailManagerArchiveSearch;
import io.github.hectorvent.floci.services.mailmanager.model.MailManagerRelay;
import io.github.hectorvent.floci.services.mailmanager.model.MailManagerRuleSet;
import io.github.hectorvent.floci.services.mailmanager.model.MailManagerTrafficPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SES Mail Manager (awsJson1_0, {@code MailManagerSvc.*}). Rule sets,
 * traffic policies, relays, address lists, archives, members, and archive
 * searches. There is no archived-mail data plane, so a started search
 * completes immediately with zero rows.
 */
@ApplicationScoped
public class MailManagerService implements Resettable {

    static final String SERVICE = "mail-manager";
    static final String SIGNING_SERVICE = "ses";
    private static final String DEFAULT_RETENTION = "PERMANENT";
    private static final Set<String> RETENTION_PERIODS = Set.of(
            "THREE_MONTHS", "SIX_MONTHS", "NINE_MONTHS", "ONE_YEAR",
            "EIGHTEEN_MONTHS", "TWO_YEARS", "THIRTY_MONTHS", "THREE_YEARS",
            "FOUR_YEARS", "FIVE_YEARS", "SIX_YEARS", "SEVEN_YEARS",
            "EIGHT_YEARS", "NINE_YEARS", "TEN_YEARS", "PERMANENT");

    private final StorageBackend<String, MailManagerAddressList> addressLists;
    private final StorageBackend<String, MailManagerArchive> archives;
    private final StorageBackend<String, MailManagerArchiveSearch> searches;
    private final StorageBackend<String, MailManagerRuleSet> ruleSets;
    private final StorageBackend<String, MailManagerTrafficPolicy> trafficPolicies;
    private final StorageBackend<String, MailManagerRelay> relays;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MailManagerService(StorageFactory storageFactory, RegionResolver regionResolver,
                              ObjectMapper objectMapper) {
        this(storageFactory.create("mailmanager", "mailmanager-address-lists.json",
                        new TypeReference<Map<String, MailManagerAddressList>>() {
                        }),
                storageFactory.create("mailmanager", "mailmanager-archives.json",
                        new TypeReference<Map<String, MailManagerArchive>>() {
                        }),
                storageFactory.create("mailmanager", "mailmanager-archive-searches.json",
                        new TypeReference<Map<String, MailManagerArchiveSearch>>() {
                        }),
                storageFactory.create("mailmanager", "mailmanager-rule-sets.json",
                        new TypeReference<Map<String, MailManagerRuleSet>>() {
                        }),
                storageFactory.create("mailmanager", "mailmanager-traffic-policies.json",
                        new TypeReference<Map<String, MailManagerTrafficPolicy>>() {
                        }),
                storageFactory.create("mailmanager", "mailmanager-relays.json",
                        new TypeReference<Map<String, MailManagerRelay>>() {
                        }),
                regionResolver, objectMapper);
    }

    MailManagerService(StorageBackend<String, MailManagerAddressList> addressLists,
                       StorageBackend<String, MailManagerArchive> archives,
                       StorageBackend<String, MailManagerArchiveSearch> searches,
                       StorageBackend<String, MailManagerRuleSet> ruleSets,
                       StorageBackend<String, MailManagerTrafficPolicy> trafficPolicies,
                       StorageBackend<String, MailManagerRelay> relays,
                       RegionResolver regionResolver,
                       ObjectMapper objectMapper) {
        this.addressLists = addressLists;
        this.archives = archives;
        this.searches = searches;
        this.ruleSets = ruleSets;
        this.trafficPolicies = trafficPolicies;
        this.relays = relays;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void clear() {
        addressLists.clear();
        archives.clear();
        searches.clear();
        ruleSets.clear();
        trafficPolicies.clear();
        relays.clear();
    }

    public synchronized ObjectNode createRuleSet(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            Optional<MailManagerRuleSet> existing = ruleSetsInRegion(region).stream()
                    .filter(ruleSet -> token.equals(ruleSet.getClientToken()))
                    .findFirst();
            if (existing.isPresent()) {
                return idNode("RuleSetId", existing.get().getRuleSetId());
            }
        }
        String name = requireText(request, "RuleSetName");
        if (findRuleSetByName(region, name).isPresent()) {
            throw conflict("Rule set " + name + " already exists.");
        }
        long now = nowSeconds();
        String id = newId("rs-");
        MailManagerRuleSet ruleSet = new MailManagerRuleSet();
        ruleSet.setRuleSetId(id);
        ruleSet.setRuleSetArn(arn(region, "mailmanager-rule-set/" + id));
        ruleSet.setRuleSetName(name);
        ruleSet.setRegion(region);
        ruleSet.setCreatedTimestamp(now);
        ruleSet.setLastUpdatedTimestamp(now);
        ruleSet.setClientToken(token);
        ruleSet.setRules(copyArray(request.get("Rules")));
        ruleSet.setTags(readTags(request));
        ruleSets.put(storageKey(region, id), ruleSet);
        return idNode("RuleSetId", id);
    }

    public synchronized ObjectNode getRuleSet(JsonNode request, String region) {
        return ruleSetNode(requireRuleSet(region, requireText(request, "RuleSetId")));
    }

    public synchronized ObjectNode updateRuleSet(JsonNode request, String region) {
        MailManagerRuleSet ruleSet = requireRuleSet(region, requireText(request, "RuleSetId"));
        String name = textOrNull(request, "RuleSetName");
        if (name != null) {
            Optional<MailManagerRuleSet> clash = findRuleSetByName(region, name);
            if (clash.isPresent() && !clash.get().getRuleSetId().equals(ruleSet.getRuleSetId())) {
                throw conflict("Rule set " + name + " already exists.");
            }
            ruleSet.setRuleSetName(name);
        }
        if (request.has("Rules")) {
            ruleSet.setRules(copyArray(request.get("Rules")));
        }
        ruleSet.setLastUpdatedTimestamp(nowSeconds());
        ruleSets.put(storageKey(region, ruleSet.getRuleSetId()), ruleSet);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteRuleSet(JsonNode request, String region) {
        ruleSets.delete(storageKey(region, requireText(request, "RuleSetId")));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listRuleSets(JsonNode request, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("RuleSets");
        for (MailManagerRuleSet ruleSet : ruleSetsInRegion(region)) {
            ObjectNode summary = items.addObject();
            summary.put("RuleSetId", ruleSet.getRuleSetId());
            summary.put("RuleSetName", ruleSet.getRuleSetName());
            summary.put("LastModificationDate", ruleSet.getLastUpdatedTimestamp());
        }
        return out;
    }

    public synchronized ObjectNode createTrafficPolicy(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            Optional<MailManagerTrafficPolicy> existing = trafficPoliciesInRegion(region).stream()
                    .filter(policy -> token.equals(policy.getClientToken()))
                    .findFirst();
            if (existing.isPresent()) {
                return idNode("TrafficPolicyId", existing.get().getTrafficPolicyId());
            }
        }
        String name = requireText(request, "TrafficPolicyName");
        if (findTrafficPolicyByName(region, name).isPresent()) {
            throw conflict("Traffic policy " + name + " already exists.");
        }
        long now = nowSeconds();
        String id = newId("tp-");
        MailManagerTrafficPolicy policy = new MailManagerTrafficPolicy();
        policy.setTrafficPolicyId(id);
        policy.setTrafficPolicyArn(arn(region, "mailmanager-traffic-policy/" + id));
        policy.setTrafficPolicyName(name);
        policy.setDefaultAction(requireText(request, "DefaultAction"));
        policy.setMaxMessageSizeBytes(intOrNull(request, "MaxMessageSizeBytes"));
        policy.setPolicyStatements(copyArray(request.get("PolicyStatements")));
        policy.setRegion(region);
        policy.setCreatedTimestamp(now);
        policy.setLastUpdatedTimestamp(now);
        policy.setClientToken(token);
        policy.setTags(readTags(request));
        trafficPolicies.put(storageKey(region, id), policy);
        return idNode("TrafficPolicyId", id);
    }

    public synchronized ObjectNode getTrafficPolicy(JsonNode request, String region) {
        return trafficPolicyNode(requireTrafficPolicy(region, requireText(request, "TrafficPolicyId")));
    }

    public synchronized ObjectNode updateTrafficPolicy(JsonNode request, String region) {
        MailManagerTrafficPolicy policy = requireTrafficPolicy(region, requireText(request, "TrafficPolicyId"));
        String name = textOrNull(request, "TrafficPolicyName");
        if (name != null) {
            Optional<MailManagerTrafficPolicy> clash = findTrafficPolicyByName(region, name);
            if (clash.isPresent() && !clash.get().getTrafficPolicyId().equals(policy.getTrafficPolicyId())) {
                throw conflict("Traffic policy " + name + " already exists.");
            }
            policy.setTrafficPolicyName(name);
        }
        if (request.hasNonNull("DefaultAction")) {
            policy.setDefaultAction(requireText(request, "DefaultAction"));
        }
        if (request.has("MaxMessageSizeBytes")) {
            policy.setMaxMessageSizeBytes(intOrNull(request, "MaxMessageSizeBytes"));
        }
        if (request.has("PolicyStatements")) {
            policy.setPolicyStatements(copyArray(request.get("PolicyStatements")));
        }
        policy.setLastUpdatedTimestamp(nowSeconds());
        trafficPolicies.put(storageKey(region, policy.getTrafficPolicyId()), policy);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteTrafficPolicy(JsonNode request, String region) {
        trafficPolicies.delete(storageKey(region, requireText(request, "TrafficPolicyId")));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listTrafficPolicies(JsonNode request, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("TrafficPolicies");
        for (MailManagerTrafficPolicy policy : trafficPoliciesInRegion(region)) {
            ObjectNode summary = items.addObject();
            summary.put("TrafficPolicyName", policy.getTrafficPolicyName());
            summary.put("TrafficPolicyId", policy.getTrafficPolicyId());
            summary.put("DefaultAction", policy.getDefaultAction());
        }
        return out;
    }

    public synchronized ObjectNode createRelay(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            Optional<MailManagerRelay> existing = relaysInRegion(region).stream()
                    .filter(relay -> token.equals(relay.getClientToken()))
                    .findFirst();
            if (existing.isPresent()) {
                return idNode("RelayId", existing.get().getRelayId());
            }
        }
        String name = requireText(request, "RelayName");
        if (findRelayByName(region, name).isPresent()) {
            throw conflict("Relay " + name + " already exists.");
        }
        JsonNode authentication = request.get("Authentication");
        if (authentication == null || authentication.isNull() || authentication.isMissingNode()) {
            throw invalid("Authentication is a required parameter.");
        }
        long now = nowSeconds();
        String id = newId("rl-");
        MailManagerRelay relay = new MailManagerRelay();
        relay.setRelayId(id);
        relay.setRelayArn(arn(region, "mailmanager-relay/" + id));
        relay.setRelayName(name);
        relay.setServerName(requireText(request, "ServerName"));
        relay.setServerPort(requireInt(request, "ServerPort"));
        relay.setAuthentication(authentication.deepCopy());
        relay.setRegion(region);
        relay.setCreatedTimestamp(now);
        relay.setLastUpdatedTimestamp(now);
        relay.setClientToken(token);
        relay.setTags(readTags(request));
        relays.put(storageKey(region, id), relay);
        return idNode("RelayId", id);
    }

    public synchronized ObjectNode getRelay(JsonNode request, String region) {
        return relayNode(requireRelay(region, requireText(request, "RelayId")));
    }

    public synchronized ObjectNode updateRelay(JsonNode request, String region) {
        MailManagerRelay relay = requireRelay(region, requireText(request, "RelayId"));
        String name = textOrNull(request, "RelayName");
        if (name != null) {
            Optional<MailManagerRelay> clash = findRelayByName(region, name);
            if (clash.isPresent() && !clash.get().getRelayId().equals(relay.getRelayId())) {
                throw conflict("Relay " + name + " already exists.");
            }
            relay.setRelayName(name);
        }
        if (request.hasNonNull("ServerName")) {
            relay.setServerName(requireText(request, "ServerName"));
        }
        if (request.has("ServerPort") && !request.get("ServerPort").isNull()) {
            relay.setServerPort(requireInt(request, "ServerPort"));
        }
        if (request.has("Authentication") && !request.get("Authentication").isNull()) {
            relay.setAuthentication(request.get("Authentication").deepCopy());
        }
        relay.setLastUpdatedTimestamp(nowSeconds());
        relays.put(storageKey(region, relay.getRelayId()), relay);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteRelay(JsonNode request, String region) {
        relays.delete(storageKey(region, requireText(request, "RelayId")));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listRelays(JsonNode request, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("Relays");
        for (MailManagerRelay relay : relaysInRegion(region)) {
            ObjectNode summary = items.addObject();
            summary.put("RelayId", relay.getRelayId());
            summary.put("RelayName", relay.getRelayName());
            summary.put("LastModifiedTimestamp", relay.getLastUpdatedTimestamp());
        }
        return out;
    }

    public synchronized ObjectNode createAddressList(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            Optional<MailManagerAddressList> existing = findListByToken(region, token);
            if (existing.isPresent()) {
                return idNode("AddressListId", existing.get().getAddressListId());
            }
        }
        String name = requireText(request, "AddressListName");
        if (findActiveListByName(region, name).isPresent()) {
            throw conflict("Address list " + name + " already exists.");
        }
        long now = nowSeconds();
        String id = newId("al-");
        MailManagerAddressList list = new MailManagerAddressList();
        list.setAddressListId(id);
        list.setAddressListArn(arn(region, "mailmanager-address-list/" + id));
        list.setAddressListName(name);
        list.setRegion(region);
        list.setCreatedTimestamp(now);
        list.setLastUpdatedTimestamp(now);
        list.setClientToken(token);
        list.setTags(readTags(request));
        addressLists.put(storageKey(region, id), list);
        return idNode("AddressListId", id);
    }

    public synchronized ObjectNode getAddressList(JsonNode request, String region) {
        return addressListNode(requireList(region, requireText(request, "AddressListId")));
    }

    public synchronized ObjectNode listAddressLists(JsonNode request, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("AddressLists");
        for (MailManagerAddressList list : listsInRegion(region)) {
            items.add(addressListNode(list));
        }
        return out;
    }

    public synchronized ObjectNode deleteAddressList(JsonNode request, String region) {
        String id = requireText(request, "AddressListId");
        addressLists.delete(storageKey(region, id));
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode registerMember(JsonNode request, String region) {
        MailManagerAddressList list = requireList(region, requireText(request, "AddressListId"));
        String address = requireText(request, "Address");
        list.getMembers().putIfAbsent(normalizeAddress(address), nowSeconds());
        list.setLastUpdatedTimestamp(nowSeconds());
        addressLists.put(storageKey(region, list.getAddressListId()), list);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deregisterMember(JsonNode request, String region) {
        MailManagerAddressList list = requireList(region, requireText(request, "AddressListId"));
        String address = requireText(request, "Address");
        list.getMembers().remove(normalizeAddress(address));
        list.setLastUpdatedTimestamp(nowSeconds());
        addressLists.put(storageKey(region, list.getAddressListId()), list);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode getMember(JsonNode request, String region) {
        MailManagerAddressList list = requireList(region, requireText(request, "AddressListId"));
        String address = requireText(request, "Address");
        Long created = list.getMembers().get(normalizeAddress(address));
        if (created == null) {
            throw notFound("Member " + address + " was not found.");
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.put("Address", address);
        out.put("CreatedTimestamp", created);
        return out;
    }

    public synchronized ObjectNode listMembers(JsonNode request, String region) {
        MailManagerAddressList list = requireList(region, requireText(request, "AddressListId"));
        String prefix = null;
        JsonNode filter = request.get("Filter");
        if (filter != null && filter.isObject()) {
            prefix = textOrNull(filter, "AddressPrefix");
        }
        String normalizedPrefix = prefix == null ? null : normalizeAddress(prefix);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("Addresses");
        List<Map.Entry<String, Long>> members = new ArrayList<>(list.getMembers().entrySet());
        members.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Long> member : members) {
            if (normalizedPrefix != null && !member.getKey().startsWith(normalizedPrefix)) {
                continue;
            }
            ObjectNode row = items.addObject();
            row.put("Address", member.getKey());
            row.put("CreatedTimestamp", member.getValue());
        }
        return out;
    }

    public synchronized ObjectNode listAddressListImportJobs(JsonNode request, String region) {
        requireList(region, requireText(request, "AddressListId"));
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("ImportJobs");
        return out;
    }

    public synchronized ObjectNode createArchive(JsonNode request, String region) {
        String token = textOrNull(request, "ClientToken");
        if (token != null) {
            Optional<MailManagerArchive> existing = findArchiveByToken(region, token);
            if (existing.isPresent()) {
                return idNode("ArchiveId", existing.get().getArchiveId());
            }
        }
        String name = requireText(request, "ArchiveName");
        if (findActiveArchiveByName(region, name).isPresent()) {
            throw conflict("Archive " + name + " already exists.");
        }
        long now = nowSeconds();
        String id = newId("a-");
        MailManagerArchive archive = new MailManagerArchive();
        archive.setArchiveId(id);
        archive.setArchiveArn(arn(region, "mailmanager-archive/" + id));
        archive.setArchiveName(name);
        archive.setArchiveState("ACTIVE");
        archive.setRetentionPeriod(readRetention(request.get("Retention")));
        archive.setKmsKeyArn(textOrNull(request, "KmsKeyArn"));
        archive.setRegion(region);
        archive.setCreatedTimestamp(now);
        archive.setLastUpdatedTimestamp(now);
        archive.setClientToken(token);
        archive.setTags(readTags(request));
        archives.put(storageKey(region, id), archive);
        return idNode("ArchiveId", id);
    }

    public synchronized ObjectNode getArchive(JsonNode request, String region) {
        return archiveNode(requireArchive(region, requireText(request, "ArchiveId")));
    }

    public synchronized ObjectNode listArchives(JsonNode request, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("Archives");
        for (MailManagerArchive archive : archivesInRegion(region)) {
            ObjectNode summary = items.addObject();
            summary.put("ArchiveId", archive.getArchiveId());
            summary.put("ArchiveName", archive.getArchiveName());
            summary.put("ArchiveState", archive.getArchiveState());
            summary.put("LastUpdatedTimestamp", archive.getLastUpdatedTimestamp());
        }
        return out;
    }

    public synchronized ObjectNode updateArchive(JsonNode request, String region) {
        MailManagerArchive archive = requireActiveArchive(region, requireText(request, "ArchiveId"));
        String name = textOrNull(request, "ArchiveName");
        if (name != null) {
            Optional<MailManagerArchive> clash = findActiveArchiveByName(region, name);
            if (clash.isPresent() && !clash.get().getArchiveId().equals(archive.getArchiveId())) {
                throw conflict("Archive " + name + " already exists.");
            }
            archive.setArchiveName(name);
        }
        if (request.has("Retention") && !request.get("Retention").isNull()) {
            archive.setRetentionPeriod(readRetention(request.get("Retention")));
        }
        archive.setLastUpdatedTimestamp(nowSeconds());
        archives.put(storageKey(region, archive.getArchiveId()), archive);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteArchive(JsonNode request, String region) {
        String id = requireText(request, "ArchiveId");
        Optional<MailManagerArchive> found = archives.get(storageKey(region, id));
        if (found.isPresent()) {
            MailManagerArchive archive = found.get();
            archive.setArchiveState("PENDING_DELETION");
            archive.setLastUpdatedTimestamp(nowSeconds());
            archives.put(storageKey(region, id), archive);
        }
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode startArchiveSearch(JsonNode request, String region) {
        MailManagerArchive archive = requireActiveArchive(region, requireText(request, "ArchiveId"));
        long from = requireEpoch(request, "FromTimestamp");
        long to = requireEpoch(request, "ToTimestamp");
        int maxResults = requireInt(request, "MaxResults");
        if (maxResults < 1 || maxResults > 1000) {
            throw invalid("MaxResults must be between 1 and 1000.");
        }
        long now = nowSeconds();
        String searchId = UUID.randomUUID().toString();
        MailManagerArchiveSearch search = new MailManagerArchiveSearch();
        search.setSearchId(searchId);
        search.setArchiveId(archive.getArchiveId());
        search.setRegion(region);
        JsonNode filters = request.get("Filters");
        search.setFilters(filters != null && filters.isObject() ? filters.deepCopy() : null);
        search.setFromTimestamp(from);
        search.setToTimestamp(to);
        search.setMaxResults(maxResults);
        search.setState("COMPLETED");
        search.setSubmissionTimestamp(now);
        search.setCompletionTimestamp(now);
        searches.put(storageKey(region, searchId), search);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("SearchId", searchId);
        return out;
    }

    public synchronized ObjectNode getArchiveSearch(JsonNode request, String region) {
        MailManagerArchiveSearch search = requireSearch(region, requireText(request, "SearchId"));
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ArchiveId", search.getArchiveId());
        if (search.getFilters() != null) {
            out.set("Filters", search.getFilters());
        }
        out.put("FromTimestamp", search.getFromTimestamp());
        out.put("ToTimestamp", search.getToTimestamp());
        out.put("MaxResults", search.getMaxResults());
        ObjectNode status = out.putObject("Status");
        status.put("SubmissionTimestamp", search.getSubmissionTimestamp());
        if (search.getCompletionTimestamp() != null) {
            status.put("CompletionTimestamp", search.getCompletionTimestamp());
        }
        status.put("State", search.getState());
        if (search.getErrorMessage() != null) {
            status.put("ErrorMessage", search.getErrorMessage());
        }
        return out;
    }

    public synchronized ObjectNode getArchiveSearchResults(JsonNode request, String region) {
        requireSearch(region, requireText(request, "SearchId"));
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("Rows");
        return out;
    }

    public synchronized ObjectNode listArchiveSearches(JsonNode request, String region) {
        MailManagerArchive archive = requireArchive(region, requireText(request, "ArchiveId"));
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("Searches");
        List<MailManagerArchiveSearch> matches = new ArrayList<>();
        for (MailManagerArchiveSearch search : searches.values()) {
            if (region.equals(search.getRegion()) && archive.getArchiveId().equals(search.getArchiveId())) {
                matches.add(search);
            }
        }
        matches.sort(Comparator.comparing(MailManagerArchiveSearch::getSubmissionTimestamp).reversed());
        for (MailManagerArchiveSearch search : matches) {
            ObjectNode summary = items.addObject();
            summary.put("SearchId", search.getSearchId());
            ObjectNode status = summary.putObject("Status");
            status.put("SubmissionTimestamp", search.getSubmissionTimestamp());
            if (search.getCompletionTimestamp() != null) {
                status.put("CompletionTimestamp", search.getCompletionTimestamp());
            }
            status.put("State", search.getState());
        }
        return out;
    }

    public synchronized ObjectNode listArchiveExports(JsonNode request, String region) {
        requireArchive(region, requireText(request, "ArchiveId"));
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray("Exports");
        return out;
    }

    public synchronized ObjectNode tagResource(JsonNode request, String region) {
        String arn = requireText(request, "ResourceArn");
        Tagged tagged = requireTagged(region, arn);
        Map<String, String> tags = tagged.tags();
        JsonNode list = request.get("Tags");
        if (list == null || !list.isArray()) {
            throw invalid("Tags is a required parameter.");
        }
        for (JsonNode tag : list) {
            String key = textOrNull(tag, "Key");
            String value = textOrNull(tag, "Value");
            if (key == null) {
                throw invalid("Tag Key is a required parameter.");
            }
            tags.put(key, value == null ? "" : value);
        }
        persistTagged(region, tagged);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request, String region) {
        String arn = requireText(request, "ResourceArn");
        Tagged tagged = requireTagged(region, arn);
        JsonNode keys = request.get("TagKeys");
        if (keys != null && keys.isArray()) {
            for (JsonNode key : keys) {
                if (key.isTextual()) {
                    tagged.tags().remove(key.asText());
                }
            }
        }
        persistTagged(region, tagged);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listTagsForResource(JsonNode request, String region) {
        Tagged tagged = requireTagged(region, requireText(request, "ResourceArn"));
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode tags = out.putArray("Tags");
        for (Map.Entry<String, String> entry : tagged.tags().entrySet()) {
            ObjectNode tag = tags.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
        return out;
    }

    private ObjectNode addressListNode(MailManagerAddressList list) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("AddressListId", list.getAddressListId());
        out.put("AddressListArn", list.getAddressListArn());
        out.put("AddressListName", list.getAddressListName());
        out.put("CreatedTimestamp", list.getCreatedTimestamp());
        out.put("LastUpdatedTimestamp", list.getLastUpdatedTimestamp());
        return out;
    }

    private ObjectNode archiveNode(MailManagerArchive archive) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ArchiveId", archive.getArchiveId());
        out.put("ArchiveName", archive.getArchiveName());
        out.put("ArchiveArn", archive.getArchiveArn());
        out.put("ArchiveState", archive.getArchiveState());
        out.putObject("Retention").put("RetentionPeriod", archive.getRetentionPeriod());
        out.put("CreatedTimestamp", archive.getCreatedTimestamp());
        out.put("LastUpdatedTimestamp", archive.getLastUpdatedTimestamp());
        if (archive.getKmsKeyArn() != null) {
            out.put("KmsKeyArn", archive.getKmsKeyArn());
        }
        return out;
    }

    private ObjectNode ruleSetNode(MailManagerRuleSet ruleSet) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("RuleSetId", ruleSet.getRuleSetId());
        out.put("RuleSetArn", ruleSet.getRuleSetArn());
        out.put("RuleSetName", ruleSet.getRuleSetName());
        out.put("CreatedDate", ruleSet.getCreatedTimestamp());
        out.put("LastModificationDate", ruleSet.getLastUpdatedTimestamp());
        out.set("Rules", ruleSet.getRules() != null ? ruleSet.getRules() : objectMapper.createArrayNode());
        return out;
    }

    private ObjectNode trafficPolicyNode(MailManagerTrafficPolicy policy) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("TrafficPolicyName", policy.getTrafficPolicyName());
        out.put("TrafficPolicyId", policy.getTrafficPolicyId());
        out.put("TrafficPolicyArn", policy.getTrafficPolicyArn());
        if (policy.getPolicyStatements() != null) {
            out.set("PolicyStatements", policy.getPolicyStatements());
        }
        if (policy.getMaxMessageSizeBytes() != null) {
            out.put("MaxMessageSizeBytes", policy.getMaxMessageSizeBytes());
        }
        out.put("DefaultAction", policy.getDefaultAction());
        out.put("CreatedTimestamp", policy.getCreatedTimestamp());
        out.put("LastUpdatedTimestamp", policy.getLastUpdatedTimestamp());
        return out;
    }

    private ObjectNode relayNode(MailManagerRelay relay) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("RelayId", relay.getRelayId());
        out.put("RelayArn", relay.getRelayArn());
        out.put("RelayName", relay.getRelayName());
        out.put("ServerName", relay.getServerName());
        out.put("ServerPort", relay.getServerPort());
        if (relay.getAuthentication() != null) {
            out.set("Authentication", relay.getAuthentication());
        }
        out.put("CreatedTimestamp", relay.getCreatedTimestamp());
        out.put("LastModifiedTimestamp", relay.getLastUpdatedTimestamp());
        return out;
    }

    private MailManagerRuleSet requireRuleSet(String region, String id) {
        return ruleSets.get(storageKey(region, id))
                .orElseThrow(() -> notFound("Rule set " + id + " was not found."));
    }

    private MailManagerTrafficPolicy requireTrafficPolicy(String region, String id) {
        return trafficPolicies.get(storageKey(region, id))
                .orElseThrow(() -> notFound("Traffic policy " + id + " was not found."));
    }

    private MailManagerRelay requireRelay(String region, String id) {
        return relays.get(storageKey(region, id))
                .orElseThrow(() -> notFound("Relay " + id + " was not found."));
    }

    private MailManagerAddressList requireList(String region, String id) {
        return addressLists.get(storageKey(region, id))
                .orElseThrow(() -> notFound("Address list " + id + " was not found."));
    }

    private MailManagerArchive requireArchive(String region, String id) {
        return archives.get(storageKey(region, id))
                .orElseThrow(() -> notFound("Archive " + id + " was not found."));
    }

    private MailManagerArchive requireActiveArchive(String region, String id) {
        MailManagerArchive archive = requireArchive(region, id);
        if ("PENDING_DELETION".equals(archive.getArchiveState())) {
            throw conflict("Archive " + id + " is pending deletion.");
        }
        return archive;
    }

    private MailManagerArchiveSearch requireSearch(String region, String id) {
        return searches.get(storageKey(region, id))
                .orElseThrow(() -> notFound("Search " + id + " was not found."));
    }

    private Optional<MailManagerAddressList> findActiveListByName(String region, String name) {
        return listsInRegion(region).stream()
                .filter(list -> name.equals(list.getAddressListName()))
                .findFirst();
    }

    private Optional<MailManagerAddressList> findListByToken(String region, String token) {
        return listsInRegion(region).stream()
                .filter(list -> token.equals(list.getClientToken()))
                .findFirst();
    }

    private Optional<MailManagerArchive> findActiveArchiveByName(String region, String name) {
        return archivesInRegion(region).stream()
                .filter(archive -> "ACTIVE".equals(archive.getArchiveState()))
                .filter(archive -> name.equals(archive.getArchiveName()))
                .findFirst();
    }

    private Optional<MailManagerArchive> findArchiveByToken(String region, String token) {
        return archivesInRegion(region).stream()
                .filter(archive -> token.equals(archive.getClientToken()))
                .findFirst();
    }

    private List<MailManagerAddressList> listsInRegion(String region) {
        List<MailManagerAddressList> out = new ArrayList<>();
        for (MailManagerAddressList list : addressLists.values()) {
            if (region.equals(list.getRegion())) {
                out.add(list);
            }
        }
        out.sort(Comparator.comparing(MailManagerAddressList::getAddressListId));
        return out;
    }

    private List<MailManagerArchive> archivesInRegion(String region) {
        List<MailManagerArchive> out = new ArrayList<>();
        for (MailManagerArchive archive : archives.values()) {
            if (region.equals(archive.getRegion())) {
                out.add(archive);
            }
        }
        out.sort(Comparator.comparing(MailManagerArchive::getArchiveId));
        return out;
    }

    private List<MailManagerRuleSet> ruleSetsInRegion(String region) {
        List<MailManagerRuleSet> out = new ArrayList<>();
        for (MailManagerRuleSet ruleSet : ruleSets.values()) {
            if (region.equals(ruleSet.getRegion())) {
                out.add(ruleSet);
            }
        }
        out.sort(Comparator.comparing(MailManagerRuleSet::getRuleSetId));
        return out;
    }

    private List<MailManagerTrafficPolicy> trafficPoliciesInRegion(String region) {
        List<MailManagerTrafficPolicy> out = new ArrayList<>();
        for (MailManagerTrafficPolicy policy : trafficPolicies.values()) {
            if (region.equals(policy.getRegion())) {
                out.add(policy);
            }
        }
        out.sort(Comparator.comparing(MailManagerTrafficPolicy::getTrafficPolicyId));
        return out;
    }

    private List<MailManagerRelay> relaysInRegion(String region) {
        List<MailManagerRelay> out = new ArrayList<>();
        for (MailManagerRelay relay : relays.values()) {
            if (region.equals(relay.getRegion())) {
                out.add(relay);
            }
        }
        out.sort(Comparator.comparing(MailManagerRelay::getRelayId));
        return out;
    }

    private Optional<MailManagerRuleSet> findRuleSetByName(String region, String name) {
        return ruleSetsInRegion(region).stream()
                .filter(ruleSet -> name.equals(ruleSet.getRuleSetName()))
                .findFirst();
    }

    private Optional<MailManagerTrafficPolicy> findTrafficPolicyByName(String region, String name) {
        return trafficPoliciesInRegion(region).stream()
                .filter(policy -> name.equals(policy.getTrafficPolicyName()))
                .findFirst();
    }

    private Optional<MailManagerRelay> findRelayByName(String region, String name) {
        return relaysInRegion(region).stream()
                .filter(relay -> name.equals(relay.getRelayName()))
                .findFirst();
    }

    private Tagged requireTagged(String region, String arn) {
        for (MailManagerAddressList list : listsInRegion(region)) {
            if (arn.equals(list.getAddressListArn())) {
                return Tagged.list(list);
            }
        }
        for (MailManagerArchive archive : archivesInRegion(region)) {
            if (arn.equals(archive.getArchiveArn())) {
                return Tagged.archive(archive);
            }
        }
        for (MailManagerRuleSet ruleSet : ruleSetsInRegion(region)) {
            if (arn.equals(ruleSet.getRuleSetArn())) {
                return Tagged.ruleSet(ruleSet);
            }
        }
        for (MailManagerTrafficPolicy policy : trafficPoliciesInRegion(region)) {
            if (arn.equals(policy.getTrafficPolicyArn())) {
                return Tagged.policy(policy);
            }
        }
        for (MailManagerRelay relay : relaysInRegion(region)) {
            if (arn.equals(relay.getRelayArn())) {
                return Tagged.relay(relay);
            }
        }
        throw notFound("Resource " + arn + " was not found.");
    }

    private void persistTagged(String region, Tagged tagged) {
        long now = nowSeconds();
        if (tagged.list != null) {
            tagged.list.setLastUpdatedTimestamp(now);
            addressLists.put(storageKey(region, tagged.list.getAddressListId()), tagged.list);
        } else if (tagged.archive != null) {
            tagged.archive.setLastUpdatedTimestamp(now);
            archives.put(storageKey(region, tagged.archive.getArchiveId()), tagged.archive);
        } else if (tagged.ruleSet != null) {
            tagged.ruleSet.setLastUpdatedTimestamp(now);
            ruleSets.put(storageKey(region, tagged.ruleSet.getRuleSetId()), tagged.ruleSet);
        } else if (tagged.policy != null) {
            tagged.policy.setLastUpdatedTimestamp(now);
            trafficPolicies.put(storageKey(region, tagged.policy.getTrafficPolicyId()), tagged.policy);
        } else {
            tagged.relay.setLastUpdatedTimestamp(now);
            relays.put(storageKey(region, tagged.relay.getRelayId()), tagged.relay);
        }
    }

    private record Tagged(MailManagerAddressList list, MailManagerArchive archive,
                          MailManagerRuleSet ruleSet, MailManagerTrafficPolicy policy,
                          MailManagerRelay relay) {
        static Tagged list(MailManagerAddressList list) {
            return new Tagged(list, null, null, null, null);
        }

        static Tagged archive(MailManagerArchive archive) {
            return new Tagged(null, archive, null, null, null);
        }

        static Tagged ruleSet(MailManagerRuleSet ruleSet) {
            return new Tagged(null, null, ruleSet, null, null);
        }

        static Tagged policy(MailManagerTrafficPolicy policy) {
            return new Tagged(null, null, null, policy, null);
        }

        static Tagged relay(MailManagerRelay relay) {
            return new Tagged(null, null, null, null, relay);
        }

        Map<String, String> tags() {
            if (list != null) {
                return list.getTags();
            }
            if (archive != null) {
                return archive.getTags();
            }
            if (ruleSet != null) {
                return ruleSet.getTags();
            }
            if (policy != null) {
                return policy.getTags();
            }
            return relay.getTags();
        }
    }

    private ObjectNode idNode(String field, String id) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put(field, id);
        return out;
    }

    private Map<String, String> readTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode list = request.get("Tags");
        if (list == null || !list.isArray()) {
            return tags;
        }
        for (JsonNode tag : list) {
            String key = textOrNull(tag, "Key");
            if (key == null) {
                continue;
            }
            String value = textOrNull(tag, "Value");
            tags.put(key, value == null ? "" : value);
        }
        return tags;
    }

    private String readRetention(JsonNode retention) {
        if (retention == null || retention.isNull() || retention.isMissingNode()) {
            return DEFAULT_RETENTION;
        }
        if (!retention.isObject()) {
            throw invalid("Retention must be an object.");
        }
        String period = textOrNull(retention, "RetentionPeriod");
        if (period == null) {
            return DEFAULT_RETENTION;
        }
        if (!RETENTION_PERIODS.contains(period)) {
            throw invalid("RetentionPeriod " + period + " is not supported.");
        }
        return period;
    }

    private JsonNode copyArray(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isArray()) {
            return objectMapper.createArrayNode();
        }
        return node.deepCopy();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull() || !node.get(field).isNumber()) {
            return null;
        }
        return node.get(field).asInt();
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SIGNING_SERVICE, region, resource);
    }

    private static String storageKey(String region, String id) {
        return region + ":" + id;
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String normalizeAddress(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isTextual() && !value.isNumber()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static String requireText(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null) {
            throw invalid(field + " is a required parameter.");
        }
        return value;
    }

    private static int requireInt(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull() || !node.get(field).isNumber()) {
            throw invalid(field + " is a required parameter.");
        }
        return node.get(field).asInt();
    }

    private static long requireEpoch(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            throw invalid(field + " is a required parameter.");
        }
        JsonNode value = node.get(field);
        if (value.isNumber()) {
            return value.asLong();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException e) {
                throw invalid(field + " must be epoch seconds.");
            }
        }
        throw invalid(field + " must be epoch seconds.");
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
