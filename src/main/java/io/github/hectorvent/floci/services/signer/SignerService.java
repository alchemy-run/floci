package io.github.hectorvent.floci.services.signer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.signer.model.SigningJob;
import io.github.hectorvent.floci.services.signer.model.ProfilePermission;
import io.github.hectorvent.floci.services.signer.model.SigningProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Signer restJson1 control plane used by Alchemy SigningProfile and bindings:
 * platforms, profiles, S3 signing jobs, payload signing, and revocation.
 */
@ApplicationScoped
public class SignerService implements TagHandler {

    static final String SERVICE = "signer";
    private static final Pattern PROFILE_NAME = Pattern.compile("^[a-zA-Z0-9_]{2,64}$");
    private static final int DEFAULT_MAX_RESULTS = 25;
    private static final int MAX_RESULTS = 100;
    private static final int MAX_PERMISSIONS = 20;
    private static final String TOKEN_PREFIX = "signer:v1:";
    private static final Set<String> ACTIONS = Set.of(
            "signer:StartSigningJob",
            "signer:GetSigningProfile",
            "signer:RevokeSignature",
            "signer:SignPayload");

    private static final List<Platform> PLATFORMS = List.of(
            platform("AWSLambda-SHA384-ECDSA", "AWS Lambda", "Amazon Web Services", "AWS Lambda",
                    "ECDSA", "SHA384", true, 250),
            platform("Notation-OCI-SHA384-ECDSA", "Notation for OCI artifacts", "Notary Project",
                    "container image", "ECDSA", "SHA384", true, 200),
            platform("AWSIoTDeviceManagement-SHA256-ECDSA", "AWS IoT Device Management",
                    "Amazon Web Services", "AWS IoT Device Management", "ECDSA", "SHA256", true, 100),
            platform("AmazonFreeRTOS-Default", "Amazon FreeRTOS", "Amazon Web Services",
                    "Amazon FreeRTOS", "ECDSA", "SHA256", false, 16),
            platform("AmazonFreeRTOS-TI-CC3220SF", "Amazon FreeRTOS TI CC3220SF",
                    "Amazon Web Services", "Amazon FreeRTOS", "ECDSA", "SHA256", false, 16));

    private final StorageBackend<String, SigningProfile> profiles;
    private final StorageBackend<String, SigningJob> jobs;
    private final ObjectMapper objectMapper;
    private final S3Service s3Service;

    @Inject
    public SignerService(StorageFactory storageFactory, ObjectMapper objectMapper, S3Service s3Service) {
        this(
                storageFactory.create("signer", "signer-profiles.json",
                        new TypeReference<Map<String, SigningProfile>>() {
                        }),
                storageFactory.create("signer", "signer-jobs.json",
                        new TypeReference<Map<String, SigningJob>>() {
                        }),
                objectMapper,
                s3Service);
    }

