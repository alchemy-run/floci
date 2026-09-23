package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.macie2.model.MacieMember;
import io.github.hectorvent.floci.services.macie2.model.MacieState;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class MacieService implements Resettable {
    private final AccountAwareStorageBackend<MacieState> states;
    private final AccountAwareStorageBackend<MacieMember> members;

    private final S3Service s3Service;

    @Inject
    public MacieService(StorageFactory storageFactory, S3Service s3Service) {
        this(storageFactory.create("macie2", "macie2-state.json",
                        new TypeReference<Map<String, MacieState>>() {}),
                storageFactory.create("macie2", "macie2-members.json",
                        new TypeReference<Map<String, MacieMember>>() {}), s3Service);
    }

    MacieService(AccountAwareStorageBackend<MacieState> states,
                 AccountAwareStorageBackend<MacieMember> members) {
        this(states, members, null);
    }

    MacieService(AccountAwareStorageBackend<MacieState> states,
                 AccountAwareStorageBackend<MacieMember> members, S3Service s3Service) {
        this.states = states;
        this.members = members;
        this.s3Service = s3Service;
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

    private static void requireText(String value, String field, int minimumLength, int maximumLength) {
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

    public ObjectNode getResource(String region, String accountId, String kind, String id) {
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
        if (identity.length != 2 || !List.of("allow-list", "custom-data-identifier", "findings-filter")
                .contains(identity[0])) {
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
        // Catalog metadata only. Classification execution is rejected independently.
        List<ObjectNode> catalog = List.of(
                object().put("id", "EMAIL_ADDRESS").put("category", "PERSONAL"),
                object().put("id", "USA_SOCIAL_SECURITY_NUMBER").put("category", "PERSONAL"));
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

    private List<ObjectNode> documents(String region, String accountId, String kind) {
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

    private static String text(JsonNode request, String field, boolean required) {
        JsonNode value = request.get(field);
        if (value == null && !required) {
            return null;
        }
        if (value == null || !value.isTextual() || (required && value.textValue().isEmpty())) {
            throw validation(field + " must be a string" + (required ? " and cannot be empty." : "."));
        }
        return value.textValue();
    }

    private static List<String> strings(JsonNode node, String field) {
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

    private static Map<String, String> readTags(JsonNode node) {
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

    private static ObjectNode tagsNode(Map<String, String> tags) {
        ObjectNode node = object();
        tags.forEach(node::put);
        return node;
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public record Page<T>(List<T> items, String nextToken) {}

    @Override
    public void clear() {
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

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException notFound(String message) {
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
