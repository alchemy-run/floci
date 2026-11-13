package io.floci.aws.services.location;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Location's {@code ValidateAddress} batch jobs. A job reads the Parquet input through Floci's
 * DuckDB sidecar, looks each row's address up in {@link LocationGazetteer}, and writes the rows
 * back as Parquet under {@code <OutputOptions.Location>/<JobId>/} with Floci's validation columns
 * added: {@code Validated}, {@code FormattedAddress}, {@code Longitude} and {@code Latitude}. The
 * job is {@code Pending}, then {@code Running} while that runs in the background, then
 * {@code Completed}, or {@code Failed} with an {@code Error}. Cancelling a job that hasn't finished
 * leaves it {@code Cancelled}.
 */
@ApplicationScoped
public class LocationJobs implements Resettable {
    private static final Logger LOG = Logger.getLogger(LocationJobs.class);
    private static final List<String> STREET = List.of("addressline1", "address", "street", "addressline");
    private static final List<String> CITY = List.of("city", "locality", "municipality", "town");
    private static final List<String> REGION = List.of("region", "state", "province");
    private static final List<String> POSTAL = List.of("postalcode", "zip", "zipcode", "postcode");
    private static final List<String> COUNTRY = List.of("country", "countrycode");

    private final AccountAwareStorageBackend<ObjectNode> jobs;
    private final RegionResolver regionResolver;
    private final Instance<IamService> iam;
    private final Instance<FlociDuckClient> duck;
    private final Instance<S3Service> s3;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "location-jobs");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public LocationJobs(StorageFactory storageFactory, RegionResolver regionResolver, Instance<IamService> iam,
                        Instance<FlociDuckClient> duck, Instance<S3Service> s3, ObjectMapper mapper) {
        this.jobs = storageFactory.create("location", "location-jobs.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
        this.iam = iam;
        this.duck = duck;
        this.s3 = s3;
        this.mapper = mapper;
    }

    public synchronized ObjectNode start(JsonNode request, String region) {
        if (!"ValidateAddress".equals(request.path("Action").asText(null))) {
            throw LocationService.validation("Action must be ValidateAddress.");
        }
        String role = request.path("ExecutionRoleArn").asText(null);
        if (!roleExists(role)) {
            throw new AwsException("AccessDeniedException", "Amazon Location can't assume the execution role " + role
                    + ".", 403);
        }
        String input = s3Uri(request.path("InputOptions"), "InputOptions");
        String output = s3Uri(request.path("OutputOptions"), "OutputOptions");
        String token = request.path("ClientToken").asText(null);
        if (token != null) {
            for (ObjectNode existing : jobs.scan(k -> k.startsWith(region + "::"))) {
                if (token.equals(existing.path("clientToken").asText(null))) {
                    return summary(existing);
                }
            }
        }
        String jobId = UUID.randomUUID().toString();
        String now = LocationService.now();
        ObjectNode job = mapper.createObjectNode();
        job.put("Action", "ValidateAddress");
        if (request.has("ActionOptions")) {
            job.set("ActionOptions", request.get("ActionOptions").deepCopy());
        }
        job.put("CreatedAt", now);
        job.put("ExecutionRoleArn", role);
        job.set("InputOptions", request.get("InputOptions").deepCopy());
        job.put("JobArn", regionResolver.buildArn("geo", region, "job/" + jobId));
        job.put("JobId", jobId);
        if (request.hasNonNull("Name")) {
            job.put("Name", request.get("Name").asText());
        }
        job.set("OutputOptions", request.get("OutputOptions").deepCopy());
        job.put("Status", "Pending");
        job.put("UpdatedAt", now);
        job.set("Tags", request.path("Tags").isObject() ? request.get("Tags").deepCopy() : mapper.createObjectNode());
        if (token != null) {
            job.put("clientToken", token);
        }
        String account = regionResolver.getAccountId();
        String key = LocationService.key(region, jobId);
        jobs.putForAccount(account, key, job);
        executor.submit(() -> run(account, key, input, output));
        return summary(job);
    }

    private ObjectNode summary(ObjectNode job) {
        ObjectNode response = mapper.createObjectNode();
        response.put("CreatedAt", job.path("CreatedAt").asText());
        response.put("JobArn", job.path("JobArn").asText());
        response.put("JobId", job.path("JobId").asText());
        response.put("Status", job.path("Status").asText());
        return response;
    }

    private void run(String account, String key, String input, String output) {
        if (!transition(account, key, "Running", null)) {
            return;
        }
        try {
            FlociDuckClient client = duck.get();
            List<Map<String, Object>> rows = client.query("SELECT * FROM read_parquet('" + escape(input) + "')", null,
                    account);
            StringBuilder ndjson = new StringBuilder();
            for (Map<String, Object> row : rows) {
                ndjson.append(mapper.writeValueAsString(validate(row))).append('\n');
            }
            String jobId = key.substring(key.indexOf("::") + 2);
            String outputPrefix = output.endsWith("/") ? output : output + "/";
            S3Uri staging = S3Uri.parse(outputPrefix + jobId + "/staging.ndjson");
            s3.get().putObject(staging.bucket(), staging.key(), ndjson.toString().getBytes(StandardCharsets.UTF_8),
                    "application/x-ndjson", Map.of());
            try {
                client.execute("COPY (SELECT * FROM read_json_auto('" + escape("s3://" + staging.bucket() + "/"
                        + staging.key()) + "')) TO '" + escape(outputPrefix + jobId + "/output.parquet")
                        + "' (FORMAT PARQUET)", null, null, account);
            } finally {
                s3.get().deleteObject(staging.bucket(), staging.key());
            }
            transition(account, key, "Completed", null);
        } catch (Exception e) {
            LOG.warnv("Location job {0} failed: {1}", key, e.getMessage());
            transition(account, key, "Failed", e.getMessage());
        }
    }

    /** The row with Floci's validation columns: whether the address matched, and where. */
    private Map<String, Object> validate(Map<String, Object> row) {
        Map<String, Object> lower = new LinkedHashMap<>();
        row.forEach((column, value) -> lower.put(column.toLowerCase(Locale.ROOT).replace("_", ""), value));
        List<String> parts = new ArrayList<>();
        for (List<String> names : List.of(STREET, CITY, REGION, POSTAL, COUNTRY)) {
            for (String name : names) {
                Object value = lower.get(name);
                if (value != null && !String.valueOf(value).isBlank()) {
                    parts.add(String.valueOf(value));
                    break;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(row);
        List<LocationGazetteer.Match> matches = parts.isEmpty() ? List.of()
                : LocationGazetteer.search(String.join(", ", parts), null, null, null, null, 1);
        if (matches.isEmpty()) {
            result.put("Validated", false);
            result.put("FormattedAddress", null);
            result.put("Longitude", null);
            result.put("Latitude", null);
        } else {
            LocationGazetteer.Place place = matches.getFirst().place();
            result.put("Validated", true);
            result.put("FormattedAddress", place.label());
            result.put("Longitude", place.position()[0]);
            result.put("Latitude", place.position()[1]);
        }
        return result;
    }

    private synchronized boolean transition(String account, String key, String status, String error) {
        ObjectNode job = jobs.getForAccount(account, key).orElse(null);
        if (job == null || "Cancelled".equals(job.path("Status").asText())
                || "Cancelling".equals(job.path("Status").asText())) {
            if (job != null && "Cancelling".equals(job.path("Status").asText())) {
                job.put("Status", "Cancelled");
                job.put("EndedAt", LocationService.now());
                jobs.putForAccount(account, key, job);
            }
            return false;
        }
        job.put("Status", status);
        job.put("UpdatedAt", LocationService.now());
        if ("Completed".equals(status) || "Failed".equals(status)) {
            job.put("EndedAt", LocationService.now());
        }
        if (error != null) {
            ObjectNode failure = job.putObject("Error");
            failure.put("Code", "InternalServerError");
            failure.putArray("Messages").add(error);
        }
        jobs.putForAccount(account, key, job);
        return true;
    }

    public ObjectNode get(String jobId, String region) {
        ObjectNode job = require(jobId, region).deepCopy();
        job.remove("clientToken");
        return job;
    }

    public ObjectNode list(JsonNode request, String region) {
        int max = request.path("MaxResults").asInt(100);
        if (max < 1 || max > 100) {
            throw LocationService.validation("MaxResults must be between 1 and 100.");
        }
        String status = request.path("Filter").path("JobStatus").asText(null);
        List<ObjectNode> all = jobs.scan(k -> k.startsWith(region + "::")).stream()
                .filter(job -> status == null || status.equals(job.path("Status").asText()))
                .sorted(Comparator.comparing((ObjectNode job) -> job.path("CreatedAt").asText()).reversed())
                .toList();
        int start = LocationService.offset(request.path("NextToken").asText(null));
        int end = Math.min(all.size(), start + max);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (ObjectNode job : all.subList(Math.min(start, end), end)) {
            ObjectNode entry = job.deepCopy();
            entry.remove(List.of("clientToken", "Tags"));
            entries.add(entry);
        }
        if (end < all.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return response;
    }

    public synchronized ObjectNode cancel(String jobId, String region) {
        ObjectNode job = require(jobId, region);
        String status = job.path("Status").asText();
        if ("Completed".equals(status) || "Failed".equals(status) || "Cancelled".equals(status)) {
            throw new AwsException("ValidationException", "The job " + jobId + " has already ended (" + status + ").",
                    400);
        }
        job.put("Status", "Pending".equals(status) ? "Cancelled" : "Cancelling");
        job.put("UpdatedAt", LocationService.now());
        jobs.put(LocationService.key(region, jobId), job);
        ObjectNode response = mapper.createObjectNode();
        response.put("JobArn", job.path("JobArn").asText());
        response.put("JobId", jobId);
        response.put("Status", job.path("Status").asText());
        return response;
    }

    ObjectNode require(String jobId, String region) {
        if (jobId == null || !jobId.matches("[-._\\w]{1,200}")) {
            throw LocationService.validation("JobId is not valid.");
        }
        return jobs.get(LocationService.key(region, jobId)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The job " + jobId + " was not found.", 404));
    }

    private boolean roleExists(String roleArn) {
        if (roleArn == null || !AwsArnUtils.isArnFor(roleArn, "iam") || !iam.isResolvable()) {
            return false;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return arn.resource().startsWith("role/") && iam.get().findRole(arn.accountId(), name)
                .filter(role -> IamService.roleArnMatches(roleArn, role)).isPresent();
    }

    /** {@code s3://bucket/key} from an options object whose Location is an S3 URI or ARN. */
    private static String s3Uri(JsonNode options, String member) {
        if (!"Parquet".equals(options.path("Format").asText(null))) {
            throw LocationService.validation(member + ".Format must be Parquet.");
        }
        String location = options.path("Location").asText(null);
        if (location == null) {
            throw LocationService.validation(member + ".Location is required.");
        }
        if (location.startsWith("s3://")) {
            return location;
        }
        if (AwsArnUtils.isArnFor(location, "s3")) {
            return "s3://" + AwsArnUtils.parse(location).resource();
        }
        throw LocationService.validation(member + ".Location must be an S3 URI or ARN.");
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }

    /** An {@code s3://bucket/key} split. */
    private record S3Uri(String bucket, String key) {
        static S3Uri parse(String uri) {
            String rest = uri.substring("s3://".length());
            int slash = rest.indexOf('/');
            return new S3Uri(rest.substring(0, slash), rest.substring(slash + 1));
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public void clear() {
        jobs.clear();
    }
}