    SignerService(
            StorageBackend<String, SigningProfile> profiles,
            StorageBackend<String, SigningJob> jobs,
            ObjectMapper objectMapper,
            S3Service s3Service) {
        this.profiles = profiles;
        this.jobs = jobs;
        this.objectMapper = objectMapper;
        this.s3Service = s3Service;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    public synchronized ObjectNode putSigningProfile(String accountId, String region, String profileName, JsonNode body) {
        requireProfileName(profileName);
        JsonNode request = requireObject(body);
        String platformId = requireText(request, "platformId");
        Platform platform = requirePlatform(platformId);
        if (platformId.startsWith("AWSLambda-") && request.has("signingParameters")
                && !request.get("signingParameters").isNull()
                && request.get("signingParameters").size() > 0) {
            throw validation("Signing parameters should not be present when using " + platformId + " platform");
        }
        String key = profileKey(accountId, region, profileName);
        if (profiles.get(key).isPresent()) {
            throw new AwsException("ValidationException",
                    "Profile with name " + profileName + " already exists", 400);
        }
        long now = Instant.now().getEpochSecond();
        String version = newVersion();
        SigningProfile profile = new SigningProfile();
        profile.accountId = accountId;
        profile.region = region;
        profile.profileName = profileName;
        profile.profileVersion = version;
        profile.arn = profileArn(region, accountId, profileName);
        profile.profileVersionArn = profile.arn + "/" + version;
        profile.platformId = platform.id;
        profile.platformDisplayName = platform.displayName;
        profile.status = "Active";
        JsonNode validity = request.path("signatureValidityPeriod");
        profile.signatureValidityValue = validity.path("value").isNumber() ? validity.path("value").asInt() : 135;
        profile.signatureValidityType = textOr(validity.path("type"), "MONTHS");
        profile.certificateArn = request.path("signingMaterial").path("certificateArn").asText(null);
        JsonNode overrides = request.get("overrides");
        if (overrides != null && overrides.isObject()) {
            profile.overrides = overrides.deepCopy();
        }
        profile.signingParameters = parseStringMap(request.path("signingParameters"));
        profile.tags = parseStringMap(request.path("tags"));
        profile.createdAt = now;
        profiles.put(key, profile);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", profile.arn);
        response.put("profileVersion", profile.profileVersion);
        response.put("profileVersionArn", profile.profileVersionArn);
        return response;
    }

    public ObjectNode getSigningProfile(String accountId, String region, String profileName) {
        return profileJson(requireProfile(accountId, region, profileName), true);
    }

    public ObjectNode listSigningProfiles(
            String accountId, String region, Boolean includeCanceled, String platformId,
            String maxResults, String nextToken) {
        boolean include = Boolean.TRUE.equals(includeCanceled);
        List<SigningProfile> matches = profiles.scan(k -> k.startsWith(accountId + ":" + region + ":")).stream()
                .filter(p -> include || "Active".equals(p.status))
                .filter(p -> platformId == null || platformId.isBlank() || platformId.equals(p.platformId))
                .sorted(Comparator.comparing(p -> p.profileName))
                .toList();
        Page<SigningProfile> page = paginate(matches, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("profiles");
        for (SigningProfile profile : page.items) {
            list.add(profileJson(profile, false));
        }
        if (page.nextToken != null) {
            response.put("nextToken", page.nextToken);
        }
        return response;
    }

    public synchronized void cancelSigningProfile(String accountId, String region, String profileName) {
        SigningProfile profile = requireProfile(accountId, region, profileName);
        if (!"Canceled".equals(profile.status)) {
            profile.status = "Canceled";
            profile.statusReason = "Canceled by request";
            profiles.put(profileKey(accountId, region, profileName), profile);
        }
    }

    public synchronized ObjectNode addProfilePermission(
            String accountId, String region, String profileName, JsonNode body) {
        SigningProfile profile = requireProfile(accountId, region, profileName);
        JsonNode request = requireObject(body);
        checkRevision(profile, textOr(request.path("revisionId"), null));
        String statementId = requireText(request, "statementId");
        if (statementId.length() > 64) {
            throw validation("statementId must be 1-64 characters");
        }
        String action = requireText(request, "action");
        if (!ACTIONS.contains(action)) {
            throw validation("action must be a supported signer permission action");
        }
        String principal = requireText(request, "principal");
        if (profile.permissions == null) {
            profile.permissions = new ArrayList<>();
        }
        for (ProfilePermission existing : profile.permissions) {
            if (statementId.equals(existing.statementId)) {
                throw new AwsException("ConflictException",
                        "A statement with id " + statementId + " already exists.", 409);
            }
        }
        if (profile.permissions.size() >= MAX_PERMISSIONS) {
            throw new AwsException("ServiceLimitExceededException",
                    "The signing profile policy is limited to " + MAX_PERMISSIONS + " statements.", 402);
        }
        ProfilePermission permission = new ProfilePermission();
        permission.action = action;
        permission.principal = principal;
        permission.statementId = statementId;
        permission.profileVersion = textOr(request.path("profileVersion"), null);
        profile.permissions.add(permission);
        profile.revisionId = UUID.randomUUID().toString();
        profiles.put(profileKey(accountId, region, profileName), profile);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("revisionId", profile.revisionId);
        return response;
    }

    public ObjectNode listProfilePermissions(String accountId, String region, String profileName) {
        SigningProfile profile = requireProfile(accountId, region, profileName);
        if (profile.permissions == null || profile.permissions.isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "No policies associated with profile " + profileName, 404);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("revisionId", profile.revisionId);
        int size = 0;
        ArrayNode list = response.putArray("permissions");
        for (ProfilePermission permission : profile.permissions) {
            ObjectNode node = list.addObject();
            if (permission.action != null) {
                node.put("action", permission.action);
                size += permission.action.length();
            }
            if (permission.principal != null) {
                node.put("principal", permission.principal);
                size += permission.principal.length();
            }
            if (permission.statementId != null) {
                node.put("statementId", permission.statementId);
                size += permission.statementId.length();
            }
            if (permission.profileVersion != null) {
                node.put("profileVersion", permission.profileVersion);
                size += permission.profileVersion.length();
            }
            size += 64;
        }
        response.put("policySizeBytes", size);
        return response;
    }

    public synchronized ObjectNode removeProfilePermission(
            String accountId, String region, String profileName, String statementId, String revisionId) {
        SigningProfile profile = requireProfile(accountId, region, profileName);
        if (revisionId == null || revisionId.isBlank()) {
            throw validation("revisionId is required");
        }
        checkRevision(profile, revisionId);
        if (profile.permissions == null || profile.permissions.isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "No policies associated with profile " + profileName, 404);
        }
        boolean removed = profile.permissions.removeIf(p -> statementId.equals(p.statementId));
        if (!removed) {
            throw notFound("Statement " + statementId + " not found on profile " + profileName);
        }
        profile.revisionId = UUID.randomUUID().toString();
        profiles.put(profileKey(accountId, region, profileName), profile);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("revisionId", profile.revisionId);
        return response;
    }

    private static void checkRevision(SigningProfile profile, String expectedRevision) {
        if (expectedRevision == null || expectedRevision.isBlank()) {
            return;
        }
        if (profile.revisionId != null && !profile.revisionId.equals(expectedRevision)) {
            throw new AwsException("ConflictException",
                    "The revision id does not match the current policy revision.", 409);
        }
    }

    public synchronized void revokeSigningProfile(
            String accountId, String region, String profileName, JsonNode body) {
        SigningProfile profile = requireProfile(accountId, region, profileName);
        JsonNode request = requireObject(body);
        String version = requireText(request, "profileVersion");
        if (!version.equals(profile.profileVersion)) {
            throw validation("profileVersion does not match the current profile version");
        }
        String reason = requireText(request, "reason");
        long effective = timestamp(request.path("effectiveTime"), Instant.now().getEpochSecond());
        profile.status = "Revoked";
        profile.statusReason = reason;
        profile.revocationReason = reason;
        profile.revokedAt = Instant.now().getEpochSecond();
        profile.revokedBy = accountId;
        profile.revocationEffectiveFrom = effective;
        profiles.put(profileKey(accountId, region, profileName), profile);
    }

    public ObjectNode listSigningPlatforms(
            String category, String partner, String target, String maxResults, String nextToken) {
        List<Platform> matches = PLATFORMS.stream()
                .filter(p -> category == null || category.isBlank() || category.equals(p.category))
                .filter(p -> partner == null || partner.isBlank() || partner.equals(p.partner))
                .filter(p -> target == null || target.isBlank() || target.equals(p.target))
                .toList();
        Page<Platform> page = paginate(matches, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("platforms");
        for (Platform platform : page.items) {
            list.add(platformJson(platform));
        }
        if (page.nextToken != null) {
            response.put("nextToken", page.nextToken);
        }
        return response;
    }

    public ObjectNode getSigningPlatform(String platformId) {
        return platformJson(requirePlatform(platformId));
    }

    public synchronized ObjectNode startSigningJob(
            String accountId, String region, JsonNode body) {
        JsonNode request = requireObject(body);
        String profileName = requireText(request, "profileName");
        String token = requireText(request, "clientRequestToken");
        SigningProfile profile = requireActiveProfile(accountId, region, profileName);
        for (SigningJob existing : jobs.scan(k -> k.startsWith(accountId + ":" + region + ":"))) {
            if (token.equals(existing.clientRequestToken)) {
                return jobIdJson(existing);
            }
        }
        JsonNode s3Source = request.path("source").path("s3");
        String sourceBucket = requireText(s3Source, "bucketName");
        String sourceKey = requireText(s3Source, "key");
        String sourceVersion = requireText(s3Source, "version");
        JsonNode s3Dest = request.path("destination").path("s3");
        String destBucket = requireText(s3Dest, "bucketName");
        String destPrefix = textOr(s3Dest.path("prefix"), "");
        String signedKey = destPrefix + sourceKey;
        try {
            S3Object source = s3Service.getObject(sourceBucket, sourceKey, sourceVersion);
            byte[] data = source.getData() != null ? source.getData() : new byte[0];
            String contentType = source.getContentType() != null ? source.getContentType() : "application/octet-stream";
            Map<String, String> metadata = source.getMetadata() != null ? source.getMetadata() : Map.of();
            s3Service.putObject(destBucket, signedKey, data, contentType, metadata);
        } catch (AwsException e) {
            throw new AwsException("ValidationException",
                    "S3 bucket " + sourceBucket + " not accessible: " + e.getMessage(), 400);
        }
        SigningJob job = newJob(accountId, region, profile, token);
        job.sourceBucket = sourceBucket;
        job.sourceKey = sourceKey;
        job.sourceVersion = sourceVersion;
        job.destBucket = destBucket;
        job.destPrefix = destPrefix;
        job.signedKey = signedKey;
        completeJob(job, profile);
        jobs.put(jobKey(accountId, region, job.jobId), job);
        return jobIdJson(job);
    }

    public ObjectNode describeSigningJob(String accountId, String region, String jobId) {
        return jobJson(requireJob(accountId, region, jobId), true);
    }

    public ObjectNode listSigningJobs(
            String accountId, String region, String status, String platformId, String maxResults, String nextToken) {
        List<SigningJob> matches = jobs.scan(k -> k.startsWith(accountId + ":" + region + ":")).stream()
                .filter(j -> status == null || status.isBlank() || status.equals(j.status))
                .filter(j -> platformId == null || platformId.isBlank() || platformId.equals(j.platformId))
                .sorted(Comparator.comparing((SigningJob j) -> j.createdAt).reversed())
                .toList();
        Page<SigningJob> page = paginate(matches, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("jobs");
        for (SigningJob job : page.items) {
            list.add(jobJson(job, false));
        }
        if (page.nextToken != null) {
            response.put("nextToken", page.nextToken);
        }
        return response;
    }

    public synchronized void revokeSignature(String accountId, String region, String jobId, JsonNode body) {
        SigningJob job = requireJob(accountId, region, jobId);
        if (!"Succeeded".equals(job.status)) {
            throw validation("Only succeeded signing jobs can be revoked");
        }
        JsonNode request = body == null || body.isNull() || body.isMissingNode()
                ? objectMapper.createObjectNode()
                : requireObject(body);
        String reason = requireText(request, "reason");
        job.revoked = true;
        job.revokeReason = reason;
        job.revokedAt = Instant.now().getEpochSecond();
        job.revokedBy = accountId;
        jobs.put(jobKey(accountId, region, jobId), job);
    }

    public synchronized ObjectNode signPayload(String accountId, String region, JsonNode body) {
        JsonNode request = requireObject(body);
        String profileName = requireText(request, "profileName");
        SigningProfile profile = requireActiveProfile(accountId, region, profileName);
        if (profile.platformId.startsWith("AWSLambda-")) {
            throw validation("SignPayload is not supported for platform " + profile.platformId);
        }
        String format = requireText(request, "payloadFormat");
        byte[] payload = readBlob(request.get("payload"));
        if (payload.length == 0) {
            throw validation("payload must not be empty");
        }
        String token = "payload-" + UUID.randomUUID();
        SigningJob job = newJob(accountId, region, profile, token);
        job.signature = sha384(payload);
        completeJob(job, profile);
        jobs.put(jobKey(accountId, region, job.jobId), job);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobId", job.jobId);
        response.put("jobOwner", job.jobOwner);
        response.put("signature", Base64.getEncoder().encodeToString(job.signature));
        ObjectNode metadata = response.putObject("metadata");
        metadata.put("payloadFormat", format);
        return response;
    }

    public ObjectNode getRevocationStatus(
            String accountId, String region, String platformId, String profileVersionArn,
            String jobArn, List<String> certificateHashes) {
        if (platformId == null || platformId.isBlank()) {
            throw validation("platformId is required");
        }
        if (profileVersionArn == null || profileVersionArn.isBlank()) {
            throw validation("profileVersionArn is required");
        }
        if (jobArn == null || jobArn.isBlank()) {
            throw validation("jobArn is required");
        }
        List<String> revoked = new ArrayList<>();
        String jobId = jobIdFromArn(jobArn);
        if (jobId != null) {
            jobs.get(jobKey(accountId, region, jobId)).ifPresent(job -> {
                if (job.revoked) {
                    revoked.add(jobArn);
                }
            });
        }
        String profileName = profileNameFromArn(profileVersionArn);
        if (profileName != null) {
            profiles.get(profileKey(accountId, region, profileName)).ifPresent(profile -> {
                if ("Revoked".equals(profile.status)) {
                    revoked.add(profileVersionArn);
                }
            });
        }
        if (certificateHashes != null) {
            for (String hash : certificateHashes) {
                if (hash != null && !hash.isBlank()) {
                    // Emulator does not persist certificate material; hashes never match.
                }
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entities = response.putArray("revokedEntities");
        for (String arn : revoked) {
            entities.add(arn);
        }
        return response;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = requireProfileByArn(region, arn).tags;
        return tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        SigningProfile profile = requireProfileByArn(region, arn);
        if (profile.tags == null) {
            profile.tags = new LinkedHashMap<>();
        }
        if (tags != null) {
            profile.tags.putAll(tags);
        }
        profiles.put(profileKey(profile.accountId, profile.region, profile.profileName), profile);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        SigningProfile profile = requireProfileByArn(region, arn);
        if (profile.tags == null) {
            profile.tags = new LinkedHashMap<>();
        }
        if (tagKeys != null) {
            tagKeys.forEach(profile.tags::remove);
        }
        profiles.put(profileKey(profile.accountId, profile.region, profile.profileName), profile);
    }

    private SigningProfile requireProfileByArn(String region, String arn) {
        if (arn == null || arn.isBlank()) {
            throw validation("resourceArn is required");
        }
        String profileName = profileNameFromArn(arn);
        if (profileName == null) {
            throw notFound("Signing profile not found");
        }
        String accountId = accountFromArn(arn);
        return requireProfile(accountId, region, profileName);
    }

    private SigningProfile requireProfile(String accountId, String region, String profileName) {
        requireProfileName(profileName);
        return profiles.get(profileKey(accountId, region, profileName))
                .orElseThrow(() -> notFound("Signing profile " + profileName + " not found"));
    }

    private SigningProfile requireActiveProfile(String accountId, String region, String profileName) {
        SigningProfile profile = requireProfile(accountId, region, profileName);
        if (!"Active".equals(profile.status)) {
            throw validation("Signing profile " + profileName + " is not Active");
        }
        return profile;
    }

    private SigningJob requireJob(String accountId, String region, String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw validation("jobId is required");
        }
        return jobs.get(jobKey(accountId, region, jobId))
                .orElseThrow(() -> notFound("Signing job " + jobId + " not found"));
    }

    private Platform requirePlatform(String platformId) {
        if (platformId == null || platformId.isBlank()) {
            throw validation("platformId is required");
        }
        return PLATFORMS.stream()
                .filter(p -> p.id.equals(platformId))
                .findFirst()
                .orElseThrow(() -> notFound("Signing platform " + platformId + " not found"));
    }

    private SigningJob newJob(String accountId, String region, SigningProfile profile, String token) {
        long now = Instant.now().getEpochSecond();
        SigningJob job = new SigningJob();
        job.accountId = accountId;
        job.region = region;
        job.jobId = UUID.randomUUID().toString();
        job.jobOwner = accountId;
        job.jobInvoker = accountId;
        job.clientRequestToken = token;
        job.profileName = profile.profileName;
        job.profileVersion = profile.profileVersion;
        job.platformId = profile.platformId;
        job.platformDisplayName = profile.platformDisplayName;
        job.status = "InProgress";
        job.createdAt = now;
        return job;
    }

    private void completeJob(SigningJob job, SigningProfile profile) {
        long now = Instant.now().getEpochSecond();
        job.status = "Succeeded";
        job.completedAt = now;
        job.signatureExpiresAt = expireAt(now, profile);
    }

    private long expireAt(long now, SigningProfile profile) {
        int value = profile.signatureValidityValue == null ? 135 : profile.signatureValidityValue;
        String type = profile.signatureValidityType == null ? "MONTHS" : profile.signatureValidityType;
        Instant base = Instant.ofEpochSecond(now);
        Instant expires = switch (type.toUpperCase(Locale.ROOT)) {
            case "DAYS" -> base.plus(value, ChronoUnit.DAYS);
            case "YEARS" -> base.plus(value * 365L, ChronoUnit.DAYS);
            default -> base.plus(value * 30L, ChronoUnit.DAYS);
        };
        return expires.getEpochSecond();
    }

    private ObjectNode profileJson(SigningProfile profile, boolean detailed) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("profileName", profile.profileName);
        node.put("profileVersion", profile.profileVersion);
        node.put("profileVersionArn", profile.profileVersionArn);
        node.put("platformId", profile.platformId);
        node.put("platformDisplayName", profile.platformDisplayName);
        node.put("status", profile.status);
        node.put("arn", profile.arn);
        ObjectNode validity = node.putObject("signatureValidityPeriod");
        validity.put("value", profile.signatureValidityValue == null ? 135 : profile.signatureValidityValue);
        validity.put("type", profile.signatureValidityType == null ? "MONTHS" : profile.signatureValidityType);
        if (profile.certificateArn != null) {
            node.putObject("signingMaterial").put("certificateArn", profile.certificateArn);
        }
        if (profile.signingParameters != null && !profile.signingParameters.isEmpty()) {
            ObjectNode parameters = node.putObject("signingParameters");
            profile.signingParameters.forEach(parameters::put);
        }
        if (profile.overrides != null && profile.overrides.isObject()) {
            node.set("overrides", profile.overrides);
        }
        if (profile.tags != null && !profile.tags.isEmpty()) {
            ObjectNode tags = node.putObject("tags");
            profile.tags.forEach(tags::put);
        } else if (detailed) {
            node.putObject("tags");
        }
        if (profile.statusReason != null) {
            node.put("statusReason", profile.statusReason);
        }
        if (profile.revokedAt != null) {
            ObjectNode revocation = node.putObject("revocationRecord");
            revocation.put("revokedAt", profile.revokedAt);
            if (profile.revokedBy != null) {
                revocation.put("revokedBy", profile.revokedBy);
            }
            if (profile.revocationEffectiveFrom != null) {
                revocation.put("revocationEffectiveFrom", profile.revocationEffectiveFrom);
            }
        }
        return node;
    }

    private ObjectNode jobJson(SigningJob job, boolean detailed) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jobId", job.jobId);
        node.put("status", job.status);
        node.put("profileName", job.profileName);
        node.put("profileVersion", job.profileVersion);
        node.put("platformId", job.platformId);
        node.put("platformDisplayName", job.platformDisplayName);
        node.put("jobOwner", job.jobOwner);
        node.put("jobInvoker", job.jobInvoker);
        node.put("createdAt", job.createdAt);
        node.put("isRevoked", job.revoked);
        if (job.completedAt != null) {
            node.put("completedAt", job.completedAt);
        }
        if (job.signatureExpiresAt != null) {
            node.put("signatureExpiresAt", job.signatureExpiresAt);
        }
        if (job.statusReason != null) {
            node.put("statusReason", job.statusReason);
        }
        if (job.sourceBucket != null) {
            ObjectNode s3 = node.putObject("source").putObject("s3");
            s3.put("bucketName", job.sourceBucket);
            s3.put("key", job.sourceKey);
            s3.put("version", job.sourceVersion);
        }
        if (job.signedKey != null) {
            ObjectNode s3 = node.putObject("signedObject").putObject("s3");
            s3.put("bucketName", job.destBucket);
            s3.put("key", job.signedKey);
        }
        if (job.revoked) {
            ObjectNode revocation = node.putObject("revocationRecord");
            revocation.put("reason", job.revokeReason);
            if (job.revokedAt != null) {
                revocation.put("revokedAt", job.revokedAt);
            }
            if (job.revokedBy != null) {
                revocation.put("revokedBy", job.revokedBy);
            }
        }
        if (detailed) {
            node.put("requestedBy", job.jobInvoker);
        }
        return node;
    }

    private ObjectNode jobIdJson(SigningJob job) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jobId", job.jobId);
        response.put("jobOwner", job.jobOwner);
        return response;
    }

    private ObjectNode platformJson(Platform platform) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("platformId", platform.id);
        node.put("displayName", platform.displayName);
        node.put("partner", platform.partner);
        node.put("target", platform.target);
        node.put("category", platform.category);
        node.put("maxSizeInMB", platform.maxSizeInMb);
        node.put("revocationSupported", platform.revocationSupported);
        ObjectNode configuration = node.putObject("signingConfiguration");
        ObjectNode encryption = configuration.putObject("encryptionAlgorithmOptions");
        encryption.putArray("allowedValues").add(platform.encryption);
        encryption.put("defaultValue", platform.encryption);
        ObjectNode hash = configuration.putObject("hashAlgorithmOptions");
        hash.putArray("allowedValues").add(platform.hash);
        hash.put("defaultValue", platform.hash);
        ObjectNode image = node.putObject("signingImageFormat");
        image.putArray("supportedFormats").add("JSONDetached");
        image.put("defaultFormat", "JSONDetached");
        return node;
    }

