package io.github.hectorvent.floci.services.backupsearch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backupsearch.model.ExportJob;
import io.github.hectorvent.floci.services.backupsearch.model.SearchJob;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Backup Search restJson1 — search jobs and result-export jobs.
 *
 * <p>There is no backup-index data plane in the emulator, so a started search
 * job completes immediately with empty results (the same lifecycle AWS uses
 * when no recovery points have an {@code ACTIVE} index). Jobs cannot be
 * deleted; {@code StopSearchJob} only succeeds while {@code RUNNING}.
 */
@ApplicationScoped
public class BackupSearchService implements TagHandler {

    static final String SERVICE = "backup-search";
    static final String SEARCH_JOB_RESOURCE = "SEARCH_JOB";
    static final String EXPORT_JOB_RESOURCE = "EXPORT_JOB";
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final int MAX_RESULTS = 100;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> RESOURCE_TYPES = Set.of("S3", "EBS");
    private static final Set<String> SEARCH_STATES = Set.of(
            "RUNNING", "COMPLETED", "STOPPING", "STOPPED", "FAILED");
    private static final Set<String> EXPORT_STATES = Set.of("RUNNING", "FAILED", "COMPLETED");
    private static final String NO_INDEX_MESSAGE =
            "No recovery points with an ACTIVE backup index matched the search scope.";

    private final StorageBackend<String, SearchJob> searchJobs;
    private final StorageBackend<String, ExportJob> exportJobs;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BackupSearchService(StorageFactory storageFactory, RegionResolver regionResolver,
                               ObjectMapper objectMapper) {
        this(storageFactory.create("backupsearch", "backupsearch-search-jobs.json",
                        new TypeReference<Map<String, SearchJob>>() {
                        }),
                storageFactory.create("backupsearch", "backupsearch-export-jobs.json",
                        new TypeReference<Map<String, ExportJob>>() {
                        }),
                regionResolver, objectMapper);
    }

