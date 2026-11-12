package io.floci.aws.services.signer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.lambda.CodeSignatureVerifier;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Signer: the platform catalog, signing profiles with their own ECDSA P-384 key and certificate, cross-account
 * permissions, signing jobs that read a versioned S3 object and write the signed artifact, synchronous Notation
 * payload signing, and revocation of signatures and profiles.
 *
 * <p>Lambda code signing checks packages through this service: a signed .zip carries its signature in the archive
 * comment, and a function whose code signing configuration allows the signing profile version deploys it only while
 * the signature matches the code, the profile and the job aren't revoked and the signature hasn't expired. Job
 * status changes are published to the default event bus as {@code Signer Job Status Change}.
 */
@ApplicationScoped
public class SignerService implements Resettable, CodeSignatureVerifier {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ACTIONS = Set.of("signer:StartSigningJob", "signer:SignPayload",
            "signer:GetSigningProfile", "signer:RevokeSignature");
    private static final String NOTARY_PAYLOAD = "application/vnd.cncf.notary.payload.v1+json";

    /** The platforms Floci signs for, and the IoT platforms profiles can name with their own certificate. */
    private static final List<String[]> PLATFORMS = List.of(
            new String[]{"AWSLambda-SHA384-ECDSA", "AWS Lambda", "AWSLambda", "SHA384-ECDSA", "250", "true",
                    "JSONDetached"},
            new String[]{"Notation-OCI-SHA384-ECDSA", "Notation for OCI registries", "AWSSigner", "SHA384-ECDSA",
                    "4", "true", "JSONEmbedded"},
            new String[]{"AWSIoTDeviceManagement-SHA256-ECDSA", "AWS IoT Device Management SHA256-ECDSA",
                    "AWSIoTDeviceManagement", "SHA256-ECDSA", "2048", "false", "JSONDetached"},
            new String[]{"AmazonFreeRTOS-TI-CC3220SF", "Amazon FreeRTOS SHA1-RSA CC3220SF-Format",
                    "AmazonFreeRTOS", "SHA1-RSA-TISHA1", "16", "false", "JSONEmbedded"},
            new String[]{"AmazonFreeRTOS-Default", "Amazon FreeRTOS SHA256-ECDSA", "AmazonFreeRTOS",
                    "SHA256-ECDSA", "16", "false", "JSONEmbedded"});

    private final AccountAwareStorageBackend<ObjectNode> profiles;
    private final AccountAwareStorageBackend<ObjectNode> jobs;
    private final ObjectMapper mapper;
    private final Provider<S3Service> s3;
    private final Provider<AcmService> acm;
    private final Provider<EventBridgeService> events;