    private static String profileKey(String accountId, String region, String profileName) {
        return accountId + ":" + region + ":" + profileName;
    }

    private static String jobKey(String accountId, String region, String jobId) {
        return accountId + ":" + region + ":" + jobId;
    }

    static String profileArn(String region, String accountId, String profileName) {
        return "arn:aws:signer:" + region + ":" + accountId + ":/signing-profiles/" + profileName;
    }

    private static String jobIdFromArn(String arn) {
        int idx = arn.indexOf(":/signing-jobs/");
        if (idx < 0) {
            return null;
        }
        return arn.substring(idx + ":/signing-jobs/".length());
    }

    private static String profileNameFromArn(String arn) {
        int idx = arn.indexOf(":/signing-profiles/");
        if (idx < 0) {
            return null;
        }
        String rest = arn.substring(idx + ":/signing-profiles/".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String accountFromArn(String arn) {
        String[] parts = arn.split(":", 6);
        if (parts.length < 5) {
            throw validation("Invalid signing profile ARN");
        }
        return parts[4];
    }

    private static void requireProfileName(String profileName) {
        if (profileName == null || !PROFILE_NAME.matcher(profileName).matches()) {
            throw validation("profileName must match ^[a-zA-Z0-9_]{2,64}$");
        }
    }

    private JsonNode requireObject(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return objectMapper.createObjectNode();
        }
        if (!body.isObject()) {
            throw validation("Request body must be a JSON object.");
        }
        return body;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw validation(field + " is required");
        }
        return value.asText();
    }

