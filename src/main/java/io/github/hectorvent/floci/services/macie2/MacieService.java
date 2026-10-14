package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.macie2.model.MacieMember;
import io.github.hectorvent.floci.services.macie2.model.MacieState;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class MacieService implements Resettable {
    private static final Logger LOG = Logger.getLogger(MacieService.class);

    private final AccountAwareStorageBackend<MacieState> states;
    private final AccountAwareStorageBackend<MacieMember> members;

    private final S3Service s3Service;

    // Classification job execution. Run bookkeeping is guarded by this service's monitor.
    private final Executor jobExecutor;
    private final ExecutorService ownedJobExecutor;
    private final Map<String, JobRun> runs = new HashMap<>();
    private ScheduledExecutorService jobScheduler;

    @Inject
    public MacieService(StorageFactory storageFactory, S3Service s3Service) {
        this(storageFactory.create("macie2", "macie2-state.json",
                        new TypeReference<Map<String, MacieState>>() {}),
                storageFactory.create("macie2", "macie2-members.json",
                        new TypeReference<Map<String, MacieMember>>() {}), s3Service, null);
    }

    MacieService(AccountAwareStorageBackend<MacieState> states,
                 AccountAwareStorageBackend<MacieMember> members) {
        this(states, members, null, null);
    }

    MacieService(AccountAwareStorageBackend<MacieState> states,
                 AccountAwareStorageBackend<MacieMember> members, S3Service s3Service) {
        this(states, members, s3Service, null);
    }

    MacieService(AccountAwareStorageBackend<MacieState> states,
                 AccountAwareStorageBackend<MacieMember> members, S3Service s3Service, Executor jobExecutor) {
        this.states = states;
        this.members = members;
        this.s3Service = s3Service;
        if (jobExecutor == null) {
            this.ownedJobExecutor = Executors.newFixedThreadPool(JOB_WORKERS,
                    Thread.ofPlatform().daemon().name("macie2-classification-", 0).factory());
            this.jobExecutor = ownedJobExecutor;
        } else {
            this.ownedJobExecutor = null;
            this.jobExecutor = jobExecutor;
        }
    }

    @PreDestroy
    synchronized void shutdown() {
        if (ownedJobExecutor != null) {
            ownedJobExecutor.shutdownNow();
        }
        if (jobScheduler != null) {
            jobScheduler.shutdownNow();
            jobScheduler = null;
        }
    }

    public MacieState state(String region) {
        return states.get(region).orElseGet(MacieState::new);
    }

    public synchronized void enableOrganizationAdminAccount(String region, String accountId) {
        requireAccountId(accountId);
        MacieState state = state(region);
        if (state.getAdminAccountId() != null && !state.getAdminAccountId().equals(accountId)) {
            throw conflict("A different Macie administrator account is already configured.");
        }
        state.setAdminAccountId(accountId);
        states.put(region, state);

        MacieState delegated = states.getForAccount(accountId, region).orElseGet(MacieState::new);
        delegated.setAdminAccountId(accountId);
        if (!delegated.isEnabled()) {
            initializeSession(delegated, "ENABLED", "SIX_HOURS");
        }
        states.putForAccount(accountId, region, delegated);
    }

    public void enableMacie(String region) {
        enableMacie(region, null, null);
    }

    public synchronized void enableMacie(String region, String status, String frequency) {
        validateSession(status, frequency);
        MacieState state = state(region);
        if (state.isEnabled()) {
            throw conflict("Macie is already enabled for this account.");
        }
        initializeSession(state, status == null ? "ENABLED" : status,
                frequency == null ? "SIX_HOURS" : frequency);
        states.put(region, state);
    }

    public synchronized void updateMacieSession(String region, String status, String frequency) {
        validateSession(status, frequency);
        MacieState state = requireSession(region);
        if (status != null) {
            state.setStatus(status);
        }
        if (frequency != null) {
            state.setFindingPublishingFrequency(frequency);
        }
        state.setUpdatedAt(Instant.now().toString());
        states.put(region, state);
    }

    public synchronized void disableMacie(String region, String accountId) {
        MacieState state = requireSessionForAccount(region, accountId);
        // Disabling Macie deletes every job, so in-flight runs stop without committing findings.
        stopRuns(run -> run.accountId.equals(accountId) && run.region.equals(region));
        state.setEnabled(false);
        state.setStatus(null);
        state.setFindingPublishingFrequency(null);
        state.setCreatedAt(null);
        state.setUpdatedAt(null);
        state.setAutoEnable(false);
        state.getDocuments().clear();
        state.getCreateRequests().clear();
        states.putForAccount(accountId, region, state);
        for (AccountAwareStorageBackend.AccountEntry<MacieMember> entry
                : members.scanAllAccountEntries(key -> key.startsWith(region + "::"))) {
            if (accountId.equals(entry.accountId()) || accountId.equals(entry.value().accountId())) {
                members.deleteForAccount(entry.accountId(), entry.key());
            }
        }
    }

    private static void initializeSession(MacieState state, String status, String frequency) {
        String now = Instant.now().toString();
        state.setEnabled(true);
        state.setStatus(status);
        state.setFindingPublishingFrequency(frequency);
        state.setCreatedAt(now);
        state.setUpdatedAt(now);
    }

    private static void validateSession(String status, String frequency) {
        if (status != null && !"ENABLED".equals(status) && !"PAUSED".equals(status)) {
            throw validation("status must be ENABLED or PAUSED.");
        }
        if (frequency != null && !List.of("FIFTEEN_MINUTES", "ONE_HOUR", "SIX_HOURS").contains(frequency)) {
            throw validation("findingPublishingFrequency must be FIFTEEN_MINUTES, ONE_HOUR, or SIX_HOURS.");
        }
    }

    public MacieState requireSession(String region) {
        MacieState state = state(region);
        if (!state.isEnabled()) {
            throw notEnabled();
        }
        return state;
    }

    public int testCustomDataIdentifier(String region, String regex, String sampleText,
                                        List<String> keywords, List<String> ignoreWords, Integer maximumMatchDistance) {
        requireSession(region);
        requireText(regex, "regex", 1, 512);
        requireText(sampleText, "sampleText", 0, 1000);
        validateWords(keywords, "keywords", 50, 3);
        validateWords(ignoreWords, "ignoreWords", 10, 4);
        int distance = maximumMatchDistance == null ? 50 : maximumMatchDistance;
        if (distance < 1 || distance > 300) {
            throw validation("maximumMatchDistance must be between 1 and 300.");
        }
        try {
            Pattern pattern = Pattern.compile(regex);
            validatePatternSyntax(regex, pattern);
            Matcher matcher = pattern.matcher(new RegexInput(sampleText));
            List<Pattern> keywordPatterns = keywords.stream()
                    .map(word -> Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                    .toList();
            int count = 0;
            while (matcher.find()) {
                if (matcher.start() == matcher.end()) {
                    throw validation("Floci does not support regex matches of zero length.");
                }
                String match = sampleText.substring(matcher.start(), matcher.end());
                if (ignoreWords.stream().anyMatch(match::contains)) {
                    continue;
                }
                if (keywordPatterns.isEmpty() || hasPrecedingKeyword(
                        sampleText, matcher.start(), matcher.end(), keywordPatterns, distance)) {
                    count++;
                }
            }
            return count;
        } catch (PatternSyntaxException e) {
            throw validation("regex is invalid or uses syntax unsupported by Floci: " + e.getDescription());
        } catch (RegexLimitException | StackOverflowError e) {
            throw validation("regex exceeds Floci's evaluation complexity limit.");
        }
    }

    private static void validatePatternSyntax(String regex, Pattern pattern) {
        if (pattern.matcher("").groupCount() > 0) {
            throw validation("Capturing groups are not supported by Macie.");
        }
        boolean quoted = false;
        int characterClass = 0;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                char escaped = regex.charAt(++i);
                if (escaped == 'E') {
                    quoted = false;
                } else if (!quoted && escaped == 'Q') {
                    quoted = true;
                } else if (!quoted && ((escaped >= '1' && escaped <= '9') || escaped == 'k' || escaped == 'g')) {
                    throw validation("Backreferences are not supported by Macie.");
                }
                continue;
            }
            if (quoted) {
                continue;
            }
            if (c == '[') {
                characterClass++;
            } else if (c == ']' && characterClass > 0) {
                characterClass--;
            } else if (characterClass == 0 && c == '(' && !regex.startsWith("(?:", i)) {
                int flag = i + 2;
                while (flag < regex.length() && "imsx-".indexOf(regex.charAt(flag)) >= 0) {
                    flag++;
                }
                if (!regex.startsWith("(?", i) || flag == i + 2 || flag >= regex.length()
                        || (regex.charAt(flag) != ')' && regex.charAt(flag) != ':')) {
                    throw validation("The regex group or assertion is not supported by Macie.");
                }
            }
        }
    }

    private static boolean hasPrecedingKeyword(String text, int start, int end,
                                               List<Pattern> keywords, int distance) {
        for (Pattern keyword : keywords) {
            Matcher matcher = keyword.matcher(text.substring(0, start));
            int next = 0;
            while (matcher.find(next)) {
                if (text.codePointCount(matcher.end(), end) <= distance) {
                    return true;
                }
                next = matcher.start() + 1;
            }
        }
        return false;
    }

    private static void validateWords(List<String> words, String field, int limit, int minimumLength) {
        if (words == null || words.size() > limit) {
            throw validation(field + " must contain at most " + limit + " strings.");
        }
        words.forEach(word -> requireText(word, field, minimumLength, 90));
    }

    static void requireText(String value, String field, int minimumLength, int maximumLength) {
        if (value == null || value.codePointCount(0, value.length()) < minimumLength
                || value.codePointCount(0, value.length()) > maximumLength) {
            throw validation(field + " must contain " + minimumLength + " to " + maximumLength + " characters.");
        }
    }

    // Bound backtracking work on untrusted patterns without starting background threads.
    private static final class RegexInput implements CharSequence {
        private final String text;
        private final int[] remaining;

        private RegexInput(String text) {
            this(text, new int[]{1_000_000});
        }

        private RegexInput(String text, int[] remaining) {
            this.text = text;
            this.remaining = remaining;
        }

        @Override
        public int length() { return text.length(); }

        @Override
        public char charAt(int index) {
            if (--remaining[0] < 0) {
                throw new RegexLimitException();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new RegexInput(text.substring(start, end), remaining);
        }

        @Override
        public String toString() { return text; }
    }

    private static final class RegexLimitException extends RuntimeException {}

    public MacieState requireAdministratorSession(String region, String callerAccountId) {
        MacieState state = states.getForAccount(callerAccountId, region).orElseGet(MacieState::new);
        if (state.getAdminAccountId() == null && !state.isEnabled()) {
            throw notEnabled();
        }
        if (!callerAccountId.equals(state.getAdminAccountId())) {
            throw accessDenied();
        }
        if (!state.isEnabled()) {
            throw notEnabled();
        }
        return state;
    }

    public synchronized void updateOrganizationConfiguration(
            String region, String callerAccountId, boolean autoEnable) {
        MacieState state = requireAdministratorSession(region, callerAccountId);
        state.setAutoEnable(autoEnable);
        states.putForAccount(callerAccountId, region, state);
    }

    public synchronized MacieMember createMember(
            String region, String callerAccountId, String memberAccountId, String email, Map<String, String> tags) {
        MacieState callerState = requireSessionForAccount(region, callerAccountId);
        requireAccountId(memberAccountId, "accountId");
        if (callerAccountId.equals(memberAccountId)) {
            throw validation("accountId must identify a different AWS account.");
        }
        requireEmail(email);
        Map<String, String> safeTags = validateTags(tags);

        String key = memberKey(region, memberAccountId);
        if (members.getForAccount(callerAccountId, key).isPresent()) {
            throw conflict("The account is already associated with this Macie administrator account.");
        }
        boolean associatedWithDifferentAdministrator = members.scanAllAccountEntries(
                        candidate -> candidate.startsWith(region + "::")).stream()
                .anyMatch(entry -> memberAccountId.equals(entry.value().accountId())
                        && !callerAccountId.equals(entry.value().administratorAccountId()));
        if (associatedWithDifferentAdministrator) {
            throw conflict("The account is already associated with a different Macie administrator account.");
        }

        boolean organizationAdministrator = callerAccountId.equals(callerState.getAdminAccountId());
        String now = Instant.now().toString();
        String arn = "arn:aws:macie2:" + region + ":" + callerAccountId + ":member/" + memberAccountId;
        MacieMember member = new MacieMember(
                memberAccountId,
                callerAccountId,
                callerAccountId,
                arn,
                organizationAdministrator ? null : email,
                null,
                organizationAdministrator ? "Enabled" : "Created",
                safeTags,
                now);
        members.putForAccount(callerAccountId, key, member);
        return member;
    }

    public Page<MacieMember> listMembers(
            String region, String callerAccountId, String maxResultsValue, String nextToken, String onlyAssociated) {
        requireSessionForAccount(region, callerAccountId);
        int maxResults = parseMaxResults(maxResultsValue);
        Boolean associatedOnly = parseOnlyAssociated(onlyAssociated);
        List<MacieMember> items = new ArrayList<>(members.scanForAccount(
                callerAccountId, key -> key.startsWith(region + "::")));
        if (associatedOnly == null || associatedOnly) {
            items.removeIf(member -> !isCurrentMember(member.relationshipStatus()));
        }
        items.sort(Comparator.comparing(MacieMember::accountId));
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(items.size(), offset + maxResults);
        return new Page<>(List.copyOf(items.subList(offset, end)),
                end < items.size() ? encodeOffset(end) : null);
    }

    public java.util.Optional<MacieMember> administrator(String region, String callerAccountId) {
        requireSessionForAccount(region, callerAccountId);
        return membershipRecords(region, callerAccountId).stream()
                .filter(member -> isCurrentMember(member.relationshipStatus()))
                .findFirst();
    }

    public Page<MacieMember> listInvitations(String region, String callerAccountId,
                                            String maxResults, String nextToken) {
        requireSessionForAccount(region, callerAccountId);
        List<MacieMember> invitations = membershipRecords(region, callerAccountId).stream()
                .filter(member -> member.invitedAt() != null && "Invited".equals(member.relationshipStatus()))
                .toList();
        int limit = parseMaxResults(maxResults);
        int start = decodeOffset(nextToken, invitations.size());
        int end = Math.min(invitations.size(), start + limit);
        return new Page<>(invitations.subList(start, end), end < invitations.size() ? encodeOffset(end) : null);
    }

    public long invitationsCount(String region, String callerAccountId) {
        requireSessionForAccount(region, callerAccountId);
        return membershipRecords(region, callerAccountId).stream()
                .filter(member -> member.invitedAt() != null && "Invited".equals(member.relationshipStatus()))
                .count();
    }

    private List<MacieMember> membershipRecords(String region, String callerAccountId) {
        return members.scanAllAccountEntries(key -> key.equals(memberKey(region, callerAccountId))).stream()
                .map(AccountAwareStorageBackend.AccountEntry::value)
                .filter(member -> callerAccountId.equals(member.accountId())
                        && !callerAccountId.equals(member.administratorAccountId()))
                .sorted(Comparator.comparing(MacieMember::administratorAccountId))
                .toList();
    }

    private MacieState requireSessionForAccount(String region, String accountId) {
        MacieState state = states.getForAccount(accountId, region).orElseGet(MacieState::new);
        if (!state.isEnabled()) {
            throw notEnabled();
        }
        return state;
    }

    private static boolean isCurrentMember(String relationshipStatus) {
        return "Enabled".equals(relationshipStatus) || "Paused".equals(relationshipStatus);
    }

    private static Boolean parseOnlyAssociated(String value) {
        if (value == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw validation("onlyAssociated must be true or false.");
    }

    private static int parseMaxResults(String value) {
        if (value == null) {
            return 25;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 25) {
                throw validation("maxResults must be between 1 and 25.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be between 1 and 25.");
        }
    }

    private static int decodeOffset(String token, int size) {
        if (token == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            if (offset < 0 || offset > size) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static String memberKey(String region, String accountId) {
        return region + "::" + accountId;
    }

    private static void requireEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 320 || email.indexOf('@') <= 0
                || email.endsWith("@")) {
            throw validation("email must be a valid email address.");
        }
    }

    private static Map<String, String> validateTags(Map<String, String> tags) {
        if (tags == null) {
            return Map.of();
        }
        if (tags.size() > 50) {
            throw validation("A member can have at most 50 tags.");
        }
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            if (tag.getKey() == null || tag.getKey().length() > 128
                    || tag.getValue() == null || tag.getValue().length() > 256) {
                throw validation("Tag keys can be at most 128 characters and values at most 256 characters.");
            }
        }
        return Map.copyOf(tags);
    }

    public synchronized ObjectNode createResource(String region, String accountId, String kind, JsonNode request) {
        MacieState state = requireSessionForAccount(region, accountId);
        String token = text(request, "clientToken", false);
        String tokenKey = kind + "/" + token;
        ObjectNode previous = token == null ? null : state.getCreateRequests().get(tokenKey);
        if (previous != null) {
            if (!previous.path("request").equals(request)) {
                throw conflict("clientToken has already been used with different parameters.");
            }
            return previous.withObject("/response").deepCopy();
        }
        ObjectNode document = object();
        for (String field : resourceFields(kind)) {
            if (request.has(field)) {
                document.set(field, request.get(field).deepCopy());
            }
        }
        document.set("tags", tagsNode(readTags(request.path("tags"))));
        validateResource(region, kind, document);
        String id = UUID.randomUUID().toString().replace("-", "");
        if ("allow-list".equals(kind)) {
            id = id.substring(0, 22);
        }
        document.put("id", id);
        document.put("arn", "arn:aws:macie2:" + region + ":" + accountId + ":" + kind + "/" + id);
        if (!"findings-filter".equals(kind)) {
            document.put("createdAt", Instant.now().toString());
        }
        if ("custom-data-identifier".equals(kind)) {
            document.put("deleted", false);
        }
        if ("allow-list".equals(kind)) {
            document.put("updatedAt", document.path("createdAt").asText());
            document.set("status", object().put("code", "OK"));
        }
        state.getDocuments().put(kind + "/" + id, document);
        ObjectNode response = resourceIdentity(kind, document);
        if (token != null) {
            ObjectNode receipt = object();
            receipt.set("request", request.deepCopy());
            receipt.set("response", response.deepCopy());
            state.getCreateRequests().put(tokenKey, receipt);
        }
        states.putForAccount(accountId, region, state);
        return response;
    }

    public synchronized ObjectNode getResource(String region, String accountId, String kind, String id) {
        ObjectNode document = requireSessionForAccount(region, accountId).getDocuments().get(kind + "/" + id);
        if (document == null) {
            throw notFound("The requested Macie " + kind + " does not exist.");
        }
        return document.deepCopy();
    }

    public synchronized ObjectNode updateResource(String region, String accountId, String kind,
                                                   String id, JsonNode request) {
        if ("custom-data-identifier".equals(kind)) {
            throw validation("Custom data identifier definitions are immutable.");
        }
        ObjectNode document = getResource(region, accountId, kind, id);
        for (String field : resourceFields(kind)) {
            if (request.has(field)) {
                document.set(field, request.get(field).deepCopy());
            }
        }
        validateResource(region, kind, document);
        if ("allow-list".equals(kind)) {
            document.put("updatedAt", Instant.now().toString());
        }
        MacieState state = requireSessionForAccount(region, accountId);
        state.getDocuments().put(kind + "/" + id, document);
        states.putForAccount(accountId, region, state);
        return resourceIdentity(kind, document);
    }

    public synchronized void deleteResource(String region, String accountId, String kind, String id) {
        ObjectNode document = getResource(region, accountId, kind, id);
        MacieState state = requireSessionForAccount(region, accountId);
        if ("custom-data-identifier".equals(kind)) {
            document.put("deleted", true);
            state.getDocuments().put(kind + "/" + id, document);
        } else {
            state.getDocuments().remove(kind + "/" + id);
        }
        states.putForAccount(accountId, region, state);
    }

    public ObjectNode listResources(String region, String accountId, String kind, String resultKey,
                                     String maxResults, String nextToken) {
        List<ObjectNode> documents = documents(region, accountId, kind).stream()
                .filter(document -> !document.path("deleted").asBoolean())
                .map(document -> {
                    switch (kind) {
                        case "allow-list" -> document.retain("id", "arn", "name", "description", "createdAt", "updatedAt");
                        case "custom-data-identifier" -> document.retain("id", "arn", "name", "description", "createdAt");
                        case "findings-filter" -> document.retain("id", "arn", "name", "action", "tags");
                        default -> { }
                    }
                    return document;
                }).toList();
        return page(documents, resultKey, maxResults, nextToken);
    }

    public ObjectNode batchGetIdentifiers(String region, String accountId, JsonNode request) {
        MacieState state = requireSessionForAccount(region, accountId);
        List<String> ids = strings(request.path("ids"), "ids");
        if (ids.isEmpty() || ids.size() > 25) {
            throw validation("ids must contain between 1 and 25 identifiers.");
        }
        ObjectNode response = object();
        ArrayNode found = response.putArray("customDataIdentifiers");
        ArrayNode missing = response.putArray("notFoundIdentifierIds");
        for (String id : ids) {
            ObjectNode document = state.getDocuments().get("custom-data-identifier/" + id);
            if (document == null) {
                missing.add(id);
            } else {
                ObjectNode summary = document.deepCopy();
                summary.retain("id", "arn", "name", "description", "createdAt", "deleted");
                found.add(summary);
            }
        }
        return response;
    }

    public Map<String, String> resourceTags(String region, String accountId, String arn) {
        return readTags(resourceByArn(region, accountId, arn).path("tags"));
    }

    public synchronized void changeTags(String region, String accountId, String arn,
                                         Map<String, String> additions, List<String> removals) {
        ObjectNode document = resourceByArn(region, accountId, arn);
        Map<String, String> tags = new LinkedHashMap<>(readTags(document.path("tags")));
        tags.putAll(additions);
        removals.forEach(tags::remove);
        document.set("tags", tagsNode(validateTags(tags)));
        MacieState state = requireSessionForAccount(region, accountId);
        String key = arn.substring(("arn:aws:macie2:" + region + ":" + accountId + ":").length());
        state.getDocuments().put(key, document);
        states.putForAccount(accountId, region, state);
    }

    private ObjectNode resourceByArn(String region, String accountId, String arn) {
        String prefix = "arn:aws:macie2:" + region + ":" + accountId + ":";
        if (arn == null || !arn.startsWith(prefix)) {
            throw notFound("The resource ARN does not belong to this account and region.");
        }
        String[] identity = arn.substring(prefix.length()).split("/", -1);
        if (identity.length != 2 || !List.of("allow-list", "custom-data-identifier", "findings-filter",
                        MacieClassificationJobs.KIND).contains(identity[0])) {
            throw validation("Invalid Macie resource ARN.");
        }
        ObjectNode document = getResource(region, accountId, identity[0], identity[1]);
        if (document.path("deleted").asBoolean()) {
            throw notFound("The custom data identifier has been deleted.");
        }
        return document;
    }

    private void validateResource(String region, String kind, ObjectNode document) {
        requireText(text(document, "name", true), "name", 1, "findings-filter".equals(kind) ? 64 : 128);
        if (document.has("description")) {
            requireText(text(document, "description", false), "description", 0, 512);
        }
        switch (kind) {
            case "custom-data-identifier" -> {
                Integer distance = integer(document, "maximumMatchDistance", 1, 300, null);
                testCustomDataIdentifier(region, text(document, "regex", true), "",
                        strings(document.path("keywords"), "keywords"),
                        strings(document.path("ignoreWords"), "ignoreWords"), distance);
                if (document.has("severityLevels")) {
                    JsonNode levels = document.get("severityLevels");
                    if (!levels.isArray() || levels.isEmpty() || levels.size() > 3) {
                        throw validation("severityLevels must contain between 1 and 3 levels.");
                    }
                    int last = 0;
                    for (JsonNode level : levels) {
                        int threshold = integer(level, "occurrencesThreshold", 1, Integer.MAX_VALUE, null);
                        if (threshold <= last || !List.of("LOW", "MEDIUM", "HIGH")
                                .contains(text(level, "severity", true))) {
                            throw validation("severityLevels must have increasing thresholds and valid severities.");
                        }
                        last = threshold;
                    }
                }
            }
            case "allow-list" -> {
                JsonNode criteria = document.path("criteria");
                if (!criteria.isObject() || criteria.has("regex") == criteria.has("s3WordsList")) {
                    throw validation("criteria must specify exactly one of regex and s3WordsList.");
                }
                if (criteria.has("s3WordsList")) {
                    throw validation("Floci does not support Macie S3 words-list ingestion.");
                }
                String regex = text(criteria, "regex", true);
                requireText(regex, "regex", 1, 512);
                try {
                    validatePatternSyntax(regex, Pattern.compile(regex));
                } catch (PatternSyntaxException e) {
                    throw validation("regex is invalid: " + e.getDescription());
                }
            }
            case "findings-filter" -> {
                if (!List.of("ARCHIVE", "NOOP").contains(text(document, "action", true))) {
                    throw validation("action must be ARCHIVE or NOOP.");
                }
                integer(document, "position", 1, Integer.MAX_VALUE, 1);
                validateCriteria(document.path("findingCriteria"));
            }
            default -> throw validation("Unsupported Macie resource type.");
        }
    }

    private static List<String> resourceFields(String kind) {
        return switch (kind) {
            case "allow-list" -> List.of("name", "description", "criteria");
            case "custom-data-identifier" -> List.of("name", "description", "regex", "keywords", "ignoreWords",
                    "maximumMatchDistance", "severityLevels");
            case "findings-filter" -> List.of("name", "description", "action", "position", "findingCriteria");
            default -> throw validation("Unsupported Macie resource type.");
        };
    }

    private static ObjectNode resourceIdentity(String kind, ObjectNode document) {
        if ("custom-data-identifier".equals(kind)) {
            return object().put("customDataIdentifierId", document.path("id").asText());
        }
        return object().put("id", document.path("id").asText()).put("arn", document.path("arn").asText());
    }

    public synchronized ObjectNode configuration(String region, String accountId, String name) {
        MacieState state = requireSessionForAccount(region, accountId);
        String key = "configuration/" + name;
        ObjectNode stored = state.getDocuments().get(key);
        if (stored == null) {
            stored = object();
            switch (name) {
                case "export" -> stored.set("configuration", object());
                case "discovery" -> {
                    stored.put("status", "DISABLED");
                    stored.put("autoEnableOrganizationMembers", "NONE");
                    stored.put("classificationScopeId", configuration(region, accountId, "scope").path("id").asText());
                }
                case "reveal" -> stored.set("configuration", object().put("status", "DISABLED"));
                case "scope" -> {
                    stored.put("id", UUID.randomUUID().toString());
                    stored.put("name", "default");
                    stored.putObject("s3").putObject("excludes").putArray("bucketNames");
                }
                default -> throw validation("Unknown Macie configuration.");
            }
            state.getDocuments().put(key, stored);
            states.putForAccount(accountId, region, state);
        }
        return stored.deepCopy();
    }

    public synchronized void putExportConfiguration(String region, String accountId, JsonNode request) {
        MacieState state = requireSessionForAccount(region, accountId);
        JsonNode configuration = request.path("configuration");
        if (!configuration.isObject()) {
            throw validation("configuration must be an object.");
        }
        if (configuration.has("s3Destination")) {
            JsonNode destination = configuration.get("s3Destination");
            text(destination, "bucketName", true);
            text(destination, "kmsKeyArn", true);
        }
        ObjectNode stored = object();
        stored.set("configuration", configuration.deepCopy());
        state.getDocuments().put("configuration/export", stored);
        states.putForAccount(accountId, region, state);
    }

    public ObjectNode classificationScope(String region, String accountId, String id) {
        ObjectNode scope = configuration(region, accountId, "scope");
        if (!scope.path("id").asText().equals(id)) {
            throw notFound("The classification scope does not exist.");
        }
        return scope;
    }

    public ObjectNode usageTotals(String region, String accountId) {
        return page(documents(region, accountId, "usage"), "usageTotals", null, null);
    }

    public ObjectNode listManagedIdentifiers(String region, String accountId, JsonNode request) {
        requireSessionForAccount(region, accountId);
        // The managed identifiers that classification jobs evaluate in Floci.
        List<ObjectNode> catalog = MacieDataClassifier.MANAGED.stream()
                .map(identifier -> object().put("id", identifier.id()).put("category", identifier.category()))
                .toList();
        return page(catalog, "items", limit(request), text(request, "nextToken", false));
    }

    public synchronized void createSampleFindings(String region, String accountId, JsonNode request) {
        MacieState state = requireSessionForAccount(region, accountId);
        List<String> types = strings(request.path("findingTypes"), "findingTypes");
        if (types.isEmpty()) {
            types = List.of("Policy:IAMUser/S3BucketPublic");
        }
        for (String type : types) {
            if (!"Policy:IAMUser/S3BucketPublic".equals(type)) {
                throw validation("Floci supports the Policy:IAMUser/S3BucketPublic sample finding only.");
            }
        }
        for (String type : types) {
            String id = UUID.randomUUID().toString().replace("-", "");
            ObjectNode finding = object().put("id", id).put("type", type).put("sample", true)
                    .put("accountId", accountId).put("region", region).put("category", "POLICY")
                    .put("arn", "arn:aws:macie2:" + region + ":" + accountId + ":finding/" + id)
                    .put("title", "Sample public bucket finding")
                    .put("description", "Sample finding generated by CreateSampleFindings. No S3 data was analyzed.")
                    .put("createdAt", Instant.now().toString()).put("updatedAt", Instant.now().toString())
                    .put("count", 1).put("archived", false);
            finding.set("severity", object().put("description", "High").put("score", 3));
            boolean archived = documents(region, accountId, "findings-filter").stream()
                    .anyMatch(filter -> "ARCHIVE".equals(filter.path("action").asText())
                            && matches(finding, filter.path("findingCriteria")));
            finding.put("archived", archived);
            state.getDocuments().put("finding/" + id, finding);
        }
        states.putForAccount(accountId, region, state);
    }

    public ObjectNode listFindings(String region, String accountId, JsonNode request) {
        JsonNode criteria = request.path("findingCriteria");
        if (!criteria.isMissingNode()) {
            validateCriteria(criteria);
        }
        if (request.has("sortCriteria")) {
            throw validation("Floci does not support Macie finding sort criteria.");
        }
        List<ObjectNode> found = documents(region, accountId, "finding").stream()
                .filter(finding -> matches(finding, criteria)).toList();
        ObjectNode response = page(found, "items", limit(request), text(request, "nextToken", false));
        ArrayNode ids = response.putArray("findingIds");
        response.path("items").forEach(finding -> ids.add(finding.path("id").asText()));
        response.remove("items");
        return response;
    }

    public ObjectNode getFindings(String region, String accountId, JsonNode request) {
        requireSessionForAccount(region, accountId);
        List<String> ids = strings(request.path("findingIds"), "findingIds");
        if (ids.isEmpty() || ids.size() > 50) {
            throw validation("findingIds must contain between 1 and 50 identifiers.");
        }
        ObjectNode response = object();
        ArrayNode findings = response.putArray("findings");
        for (String id : ids) {
            findings.add(getResource(region, accountId, "finding", id));
        }
        return response;
    }

    public ObjectNode findingStatistics(String region, String accountId, JsonNode request) {
        String groupBy = text(request, "groupBy", true);
        if (!List.of("resourcesAffected.s3Bucket.name", "type", "classificationDetails.jobId",
                "severity.description").contains(groupBy)) {
            throw validation("Unsupported finding statistics groupBy.");
        }
        JsonNode criteria = request.path("findingCriteria");
        if (!criteria.isMissingNode()) {
            validateCriteria(criteria);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ObjectNode finding : documents(region, accountId, "finding")) {
            JsonNode value = field(finding, groupBy);
            if (matches(finding, criteria) && !value.isMissingNode()) {
                counts.merge(value.asText(), 1L, Long::sum);
            }
        }
        ObjectNode response = object();
        ArrayNode groups = response.putArray("countsByGroup");
        counts.forEach((key, count) -> groups.add(object().put("groupKey", key).put("count", count)));
        return response;
    }

    public ObjectNode bucketStatistics(String region, String accountId, JsonNode request) {
        requireSessionForAccount(region, accountId);
        List<ObjectNode> buckets = inventory(region, accountId);
        String requestedAccount = text(request, "accountId", false);
        if (requestedAccount != null && !accountId.equals(requestedAccount)) {
            throw validation("Floci does not support cross-account Macie inventory.");
        }
        return object().put("bucketCount", buckets.size())
                .put("objectCount", buckets.stream().mapToLong(bucket -> bucket.path("objectCount").asLong()).sum())
                .put("sizeInBytes", buckets.stream().mapToLong(bucket -> bucket.path("sizeInBytes").asLong()).sum());
    }

    public ObjectNode searchResources(String region, String accountId, JsonNode request) {
        requireSessionForAccount(region, accountId);
        if (request.has("sortCriteria")) {
            throw validation("Floci does not support Macie inventory sort criteria.");
        }
        JsonNode criteria = request.path("bucketCriteria");
        validateBucketCriteria(criteria);
        List<ObjectNode> matching = inventory(region, accountId).stream()
                .filter(bucket -> bucketMatches(bucket, criteria.path("includes"), true)
                        && !bucketMatches(bucket, criteria.path("excludes"), false))
                .map(bucket -> {
                    ObjectNode resource = object();
                    resource.set("matchingBucket", bucket);
                    return resource;
                }).toList();
        return page(matching, "matchingResources", limit(request), text(request, "nextToken", false));
    }

    private List<ObjectNode> inventory(String region, String accountId) {
        List<ObjectNode> inventory = new ArrayList<>();
        for (Bucket bucket : s3Service.listBuckets()) {
            if (!region.equals(bucket.getRegion())) {
                continue;
            }
            List<S3Object> objects = s3Service.listObjects(bucket.getName(), null, null, Integer.MAX_VALUE);
            inventory.add(object().put("accountId", accountId).put("bucketName", bucket.getName())
                    .put("objectCount", objects.size())
                    .put("sizeInBytes", objects.stream().mapToLong(S3Object::getSize).sum())
                    .put("automatedDiscoveryMonitoringStatus", "NOT_MONITORED"));
        }
        inventory.sort(Comparator.comparing(bucket -> bucket.path("bucketName").asText()));
        return inventory;
    }

    private static void validateBucketCriteria(JsonNode criteria) {
        if (criteria.isMissingNode()) {
            return;
        }
        if (!criteria.isObject()) {
            throw validation("bucketCriteria must be an object.");
        }
        for (String mode : List.of("includes", "excludes")) {
            if (!criteria.has(mode)) {
                continue;
            }
            JsonNode terms = criteria.path(mode).path("and");
            if (!terms.isArray() || terms.isEmpty()) {
                throw validation("Bucket criteria must contain a nonempty and array.");
            }
            for (JsonNode term : terms) {
                JsonNode simple = term.path("simpleCriterion");
                if (term.has("tagCriterion") || !"S3_BUCKET_NAME".equals(simple.path("key").asText())
                        || !List.of("EQ", "NE").contains(simple.path("comparator").asText())
                        || strings(simple.path("values"), "values").isEmpty()) {
                    throw validation("Floci supports S3_BUCKET_NAME bucket criteria with EQ or NE only.");
                }
            }
        }
    }

    private static boolean bucketMatches(JsonNode bucket, JsonNode criteria, boolean missing) {
        if (criteria.isMissingNode()) {
            return missing;
        }
        for (JsonNode term : criteria.path("and")) {
            JsonNode simple = term.path("simpleCriterion");
            boolean contains = strings(simple.path("values"), "values").contains(bucket.path("bucketName").asText());
            if ("EQ".equals(simple.path("comparator").asText()) != contains) {
                return false;
            }
        }
        return true;
    }

    private static void validateCriteria(JsonNode criteria) {
        if (!criteria.isObject() || !criteria.path("criterion").isObject()) {
            throw validation("findingCriteria.criterion must be an object.");
        }
        criteria.path("criterion").fields().forEachRemaining(entry -> {
            JsonNode condition = entry.getValue();
            if (!condition.isObject() || condition.isEmpty()) {
                throw validation("Finding criterion must specify a comparison.");
            }
            condition.fields().forEachRemaining(comparison -> {
                if (!List.of("eq", "neq", "eqExactMatch", "gt", "gte", "lt", "lte")
                        .contains(comparison.getKey())) {
                    throw validation("Unsupported finding criterion comparison.");
                }
                if (List.of("eq", "neq", "eqExactMatch").contains(comparison.getKey())) {
                    if (strings(comparison.getValue(), comparison.getKey()).isEmpty()) {
                        throw validation("Finding criterion values cannot be empty.");
                    }
                } else if (!comparison.getValue().isNumber()) {
                    throw validation("Numeric finding comparisons require numbers.");
                }
            });
        });
    }

    private static boolean matches(JsonNode document, JsonNode criteria) {
        for (Map.Entry<String, JsonNode> entry : criteria.path("criterion").properties()) {
            JsonNode actual = field(document, entry.getKey());
            for (Map.Entry<String, JsonNode> comparison : entry.getValue().properties()) {
                boolean equal = comparison.getValue().isArray()
                        && strings(comparison.getValue(), comparison.getKey()).contains(actual.asText());
                boolean match = switch (comparison.getKey()) {
                    case "eq", "eqExactMatch" -> equal;
                    case "neq" -> !equal;
                    case "gt" -> actual.isNumber() && actual.asDouble() > comparison.getValue().asDouble();
                    case "gte" -> actual.isNumber() && actual.asDouble() >= comparison.getValue().asDouble();
                    case "lt" -> actual.isNumber() && actual.asDouble() < comparison.getValue().asDouble();
                    case "lte" -> actual.isNumber() && actual.asDouble() <= comparison.getValue().asDouble();
                    default -> false;
                };
                if (!match) {
                    return false;
                }
            }
        }
        return true;
    }

    private static JsonNode field(JsonNode node, String path) {
        for (String part : path.split("\\.")) {
            node = node.path(part);
        }
        return node;
    }

    private synchronized List<ObjectNode> documents(String region, String accountId, String kind) {
        return requireSessionForAccount(region, accountId).getDocuments().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(kind + "/"))
                .sorted(Map.Entry.comparingByKey()).map(entry -> entry.getValue().deepCopy()).toList();
    }

    public static ObjectNode page(List<ObjectNode> documents, String key, String maxResults, String nextToken) {
        int limit = 50;
        if (maxResults != null) {
            try {
                limit = Integer.parseInt(maxResults);
            } catch (NumberFormatException e) {
                throw validation("maxResults must be an integer.");
            }
            if (limit < 1 || limit > 1000) {
                throw validation("maxResults must be between 1 and 1000.");
            }
        }
        int start = decodeOffset(nextToken, documents.size());
        int end = Math.min(documents.size(), start + limit);
        ObjectNode response = object();
        ArrayNode items = response.putArray(key);
        documents.subList(start, end).forEach(document -> items.add(document.deepCopy()));
        if (end < documents.size()) {
            response.put("nextToken", encodeOffset(end));
        }
        return response;
    }

    public static String limit(JsonNode request) {
        Integer value = integer(request, "maxResults", 1, 1000, 50);
        return value.toString();
    }

    private static Integer integer(JsonNode request, String field, int min, int max, Integer fallback) {
        JsonNode value = request.get(field);
        if (value == null && fallback != null) {
            return fallback;
        }
        if (value == null && "maximumMatchDistance".equals(field)) {
            return null;
        }
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            throw validation(field + " must be an integer between " + min + " and " + max + ".");
        }
        return value.intValue();
    }

    static String text(JsonNode request, String field, boolean required) {
        JsonNode value = request.get(field);
        if (value == null && !required) {
            return null;
        }
        if (value == null || !value.isTextual() || (required && value.textValue().isEmpty())) {
            throw validation(field + " must be a string" + (required ? " and cannot be empty." : "."));
        }
        return value.textValue();
    }

    static List<String> strings(JsonNode node, String field) {
        if (node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw validation(field + " must be an array of strings.");
            }
            result.add(item.textValue());
        }
        return result;
    }

    static Map<String, String> readTags(JsonNode node) {
        if (node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw validation("tags must be an object.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw validation("Tag values must be strings.");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return validateTags(tags);
    }

    static ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = object();
        tags.forEach(node::put);
        return node;
    }

    static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    // --- Classification jobs ---

    private static final String JOB = MacieClassificationJobs.KIND;
    private static final int JOB_WORKERS = 2;
    private static final long SCHEDULER_PERIOD_SECONDS = 60;
    private static final String STOP_CANCELLED = "CANCELLED";
    private static final String STOP_PAUSED = "USER_PAUSED";

    /** One in-progress (or user-paused) run of a job. Mutable fields are guarded by the service monitor. */
    static final class JobRun {
        final String accountId;
        final String region;
        final String jobId;
        /** Only objects changed after this instant are analyzed; null analyzes every object. */
        final Instant changedAfter;
        /** Skip objects that already have a finding from this job (a run restarted after a Floci restart). */
        final boolean skipReported;
        final Set<String> processed = new HashSet<>();
        String stop;
        boolean active;
        volatile boolean errors;

        JobRun(String accountId, String region, String jobId, Instant changedAfter, boolean skipReported) {
            this.accountId = accountId;
            this.region = region;
            this.jobId = jobId;
            this.changedAfter = changedAfter;
            this.skipReported = skipReported;
        }

        String key() {
            return runKey(accountId, region, jobId);
        }
    }

    /** What a run evaluates, snapshotted from the job and the identifiers it references. */
    private record RunPlan(ObjectNode job, List<MacieDataClassifier.Rule> rules, List<Pattern> allowList,
                           Map<String, JsonNode> customSeverityLevels) {}

    private record Outcome(ObjectNode finding, boolean error) {}

    private static String runKey(String accountId, String region, String jobId) {
        return accountId + "/" + region + "/" + jobId;
    }

    public synchronized ObjectNode createClassificationJob(String region, String accountId, JsonNode request) {
        MacieState state = requireSessionForAccount(region, accountId);
        String token = text(request, "clientToken", true);
        String tokenKey = JOB + "/" + token;
        ObjectNode previous = state.getCreateRequests().get(tokenKey);
        if (previous != null) {
            if (!previous.path("request").equals(request)) {
                throw conflict("clientToken has already been used with different parameters.");
            }
            return previous.withObject("/response").deepCopy();
        }
        ObjectNode job = MacieClassificationJobs.definition(request, accountId,
                member -> members.getForAccount(accountId, memberKey(region, member))
                        .map(existing -> isCurrentMember(existing.relationshipStatus())).orElse(false),
                id -> {
                    ObjectNode identifier = state.getDocuments().get("custom-data-identifier/" + id);
                    return identifier != null && !identifier.path("deleted").asBoolean();
                },
                id -> state.getDocuments().containsKey("allow-list/" + id));
        String id = UUID.randomUUID().toString().replace("-", "");
        String now = Instant.now().toString();
        String jobArn = "arn:aws:macie2:" + region + ":" + accountId + ":" + JOB + "/" + id;
        job.put("jobId", id);
        job.put("jobArn", jobArn);
        job.put("clientToken", token);
        job.put("createdAt", now);
        job.put("lastRunTime", now);
        job.put("jobStatus", "IDLE");
        job.set("lastRunErrorStatus", object().put("code", "NONE"));
        job.set("statistics", object().put("approximateNumberOfObjectsToProcess", 0).put("numberOfRuns", 0));
        state.getDocuments().put(JOB + "/" + id, job);
        ObjectNode response = object().put("jobArn", jobArn).put("jobId", id);
        ObjectNode receipt = object();
        receipt.set("request", request.deepCopy());
        receipt.set("response", response.deepCopy());
        state.getCreateRequests().put(tokenKey, receipt);
        states.putForAccount(accountId, region, state);

        boolean scheduled = MacieClassificationJobs.SCHEDULED.equals(job.path("jobType").asText());
        if (!scheduled || job.path("initialRun").asBoolean(false)) {
            startRun(accountId, region, id, null, false, true);
        }
        if (scheduled) {
            ensureJobScheduler();
        }
        return response;
    }

    public synchronized ObjectNode describeClassificationJob(String region, String accountId, String jobId) {
        MacieState state = requireSessionForAccount(region, accountId);
        expirePausedJobs(accountId, region, state);
        ObjectNode job = state.getDocuments().get(JOB + "/" + jobId);
        if (job == null) {
            throw notFound("The classification job " + jobId + " does not exist.");
        }
        return job.deepCopy();
    }

    public synchronized ObjectNode listClassificationJobs(String region, String accountId, JsonNode request) {
        MacieState state = requireSessionForAccount(region, accountId);
        MacieClassificationJobs.validateListRequest(request);
        expirePausedJobs(accountId, region, state);
        JsonNode filter = request.get("filterCriteria");
        List<ObjectNode> summaries = state.getDocuments().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(JOB + "/"))
                .map(Map.Entry::getValue)
                .filter(job -> MacieClassificationJobs.listed(job, filter))
                .sorted(MacieClassificationJobs.listOrder(request.get("sortCriteria")))
                .map(MacieClassificationJobs::summary)
                .toList();
        return page(summaries, "items", limit(request), text(request, "nextToken", false));
    }

    /**
     * Applies UpdateClassificationJob: CANCELLED (from IDLE, PAUSED, RUNNING, USER_PAUSED),
     * USER_PAUSED (from IDLE, PAUSED, RUNNING) and RUNNING (resume, from USER_PAUSED).
     */
    public synchronized void updateClassificationJob(String region, String accountId, String jobId, JsonNode request) {
        MacieState state = requireSessionForAccount(region, accountId);
        String target = text(request, "jobStatus", true);
        if (!List.of("CANCELLED", "USER_PAUSED", "RUNNING").contains(target)) {
            throw validation("jobStatus must be CANCELLED, RUNNING, or USER_PAUSED.");
        }
        expirePausedJobs(accountId, region, state);
        ObjectNode job = state.getDocuments().get(JOB + "/" + jobId);
        if (job == null) {
            throw notFound("The classification job " + jobId + " does not exist.");
        }
        String current = job.path("jobStatus").asText();
        String key = runKey(accountId, region, jobId);
        switch (target) {
            case "CANCELLED" -> {
                requireTransition(current, target, List.of("IDLE", "PAUSED", "RUNNING", "USER_PAUSED"));
                JobRun run = runs.remove(key);
                if (run != null) {
                    run.stop = STOP_CANCELLED;
                }
                job.put("jobStatus", "CANCELLED");
                job.remove("userPausedDetails");
                job.withObject("/statistics").put("approximateNumberOfObjectsToProcess", 0);
            }
            case "USER_PAUSED" -> {
                requireTransition(current, target, List.of("IDLE", "PAUSED", "RUNNING"));
                JobRun run = runs.get(key);
                if (run != null) {
                    run.stop = STOP_PAUSED;
                }
                Instant now = Instant.now();
                job.put("jobStatus", "USER_PAUSED");
                job.set("userPausedDetails", object().put("jobPausedAt", now.toString())
                        .put("jobExpiresAt", now.plus(MacieClassificationJobs.PAUSE_EXPIRY).toString()));
            }
            default -> {
                requireTransition(current, target, List.of("USER_PAUSED"));
                job.remove("userPausedDetails");
                JobRun run = runs.get(key);
                if (run != null) {
                    run.stop = null;
                    job.put("jobStatus", "RUNNING");
                    states.putForAccount(accountId, region, state);
                    if (!run.active) {
                        run.active = true;
                        submit(run);
                    }
                    return;
                }
                if (MacieClassificationJobs.ONE_TIME.equals(job.path("jobType").asText())) {
                    // The paused run's progress was lost (Floci restarted): rerun, skipping objects already reported.
                    states.putForAccount(accountId, region, state);
                    startRun(accountId, region, jobId, null, true, false);
                    return;
                }
                job.put("jobStatus", "IDLE");
            }
        }
        states.putForAccount(accountId, region, state);
    }

    private static void requireTransition(String current, String target, List<String> allowed) {
        if (!allowed.contains(current)) {
            throw conflict("The job's status is " + current + ", so it can't be changed to " + target + ".");
        }
    }

    /** Starts a run under the service monitor: the job becomes RUNNING before the worker is queued. */
    private void startRun(String accountId, String region, String jobId, Instant changedAfter,
                          boolean skipReported, boolean newRun) {
        MacieState state = states.getForAccount(accountId, region).orElse(null);
        ObjectNode job = state == null ? null : state.getDocuments().get(JOB + "/" + jobId);
        if (job == null) {
            return;
        }
        job.put("jobStatus", "RUNNING");
        if (newRun) {
            job.put("lastRunTime", Instant.now().toString());
            job.set("lastRunErrorStatus", object().put("code", "NONE"));
            ObjectNode statistics = job.withObject("/statistics");
            statistics.put("numberOfRuns", statistics.path("numberOfRuns").asLong() + 1);
        }
        states.putForAccount(accountId, region, state);
        JobRun run = new JobRun(accountId, region, jobId, changedAfter, skipReported);
        run.active = true;
        runs.put(run.key(), run);
        submit(run);
    }

    private void submit(JobRun run) {
        try {
            jobExecutor.execute(() -> execute(run));
        } catch (RejectedExecutionException e) {
            run.errors = true;
            finishRun(run, true);
        }
    }

    private void stopRuns(Predicate<JobRun> selected) {
        runs.values().removeIf(run -> {
            if (selected.test(run)) {
                run.stop = STOP_CANCELLED;
                return true;
            }
            return false;
        });
    }

    /** Runs one job execution on a worker thread; state is only touched under the service monitor. */
    private void execute(JobRun run) {
        boolean failed = false;
        try {
            RunPlan plan;
            synchronized (this) {
                if (stopped(run)) {
                    return;
                }
                plan = plan(run);
                if (plan == null) {
                    run.active = false;
                    runs.remove(run.key(), run);
                    return;
                }
            }
            List<MacieClassificationJobs.Candidate> candidates = sample(plan.job(), candidates(run, plan.job()));
            synchronized (this) {
                if (stopped(run)) {
                    return;
                }
                long remaining = candidates.stream().filter(c -> !run.processed.contains(c.id())).count();
                updateJob(run, job -> job.withObject("/statistics")
                        .put("approximateNumberOfObjectsToProcess", remaining));
            }
            for (MacieClassificationJobs.Candidate candidate : candidates) {
                synchronized (this) {
                    if (stopped(run)) {
                        return;
                    }
                    if (run.processed.contains(candidate.id())) {
                        continue;
                    }
                }
                Outcome outcome = classify(run, plan, candidate);
                synchronized (this) {
                    if (STOP_CANCELLED.equals(run.stop)) {
                        run.active = false;
                        return;
                    }
                    commit(run, candidate, outcome);
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Macie classification job {0} failed", run.jobId);
            failed = true;
        }
        finishRun(run, failed);
    }

    /** True (and the worker exits) when the run was cancelled or paused. */
    private boolean stopped(JobRun run) {
        if (run.stop == null) {
            return false;
        }
        run.active = false;
        return true;
    }

    private synchronized void finishRun(JobRun run, boolean failed) {
        if (run.stop != null) {
            run.active = false;
            return;
        }
        run.active = false;
        runs.remove(run.key(), run);
        updateJob(run, job -> {
            if (!"RUNNING".equals(job.path("jobStatus").asText())) {
                return;
            }
            boolean scheduled = MacieClassificationJobs.SCHEDULED.equals(job.path("jobType").asText());
            job.put("jobStatus", scheduled ? "IDLE" : "COMPLETE");
            job.set("lastRunErrorStatus", object().put("code", failed || run.errors ? "ERROR" : "NONE"));
            job.withObject("/statistics").put("approximateNumberOfObjectsToProcess", 0);
        });
    }

    private void updateJob(JobRun run, java.util.function.Consumer<ObjectNode> change) {
        MacieState state = states.getForAccount(run.accountId, run.region).orElse(null);
        ObjectNode job = state == null || !state.isEnabled() ? null : state.getDocuments().get(JOB + "/" + run.jobId);
        if (job != null) {
            change.accept(job);
            states.putForAccount(run.accountId, run.region, state);
        }
    }

    private RunPlan plan(JobRun run) {
        MacieState state = states.getForAccount(run.accountId, run.region).orElse(null);
        ObjectNode job = state == null || !state.isEnabled() ? null : state.getDocuments().get(JOB + "/" + run.jobId);
        if (job == null) {
            return null;
        }
        String selector = job.path("managedDataIdentifierSelector").asText("RECOMMENDED");
        List<String> managedIds = strings(job.path("managedDataIdentifierIds"), "managedDataIdentifierIds");
        List<MacieDataClassifier.Rule> rules = new ArrayList<>();
        for (MacieDataClassifier.ManagedIdentifier identifier : MacieDataClassifier.MANAGED) {
            boolean selected = switch (selector) {
                case "ALL" -> true;
                case "INCLUDE" -> managedIds.contains(identifier.id());
                case "EXCLUDE" -> !managedIds.contains(identifier.id());
                case "NONE" -> false;
                default -> identifier.recommended();
            };
            if (selected) {
                rules.add(MacieDataClassifier.rule(identifier));
            }
        }
        Map<String, JsonNode> severityLevels = new HashMap<>();
        // Deleted custom identifiers are soft-deleted and still apply to jobs that reference them.
        for (String id : strings(job.path("customDataIdentifierIds"), "customDataIdentifierIds")) {
            ObjectNode identifier = state.getDocuments().get("custom-data-identifier/" + id);
            if (identifier == null) {
                continue;
            }
            rules.add(new MacieDataClassifier.Rule(id, identifier.path("name").asText(),
                    identifier.path("arn").asText(), null, Pattern.compile(identifier.path("regex").asText()),
                    strings(identifier.path("keywords"), "keywords"),
                    strings(identifier.path("ignoreWords"), "ignoreWords"),
                    identifier.path("maximumMatchDistance").asInt(50), value -> true));
            severityLevels.put(id, identifier.path("severityLevels"));
        }
        List<Pattern> allowList = new ArrayList<>();
        for (String id : strings(job.path("allowListIds"), "allowListIds")) {
            ObjectNode list = state.getDocuments().get("allow-list/" + id);
            if (list != null && list.path("criteria").has("regex")) {
                allowList.add(Pattern.compile(list.path("criteria").path("regex").asText()));
            }
        }
        return new RunPlan(job.deepCopy(), rules, allowList, severityLevels);
    }

    /** Lists the in-scope objects of every bucket the job selects, reading S3 as the bucket owner. */
    private List<MacieClassificationJobs.Candidate> candidates(JobRun run, ObjectNode job) {
        JsonNode definition = job.path("s3JobDefinition");
        List<MacieClassificationJobs.Target> targets = new ArrayList<>();
        if (definition.has("bucketDefinitions")) {
            for (JsonNode bucketDefinition : definition.path("bucketDefinitions")) {
                String owner = bucketDefinition.path("accountId").asText();
                List<Bucket> buckets = RequestScopes.callAs(owner, s3Service::listBuckets);
                for (String name : strings(bucketDefinition.path("buckets"), "buckets")) {
                    Bucket bucket = buckets.stream().filter(b -> name.equals(b.getName())).findFirst().orElse(null);
                    if (bucket == null || !run.region.equals(bucketRegion(bucket))) {
                        LOG.infov("Macie job {0}: bucket {1} does not exist in {2}", run.jobId, name, run.region);
                        run.errors = true;
                        continue;
                    }
                    targets.add(new MacieClassificationJobs.Target(owner, bucket));
                }
            }
        } else {
            JsonNode criteria = definition.path("bucketCriteria");
            for (Bucket bucket : RequestScopes.callAs(run.accountId, s3Service::listBuckets)) {
                if (run.region.equals(bucketRegion(bucket))
                        && MacieClassificationJobs.bucketSelected(criteria, run.accountId, bucket)) {
                    targets.add(new MacieClassificationJobs.Target(run.accountId, bucket));
                }
            }
        }
        JsonNode scoping = definition.get("scoping");
        boolean tagScoped = MacieClassificationJobs.scopingUsesTags(scoping);
        List<MacieClassificationJobs.Candidate> candidates = new ArrayList<>();
        for (MacieClassificationJobs.Target target : targets) {
            String name = target.bucket().getName();
            List<S3Object> objects;
            try {
                objects = RequestScopes.callAs(target.accountId(),
                        () -> s3Service.listObjects(name, null, null, Integer.MAX_VALUE));
            } catch (AwsException e) {
                LOG.infov("Macie job {0}: cannot list bucket {1}: {2}", run.jobId, name, e.getMessage());
                run.errors = true;
                continue;
            }
            for (S3Object object : objects) {
                if (object.isDeleteMarker() || object.getKey() == null || object.getKey().endsWith("/")) {
                    continue;
                }
                if (run.changedAfter != null && (object.getLastModified() == null
                        || !object.getLastModified().isAfter(run.changedAfter))) {
                    continue;
                }
                Map<String, String> tags = object.getTags();
                if (tagScoped && (tags == null || tags.isEmpty())) {
                    tags = RequestScopes.callAs(target.accountId(),
                            () -> s3Service.getObjectTagging(name, object.getKey()));
                }
                if (MacieClassificationJobs.objectInScope(scoping, object.getKey(), object.getSize(),
                        object.getLastModified(), tags)) {
                    candidates.add(new MacieClassificationJobs.Candidate(target, object.getKey(), object.getSize(),
                            object.getLastModified()));
                }
            }
        }
        candidates.sort(Comparator.comparing(MacieClassificationJobs.Candidate::id));
        return candidates;
    }

    private static String bucketRegion(Bucket bucket) {
        return bucket.getRegion() == null || bucket.getRegion().isBlank() ? "us-east-1" : bucket.getRegion();
    }

    /** Applies samplingPercentage: a random subset, stable for a run, of at most that share of objects. */
    private static List<MacieClassificationJobs.Candidate> sample(ObjectNode job,
                                                                  List<MacieClassificationJobs.Candidate> candidates) {
        int percentage = job.path("samplingPercentage").asInt(100);
        if (percentage >= 100) {
            return candidates;
        }
        List<MacieClassificationJobs.Candidate> shuffled = new ArrayList<>(candidates);
        long seed = (job.path("jobId").asText() + "/" + job.path("statistics").path("numberOfRuns").asLong())
                .hashCode();
        java.util.Collections.shuffle(shuffled, new java.util.Random(seed));
        List<MacieClassificationJobs.Candidate> selected =
                new ArrayList<>(shuffled.subList(0, (int) ((long) candidates.size() * percentage / 100)));
        selected.sort(Comparator.comparing(MacieClassificationJobs.Candidate::id));
        return selected;
    }

    private Outcome classify(JobRun run, RunPlan plan, MacieClassificationJobs.Candidate candidate) {
        String bucket = candidate.target().bucket().getName();
        if (candidate.size() > MacieClassificationJobs.MAX_OBJECT_BYTES) {
            LOG.infov("Macie job {0}: skipped {1}/{2}: object exceeds {3} bytes", run.jobId, bucket,
                    candidate.key(), MacieClassificationJobs.MAX_OBJECT_BYTES);
            return new Outcome(null, false);
        }
        S3Object object;
        try {
            object = RequestScopes.callAs(candidate.target().accountId(),
                    () -> s3Service.getObject(bucket, candidate.key()));
        } catch (AwsException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) {
                return new Outcome(null, false);
            }
            LOG.infov("Macie job {0}: cannot read {1}/{2}: {3}", run.jobId, bucket, candidate.key(), e.getMessage());
            return new Outcome(null, true);
        } catch (RuntimeException e) {
            LOG.infov("Macie job {0}: cannot read {1}/{2}: {3}", run.jobId, bucket, candidate.key(), e.getMessage());
            return new Outcome(null, true);
        }
        byte[] data = object.getData() == null ? new byte[0] : object.getData();
        String text = MacieClassificationJobs.decodeText(data);
        if (text == null) {
            LOG.debugv("Macie job {0}: skipped {1}/{2}: not a text object", run.jobId, bucket, candidate.key());
            return new Outcome(null, false);
        }
        MacieDataClassifier.Evaluation evaluation =
                MacieDataClassifier.evaluate(text, plan.rules(), plan.allowList());
        ObjectNode finding = MacieClassificationJobs.finding(UUID.randomUUID().toString().replace("-", ""),
                run.region, run.accountId, plan.job(), candidate, object, evaluation,
                plan.customSeverityLevels(), Instant.now());
        return new Outcome(finding, false);
    }

    private void commit(JobRun run, MacieClassificationJobs.Candidate candidate, Outcome outcome) {
        run.processed.add(candidate.id());
        if (outcome.error()) {
            run.errors = true;
        }
        MacieState state = states.getForAccount(run.accountId, run.region).orElse(null);
        ObjectNode job = state == null || !state.isEnabled() ? null : state.getDocuments().get(JOB + "/" + run.jobId);
        if (job == null) {
            return;
        }
        ObjectNode finding = outcome.finding();
        if (finding != null && !(run.skipReported && alreadyReported(state, run.jobId, finding))) {
            List<JsonNode> filters = state.getDocuments().entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("findings-filter/"))
                    .map(Map.Entry::getValue)
                    .filter(filter -> "ARCHIVE".equals(filter.path("action").asText()))
                    .map(JsonNode.class::cast)
                    .toList();
            finding.put("archived", filters.stream()
                    .anyMatch(filter -> matches(finding, filter.path("findingCriteria"))));
            state.getDocuments().put("finding/" + finding.path("id").asText(), finding);
        }
        ObjectNode statistics = job.withObject("/statistics");
        statistics.put("approximateNumberOfObjectsToProcess",
                Math.max(0, statistics.path("approximateNumberOfObjectsToProcess").asLong() - 1));
        states.putForAccount(run.accountId, run.region, state);
    }

    private static boolean alreadyReported(MacieState state, String jobId, ObjectNode finding) {
        JsonNode object = finding.path("resourcesAffected").path("s3Object");
        return state.getDocuments().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("finding/"))
                .map(Map.Entry::getValue)
                .anyMatch(existing -> jobId.equals(existing.path("classificationDetails").path("jobId").asText())
                        && object.path("path").equals(existing.path("resourcesAffected").path("s3Object").path("path"))
                        && object.path("eTag").equals(existing.path("resourcesAffected").path("s3Object").path("eTag")));
    }

    /** Paused jobs expire after 30 days: a one-time job is cancelled; a scheduled job's paused run is dropped. */
    private void expirePausedJobs(String accountId, String region, MacieState state) {
        Instant now = Instant.now();
        boolean changed = false;
        for (Map.Entry<String, ObjectNode> entry : new ArrayList<>(state.getDocuments().entrySet())) {
            ObjectNode job = entry.getValue();
            if (!entry.getKey().startsWith(JOB + "/") || !"USER_PAUSED".equals(job.path("jobStatus").asText())) {
                continue;
            }
            String expiresAt = job.path("userPausedDetails").path("jobExpiresAt").asText(null);
            if (expiresAt == null || Instant.parse(expiresAt).isAfter(now)) {
                continue;
            }
            JobRun run = runs.remove(runKey(accountId, region, job.path("jobId").asText()));
            if (run != null) {
                run.stop = STOP_CANCELLED;
            }
            if (MacieClassificationJobs.ONE_TIME.equals(job.path("jobType").asText())) {
                job.put("jobStatus", "CANCELLED");
                job.withObject("/statistics").put("approximateNumberOfObjectsToProcess", 0);
                changed = true;
            } else if (run != null) {
                job.withObject("/statistics").put("approximateNumberOfObjectsToProcess", 0);
                changed = true;
            }
        }
        if (changed) {
            states.putForAccount(accountId, region, state);
        }
    }

    private synchronized void ensureJobScheduler() {
        if (jobScheduler != null) {
            return;
        }
        jobScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("macie2-job-scheduler").factory());
        jobScheduler.scheduleWithFixedDelay(() -> {
            try {
                runDueJobs(Instant.now());
            } catch (RuntimeException e) {
                LOG.warnv(e, "Macie job scheduling failed");
            }
        }, SCHEDULER_PERIOD_SECONDS, SCHEDULER_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Starts scheduled runs whose start time has passed, and settles RUNNING jobs whose worker
     * no longer exists (Floci restarted mid-run with persistent storage).
     */
    synchronized void runDueJobs(Instant now) {
        for (AccountAwareStorageBackend.AccountEntry<MacieState> entry : states.scanAllAccountEntries(key -> true)) {
            String accountId = entry.accountId();
            String region = entry.key();
            MacieState state = entry.value();
            if (!state.isEnabled()) {
                continue;
            }
            expirePausedJobs(accountId, region, state);
            List<ObjectNode> jobs = state.getDocuments().entrySet().stream()
                    .filter(document -> document.getKey().startsWith(JOB + "/"))
                    .map(Map.Entry::getValue).toList();
            for (ObjectNode job : jobs) {
                String jobId = job.path("jobId").asText();
                String status = job.path("jobStatus").asText();
                boolean scheduled = MacieClassificationJobs.SCHEDULED.equals(job.path("jobType").asText());
                if ("RUNNING".equals(status) && !runs.containsKey(runKey(accountId, region, jobId))) {
                    if (scheduled) {
                        job.put("jobStatus", "IDLE");
                        job.set("lastRunErrorStatus", object().put("code", "ERROR"));
                        states.putForAccount(accountId, region, state);
                    } else {
                        startRun(accountId, region, jobId, null, true, false);
                    }
                    continue;
                }
                if (!scheduled || !"IDLE".equals(status)) {
                    continue;
                }
                Instant lastRun = Instant.parse(job.path("lastRunTime").asText());
                if (MacieClassificationJobs.nextRun(job.path("scheduleFrequency"), lastRun).isAfter(now)) {
                    continue;
                }
                // A scheduled run analyzes objects created or changed since the previous run started;
                // without an initial run, the first run covers objects changed since the job was created.
                startRun(accountId, region, jobId, lastRun, false, true);
            }
        }
    }

    public record Page<T>(List<T> items, String nextToken) {}

    @Override
    public synchronized void clear() {
        stopRuns(run -> true);
        states.clear();
        members.clear();
    }

    private static void requireAccountId(String accountId) {
        requireAccountId(accountId, "adminAccountId");
    }

    private static void requireAccountId(String accountId, String fieldName) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw validation(fieldName + " must be a 12 digit account ID.");
        }
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    /** AWS rejects every Macie operation with AccessDeniedException until Macie is enabled. */
    private static AwsException notEnabled() {
        return new AwsException("AccessDeniedException", "Macie is not enabled for this account.", 403);
    }

    private static AwsException accessDenied() {
        return new AwsException("AccessDeniedException",
                "Only the delegated Macie administrator account can manage organization configuration.", 403);
    }
}