    BackupSearchService(StorageBackend<String, SearchJob> searchJobs,
                        StorageBackend<String, ExportJob> exportJobs,
                        RegionResolver regionResolver,
                        ObjectMapper objectMapper) {
        this.searchJobs = searchJobs;
        this.exportJobs = exportJobs;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    public synchronized SearchJob startSearchJob(String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode scope = request.get("SearchScope");
        if (scope == null || !scope.isObject()) {
            throw new AwsException("ValidationException", "SearchScope is a required parameter.", 400);
        }
        JsonNode types = scope.get("BackupResourceTypes");
        if (types == null || !types.isArray() || types.isEmpty()) {
            throw new AwsException("ValidationException",
                    "SearchScope.BackupResourceTypes is a required parameter.", 400);
        }
        for (JsonNode type : types) {
            if (!type.isTextual() || !RESOURCE_TYPES.contains(type.asText())) {
                throw new AwsException("ValidationException",
                        "BackupResourceTypes must contain S3 and/or EBS.", 400);
            }
        }

        String clientToken = textOrNull(request, "ClientToken");
        if (clientToken != null && !clientToken.isBlank()) {
            Optional<SearchJob> existing = findByClientToken(region, clientToken);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        long now = Instant.now().getEpochSecond();
        String identifier = UUID.randomUUID().toString();
        SearchJob job = new SearchJob();
        job.setSearchJobIdentifier(identifier);
        job.setSearchJobArn(regionResolver.buildArn(SERVICE, region, "search-job/" + identifier));
        job.setName(textOrNull(request, "Name"));
        job.setEncryptionKeyArn(textOrNull(request, "EncryptionKeyArn"));
        job.setStatus("COMPLETED");
        job.setStatusMessage(NO_INDEX_MESSAGE);
        job.setCreationTime(now);
        job.setCompletionTime(now);
        job.setSearchScope(scope.deepCopy());
        JsonNode filters = request.get("ItemFilters");
        job.setItemFilters(filters != null && filters.isObject() ? filters.deepCopy() : objectMapper.createObjectNode());
        job.setTags(readStringMap(request, "Tags"));
        job.setClientToken(clientToken);
        job.setRegion(region);
        searchJobs.put(storageKey(region, identifier), job);
        return job;
    }

    public synchronized SearchJob getSearchJob(String region, String identifier) {
        return requireSearchJob(region, identifier);
    }

    public synchronized void stopSearchJob(String region, String identifier) {
        SearchJob job = requireSearchJob(region, identifier);
        if (!"RUNNING".equals(job.getStatus())) {
            throw new AwsException("ConflictException",
                    "Search job " + identifier + " is not in the RUNNING state.", 409,
                    Map.of("resourceId", identifier, "resourceType", SEARCH_JOB_RESOURCE));
        }
        long now = Instant.now().getEpochSecond();
        job.setStatus("STOPPED");
        job.setCompletionTime(now);
        job.setStatusMessage("Search job stopped by user.");
        searchJobs.put(storageKey(region, identifier), job);
    }

    public synchronized Page<SearchJob> listSearchJobs(String region, String byStatus,
                                                       String nextToken, Integer maxResults) {
        if (byStatus != null && !byStatus.isBlank() && !SEARCH_STATES.contains(byStatus)) {
            throw new AwsException("ValidationException",
                    "Status must be one of RUNNING, COMPLETED, STOPPING, STOPPED, FAILED.", 400);
        }
        List<SearchJob> jobs = new ArrayList<>();
        for (SearchJob job : searchJobs.values()) {
            if (!region.equals(job.getRegion())) {
                continue;
            }
            if (byStatus != null && !byStatus.isBlank() && !byStatus.equals(job.getStatus())) {
                continue;
            }
            jobs.add(job);
        }
        jobs.sort(Comparator.comparing(SearchJob::getCreationTime).reversed()
                .thenComparing(SearchJob::getSearchJobIdentifier));
        return page(jobs, nextToken, maxResults, SearchJob::getSearchJobIdentifier);
    }

    public synchronized SearchJob requireSearchJob(String region, String identifier) {
        requireUuid(identifier, "searchJobIdentifier");
        return searchJobs.get(storageKey(region, identifier))
                .orElseThrow(() -> notFound(SEARCH_JOB_RESOURCE, identifier, "Search job not found: " + identifier));
    }

    public synchronized ExportJob startExportJob(String region, JsonNode request) {
        requireObject(request, "Request body");
        String searchJobIdentifier = textOrNull(request, "SearchJobIdentifier");
        if (searchJobIdentifier == null || searchJobIdentifier.isBlank()) {
            throw new AwsException("ValidationException", "SearchJobIdentifier is a required parameter.", 400);
        }
        SearchJob searchJob = requireSearchJob(region, searchJobIdentifier);
        JsonNode spec = request.get("ExportSpecification");
        if (spec == null || !spec.isObject()) {
            throw new AwsException("ValidationException", "ExportSpecification is a required parameter.", 400);
        }
        JsonNode s3 = spec.has("s3ExportSpecification")
                ? spec.get("s3ExportSpecification")
                : spec.get("S3ExportSpecification");
        if (s3 == null || !s3.isObject() || textOrNull(s3, "DestinationBucket") == null) {
            throw new AwsException("ValidationException",
                    "ExportSpecification.s3ExportSpecification.DestinationBucket is required.", 400);
        }

        String clientToken = textOrNull(request, "ClientToken");
        if (clientToken != null && !clientToken.isBlank()) {
            Optional<ExportJob> existing = findExportByClientToken(region, clientToken);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        long now = Instant.now().getEpochSecond();
        String identifier = UUID.randomUUID().toString();
        ExportJob job = new ExportJob();
        job.setExportJobIdentifier(identifier);
        job.setExportJobArn(regionResolver.buildArn(SERVICE, region, "search-export-job/" + identifier));
        job.setSearchJobArn(searchJob.getSearchJobArn());
        job.setSearchJobIdentifier(searchJobIdentifier);
        job.setStatus("COMPLETED");
        job.setStatusMessage("Export completed with no matching search results.");
        job.setCreationTime(now);
        job.setCompletionTime(now);
        job.setExportSpecification(spec.deepCopy());
        job.setRoleArn(textOrNull(request, "RoleArn"));
        job.setTags(readStringMap(request, "Tags"));
        job.setClientToken(clientToken);
        job.setRegion(region);
        exportJobs.put(storageKey(region, identifier), job);
        return job;
    }

    public synchronized ExportJob getExportJob(String region, String identifier) {
        requireUuid(identifier, "exportJobIdentifier");
        return exportJobs.get(storageKey(region, identifier))
                .orElseThrow(() -> notFound(EXPORT_JOB_RESOURCE, identifier, "Export job not found: " + identifier));
    }

    public synchronized Page<ExportJob> listExportJobs(String region, String status, String searchJobIdentifier,
                                                       String nextToken, Integer maxResults) {
        if (status != null && !status.isBlank() && !EXPORT_STATES.contains(status)) {
            throw new AwsException("ValidationException",
                    "Status must be one of RUNNING, FAILED, COMPLETED.", 400);
        }
        if (searchJobIdentifier != null && !searchJobIdentifier.isBlank()) {
            requireUuid(searchJobIdentifier, "searchJobIdentifier");
        }
        List<ExportJob> jobs = new ArrayList<>();
        for (ExportJob job : exportJobs.values()) {
            if (!region.equals(job.getRegion())) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(job.getStatus())) {
                continue;
            }
            if (searchJobIdentifier != null && !searchJobIdentifier.isBlank()
                    && !searchJobIdentifier.equals(job.getSearchJobIdentifier())) {
                continue;
            }
            jobs.add(job);
        }
        jobs.sort(Comparator.comparing(ExportJob::getCreationTime).reversed()
                .thenComparing(ExportJob::getExportJobIdentifier));
        return page(jobs, nextToken, maxResults, ExportJob::getExportJobIdentifier);
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return new LinkedHashMap<>(tagsForArn(arn).orElseThrow(() ->
                notFound("Resource", arn, "Resource not found: " + arn)));
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            requireTagged(arn);
            return;
        }
        Optional<SearchJob> search = findSearchByArn(arn);
        if (search.isPresent()) {
            SearchJob job = search.get();
            job.getTags().putAll(tags);
            searchJobs.put(storageKey(job.getRegion(), job.getSearchJobIdentifier()), job);
            return;
        }
        Optional<ExportJob> exportJob = findExportByArn(arn);
        if (exportJob.isPresent()) {
            ExportJob job = exportJob.get();
            job.getTags().putAll(tags);
            exportJobs.put(storageKey(job.getRegion(), job.getExportJobIdentifier()), job);
            return;
        }
        throw notFound("Resource", arn, "Resource not found: " + arn);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        Optional<SearchJob> search = findSearchByArn(arn);
        if (search.isPresent()) {
            SearchJob job = search.get();
            if (tagKeys != null) {
                tagKeys.forEach(job.getTags()::remove);
            }
            searchJobs.put(storageKey(job.getRegion(), job.getSearchJobIdentifier()), job);
            return;
        }
        Optional<ExportJob> exportJob = findExportByArn(arn);
        if (exportJob.isPresent()) {
            ExportJob job = exportJob.get();
            if (tagKeys != null) {
                tagKeys.forEach(job.getTags()::remove);
            }
            exportJobs.put(storageKey(job.getRegion(), job.getExportJobIdentifier()), job);
            return;
        }
        throw notFound("Resource", arn, "Resource not found: " + arn);
    }

    ObjectNode toGetSearchJob(SearchJob job) {
        ObjectNode out = objectMapper.createObjectNode();
        if (job.getName() != null) {
            out.put("Name", job.getName());
        }
        ObjectNode summary = out.putObject("SearchScopeSummary");
        summary.put("TotalRecoveryPointsToScanCount", 0);
        summary.put("TotalItemsToScanCount", 0);
        ObjectNode progress = out.putObject("CurrentSearchProgress");
        progress.put("RecoveryPointsScannedCount", 0);
        progress.put("ItemsScannedCount", 0);
        progress.put("ItemsMatchedCount", 0);
        if (job.getStatusMessage() != null) {
            out.put("StatusMessage", job.getStatusMessage());
        }
        if (job.getEncryptionKeyArn() != null) {
            out.put("EncryptionKeyArn", job.getEncryptionKeyArn());
        }
        if (job.getCompletionTime() != null) {
            out.put("CompletionTime", job.getCompletionTime());
        }
        out.put("Status", job.getStatus());
        out.set("SearchScope", job.getSearchScope() == null
                ? objectMapper.createObjectNode() : job.getSearchScope());
        out.set("ItemFilters", job.getItemFilters() == null
                ? objectMapper.createObjectNode() : job.getItemFilters());
        out.put("CreationTime", job.getCreationTime());
        out.put("SearchJobIdentifier", job.getSearchJobIdentifier());
        out.put("SearchJobArn", job.getSearchJobArn());
        return out;
    }

    ObjectNode toSearchJobSummary(SearchJob job) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("SearchJobIdentifier", job.getSearchJobIdentifier());
        out.put("SearchJobArn", job.getSearchJobArn());
        if (job.getName() != null) {
            out.put("Name", job.getName());
        }
        out.put("Status", job.getStatus());
        out.put("CreationTime", job.getCreationTime());
        if (job.getCompletionTime() != null) {
            out.put("CompletionTime", job.getCompletionTime());
        }
        ObjectNode summary = out.putObject("SearchScopeSummary");
        summary.put("TotalRecoveryPointsToScanCount", 0);
        summary.put("TotalItemsToScanCount", 0);
        if (job.getStatusMessage() != null) {
            out.put("StatusMessage", job.getStatusMessage());
        }
        return out;
    }

