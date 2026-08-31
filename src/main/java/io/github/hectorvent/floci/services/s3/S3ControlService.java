package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3AccessPoint;
import io.github.hectorvent.floci.services.s3.model.S3BatchJob;
import io.github.hectorvent.floci.services.s3.model.S3MultiRegionAccessPoint;
import io.github.hectorvent.floci.services.s3.model.S3ObjectLambdaAccessPoint;
import io.github.hectorvent.floci.services.s3.model.S3StorageLensConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * S3 Control access-point store used by {@link S3ControlController}.
 *
 * <p>Lookups are by access-point name (not account). The emulator is single-tenant
 * and Alchemy's live suite hard-codes a production account id on out-of-band
 * GetAccessPoint/ListTagsForResource calls while local providers stamp
 * {@code 000000000000} into the ARN.
 */
@ApplicationScoped
public class S3ControlService implements Resettable {

    private static final Logger LOG = Logger.getLogger(S3ControlService.class);
    private static final Set<String> TERMINAL_JOB_STATUSES = Set.of("Complete", "Failed", "Cancelled");
    private static final Set<String> PRIORITY_FORBIDDEN_STATUSES =
            Set.of("Complete", "Failed", "Cancelled", "Suspended");

    private final StorageBackend<String, S3AccessPoint> accessPoints;
    private final StorageBackend<String, S3BatchJob> jobs;
    private final StorageBackend<String, S3ObjectLambdaAccessPoint> objectLambdaAccessPoints;
    private final StorageBackend<String, S3MultiRegionAccessPoint> multiRegionAccessPoints;
    private StorageBackend<String, S3StorageLensConfiguration> storageLens = new InMemoryStorage<>();
    private final S3Service s3Service;
    private final RegionResolver regionResolver;

    @Inject
    public S3ControlService(StorageFactory storageFactory, S3Service s3Service,
                            RegionResolver regionResolver) {
        this(storageFactory.create("s3", "s3-access-points.json",
                        new TypeReference<Map<String, S3AccessPoint>>() {
                        }),
                storageFactory.create("s3", "s3-batch-jobs.json",
                        new TypeReference<Map<String, S3BatchJob>>() {
                        }),
                storageFactory.create("s3", "s3-object-lambda-access-points.json",
                        new TypeReference<Map<String, S3ObjectLambdaAccessPoint>>() {
                        }),
                storageFactory.create("s3", "s3-mraps.json",
                        new TypeReference<Map<String, S3MultiRegionAccessPoint>>() {
                        }),
                s3Service, regionResolver);
        this.storageLens = storageFactory.create("s3", "s3-storage-lens.json",
                new TypeReference<Map<String, S3StorageLensConfiguration>>() {
                });
    }