    @Inject
    public SignerService(StorageFactory storageFactory, ObjectMapper mapper, Provider<S3Service> s3,
                         Provider<AcmService> acm, Provider<EventBridgeService> events) {
        this.profiles = storageFactory.create("signer", "profiles.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.jobs = storageFactory.create("signer", "jobs.json", new TypeReference<Map<String, ObjectNode>>() {});
        this.mapper = mapper;
        this.s3 = s3;
        this.acm = acm;
        this.events = events;
    }

    @Override
    public void clear() {
        profiles.clear();
        jobs.clear();
    }

    public ObjectNode handle(String operation, JsonNode in, String region) {
        return switch (operation) {
            case "ListSigningPlatforms" -> listPlatforms(in);
            case "GetSigningPlatform" -> platform(name(in, "platformId"));
            case "PutSigningProfile" -> putProfile(in, region);
            case "GetSigningProfile" -> profileView(profile(region, name(in, "profileName")));
            case "ListSigningProfiles" -> listProfiles(in, region);
            case "CancelSigningProfile" -> cancelProfile(in, region);
            case "RevokeSigningProfile" -> revokeProfile(in, region);
            case "AddProfilePermission" -> addPermission(in, region);
            case "RemoveProfilePermission" -> removePermission(in, region);
            case "ListProfilePermissions" -> listPermissions(in, region);
            case "StartSigningJob" -> startJob(in, region);
            case "DescribeSigningJob" -> describeJob(in, region);
            case "ListSigningJobs" -> listJobs(in, region);
            case "RevokeSignature" -> revokeSignature(in, region);
            case "GetRevocationStatus" -> revocationStatus(in);
            case "SignPayload" -> signPayload(in, region);
            case "TagResource" -> tag(in, region, true);
            case "UntagResource" -> tag(in, region, false);
            case "ListTagsForResource" -> listTags(in, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation " + operation + ".",
                    404);
        };
    }

    // ---------- for CloudFormation ----------

    /** Creates a profile for {@code AWS::Signer::SigningProfile}; returns its version, as PutSigningProfile does. */
    public String createProfileForStack(String region, String name, String platformId, String validityType,
                                        Integer validityValue) {
        ObjectNode in = mapper.createObjectNode().put("profileName", name).put("platformId", platformId);
        if (validityType != null && validityValue != null) {
            in.putObject("signatureValidityPeriod").put("type", validityType).put("value", validityValue);
        }
        return putProfile(in, region).path("profileVersion").asText();
    }

    /** Cancels a stack's profile; one already gone is left alone. */
    public void cancelProfileForStack(String region, String name) {
        profiles.get(key(region, name)).ifPresent(profile -> {
            ObjectNode updated = profile.deepCopy();
            updated.put("status", "Canceled");
            profiles.put(key(region, name), updated);
        });
    }

    // ---------- platforms ----------

    private ObjectNode platformNode(String[] platform) {
        ObjectNode node = mapper.createObjectNode().put("platformId", platform[0]).put("displayName", platform[1])
                .put("partner", platform[2]).put("target", platform[3]).put("category", "AWSIoT")
                .put("maxSizeInMB", Integer.parseInt(platform[4]))
                .put("revocationSupported", Boolean.parseBoolean(platform[5]));
        ObjectNode configuration = node.putObject("signingConfiguration");
        boolean rsa = platform[3].contains("RSA");
        configuration.putObject("encryptionAlgorithmOptions").put("defaultValue", rsa ? "RSA" : "ECDSA")
                .putArray("allowedValues").add(rsa ? "RSA" : "ECDSA");
        boolean sha1 = platform[3].startsWith("SHA1");
        configuration.putObject("hashAlgorithmOptions").put("defaultValue", sha1 ? "SHA1" : "SHA256")
                .putArray("allowedValues").add(sha1 ? "SHA1" : "SHA256");
        node.putObject("signingImageFormat").put("defaultFormat", platform[6]).putArray("supportedFormats")
                .add(platform[6]);
        return node;
    }

    private ObjectNode platform(String id) {
        for (String[] platform : PLATFORMS) {
            if (platform[0].equals(id)) {
                return platformNode(platform);
            }
        }
        throw notFound("Platform " + id + " does not exist.");
    }

    private ObjectNode listPlatforms(JsonNode in) {
        ArrayNode out = mapper.createArrayNode();
        for (String[] platform : PLATFORMS) {
            if ((!in.hasNonNull("partner") || in.get("partner").asText().equals(platform[2]))
                    && (!in.hasNonNull("target") || in.get("target").asText().equals(platform[3]))) {
                out.add(platformNode(platform));
            }
        }
        return mapper.createObjectNode().set("platforms", out);
    }

    // ---------- profiles ----------

    private String profileArn(String region, String name) {
        return AwsArnUtils.Arn.of("signer", region, profiles.accountId(), "/signing-profiles/" + name).toString();
    }

    private ObjectNode putProfile(JsonNode in, String region) {
        String name = name(in, "profileName");
        if (profiles.get(key(region, name)).isPresent()) {
            throw new AwsException("ValidationException", "A signing profile named " + name + " already exists.",
                    400);
        }
        String platformId = in.path("platformId").asText();
        ObjectNode platform = platform(platformId);
        boolean managed = platformId.startsWith("AWSLambda") || platformId.startsWith("Notation");
        if (!managed) {
            String certificate = in.path("signingMaterial").path("certificateArn").asText(null);
            if (certificate == null) {
                throw new AwsException("ValidationException", "The " + platformId + " platform needs signingMaterial "
                        + "with a certificateArn.", 400);
            }
            try {
                acm.get().describeCertificate(certificate, region);
            } catch (AwsException e) {
                throw new AwsException("ValidationException", "The certificate " + certificate + " does not exist.",
                        400);
            }
        }
        JsonNode validity = in.path("signatureValidityPeriod");
        int value = validity.path("value").asInt(135);
        String type = validity.path("type").asText("MONTHS");
        if (value < 1 || ("DAYS".equals(type) && value > 4380) || ("MONTHS".equals(type) && value > 135)
                || ("YEARS".equals(type) && value > 11)) {
            throw new AwsException("ValidationException", "The signature validity period can be at most 135 months.",
                    400);
        }
        String version = random(10);
        String arn = profileArn(region, name);
        ObjectNode profile = mapper.createObjectNode();
        profile.put("profileName", name).put("profileVersion", version).put("profileVersionArn", arn + "/" + version)
                .put("arn", arn).put("platformId", platformId)
                .put("platformDisplayName", platform.path("displayName").asText()).put("status", "Active");
        profile.putObject("signatureValidityPeriod").put("value", value).put("type", type);
        for (String field : List.of("signingMaterial", "overrides", "signingParameters")) {
            if (in.has(field)) {
                profile.set(field, in.get(field).deepCopy());
            }
        }
        profile.set("tags", in.path("tags").isObject() ? in.get("tags").deepCopy() : mapper.createObjectNode());
        SignerCrypto.KeyMaterial key = SignerCrypto.newKey(name + " " + version);
        profile.putObject("_").put("privateKey", key.privateKey()).put("certificate", key.certificate())
                .put("revisionId", "").putArray("permissions");
        profiles.put(key(region, name), profile);
        return mapper.createObjectNode().put("arn", arn).put("profileVersion", version)
                .put("profileVersionArn", arn + "/" + version);
    }

    private ObjectNode profile(String region, String name) {
        return profiles.get(key(region, name)).map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("Signing profile " + name + " does not exist."));
    }

