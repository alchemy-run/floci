package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.macie2.model.ClassificationJob;
import io.github.hectorvent.floci.services.macie2.model.MacieAllowList;
import io.github.hectorvent.floci.services.macie2.model.MacieCustomDataIdentifier;
import io.github.hectorvent.floci.services.macie2.model.MacieFinding;
import io.github.hectorvent.floci.services.macie2.model.MacieFindingsFilter;
import io.github.hectorvent.floci.services.macie2.model.MacieSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Amazon Macie2 restJson1: account/region session enablement, classification
 * jobs, allow lists, custom data identifiers, and findings filters.
 *
 * <p>{@code GetMacieSession} rejects a disabled account with
 * {@code AccessDeniedException} ("Macie is not enabled"). Jobs survive
 * disable so {@code DescribeClassificationJob} still works. Custom data
 * identifiers are soft-deleted ({@code deleted: true}) until Macie is
 * disabled.
 */
@ApplicationScoped
public class Macie2Service implements Resettable, TagHandler {

    static final String SERVICE = "macie2";
    private static final String MACIE_NOT_ENABLED = "Macie is not enabled";
    private static final String ALREADY_ENABLED = "Macie is already enabled.";
    private static final String TERMINAL_JOB =
            "The request failed because the specified job is already in a terminal state.";
    private static final Set<String> FREQUENCIES = Set.of("FIFTEEN_MINUTES", "ONE_HOUR", "SIX_HOURS");
    private static final Set<String> STATUSES = Set.of("ENABLED", "PAUSED");
    private static final Set<String> JOB_TYPES = Set.of("ONE_TIME", "SCHEDULED");
    private static final Set<String> TERMINAL_JOB_STATUSES = Set.of("CANCELLED", "COMPLETE");
    private static final Set<String> FILTER_ACTIONS = Set.of("ARCHIVE", "NOOP");
    private static final List<String> DEFAULT_SAMPLE_TYPES = List.of(
            "SensitiveData:S3Object/Financial",
            "SensitiveData:S3Object/Personal",
            "SensitiveData:S3Object/Credentials",
            "Policy:IAMUser/S3BucketPublic",
            "Policy:IAMUser/S3BucketEncryptionDisabled");
    private static final List<ManagedIdentifier> MANAGED_IDENTIFIERS = List.of(
            new ManagedIdentifier("CREDIT_CARD_NUMBER", "FINANCIAL_INFORMATION"),
            new ManagedIdentifier("CREDIT_CARD_SECURITY_CODE", "FINANCIAL_INFORMATION"),
            new ManagedIdentifier("BANK_ACCOUNT_NUMBER", "FINANCIAL_INFORMATION"),
            new ManagedIdentifier("AWS_SECRET_KEY", "CREDENTIALS"),
            new ManagedIdentifier("OPENSSH_PRIVATE_KEY", "CREDENTIALS"),
            new ManagedIdentifier("PGP_PRIVATE_KEY", "CREDENTIALS"),
            new ManagedIdentifier("NAME", "PERSONAL_INFORMATION"),
            new ManagedIdentifier("EMAIL_ADDRESS", "PERSONAL_INFORMATION"),
            new ManagedIdentifier("PHONE_NUMBER", "PERSONAL_INFORMATION"),
            new ManagedIdentifier("USA_SOCIAL_SECURITY_NUMBER", "PERSONAL_INFORMATION"),
            new ManagedIdentifier("ADDRESS", "PERSONAL_INFORMATION"),
            new ManagedIdentifier("DATE_OF_BIRTH", "PERSONAL_INFORMATION"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend<String, MacieSession> sessions;
    private final StorageBackend<String, ClassificationJob> jobs;
    private final StorageBackend<String, MacieAllowList> allowLists;
    private final StorageBackend<String, MacieCustomDataIdentifier> identifiers;
    private final StorageBackend<String, MacieFindingsFilter> filters;
    private final RegionResolver regionResolver;

    @Inject
    public Macie2Service(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(
                        SERVICE,
                        "macie2-sessions.json",
                        new TypeReference<Map<String, MacieSession>>() {}),
                storageFactory.create(
                        SERVICE,
                        "macie2-jobs.json",
                        new TypeReference<Map<String, ClassificationJob>>() {}),
                storageFactory.create(
                        SERVICE,
                        "macie2-allow-lists.json",
                        new TypeReference<Map<String, MacieAllowList>>() {}),
                storageFactory.create(
                        SERVICE,
                        "macie2-custom-data-identifiers.json",
                        new TypeReference<Map<String, MacieCustomDataIdentifier>>() {}),
                storageFactory.create(
                        SERVICE,
                        "macie2-findings-filters.json",
                        new TypeReference<Map<String, MacieFindingsFilter>>() {}),
                regionResolver);
    }

    Macie2Service(
            StorageBackend<String, MacieSession> sessions,
            StorageBackend<String, ClassificationJob> jobs,
            RegionResolver regionResolver) {
        this(sessions, jobs, null, null, null, regionResolver);
    }

    Macie2Service(
            StorageBackend<String, MacieSession> sessions,
            StorageBackend<String, ClassificationJob> jobs,
            StorageBackend<String, MacieAllowList> allowLists,
            StorageBackend<String, MacieCustomDataIdentifier> identifiers,
            StorageBackend<String, MacieFindingsFilter> filters,
            RegionResolver regionResolver) {
        this.sessions = sessions;
        this.jobs = jobs;
        this.allowLists = allowLists;
        this.identifiers = identifiers;
        this.filters = filters;
        this.regionResolver = regionResolver;
    }

    public MacieSession getMacieSession(String region) {
        return requireSession(region);
    }

    public synchronized MacieSession enableMacie(String region, JsonNode request) {
        requireObject(request, "Request body");
        String account = regionResolver.getAccountId();
        if (sessions.get(sessionKey(account, region)).isPresent()) {
            throw conflict(ALREADY_ENABLED);
        }
        String status = textOrNull(request, "status");
        if (status == null) {
            status = "ENABLED";
        }
        requireStatus(status);
        String frequency = textOrNull(request, "findingPublishingFrequency");
        if (frequency == null) {
            frequency = "SIX_HOURS";
        }
        requireFrequency(frequency);
        String now = now();
        MacieSession session = new MacieSession();
        session.setStatus(status);
        session.setFindingPublishingFrequency(frequency);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setRegion(region);
        session.setAccountId(account);
        session.setServiceRole("arn:aws:iam::" + account
                + ":role/aws-service-role/macie.amazonaws.com/AWSServiceRoleForAmazonMacie");
        sessions.put(sessionKey(account, region), session);
        return session;
    }

    public synchronized void updateMacieSession(String region, JsonNode request) {
        requireObject(request, "Request body");
        MacieSession session = requireSession(region);
        String status = textOrNull(request, "status");
        if (status != null) {
            requireStatus(status);
            session.setStatus(status);
        }
        String frequency = textOrNull(request, "findingPublishingFrequency");
        if (frequency != null) {
            requireFrequency(frequency);
            session.setFindingPublishingFrequency(frequency);
        }
        session.setUpdatedAt(now());
        sessions.put(sessionKey(session.getAccountId(), region), session);
    }

    public synchronized void disableMacie(String region) {
        MacieSession session = requireSession(region);
        String account = session.getAccountId();
        sessions.delete(sessionKey(account, region));
        if (allowLists != null) {
            for (MacieAllowList allowList : new ArrayList<>(allowLists.values())) {
                if (ownedBy(allowList.getAccountId(), allowList.getRegion(), account, region)) {
                    allowLists.delete(allowList.getId());
                }
            }
        }
        if (identifiers != null) {
            for (MacieCustomDataIdentifier identifier : new ArrayList<>(identifiers.values())) {
                if (ownedBy(identifier.getAccountId(), identifier.getRegion(), account, region)) {
                    identifiers.delete(identifier.getId());
                }
            }
        }
        if (filters != null) {
            for (MacieFindingsFilter filter : new ArrayList<>(filters.values())) {
                if (ownedBy(filter.getAccountId(), filter.getRegion(), account, region)) {
                    filters.delete(filter.getId());
                }
            }
        }
    }

    public synchronized ClassificationJob createClassificationJob(String region, JsonNode request) {
        requireEnabled(region);
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        for (ClassificationJob existing : jobs.values()) {
            if (name.equals(existing.getName())) {
                throw conflict("A job with the specified name already exists.");
            }
        }
        String jobType = textOrNull(request, "jobType");
        if (jobType == null) {
            jobType = "ONE_TIME";
        }
        requireJobType(jobType);
        String jobId = UUID.randomUUID().toString();
        String account = regionResolver.getAccountId();
        ClassificationJob job = new ClassificationJob();
        job.setJobId(jobId);
        job.setJobArn(jobArn(region, account, jobId));
        job.setName(name);
        job.setJobType(jobType);
        job.setJobStatus("ONE_TIME".equals(jobType) ? "RUNNING" : "IDLE");
        job.setDescription(textOrNull(request, "description"));
        job.setSamplingPercentage(readSampling(request));
        if (request.hasNonNull("initialRun")) {
            job.setInitialRun(request.get("initialRun").asBoolean());
        }
        job.setManagedDataIdentifierSelector(textOrNull(request, "managedDataIdentifierSelector"));
        job.setCreatedAt(now());
        job.setS3JobDefinition(readObjectMap(request.get("s3JobDefinition")));
        job.setTags(readTags(request.get("tags")));
        jobs.put(jobId, job);
        return job;
    }

    public ClassificationJob describeClassificationJob(String jobId) {
        return requireJob(decode(jobId));
    }

    public synchronized ClassificationJob updateClassificationJob(String region, String jobId, JsonNode request) {
        requireEnabled(region);
        requireObject(request, "Request body");
        ClassificationJob job = requireJob(decode(jobId));
        String status = textOrNull(request, "jobStatus");
        if (status == null) {
            throw validation("jobStatus is a required parameter.");
        }
        if (TERMINAL_JOB_STATUSES.contains(job.getJobStatus())) {
            throw conflict(TERMINAL_JOB);
        }
        if (!"CANCELLED".equals(status) && !"USER_PAUSED".equals(status) && !"RUNNING".equals(status)) {
            throw validation("jobStatus is invalid.");
        }
        job.setJobStatus(status);
        jobs.put(job.getJobId(), job);
        return job;
    }

    public List<ClassificationJob> listClassificationJobs(String region) {
        requireEnabled(region);
        String account = regionResolver.getAccountId();
        List<ClassificationJob> matches = new ArrayList<>();
        for (ClassificationJob job : jobs.values()) {
            if (jobOwnedBy(job, account, region)) {
                matches.add(job);
            }
        }
        return matches;
    }

    public synchronized void createSampleFindings(String region, JsonNode request) {
        MacieSession session = requireEnabledSession(region);
        requireObject(request, "Request body");
        List<String> types = readStringList(request, "findingTypes", false);
        if (types.isEmpty()) {
            types = DEFAULT_SAMPLE_TYPES;
        }
        String account = regionResolver.getAccountId();
        String now = now();
        for (String type : types) {
            MacieFinding finding = sampleFinding(account, region, type, now);
            session.getFindings().put(finding.getId(), finding);
        }
        session.setUpdatedAt(now);
        sessions.put(sessionKey(account, region), session);
    }

    public List<String> listFindingIds(String region) {
        return new ArrayList<>(requireEnabledSession(region).getFindings().keySet());
    }

    public List<MacieFinding> getFindings(String region, JsonNode request) {
        MacieSession session = requireEnabledSession(region);
        requireObject(request, "Request body");
        List<String> ids = readStringList(request, "findingIds", true);
        List<MacieFinding> findings = new ArrayList<>();
        for (String id : ids) {
            MacieFinding finding = session.getFindings().get(id);
            if (finding != null) {
                findings.add(finding);
            }
        }
        return findings;
    }

    public Map<String, Long> findingStatistics(String region, JsonNode request) {
        MacieSession session = requireEnabledSession(region);
        requireObject(request, "Request body");
        String groupBy = textOrNull(request, "groupBy");
        if (groupBy == null) {
            throw validation("groupBy is a required parameter.");
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (MacieFinding finding : session.getFindings().values()) {
            counts.merge(groupKey(finding, groupBy), 1L, Long::sum);
        }
        return counts;
    }

    public int testCustomDataIdentifier(String region, JsonNode request) {
        requireEnabled(region);
        requireObject(request, "Request body");
        String regex = requireText(request, "regex");
        String sampleText = textOrNull(request, "sampleText");
        if (sampleText == null) {
            throw validation("sampleText is a required parameter.");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw validation("regex is not a valid regular expression.");
        }
        List<String> ignoreWords = readStringList(request, "ignoreWords", false);
        Matcher matcher = pattern.matcher(sampleText);
        int count = 0;
        while (matcher.find()) {
            String match = matcher.group();
            boolean ignored = false;
            for (String word : ignoreWords) {
                if (word.equalsIgnoreCase(match)) {
                    ignored = true;
                    break;
                }
            }
            if (!ignored) {
                count++;
            }
        }
        return count;
    }

    public List<ManagedIdentifier> listManagedDataIdentifiers(String region) {
        requireEnabled(region);
        return MANAGED_IDENTIFIERS;
    }

    public MacieSession requireEnabledSession(String region) {
        requireEnabled(region);
        return requireSession(region);
    }

    public List<MacieAllowList> listAllowLists(String region) {
        requireSession(region);
        requireResourceStores();
        String account = regionResolver.getAccountId();
        List<MacieAllowList> matches = new ArrayList<>();
        for (MacieAllowList allowList : allowLists.values()) {
            if (ownedBy(allowList.getAccountId(), allowList.getRegion(), account, region)) {
                matches.add(allowList);
            }
        }
        return matches;
    }

    public MacieAllowList getAllowList(String region, String id) {
        requireSession(region);
        return requireAllowList(region, id);
    }

    public synchronized MacieAllowList createAllowList(String region, JsonNode request) {
        requireSession(region);
        requireResourceStores();
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        if (findAllowListByName(region, name) != null) {
            throw conflict("An allow list with the specified name already exists.");
        }
        Map<String, Object> criteria = readObject(request.get("criteria"), "criteria", true);
        if (!criteria.containsKey("regex") && !criteria.containsKey("s3WordsList")) {
            throw validation("criteria must contain regex or s3WordsList.");
        }
        String account = regionResolver.getAccountId();
        String id = newId();
        String now = now();
        MacieAllowList allowList = new MacieAllowList();
        allowList.setId(id);
        allowList.setArn(resourceArn(region, account, "allow-list", id));
        allowList.setName(name);
        allowList.setDescription(textOrNull(request, "description"));
        allowList.setAccountId(account);
        allowList.setRegion(region);
        allowList.setCreatedAt(now);
        allowList.setUpdatedAt(now);
        allowList.setStatusCode("OK");
        allowList.setCriteria(criteria);
        allowList.setTags(readTags(request.get("tags")));
        allowLists.put(id, allowList);
        return allowList;
    }

    public synchronized MacieAllowList updateAllowList(String region, String id, JsonNode request) {
        requireSession(region);
        requireObject(request, "Request body");
        MacieAllowList allowList = requireAllowList(region, id);
        String name = requireText(request, "name");
        MacieAllowList existing = findAllowListByName(region, name);
        if (existing != null && !existing.getId().equals(id)) {
            throw conflict("An allow list with the specified name already exists.");
        }
        allowList.setName(name);
        if (request.has("description")) {
            allowList.setDescription(textOrNull(request, "description"));
        }
        if (request.has("criteria") && !request.get("criteria").isNull()) {
            allowList.setCriteria(readObject(request.get("criteria"), "criteria", true));
        }
        allowList.setUpdatedAt(now());
        allowLists.put(id, allowList);
        return allowList;
    }

    public synchronized void deleteAllowList(String region, String id) {
        requireSession(region);
        requireAllowList(region, id);
        allowLists.delete(id);
    }

    public List<MacieCustomDataIdentifier> listCustomDataIdentifiers(String region) {
        requireSession(region);
        requireResourceStores();
        String account = regionResolver.getAccountId();
        List<MacieCustomDataIdentifier> matches = new ArrayList<>();
        for (MacieCustomDataIdentifier identifier : identifiers.values()) {
            if (!identifier.isDeleted()
                    && ownedBy(identifier.getAccountId(), identifier.getRegion(), account, region)) {
                matches.add(identifier);
            }
        }
        return matches;
    }

    public MacieCustomDataIdentifier getCustomDataIdentifier(String region, String id) {
        requireSession(region);
        return requireIdentifier(region, id);
    }

    public synchronized MacieCustomDataIdentifier createCustomDataIdentifier(String region, JsonNode request) {
        requireSession(region);
        requireResourceStores();
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        if (findIdentifierByName(region, name) != null) {
            throw conflict("A custom data identifier with the specified name already exists.");
        }
        String regex = requireText(request, "regex");
        String account = regionResolver.getAccountId();
        String id = newId();
        MacieCustomDataIdentifier identifier = new MacieCustomDataIdentifier();
        identifier.setId(id);
        identifier.setArn(resourceArn(region, account, "custom-data-identifier", id));
        identifier.setName(name);
        identifier.setDescription(textOrNull(request, "description"));
        identifier.setRegex(regex);
        identifier.setAccountId(account);
        identifier.setRegion(region);
        identifier.setCreatedAt(now());
        identifier.setDeleted(false);
        if (request.hasNonNull("maximumMatchDistance")) {
            identifier.setMaximumMatchDistance(request.get("maximumMatchDistance").asInt());
        }
        identifier.setKeywords(readStringList(request.get("keywords")));
        identifier.setIgnoreWords(readStringList(request.get("ignoreWords")));
        identifier.setSeverityLevels(readObjectList(request.get("severityLevels")));
        identifier.setTags(readTags(request.get("tags")));
        identifiers.put(id, identifier);
        return identifier;
    }

    public synchronized void deleteCustomDataIdentifier(String region, String id) {
        requireSession(region);
        MacieCustomDataIdentifier identifier = requireIdentifier(region, id);
        identifier.setDeleted(true);
        identifiers.put(id, identifier);
    }

    public List<MacieFindingsFilter> listFindingsFilters(String region) {
        requireSession(region);
        requireResourceStores();
        String account = regionResolver.getAccountId();
        List<MacieFindingsFilter> matches = new ArrayList<>();
        for (MacieFindingsFilter filter : filters.values()) {
            if (ownedBy(filter.getAccountId(), filter.getRegion(), account, region)) {
                matches.add(filter);
            }
        }
        return matches;
    }

    public MacieFindingsFilter getFindingsFilter(String region, String id) {
        requireSession(region);
        return requireFilter(region, id);
    }

    public synchronized MacieFindingsFilter createFindingsFilter(String region, JsonNode request) {
        requireSession(region);
        requireResourceStores();
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        if (findFilterByName(region, name) != null) {
            throw conflict("A findings filter with the specified name already exists.");
        }
        String action = requireAction(textOrNull(request, "action"));
        Map<String, Object> criteria = readObject(request.get("findingCriteria"), "findingCriteria", true);
        String account = regionResolver.getAccountId();
        String id = newId();
        MacieFindingsFilter filter = new MacieFindingsFilter();
        filter.setId(id);
        filter.setArn(resourceArn(region, account, "findings-filter", id));
        filter.setName(name);
        filter.setDescription(textOrNull(request, "description"));
        filter.setAction(action);
        if (request.hasNonNull("position")) {
            filter.setPosition(request.get("position").asInt());
        }
        filter.setAccountId(account);
        filter.setRegion(region);
        filter.setFindingCriteria(criteria);
        filter.setTags(readTags(request.get("tags")));
        filters.put(id, filter);
        return filter;
    }

    public synchronized MacieFindingsFilter updateFindingsFilter(String region, String id, JsonNode request) {
        requireSession(region);
        requireObject(request, "Request body");
        MacieFindingsFilter filter = requireFilter(region, id);
        if (request.hasNonNull("name")) {
            String name = request.get("name").asText();
            MacieFindingsFilter existing = findFilterByName(region, name);
            if (existing != null && !existing.getId().equals(id)) {
                throw conflict("A findings filter with the specified name already exists.");
            }
            filter.setName(name);
        }
        if (request.hasNonNull("action")) {
            filter.setAction(requireAction(request.get("action").asText()));
        }
        if (request.has("description")) {
            filter.setDescription(textOrNull(request, "description"));
        }
        if (request.hasNonNull("position")) {
            filter.setPosition(request.get("position").asInt());
        }
        if (request.has("findingCriteria") && !request.get("findingCriteria").isNull()) {
            filter.setFindingCriteria(readObject(request.get("findingCriteria"), "findingCriteria", true));
        }
        filters.put(id, filter);
        return filter;
    }

    public synchronized void deleteFindingsFilter(String region, String id) {
        requireSession(region);
        requireFilter(region, id);
        filters.delete(id);
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        if (tags != null) {
            tagged.tags().putAll(tags);
        }
        persistTagged(tagged);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        if (tagKeys != null) {
            tagKeys.forEach(tagged.tags()::remove);
        }
        persistTagged(tagged);
    }

    @Override
    public void clear() {
        sessions.clear();
        jobs.clear();
        if (allowLists != null) {
            allowLists.clear();
        }
        if (identifiers != null) {
            identifiers.clear();
        }
        if (filters != null) {
            filters.clear();
        }
    }

    private MacieSession requireSession(String region) {
        return sessions.get(sessionKey(regionResolver.getAccountId(), region))
                .orElseThrow(Macie2Service::notEnabled);
    }

    private void requireEnabled(String region) {
        MacieSession session = requireSession(region);
        if (!"ENABLED".equals(session.getStatus())) {
            throw notEnabled();
        }
    }

    private void requireResourceStores() {
        if (allowLists == null || identifiers == null || filters == null) {
            throw new IllegalStateException("Macie2 resource stores are not configured");
        }
    }

    private ClassificationJob requireJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw validation("jobId is a required parameter.");
        }
        return jobs.get(jobId).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException",
                "The specified job was not found.",
                404));
    }

    private MacieAllowList requireAllowList(String region, String id) {
        requireResourceStores();
        MacieAllowList allowList = allowLists.get(id).orElse(null);
        if (allowList == null || !ownedBy(allowList.getAccountId(), allowList.getRegion(),
                regionResolver.getAccountId(), region)) {
            throw notFound();
        }
        return allowList;
    }

    private MacieCustomDataIdentifier requireIdentifier(String region, String id) {
        requireResourceStores();
        MacieCustomDataIdentifier identifier = identifiers.get(id).orElse(null);
        if (identifier == null || !ownedBy(identifier.getAccountId(), identifier.getRegion(),
                regionResolver.getAccountId(), region)) {
            throw notFound();
        }
        return identifier;
    }

    private MacieFindingsFilter requireFilter(String region, String id) {
        requireResourceStores();
        MacieFindingsFilter filter = filters.get(id).orElse(null);
        if (filter == null || !ownedBy(filter.getAccountId(), filter.getRegion(),
                regionResolver.getAccountId(), region)) {
            throw notFound();
        }
        return filter;
    }

    private MacieAllowList findAllowListByName(String region, String name) {
        String account = regionResolver.getAccountId();
        for (MacieAllowList allowList : allowLists.values()) {
            if (name.equals(allowList.getName())
                    && ownedBy(allowList.getAccountId(), allowList.getRegion(), account, region)) {
                return allowList;
            }
        }
        return null;
    }

    private MacieCustomDataIdentifier findIdentifierByName(String region, String name) {
        String account = regionResolver.getAccountId();
        for (MacieCustomDataIdentifier identifier : identifiers.values()) {
            if (!identifier.isDeleted()
                    && name.equals(identifier.getName())
                    && ownedBy(identifier.getAccountId(), identifier.getRegion(), account, region)) {
                return identifier;
            }
        }
        return null;
    }

    private MacieFindingsFilter findFilterByName(String region, String name) {
        String account = regionResolver.getAccountId();
        for (MacieFindingsFilter filter : filters.values()) {
            if (name.equals(filter.getName())
                    && ownedBy(filter.getAccountId(), filter.getRegion(), account, region)) {
                return filter;
            }
        }
        return null;
    }

    private Tagged requireTagged(String region, String arn) {
        if (arn == null || arn.isBlank()) {
            throw validation("resourceArn is a required parameter.");
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decode(arn));
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is invalid.");
        }
        if (!SERVICE.equals(parsed.service())) {
            throw notFound();
        }
        String resource = parsed.resource();
        if (resource.startsWith("classification-job/")) {
            ClassificationJob job = requireJob(resource.substring("classification-job/".length()));
            return new Tagged(job, null, null, null, job.getTags());
        }
        requireSession(region);
        if (resource.startsWith("allow-list/")) {
            MacieAllowList allowList = requireAllowList(region, resource.substring("allow-list/".length()));
            return new Tagged(null, allowList, null, null, allowList.getTags());
        }
        if (resource.startsWith("custom-data-identifier/")) {
            MacieCustomDataIdentifier identifier =
                    requireIdentifier(region, resource.substring("custom-data-identifier/".length()));
            return new Tagged(null, null, identifier, null, identifier.getTags());
        }
        if (resource.startsWith("findings-filter/")) {
            MacieFindingsFilter filter =
                    requireFilter(region, resource.substring("findings-filter/".length()));
            return new Tagged(null, null, null, filter, filter.getTags());
        }
        throw notFound();
    }

    private void persistTagged(Tagged tagged) {
        if (tagged.job() != null) {
            jobs.put(tagged.job().getJobId(), tagged.job());
        } else if (tagged.allowList() != null) {
            allowLists.put(tagged.allowList().getId(), tagged.allowList());
        } else if (tagged.identifier() != null) {
            identifiers.put(tagged.identifier().getId(), tagged.identifier());
        } else if (tagged.filter() != null) {
            filters.put(tagged.filter().getId(), tagged.filter());
        }
    }

    private static String jobArn(String region, String accountId, String jobId) {
        return AwsArnUtils.Arn.of(SERVICE, region, accountId, "classification-job/" + jobId).toString();
    }

    private static String resourceArn(String region, String account, String kind, String id) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, kind + "/" + id).toString();
    }

    private static String sessionKey(String accountId, String region) {
        return accountId + ":" + region;
    }

    private static boolean ownedBy(String accountId, String resourceRegion, String account, String region) {
        return account.equals(accountId) && region.equals(resourceRegion);
    }

    private static int readSampling(JsonNode request) {
        if (request == null || !request.hasNonNull("samplingPercentage")) {
            return 100;
        }
        JsonNode node = request.get("samplingPercentage");
        if (!node.isNumber()) {
            throw validation("samplingPercentage must be a number.");
        }
        int value = node.asInt();
        if (value < 0 || value > 100) {
            throw validation("samplingPercentage must be between 0 and 100.");
        }
        return value;
    }

    private static Map<String, Object> readObjectMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!node.isObject()) {
            throw validation("s3JobDefinition must be an object.");
        }
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private static Map<String, Object> readObject(JsonNode node, String field, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw validation(field + " is a required parameter.");
            }
            return new LinkedHashMap<>();
        }
        if (!node.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
        return MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull() || !node.isArray()) {
            return values;
        }
        for (JsonNode value : node) {
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private static List<Map<String, Object>> readObjectList(JsonNode node) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (node == null || node.isNull() || !node.isArray()) {
            return values;
        }
        for (JsonNode value : node) {
            if (value != null && value.isObject()) {
                values.add(MAPPER.convertValue(value, new TypeReference<Map<String, Object>>() {}));
            }
        }
        return values;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be a map.");
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

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw validation(field + " is a required parameter.");
        }
        return value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireStatus(String status) {
        if (!STATUSES.contains(status)) {
            throw validation("status is invalid.");
        }
    }

    private static void requireFrequency(String frequency) {
        if (!FREQUENCIES.contains(frequency)) {
            throw validation("findingPublishingFrequency is invalid.");
        }
    }

    private static void requireJobType(String jobType) {
        if (!JOB_TYPES.contains(jobType)) {
            throw validation("jobType is invalid.");
        }
    }

    private static String requireAction(String action) {
        if (action == null) {
            throw validation("action is a required parameter.");
        }
        if (!FILTER_ACTIONS.contains(action)) {
            throw validation("action must be ARCHIVE or NOOP.");
        }
        return action;
    }

    static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static boolean jobOwnedBy(ClassificationJob job, String account, String region) {
        String arn = job.getJobArn();
        if (arn == null || arn.isBlank()) {
            return false;
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            return account.equals(parsed.accountId()) && region.equals(parsed.region());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    static AwsException notEnabled() {
        return new AwsException("AccessDeniedException", MACIE_NOT_ENABLED, 403);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound() {
        return new AwsException("ResourceNotFoundException", "The specified resource does not exist.", 404);
    }

    static AwsException accessDenied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    private static String groupKey(MacieFinding finding, String groupBy) {
        if ("severity.description".equals(groupBy)) {
            return finding.getSeverityDescription() == null ? "Unknown" : finding.getSeverityDescription();
        }
        return finding.getType() == null ? "Unknown" : finding.getType();
    }

    private static MacieFinding sampleFinding(String account, String region, String type, String now) {
        boolean policy = type.startsWith("Policy:");
        MacieFinding finding = new MacieFinding();
        finding.setId(UUID.randomUUID().toString());
        finding.setType(type);
        finding.setSample(true);
        finding.setSeverityDescription(policy ? "Medium" : "High");
        finding.setSeverityScore(policy ? 5 : 8);
        finding.setAccountId(account);
        finding.setRegion(region);
        finding.setCreatedAt(now);
        finding.setUpdatedAt(now);
        finding.setArchived(false);
        finding.setCategory(policy ? "POLICY" : "CLASSIFICATION");
        finding.setTitle("Sample: " + type);
        finding.setDescription("Sample Macie finding generated by CreateSampleFindings.");
        return finding;
    }

    private static List<String> readStringList(JsonNode request, String field, boolean required) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            if (required) {
                throw validation(field + " is a required parameter.");
            }
            return List.of();
        }
        JsonNode node = request.get(field);
        if (!node.isArray()) {
            throw validation(field + " must be an array.");
        }
        if (required && node.isEmpty()) {
            throw validation(field + " must contain at least one value.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw validation(field + " members must be strings.");
            }
            values.add(value.asText());
        }
        return values;
    }

    record ManagedIdentifier(String id, String category) {
    }

    private record Tagged(
            ClassificationJob job,
            MacieAllowList allowList,
            MacieCustomDataIdentifier identifier,
            MacieFindingsFilter filter,
            Map<String, String> tags) {}
}