    S3ControlService(StorageBackend<String, S3AccessPoint> accessPoints, S3Service s3Service,
                     RegionResolver regionResolver) {
        this(accessPoints, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), s3Service, regionResolver);
    }

    S3ControlService(StorageBackend<String, S3AccessPoint> accessPoints,
                     StorageBackend<String, S3BatchJob> jobs, S3Service s3Service,
                     RegionResolver regionResolver) {
        this(accessPoints, jobs, new InMemoryStorage<>(), new InMemoryStorage<>(),
                s3Service, regionResolver);
    }

    S3ControlService(StorageBackend<String, S3AccessPoint> accessPoints,
                     StorageBackend<String, S3BatchJob> jobs,
                     StorageBackend<String, S3MultiRegionAccessPoint> multiRegionAccessPoints,
                     S3Service s3Service, RegionResolver regionResolver) {
        this(accessPoints, jobs, new InMemoryStorage<>(), multiRegionAccessPoints,
                s3Service, regionResolver);
    }

    S3ControlService(StorageBackend<String, S3AccessPoint> accessPoints,
                     StorageBackend<String, S3BatchJob> jobs,
                     StorageBackend<String, S3ObjectLambdaAccessPoint> objectLambdaAccessPoints,
                     StorageBackend<String, S3MultiRegionAccessPoint> multiRegionAccessPoints,
                     S3Service s3Service, RegionResolver regionResolver) {
        this.accessPoints = accessPoints;
        this.jobs = jobs;
        this.objectLambdaAccessPoints = objectLambdaAccessPoints;
        this.multiRegionAccessPoints = multiRegionAccessPoints;
        this.s3Service = s3Service;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        accessPoints.clear();
        jobs.clear();
        objectLambdaAccessPoints.clear();
        multiRegionAccessPoints.clear();
        storageLens.clear();
    }

    public synchronized S3AccessPoint createAccessPoint(String accountId, String name, String bucket,
                                                         String bucketAccountId, String vpcId,
                                                         Boolean blockPublicAcls, Boolean ignorePublicAcls,
                                                         Boolean blockPublicPolicy,
                                                         Boolean restrictPublicBuckets,
                                                         Map<String, String> tags) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidRequest", "Access point name is required.", 400);
        }
        if (bucket == null || bucket.isBlank()) {
            throw new AwsException("InvalidRequest", "Bucket is required.", 400);
        }
        if (accessPoints.get(name).isPresent()) {
            throw new AwsException("AccessPointAlreadyOwnedByYou",
                    "An access point with name " + name + " already exists.", 409);
        }
        if (s3Service.listBuckets().stream().map(Bucket::getName).noneMatch(bucket::equals)) {
            throw new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404);
        }

        String account = (accountId == null || accountId.isBlank())
                ? regionResolver.getAccountId()
                : accountId;
        String region = regionResolver.getRegion();
        String arn = AwsArnUtils.Arn.of("s3", region, account, "accesspoint/" + name).toString();

        S3AccessPoint accessPoint = new S3AccessPoint();
        accessPoint.setName(name);
        accessPoint.setBucket(bucket);
        accessPoint.setBucketAccountId(
                bucketAccountId == null || bucketAccountId.isBlank() ? account : bucketAccountId);
        accessPoint.setAccountId(account);
        accessPoint.setRegion(region);
        accessPoint.setArn(arn);
        accessPoint.setAlias(name + "-s3alias");
        accessPoint.setVpcId(vpcId);
        accessPoint.setNetworkOrigin(vpcId == null || vpcId.isBlank() ? "Internet" : "VPC");
        accessPoint.setBlockPublicAcls(blockPublicAcls == null || blockPublicAcls);
        accessPoint.setIgnorePublicAcls(ignorePublicAcls == null || ignorePublicAcls);
        accessPoint.setBlockPublicPolicy(blockPublicPolicy == null || blockPublicPolicy);
        accessPoint.setRestrictPublicBuckets(restrictPublicBuckets == null || restrictPublicBuckets);
        accessPoint.setCreationDate(Instant.now());
        if (tags != null && !tags.isEmpty()) {
            accessPoint.getTags().putAll(tags);
        }
        accessPoints.put(name, accessPoint);
        return accessPoint;
    }

    public synchronized S3AccessPoint getAccessPoint(String name) {
        return require(name);
    }

    public synchronized Optional<S3AccessPoint> findAccessPoint(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return accessPoints.get(name);
    }

    public synchronized void deleteAccessPoint(String name) {
        require(name);
        accessPoints.delete(name);
    }

    public synchronized List<S3AccessPoint> listAccessPoints(String bucket) {
        List<S3AccessPoint> result = new ArrayList<>();
        for (S3AccessPoint accessPoint : accessPoints.values()) {
            if (bucket == null || bucket.isBlank() || bucket.equals(accessPoint.getBucket())) {
                result.add(accessPoint);
            }
        }
        return result;
    }

    public synchronized Map<String, String> listTags(String name) {
        return new LinkedHashMap<>(require(name).getTags());
    }

    public synchronized void tagAccessPoint(String name, Map<String, String> tags) {
        S3AccessPoint accessPoint = require(name);
        if (tags != null) {
            accessPoint.getTags().putAll(tags);
        }
        accessPoints.put(name, accessPoint);
    }

    public synchronized void untagAccessPoint(String name, List<String> tagKeys) {
        S3AccessPoint accessPoint = require(name);
        if (tagKeys != null) {
            tagKeys.forEach(accessPoint.getTags()::remove);
        }
        accessPoints.put(name, accessPoint);
    }

    public synchronized S3ObjectLambdaAccessPoint createObjectLambdaAccessPoint(
            String accountId, String name, String configurationXml) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidRequest", "Access point name is required.", 400);
        }
        if (objectLambdaAccessPoints.get(name).isPresent()) {
            throw new AwsException("AccessPointAlreadyOwnedByYou",
                    "An access point with name " + name + " already exists.", 409);
        }
        ParsedObjectLambdaConfiguration config = parseObjectLambdaConfiguration(configurationXml);
        requireSupportingAccessPoint(config.supportingAccessPoint);

        String account = (accountId == null || accountId.isBlank())
                ? regionResolver.getAccountId()
                : accountId;
        String region = regionResolver.getRegion();

        S3ObjectLambdaAccessPoint olap = new S3ObjectLambdaAccessPoint();
        olap.setName(name);
        olap.setAccountId(account);
        olap.setRegion(region);
        olap.setArn(AwsArnUtils.Arn.of("s3-object-lambda", region, account,
                "accesspoint/" + name).toString());
        olap.setAlias(name + "-ol-s3alias");
        olap.setAliasStatus("READY");
        olap.setCreationDate(Instant.now());
        applyObjectLambdaConfiguration(olap, config);
        objectLambdaAccessPoints.put(name, olap);
        return olap;
    }

    public synchronized S3ObjectLambdaAccessPoint getObjectLambdaAccessPoint(String name) {
        return requireObjectLambda(name);
    }

    public synchronized void deleteObjectLambdaAccessPoint(String name) {
        requireObjectLambda(name);
        objectLambdaAccessPoints.delete(name);
    }

    public synchronized List<S3ObjectLambdaAccessPoint> listObjectLambdaAccessPoints() {
        return new ArrayList<>(objectLambdaAccessPoints.values());
    }

    public synchronized S3ObjectLambdaAccessPoint putObjectLambdaConfiguration(
            String name, String configurationXml) {
        S3ObjectLambdaAccessPoint olap = requireObjectLambda(name);
        ParsedObjectLambdaConfiguration config = parseObjectLambdaConfiguration(configurationXml);
        requireSupportingAccessPoint(config.supportingAccessPoint);
        applyObjectLambdaConfiguration(olap, config);
        objectLambdaAccessPoints.put(name, olap);
        return olap;
    }

    private S3ObjectLambdaAccessPoint requireObjectLambda(String name) {
        if (name == null || name.isBlank()) {
            throw noSuchAccessPoint();
        }
        return objectLambdaAccessPoints.get(name).orElseThrow(this::noSuchAccessPoint);
    }

    private void requireSupportingAccessPoint(String supporting) {
        if (supporting == null || supporting.isBlank()) {
            throw new AwsException("InvalidRequest", "SupportingAccessPoint is required.", 400);
        }
        String supportingName = supportingAccessPointName(supporting);
        if (findAccessPoint(supportingName).isPresent()) {
            return;
        }
        for (S3AccessPoint accessPoint : accessPoints.values()) {
            if (supporting.equals(accessPoint.getArn())) {
                return;
            }
        }
        throw noSuchAccessPoint();
    }

    static String supportingAccessPointName(String supporting) {
        int idx = supporting.lastIndexOf(":accesspoint/");
        if (idx < 0) {
            return supporting;
        }
        String name = supporting.substring(idx + ":accesspoint/".length());
        int slash = name.indexOf('/');
        return slash >= 0 ? name.substring(0, slash) : name;
    }

    private static void applyObjectLambdaConfiguration(
            S3ObjectLambdaAccessPoint olap, ParsedObjectLambdaConfiguration config) {
        olap.setSupportingAccessPoint(config.supportingAccessPoint);
        olap.setCloudWatchMetricsEnabled(config.cloudWatchMetricsEnabled);
        olap.setAllowedFeatures(config.allowedFeatures);
        olap.setTransformationConfigurations(config.transformations);
    }

    static ParsedObjectLambdaConfiguration parseObjectLambdaConfiguration(String xml) {
        String supporting = XmlParser.extractFirst(xml, "SupportingAccessPoint", null);
        if (supporting == null || supporting.isBlank()) {
            throw new AwsException("InvalidRequest", "SupportingAccessPoint is required.", 400);
        }
        String metrics = XmlParser.extractFirst(xml, "CloudWatchMetricsEnabled", null);
        List<String> features = XmlParser.extractAll(xml, "AllowedFeature");
        List<S3ObjectLambdaAccessPoint.Transformation> transformations = parseTransformations(xml);
        if (transformations.isEmpty()) {
            throw new AwsException("InvalidRequest",
                    "TransformationConfigurations is required.", 400);
        }
        return new ParsedObjectLambdaConfiguration(
                supporting.trim(),
                metrics != null && Boolean.parseBoolean(metrics.trim()),
                features,
                transformations);
    }

    private static List<S3ObjectLambdaAccessPoint.Transformation> parseTransformations(String xml) {
        List<S3ObjectLambdaAccessPoint.Transformation> result = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return result;
        }
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            S3ObjectLambdaAccessPoint.Transformation current = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("TransformationConfiguration".equals(local)) {
                        current = new S3ObjectLambdaAccessPoint.Transformation();
                    } else if (current != null && "Action".equals(local)) {
                        String text = reader.getElementText();
                        if (text != null && !text.isBlank()) {
                            current.getActions().add(text.trim());
                        }
                    } else if (current != null && "FunctionArn".equals(local)) {
                        current.setFunctionArn(reader.getElementText());
                    } else if (current != null && "FunctionPayload".equals(local)) {
                        current.setFunctionPayload(reader.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "TransformationConfiguration".equals(reader.getLocalName())
                        && current != null) {
                    result.add(current);
                    current = null;
                }
            }
            reader.close();
        } catch (Exception e) {
            LOG.debugv("Ignoring malformed Object Lambda configuration XML: {0}", e.getMessage());
            return result;
        }
        return result;
    }

    record ParsedObjectLambdaConfiguration(
            String supportingAccessPoint,
            boolean cloudWatchMetricsEnabled,
            List<String> allowedFeatures,
            List<S3ObjectLambdaAccessPoint.Transformation> transformations) {}

    private AwsException noSuchAccessPoint() {
        return new AwsException("NoSuchAccessPoint",
                "The specified accesspoint does not exist", 404);
    }

    public synchronized void putAccessPointPolicy(String name, String policy) {
        if (policy == null || policy.isBlank()) {
            throw new AwsException("MalformedPolicy", "Access point policy is required.", 400);
        }
        S3AccessPoint accessPoint = require(name);
        accessPoint.setPolicy(policy);
        accessPoints.put(name, accessPoint);
    }

    public synchronized String getAccessPointPolicy(String name) {
        S3AccessPoint accessPoint = require(name);
        String policy = accessPoint.getPolicy();
        if (policy == null || policy.isBlank()) {
            throw noSuchAccessPointPolicy();
        }
        return policy;
    }

    public synchronized void deleteAccessPointPolicy(String name) {
        S3AccessPoint accessPoint = require(name);
        if (accessPoint.getPolicy() == null || accessPoint.getPolicy().isBlank()) {
            throw noSuchAccessPointPolicy();
        }
        accessPoint.setPolicy(null);
        accessPoints.put(name, accessPoint);
    }

    /**
     * GetAccessPointPolicyStatus — whether the attached policy currently grants public access.
     * A missing policy is {@code NoSuchAccessPointPolicy} (live AWS). Block-public-policy
     * access points always report {@code IsPublic=false}.
     */
    public synchronized boolean getAccessPointPolicyStatus(String name) {
        S3AccessPoint accessPoint = require(name);
        String policy = accessPoint.getPolicy();
        if (policy == null || policy.isBlank()) {
            throw noSuchAccessPointPolicy();
        }
        if (accessPoint.isBlockPublicPolicy()) {
            return false;
        }
        String compact = policy.replaceAll("\\s+", "");
        return compact.contains("\"Principal\":\"*\"")
                || compact.contains("\"AWS\":\"*\"");
    }

    public synchronized S3BatchJob createJob(String accountId, String clientRequestToken,
                                             Boolean confirmationRequired, Integer priority,
                                             String roleArn, String operation, String description,
                                             boolean reportEnabled, String sourceBucket) {
        if (clientRequestToken == null || clientRequestToken.isBlank()) {
            throw new AwsException("InvalidRequest", "ClientRequestToken is required.", 400);
        }
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("InvalidRequest", "RoleArn is required.", 400);
        }
        if (operation == null || operation.isBlank()) {
            throw new AwsException("InvalidRequest", "Operation is required.", 400);
        }
        if (priority == null) {
            throw new AwsException("InvalidRequest", "Priority is required.", 400);
        }

        for (S3BatchJob existing : jobs.values()) {
            if (clientRequestToken.equals(existing.getClientRequestToken())) {
                return existing;
            }
        }

        String account = (accountId == null || accountId.isBlank())
                ? regionResolver.getAccountId()
                : accountId;
        String region = regionResolver.getRegion();
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        boolean confirm = Boolean.TRUE.equals(confirmationRequired);

        S3BatchJob job = new S3BatchJob();
        job.setJobId(jobId);
        job.setJobArn(AwsArnUtils.Arn.of("s3", region, account, "job/" + jobId).toString());
        job.setClientRequestToken(clientRequestToken);
        job.setPriority(priority);
        job.setConfirmationRequired(confirm);
        job.setRoleArn(roleArn);
        job.setOperation(operation);
        job.setDescription(description);
        job.setReportEnabled(reportEnabled);
        job.setSourceBucket(sourceBucket);
        job.setCreationTime(now);
        if (confirm) {
            job.setStatus("Suspended");
            job.setSuspendedDate(now);
            job.setSuspendedCause("Confirmation required");
        } else {
            job.setStatus("Complete");
            job.setTerminationDate(now);
        }
        jobs.put(jobId, job);
        return job;
    }

    public synchronized S3BatchJob describeJob(String jobId) {
        return requireJob(jobId);
    }

    public synchronized List<S3BatchJob> listJobs(List<String> jobStatuses) {
        List<S3BatchJob> result = new ArrayList<>();
        for (S3BatchJob job : jobs.values()) {
            if (matchesJobStatusFilter(job.getStatus(), jobStatuses)) {
                result.add(job);
            }
        }
        return result;
    }

    public synchronized S3BatchJob updateJobPriority(String jobId, Integer priority) {
        if (priority == null) {
            throw new AwsException("InvalidRequest", "Priority is required.", 400);
        }
        S3BatchJob job = requireJob(jobId);
        if (PRIORITY_FORBIDDEN_STATUSES.contains(job.getStatus())) {
            throw jobStatusForbidden(job.getStatus());
        }
        job.setPriority(priority);
        jobs.put(jobId, job);
        return job;
    }

    public synchronized S3BatchJob updateJobStatus(String jobId, String requestedJobStatus,
                                                   String statusUpdateReason) {
        if (requestedJobStatus == null || requestedJobStatus.isBlank()) {
            throw new AwsException("InvalidRequest", "RequestedJobStatus is required.", 400);
        }
        S3BatchJob job = requireJob(jobId);
        String requested = requestedJobStatus.trim();
        String current = job.getStatus();
        if ("Cancelled".equalsIgnoreCase(requested)) {
            if (TERMINAL_JOB_STATUSES.contains(current)) {
                throw jobStatusForbidden(current);
            }
            job.setStatus("Cancelled");
            job.setTerminationDate(Instant.now());
        } else if ("Ready".equalsIgnoreCase(requested)) {
            if (!"Suspended".equals(current) && !"New".equals(current) && !"Preparing".equals(current)) {
                throw jobStatusForbidden(current);
            }
            job.setStatus("Complete");
            job.setTerminationDate(Instant.now());
        } else {
            throw new AwsException("InvalidRequest",
                    "RequestedJobStatus must be Cancelled or Ready.", 400);
        }
        if (statusUpdateReason != null && !statusUpdateReason.isBlank()) {
            job.setStatusUpdateReason(statusUpdateReason);
        }
        jobs.put(jobId, job);
        return job;
    }

    private S3BatchJob requireJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new AwsException("InvalidRequest", "JobId is required.", 400);
        }
        return jobs.get(jobId).orElseThrow(() -> new AwsException("NotFoundException",
                "The specified job does not exist.", 404));
    }

    private static boolean matchesJobStatusFilter(String status, List<String> jobStatuses) {
        if (jobStatuses == null || jobStatuses.isEmpty()) {
            return true;
        }
        for (String filter : jobStatuses) {
            if (filter != null && !filter.isBlank()
                    && filter.trim().equalsIgnoreCase(status)) {
                return true;
            }
        }
        return false;
    }

    private static AwsException jobStatusForbidden(String status) {
        return new AwsException("InvalidRequest",
                "The requested job status forbidden for the current job state (" + status + ").",
                400);
    }

    private static AwsException noSuchAccessPointPolicy() {
        return new AwsException("NoSuchAccessPointPolicy",
                "The specified accesspoint policy does not exist", 404);
    }

    private S3AccessPoint require(String name) {
        return accessPoints.get(name).orElseThrow(() -> new AwsException("NoSuchAccessPoint",
                "The specified accesspoint does not exist", 404));
    }

    public synchronized S3MultiRegionAccessPoint createMultiRegionAccessPoint(
            String accountId, String name, List<S3MultiRegionAccessPoint.Region> regions,
            Boolean blockPublicAcls, Boolean ignorePublicAcls,
            Boolean blockPublicPolicy, Boolean restrictPublicBuckets) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidRequest", "Multi-Region Access Point name is required.", 400);
        }
        if (regions == null || regions.isEmpty()) {
            throw new AwsException("InvalidRequest", "At least one region is required.", 400);
        }
        if (multiRegionAccessPoints.get(name).isPresent()) {
            throw new AwsException("InvalidRequest",
                    "A Multi-Region Access Point with name " + name + " already exists.", 400);
        }

        String account = (accountId == null || accountId.isBlank())
                ? regionResolver.getAccountId()
                : accountId;
        List<S3MultiRegionAccessPoint.Region> resolved = new ArrayList<>();
        for (S3MultiRegionAccessPoint.Region incoming : regions) {
            if (incoming == null || incoming.getBucket() == null || incoming.getBucket().isBlank()) {
                throw new AwsException("InvalidRequest", "Region Bucket is required.", 400);
            }
            S3MultiRegionAccessPoint.Region region = new S3MultiRegionAccessPoint.Region();
            region.setBucket(incoming.getBucket());
            region.setBucketAccountId(incoming.getBucketAccountId() == null
                    || incoming.getBucketAccountId().isBlank() ? account : incoming.getBucketAccountId());
            region.setRegion(regionForBucket(incoming.getBucket()));
            resolved.add(region);
        }

        S3MultiRegionAccessPoint mrap = new S3MultiRegionAccessPoint();
        mrap.setName(name);
        mrap.setAccountId(account);
        mrap.setAlias(nextMrapAlias());
        mrap.setCreatedAt(Instant.now());
        mrap.setStatus("READY");
        mrap.setBlockPublicAcls(blockPublicAcls == null || blockPublicAcls);
        mrap.setIgnorePublicAcls(ignorePublicAcls == null || ignorePublicAcls);
        mrap.setBlockPublicPolicy(blockPublicPolicy == null || blockPublicPolicy);
        mrap.setRestrictPublicBuckets(restrictPublicBuckets == null || restrictPublicBuckets);
        mrap.setRegions(resolved);
        mrap.setRequestTokenArn(asyncRequestArn(account, "create"));
        multiRegionAccessPoints.put(name, mrap);
        return mrap;
    }

    public synchronized S3MultiRegionAccessPoint getMultiRegionAccessPoint(String name) {
        return requireMrap(name);
    }

    public synchronized List<S3MultiRegionAccessPoint> listMultiRegionAccessPoints() {
        return new ArrayList<>(multiRegionAccessPoints.values());
    }

    public synchronized String deleteMultiRegionAccessPoint(String accountId, String name) {
        requireMrap(name);
        String account = (accountId == null || accountId.isBlank())
                ? regionResolver.getAccountId()
                : accountId;
        multiRegionAccessPoints.delete(name);
        return asyncRequestArn(account, "delete");
    }

    private S3MultiRegionAccessPoint requireMrap(String name) {
        if (name == null || name.isBlank()) {
            throw noSuchMultiRegionAccessPoint();
        }
        return multiRegionAccessPoints.get(name).orElseThrow(this::noSuchMultiRegionAccessPoint);
    }

    private AwsException noSuchMultiRegionAccessPoint() {
        return new AwsException("NoSuchMultiRegionAccessPoint",
                "The specified multi-region access point does not exist", 404);
    }

    private String regionForBucket(String bucket) {
        return s3Service.listBuckets().stream()
                .filter(b -> bucket.equals(b.getName()))
                .map(Bucket::getRegion)
                .filter(r -> r != null && !r.isBlank())
                .findFirst()
                .orElse(regionResolver.getRegion());
    }

    private String nextMrapAlias() {
        String alias;
        do {
            alias = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ".mrap";
        } while (aliasInUse(alias));
        return alias;
    }

    private boolean aliasInUse(String alias) {
        for (S3MultiRegionAccessPoint existing : multiRegionAccessPoints.values()) {
            if (alias.equals(existing.getAlias())) {
                return true;
            }
        }
        return false;
    }

    private String asyncRequestArn(String account, String operation) {
        return AwsArnUtils.Arn.of("s3", "us-west-2", account,
                "async-request/mrap/" + operation + "/" + UUID.randomUUID()).toString();
    }

    public synchronized S3StorageLensConfiguration putStorageLensConfiguration(
            String accountId, String configId, String body) {
        String account = (accountId == null || accountId.isBlank())
                ? regionResolver.getAccountId()
                : accountId;
        String region = regionResolver.getRegion();
        S3StorageLensConfiguration config = storageLens.get(configId).orElseGet(S3StorageLensConfiguration::new);
        S3StorageLensXml.applyPutBody(config, body, configId);
        String id = config.getConfigId();
        config.setAccountId(account);
        config.setRegion(region);
        config.setArn(AwsArnUtils.Arn.of("s3", region, account, "storage-lens/" + id).toString());
        storageLens.put(id, config);
        return config;
    }

    public synchronized S3StorageLensConfiguration getStorageLensConfiguration(String configId) {
        return requireLens(configId);
    }

    public synchronized void deleteStorageLensConfiguration(String configId) {
        requireLens(configId);
        storageLens.delete(configId);
    }

    public synchronized List<S3StorageLensConfiguration> listStorageLensConfigurations() {
        return new ArrayList<>(storageLens.values());
    }

    public synchronized Map<String, String> getStorageLensTags(String configId) {
        return new LinkedHashMap<>(requireLens(configId).getTags());
    }

    public synchronized void putStorageLensTags(String configId, Map<String, String> tags) {
        S3StorageLensConfiguration config = requireLens(configId);
        config.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        storageLens.put(config.getConfigId(), config);
    }

    public synchronized void deleteStorageLensTags(String configId) {
        S3StorageLensConfiguration config = requireLens(configId);
        config.getTags().clear();
        storageLens.put(config.getConfigId(), config);
    }

    private S3StorageLensConfiguration requireLens(String configId) {
        if (configId == null || configId.isBlank()) {
            throw new AwsException("InvalidRequest", "ConfigId is required.", 400);
        }
        return storageLens.get(configId).orElseThrow(() -> new AwsException("NoSuchConfiguration",
                "The specified configuration does not exist", 404));
    }
}