    private ObjectNode profileView(ObjectNode profile) {
        ObjectNode view = profile.deepCopy();
        view.remove("_");
        return view;
    }

    private ObjectNode listProfiles(JsonNode in, String region) {
        boolean includeCanceled = in.path("includeCanceled").asBoolean(false);
        List<String> statuses = new ArrayList<>();
        in.path("statuses").forEach(s -> statuses.add(s.asText()));
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode profile : profiles.scan(k -> k.startsWith(region + "/"))) {
            String status = profile.path("status").asText();
            if ("Canceled".equals(status) && !includeCanceled && !statuses.contains("Canceled")) {
                continue;
            }
            if (!statuses.isEmpty() && !statuses.contains(status)) {
                continue;
            }
            if (in.hasNonNull("platformId") && !in.get("platformId").asText()
                    .equals(profile.path("platformId").asText())) {
                continue;
            }
            ObjectNode item = profileView(profile);
            item.remove("revocationRecord");
            items.add(item);
        }
        items.sort(Comparator.comparing(item -> item.path("profileName").asText()));
        return page(items, in, "profiles");
    }

    private ObjectNode cancelProfile(JsonNode in, String region) {
        ObjectNode profile = profile(region, name(in, "profileName"));
        profile.put("status", "Canceled");
        profiles.put(key(region, profile.path("profileName").asText()), profile);
        return mapper.createObjectNode();
    }

    private ObjectNode revokeProfile(JsonNode in, String region) {
        ObjectNode profile = profile(region, name(in, "profileName"));
        if (!profile.path("profileVersion").asText().equals(in.path("profileVersion").asText())) {
            throw notFound("Profile version " + in.path("profileVersion").asText() + " does not exist.");
        }
        if (!platform(profile.path("platformId").asText()).path("revocationSupported").asBoolean()) {
            throw new AwsException("ValidationException", "The " + profile.path("platformId").asText()
                    + " platform doesn't support revocation.", 400);
        }
        long effective = seconds(in.get("effectiveTime"));
        long now = Instant.now().getEpochSecond();
        if (effective > now) {
            throw new AwsException("ValidationException", "effectiveTime can't be in the future.", 400);
        }
        profile.put("status", "Revoked").put("statusReason", in.path("reason").asText());
        profile.putObject("revocationRecord").put("revocationEffectiveFrom", effective).put("revokedAt", now)
                .put("revokedBy", caller(region));
        profiles.put(key(region, profile.path("profileName").asText()), profile);
        return mapper.createObjectNode();
    }

    // ---------- permissions ----------

    private ObjectNode addPermission(JsonNode in, String region) {
        ObjectNode profile = profile(region, name(in, "profileName"));
        ObjectNode meta = (ObjectNode) profile.get("_");
        requireRevision(meta, in.path("revisionId").asText(null));
        String action = in.path("action").asText();
        if (!ACTIONS.contains(action)) {
            throw new AwsException("ValidationException", "action must be one of " + String.join(", ", ACTIONS)
                    + ".", 400);
        }
        String statementId = in.path("statementId").asText();
        ArrayNode permissions = (ArrayNode) meta.get("permissions");
        for (JsonNode permission : permissions) {
            if (statementId.equals(permission.path("statementId").asText())) {
                throw new AwsException("ConflictException", "Statement " + statementId + " already exists.", 409);
            }
        }
        ObjectNode permission = permissions.addObject().put("action", action)
                .put("principal", in.path("principal").asText()).put("statementId", statementId);
        if (in.hasNonNull("profileVersion")) {
            permission.put("profileVersion", in.get("profileVersion").asText());
        }
        meta.put("revisionId", UUID.randomUUID().toString());
        profiles.put(key(region, profile.path("profileName").asText()), profile);
        return mapper.createObjectNode().put("revisionId", meta.path("revisionId").asText());
    }

    private void requireRevision(ObjectNode meta, String revisionId) {
        String current = meta.path("revisionId").asText();
        if (revisionId != null && !revisionId.equals(current)) {
            throw new AwsException("ConflictException", "The policy has changed; its revision is no longer "
                    + revisionId + ".", 409);
        }
    }

    private ObjectNode removePermission(JsonNode in, String region) {
        ObjectNode profile = profile(region, name(in, "profileName"));
        ObjectNode meta = (ObjectNode) profile.get("_");
        requireRevision(meta, in.path("revisionId").asText(null));
        String statementId = name(in, "statementId");
        ArrayNode permissions = (ArrayNode) meta.get("permissions");
        boolean removed = false;
        for (int i = permissions.size() - 1; i >= 0; i--) {
            if (statementId.equals(permissions.get(i).path("statementId").asText())) {
                permissions.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            throw notFound("Statement " + statementId + " does not exist.");
        }
        meta.put("revisionId", permissions.isEmpty() ? "" : UUID.randomUUID().toString());
        profiles.put(key(region, profile.path("profileName").asText()), profile);
        return mapper.createObjectNode().put("revisionId", meta.path("revisionId").asText());
    }

    private ObjectNode listPermissions(JsonNode in, String region) {
        ObjectNode meta = (ObjectNode) profile(region, name(in, "profileName")).get("_");
        ObjectNode out = mapper.createObjectNode();
        if (!meta.path("revisionId").asText().isEmpty()) {
            out.put("revisionId", meta.path("revisionId").asText());
        }
        out.set("permissions", meta.get("permissions").deepCopy());
        out.put("policySizeBytes", meta.get("permissions").toString().length());
        return out;
    }

    // ---------- signing jobs ----------

    private ObjectNode activeProfile(String region, String name) {
        ObjectNode profile = profile(region, name);
        if (!"Active".equals(profile.path("status").asText())) {
            throw new AwsException("ValidationException", "The signing profile " + name + " is "
                    + profile.path("status").asText() + ".", 400);
        }
        return profile;
    }

    private ObjectNode startJob(JsonNode in, String region) {
        String token = in.path("clientRequestToken").asText();
        for (ObjectNode existing : jobs.scan(k -> true)) {
            if (token.equals(existing.path("_").path("token").asText())) {
                return mapper.createObjectNode().put("jobId", existing.path("jobId").asText())
                        .put("jobOwner", existing.path("jobOwner").asText());
            }
        }
        ObjectNode profile = activeProfile(region, in.path("profileName").asText());
        if (!profile.path("platformId").asText().startsWith("AWSLambda")) {
            throw new AwsException("ValidationException", "Signing jobs sign code for the AWSLambda platform; the "
                    + profile.path("platformId").asText() + " platform isn't supported by Floci's signer.", 400);
        }
        JsonNode source = in.path("source").path("s3");
        JsonNode destination = in.path("destination").path("s3");
        String bucket = source.path("bucketName").asText();
        if (!"Enabled".equals(bucketVersioning(bucket))) {
            throw new AwsException("ValidationException", "The source bucket " + bucket + " must have versioning "
                    + "enabled.", 400);
        }
        byte[] code;
        try (S3Service.ObjectRead read = s3.get().openObject(bucket, source.path("key").asText(),
                source.path("version").asText())) {
            code = read.body().readAllBytes();
        } catch (AwsException e) {
            throw new AwsException("ValidationException", "The source object s3://" + bucket + "/"
                    + source.path("key").asText() + " version " + source.path("version").asText()
                    + " does not exist.", 400);
        } catch (IOException e) {
            throw new AwsException("InternalServiceErrorException", "The source object couldn't be read.", 500);
        }
        if (!s3.get().bucketExists(destination.path("bucketName").asText())) {
            throw new AwsException("ValidationException", "The destination bucket "
                    + destination.path("bucketName").asText() + " does not exist.", 400);
        }
        String id = UUID.randomUUID().toString();
        String account = jobs.accountId();
        long now = Instant.now().getEpochSecond();
        ObjectNode job = mapper.createObjectNode();
        job.put("jobId", id).put("jobOwner", account).put("jobInvoker", account)
                .put("profileName", profile.path("profileName").asText())
                .put("profileVersion", profile.path("profileVersion").asText())
                .put("platformId", profile.path("platformId").asText())
                .put("platformDisplayName", profile.path("platformDisplayName").asText())
                .put("createdAt", now).put("requestedBy", caller(region)).put("status", "InProgress");
        job.set("source", in.path("source").deepCopy());
        job.putObject("_").put("token", token).put("region", region);
        String jobArn = jobArn(region, id);
        String statusReason;
        if (!SignerCrypto.isZip(code)) {
            job.put("status", "Failed");
            statusReason = "The source object isn't a .zip archive.";
            job.put("statusReason", statusReason);
        } else {
            SignerCrypto.KeyMaterial key = keyOf(profile);
            byte[] signature = SignerCrypto.sign(key, SignerCrypto.signedRange(code));
            byte[] signed = SignerCrypto.embed(code, new SignerCrypto.Envelope(
                    profile.path("profileVersionArn").asText(), jobArn, key.certificate(), signature), mapper);
            String signedKey = destination.path("prefix").asText("") + id + ".zip";
            s3.get().putObject(destination.path("bucketName").asText(), signedKey, signed, "application/zip",
                    Map.of());
            job.put("status", "Succeeded").put("completedAt", now).put("statusReason", "Signing Succeeded")
                    .put("signatureExpiresAt", expiry(profile, now));
            job.putObject("signedObject").putObject("s3").put("bucketName", destination.path("bucketName")
                    .asText()).put("key", signedKey);
        }
        jobs.put(id, job);
        publish(job, "Started", region);
        publish(job, job.path("status").asText(), region);
        return mapper.createObjectNode().put("jobId", id).put("jobOwner", account);
    }

    private String bucketVersioning(String bucket) {
        try {
            return s3.get().getBucketVersioning(bucket);
        } catch (AwsException e) {
            throw new AwsException("ValidationException", "The source bucket " + bucket + " does not exist.", 400);
        }
    }

    private long expiry(ObjectNode profile, long from) {
        JsonNode validity = profile.path("signatureValidityPeriod");
        int value = validity.path("value").asInt(135);
        ZonedDateTime start = Instant.ofEpochSecond(from).atZone(ZoneOffset.UTC);
        return switch (validity.path("type").asText("MONTHS")) {
            case "DAYS" -> start.plusDays(value).toEpochSecond();
            case "YEARS" -> start.plusYears(value).toEpochSecond();
            default -> start.plusMonths(value).toEpochSecond();
        };
    }

    private void publish(ObjectNode job, String status, String region) {
        ObjectNode detail = mapper.createObjectNode().put("status", status).put("job_id", job.path("jobId").asText())
                .put("profile_name", job.path("profileName").asText())
                .put("platform_id", job.path("platformId").asText());
        events.get().publishServiceEvent("aws.signer", "Signer Job Status Change", detail.toString(),
                jobArn(region, job.path("jobId").asText()), region);
    }

    private String jobArn(String region, String id) {
        return AwsArnUtils.Arn.of("signer", region, jobs.accountId(), "/signing-jobs/" + id).toString();
    }

    private ObjectNode job(String id) {
        return jobs.get(id).map(ObjectNode::deepCopy).orElseThrow(() -> notFound("Job " + id + " does not exist."));
    }

    private ObjectNode jobView(ObjectNode job) {
        ObjectNode view = job.deepCopy();
        view.remove("_");
        return view;
    }

    private ObjectNode describeJob(JsonNode in, String region) {
        ObjectNode job = jobView(job(name(in, "jobId")));
        ObjectNode profile = profiles.get(key(region, job.path("profileName").asText())).orElse(null);
        if (profile != null && profile.has("signingMaterial")) {
            job.set("signingMaterial", profile.get("signingMaterial").deepCopy());
        }
        return job;
    }

    private ObjectNode listJobs(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode job : jobs.scan(k -> true)) {
            if (in.hasNonNull("status") && !in.get("status").asText().equals(job.path("status").asText())) {
                continue;
            }
            if (in.hasNonNull("platformId") && !in.get("platformId").asText()
                    .equals(job.path("platformId").asText())) {
                continue;
            }
            if (in.has("isRevoked") && in.get("isRevoked").asBoolean() != job.has("revocationRecord")) {
                continue;
            }
            ObjectNode item = jobView(job);
            item.put("isRevoked", job.has("revocationRecord"));
            item.remove("revocationRecord");
            items.add(item);
        }
        items.sort(Comparator.comparingLong((ObjectNode item) -> item.path("createdAt").asLong()).reversed());
        return page(items, in, "jobs");
    }

    private ObjectNode revokeSignature(JsonNode in, String region) {
        ObjectNode job = job(name(in, "jobId"));
        if (!"Succeeded".equals(job.path("status").asText())) {
            throw new AwsException("ValidationException", "Only a succeeded job's signature can be revoked.", 400);
        }
        if (!platform(job.path("platformId").asText()).path("revocationSupported").asBoolean()) {
            throw new AwsException("ValidationException", "The " + job.path("platformId").asText()
                    + " platform doesn't support revocation.", 400);
        }
        job.putObject("revocationRecord").put("reason", in.path("reason").asText())
                .put("revokedAt", Instant.now().getEpochSecond()).put("revokedBy", caller(region));
        jobs.put(job.path("jobId").asText(), job);
        return mapper.createObjectNode();
    }

    /** Which of the named job, profile version and certificates are revoked for a signature made at that time. */
    private ObjectNode revocationStatus(JsonNode in) {
        long signedAt = seconds(in.get("signatureTimestamp"));
        ArrayNode revoked = mapper.createArrayNode();
        String jobArn = in.path("jobArn").asText();
        String jobId = jobArn.substring(jobArn.lastIndexOf('/') + 1);
        jobs.get(jobId).filter(job -> job.has("revocationRecord")).ifPresent(job -> revoked.add(jobArn));
        String versionArn = in.path("profileVersionArn").asText();
        for (ObjectNode profile : profiles.scan(k -> true)) {
            JsonNode record = profile.path("revocationRecord");
            if (versionArn.equals(profile.path("profileVersionArn").asText()) && !record.isMissingNode()
                    && signedAt >= record.path("revocationEffectiveFrom").asLong()) {
                revoked.add(versionArn);
                String hash = SignerCrypto.certificateHash(profile.path("_").path("certificate").asText());
                for (JsonNode requested : in.path("certificateHashes")) {
                    if (hash.equalsIgnoreCase(requested.asText())) {
                        revoked.add(requested.asText());
                    }
                }
            }
        }
        return mapper.createObjectNode().set("revokedEntities", revoked);
    }

    private ObjectNode signPayload(JsonNode in, String region) {
        ObjectNode profile = activeProfile(region, in.path("profileName").asText());
        if (!profile.path("platformId").asText().startsWith("Notation")) {
            throw new AwsException("ValidationException", "SignPayload signs with a Notation profile; "
                    + profile.path("profileName").asText() + " is for " + profile.path("platformId").asText() + ".",
                    400);
        }
        String format = in.path("payloadFormat").asText();
        if (!NOTARY_PAYLOAD.equals(format)) {
            throw new AwsException("ValidationException", "payloadFormat must be " + NOTARY_PAYLOAD + ".", 400);
        }
        byte[] payload = Base64.getDecoder().decode(in.path("payload").asText());
        try {
            if (!mapper.readTree(payload).has("targetArtifact")) {
                throw new AwsException("ValidationException", "The payload needs a targetArtifact.", 400);
            }
        } catch (AwsException e) {
            throw e;
        } catch (IOException e) {
            throw new AwsException("ValidationException", "The payload isn't valid JSON.", 400);
        }
        String id = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        byte[] signature = SignerCrypto.notationJws(keyOf(profile), payload, format, mapper);
        ObjectNode job = mapper.createObjectNode();
        job.put("jobId", id).put("jobOwner", jobs.accountId()).put("jobInvoker", jobs.accountId())
                .put("profileName", profile.path("profileName").asText())
                .put("profileVersion", profile.path("profileVersion").asText())
                .put("platformId", profile.path("platformId").asText())
                .put("platformDisplayName", profile.path("platformDisplayName").asText()).put("createdAt", now)
                .put("completedAt", now).put("status", "Succeeded").put("statusReason", "Signing Succeeded")
                .put("requestedBy", caller(region)).put("signatureExpiresAt", expiry(profile, now));
        job.putObject("_").put("region", region);
        jobs.put(id, job);
        ObjectNode out = mapper.createObjectNode().put("jobId", id).put("jobOwner", jobs.accountId())
                .put("signature", Base64.getEncoder().encodeToString(signature));
        out.putObject("metadata").put("signingProfileVersionArn", profile.path("profileVersionArn").asText())
                .put("signingJobArn", jobArn(region, id));
        return out;
    }

    private SignerCrypto.KeyMaterial keyOf(ObjectNode profile) {
        return new SignerCrypto.KeyMaterial(profile.path("_").path("privateKey").asText(),
                profile.path("_").path("certificate").asText());
    }

    // ---------- Lambda code signing ----------

    @Override
    public Optional<String> rejection(byte[] zip, List<String> allowedProfileVersionArns) {
        SignerCrypto.Envelope envelope = SignerCrypto.envelope(zip, mapper);
        if (envelope == null) {
            return Optional.of("The code isn't signed by AWS Signer.");
        }
        if (!allowedProfileVersionArns.contains(envelope.profileVersionArn())) {
            return Optional.of("The code is signed with the profile version " + envelope.profileVersionArn()
                    + ", which the code signing configuration doesn't allow.");
        }
        ObjectNode profile = null;
        for (ObjectNode candidate : profiles.scan(k -> true)) {
            if (envelope.profileVersionArn().equals(candidate.path("profileVersionArn").asText())) {
                profile = candidate;
            }
        }
        if (profile == null || !envelope.certificate().equals(profile.path("_").path("certificate").asText())) {
            return Optional.of("The signing profile version " + envelope.profileVersionArn() + " doesn't exist.");
        }
        if (!SignerCrypto.verify(envelope.certificate(), SignerCrypto.signedRange(zip), envelope.signature())) {
            return Optional.of("The signature doesn't match the code.");
        }
        String jobId = envelope.jobArn().substring(envelope.jobArn().lastIndexOf('/') + 1);
        ObjectNode job = jobs.get(jobId).orElse(null);
        if (job == null) {
            return Optional.of("The signing job " + envelope.jobArn() + " doesn't exist.");
        }
        if (job.has("revocationRecord")) {
            return Optional.of("The signature from the signing job " + envelope.jobArn() + " was revoked.");
        }
        JsonNode record = profile.path("revocationRecord");
        if (!record.isMissingNode() && job.path("completedAt").asLong()
                >= record.path("revocationEffectiveFrom").asLong()) {
            return Optional.of("The signing profile version " + envelope.profileVersionArn() + " was revoked.");
        }
        if (Instant.now().getEpochSecond() > job.path("signatureExpiresAt").asLong(Long.MAX_VALUE)) {
            return Optional.of("The signature expired.");
        }
        return Optional.empty();
    }

    // ---------- tags ----------

    private ObjectNode tagged(String arn, String region) {
        if (!AwsArnUtils.isArnFor(arn, "signer")) {
            throw new AwsException("BadRequestException", "resourceArn isn't a Signer ARN.", 400);
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String prefix = "/signing-profiles/";
        if (!resource.startsWith(prefix)) {
            throw notFound("Resource " + arn + " does not exist.");
        }
        String rest = resource.substring(prefix.length());
        return profile(region, rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest);
    }

    private ObjectNode tag(JsonNode in, String region, boolean add) {
        ObjectNode profile = tagged(decode(in.path("resourceArn").asText()), region);
        ObjectNode tags = profile.withObject("/tags");
        if (add) {
            tags.setAll((ObjectNode) in.path("tags"));
            if (tags.size() > 200) {
                throw new AwsException("BadRequestException", "A resource can have at most 200 tags.", 400);
            }
        } else {
            in.path("tagKeys").forEach(tagKey -> tags.remove(tagKey.asText()));
        }
        profiles.put(key(region, profile.path("profileName").asText()), profile);
        return mapper.createObjectNode();
    }

    private ObjectNode listTags(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("tags", tagged(decode(in.path("resourceArn").asText()), region).path("tags").deepCopy());
        return out;
    }

    // ---------- shared ----------

    private ObjectNode page(List<ObjectNode> items, JsonNode in, String field) {
        int max = in.path("maxResults").asInt(100);
        int start = 0;
        if (in.hasNonNull("nextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(in.get("nextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new AwsException("ValidationException", "The nextToken is not valid.", 400);
            }
        }
        start = Math.max(0, Math.min(start, items.size()));
        int end = Math.min(items.size(), start + Math.max(1, max));
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(field);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("nextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    /** The caller, as Signer records who requested or revoked something. */
    private String caller(String region) {
        return AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", profiles.accountId(), "root")
                .toString();
    }

    private static long seconds(JsonNode value) {
        if (value == null || value.isNull()) {
            throw new AwsException("ValidationException", "A required timestamp is missing.", 400);
        }
        if (value.isNumber()) {
            return (long) value.asDouble();
        }
        try {
            return Instant.parse(value.asText()).getEpochSecond();
        } catch (RuntimeException e) {
            return (long) Double.parseDouble(value.asText());
        }
    }

    private static String random(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    private static String name(JsonNode in, String field) {
        String value = in.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationException", field + " is required.", 400);
        }
        return decode(value);
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private static String decode(String value) {
        return value.contains("%") ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value;
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }
}