    private static String textOr(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return fallback;
        }
        return node.asText();
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return map;
        }
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) {
                map.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return map;
    }

    private static byte[] readBlob(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw validation("payload is required");
        }
        try {
            if (node.isBinary()) {
                return node.binaryValue();
            }
            String text = node.asText();
            try {
                return Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException ignored) {
                return text.getBytes(StandardCharsets.UTF_8);
            }
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("payload is not valid");
        }
    }

    private static byte[] sha384(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-384").digest(payload);
        } catch (Exception e) {
            return payload;
        }
    }

    private static String newVersion() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private static long timestamp(JsonNode node, long fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Instant.parse(node.asText()).getEpochSecond();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static <T> Page<T> paginate(List<T> items, String maxResults, String nextToken) {
        int max = parseMaxResults(maxResults);
        int offset = decodeToken(nextToken);
        if (offset > items.size()) {
            throw validation("nextToken is invalid");
        }
        int end = Math.min(items.size(), offset + max);
        String token = end < items.size() ? encodeToken(end) : null;
        return new Page<>(items.subList(offset, end), token);
    }

    private static int parseMaxResults(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_RESULTS;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer");
        }
        if (value < 1 || value > MAX_RESULTS) {
            throw validation("maxResults must be between 1 and " + MAX_RESULTS);
        }
        return value;
    }

    private static int decodeToken(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        if (!nextToken.startsWith(TOKEN_PREFIX)) {
            throw validation("nextToken is invalid");
        }
        try {
            return Integer.parseInt(nextToken.substring(TOKEN_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw validation("nextToken is invalid");
        }
    }

    private static String encodeToken(int offset) {
        return TOKEN_PREFIX + offset;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static Platform platform(
            String id, String displayName, String partner, String target,
            String encryption, String hash, boolean revocationSupported, int maxSizeInMb) {
        return new Platform(id, displayName, partner, target, "AWSIoT", encryption, hash,
                revocationSupported, maxSizeInMb);
    }

    private record Platform(
            String id,
            String displayName,
            String partner,
            String target,
            String category,
            String encryption,
            String hash,
            boolean revocationSupported,
            int maxSizeInMb) {
    }

    private record Page<T>(List<T> items, String nextToken) {
    }
}