    ObjectNode toStartSearchJob(SearchJob job) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("SearchJobArn", job.getSearchJobArn());
        out.put("CreationTime", job.getCreationTime());
        out.put("SearchJobIdentifier", job.getSearchJobIdentifier());
        return out;
    }

    ObjectNode toGetExportJob(ExportJob job) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ExportJobIdentifier", job.getExportJobIdentifier());
        out.put("ExportJobArn", job.getExportJobArn());
        out.put("Status", job.getStatus());
        out.put("CreationTime", job.getCreationTime());
        if (job.getCompletionTime() != null) {
            out.put("CompletionTime", job.getCompletionTime());
        }
        if (job.getStatusMessage() != null) {
            out.put("StatusMessage", job.getStatusMessage());
        }
        if (job.getExportSpecification() != null) {
            out.set("ExportSpecification", job.getExportSpecification());
        }
        if (job.getSearchJobArn() != null) {
            out.put("SearchJobArn", job.getSearchJobArn());
        }
        return out;
    }

    ObjectNode toExportJobSummary(ExportJob job) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("ExportJobIdentifier", job.getExportJobIdentifier());
        out.put("ExportJobArn", job.getExportJobArn());
        out.put("Status", job.getStatus());
        out.put("CreationTime", job.getCreationTime());
        if (job.getCompletionTime() != null) {
            out.put("CompletionTime", job.getCompletionTime());
        }
        if (job.getStatusMessage() != null) {
            out.put("StatusMessage", job.getStatusMessage());
        }
        if (job.getSearchJobArn() != null) {
            out.put("SearchJobArn", job.getSearchJobArn());
        }
        return out;
    }

    record Page<T>(List<T> items, String nextToken) {
    }

    private Optional<SearchJob> findByClientToken(String region, String clientToken) {
        return searchJobs.values().stream()
                .filter(job -> region.equals(job.getRegion()) && clientToken.equals(job.getClientToken()))
                .findFirst();
    }

    private Optional<ExportJob> findExportByClientToken(String region, String clientToken) {
        return exportJobs.values().stream()
                .filter(job -> region.equals(job.getRegion()) && clientToken.equals(job.getClientToken()))
                .findFirst();
    }

    private Optional<SearchJob> findSearchByArn(String arn) {
        return searchJobs.values().stream()
                .filter(job -> arn.equals(job.getSearchJobArn()))
                .findFirst();
    }

    private Optional<ExportJob> findExportByArn(String arn) {
        return exportJobs.values().stream()
                .filter(job -> arn.equals(job.getExportJobArn()))
                .findFirst();
    }

    private Optional<Map<String, String>> tagsForArn(String arn) {
        Optional<SearchJob> search = findSearchByArn(arn);
        if (search.isPresent()) {
            return Optional.of(search.get().getTags());
        }
        return findExportByArn(arn).map(ExportJob::getTags);
    }

    private void requireTagged(String arn) {
        tagsForArn(arn).orElseThrow(() -> notFound("Resource", arn, "Resource not found: " + arn));
    }

    private static String storageKey(String region, String identifier) {
        return region + ":" + identifier;
    }

    static void requireUuid(String value, String field) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + field
                            + "' failed to satisfy constraint: Member must be a valid UUID.", 400);
        }
    }

    private static void requireObject(JsonNode request, String name) {
        if (request == null || !request.isObject()) {
            throw new AwsException("ValidationException", name + " must be a JSON object.", 400);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static Map<String, String> readStringMap(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> tags = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue() != null && entry.getValue().isTextual()) {
                tags.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return tags;
    }

    private static AwsException notFound(String resourceType, String resourceId, String message) {
        return new AwsException("ResourceNotFoundException", message, 404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static <T> Page<T> page(List<T> items, String nextToken, Integer maxResults,
                                    java.util.function.Function<T, String> id) {
        int limit = maxResults == null ? DEFAULT_MAX_RESULTS : maxResults;
        if (limit < 1 || limit > MAX_RESULTS) {
            throw new AwsException("ValidationException",
                    "MaxResults must be between 1 and " + MAX_RESULTS + ".", 400);
        }
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            String decoded = decodeToken(nextToken);
            boolean found = false;
            for (int i = 0; i < items.size(); i++) {
                if (decoded.equals(id.apply(items.get(i)))) {
                    start = i + 1;
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new AwsException("ValidationException", "Invalid NextToken.", 400);
            }
        }
        int end = Math.min(items.size(), start + limit);
        String token = end < items.size() ? encodeToken(id.apply(items.get(end - 1))) : null;
        return new Page<>(items.subList(start, end), token);
    }

    private static String encodeToken(String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("backupsearch:v1:" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith("backupsearch:v1:")) {
                throw new IllegalArgumentException("bad prefix");
            }
            return decoded.substring("backupsearch:v1:".length());
        } catch (RuntimeException e) {
            throw new AwsException("ValidationException", "Invalid NextToken.", 400);
        }
    }

}
