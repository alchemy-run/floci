package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import io.github.hectorvent.floci.services.ssm.model.PatchBaselineIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class SsmService {

    private static final Logger LOG = Logger.getLogger(SsmService.class);
    static final String AWS_MANAGED_SSM_ALIAS = "alias/aws/ssm";

    private final StorageBackend<String, Parameter> parameterStore;
    private final StorageBackend<String, List<ParameterHistory>> historyStore;
    private final int maxParameterHistory;
    private final RegionResolver regionResolver;
    private final KmsService kmsService;

    @Inject
    public SsmService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                      KmsService kmsService) {
        this(
                storageFactory.create("ssm", "ssm-parameters.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-history.json",
                        new TypeReference<>() {
                        }),
                config.services().ssm().maxParameterHistory(),
                regionResolver,
                kmsService
        );
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               int maxParameterHistory) {
        this(parameterStore, historyStore, maxParameterHistory,
                new RegionResolver("us-east-1", "000000000000"));
    }

    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               int maxParameterHistory, RegionResolver regionResolver) {
        this(parameterStore, historyStore, maxParameterHistory, regionResolver, null);
    }

    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               int maxParameterHistory, RegionResolver regionResolver,
               KmsService kmsService) {
        this.parameterStore = parameterStore;
        this.historyStore = historyStore;
        this.maxParameterHistory = maxParameterHistory;
        this.regionResolver = regionResolver;
        this.kmsService = kmsService;
    }

    /**
     * Create or update a parameter.
     * Returns the version number.
     */
    public long putParameter(String name, String value, String type, String description, boolean overwrite, String region) {
        return putParameter(name, value, type, description, overwrite, region, Map.of(), null, null, null, null);
    }

    /**
     * Create or update a parameter, optionally applying create-time tags and
     * metadata (tier, KMS key, allowed pattern, data type).
     */
    public long putParameter(String name, String value, String type, String description, boolean overwrite,
                             String region, Map<String, String> tags, String tier, String keyId,
                             String allowedPattern, String dataType) {
        String storageKey = regionKey(region, name);
        Parameter existing = parameterStore.get(storageKey).orElse(null);

        if (existing != null && !overwrite) {
            throw new AwsException("ParameterAlreadyExists",
                    "The parameter already exists. To overwrite this value, set the overwrite option in the request to true.",
                    400);
        }

        if (existing != null && overwrite && tags != null && !tags.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Invalid request: Tags and Overwrite can't be used together. Remove the Tags parameter from your request if you want to overwrite an existing parameter.",
                    400);
        }

        String resolvedType = type != null ? type : (existing != null ? existing.getType() : "String");
        if (allowedPattern != null && !allowedPattern.isBlank() && value != null) {
            try {
                if (!value.matches(allowedPattern)) {
                    throw new AwsException("ParameterPatternMismatchException",
                            "The parameter value does not match the allowed pattern.",
                            400);
                }
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new AwsException("ValidationException",
                        "The allowed pattern is not a valid regular expression.",
                        400);
            }
        }

        long version = (existing != null) ? existing.getVersion() + 1 : 1;

        Parameter parameter = new Parameter(name, value, resolvedType);
        parameter.setVersion(version);
        parameter.setDescription(description != null ? description : (existing != null ? existing.getDescription() : null));
        parameter.setArn(existing != null && existing.getArn() != null
                ? existing.getArn()
                : regionResolver.buildArn("ssm", region, parameterResource(name)));
        parameter.setLastModifiedDate(Instant.now());
        parameter.setTier(tier != null ? tier : (existing != null && existing.getTier() != null ? existing.getTier() : "Standard"));
        parameter.setAllowedPattern(allowedPattern != null ? allowedPattern
                : (existing != null ? existing.getAllowedPattern() : null));
        parameter.setDataType(dataType != null ? dataType
                : (existing != null && existing.getDataType() != null ? existing.getDataType() : "text"));
        if ("SecureString".equals(resolvedType)) {
            String resolvedKeyId = keyId != null ? keyId
                    : (existing != null && existing.getKeyId() != null ? existing.getKeyId() : AWS_MANAGED_SSM_ALIAS);
            parameter.setKeyId(resolvedKeyId);
            ensureAwsManagedSsmKey(resolvedKeyId, region);
        } else {
            parameter.setKeyId(null);
        }

        Map<String, String> resolvedTags = new HashMap<>();
        if (existing != null && existing.getTags() != null) {
            resolvedTags.putAll(existing.getTags());
        }
        if (tags != null) {
            resolvedTags.putAll(tags);
        }
        parameter.setTags(resolvedTags);

        parameterStore.put(storageKey, parameter);
        addHistory(storageKey, parameter);

        LOG.infov("Put parameter: {0} in region {1} (version {2})", name, region, version);
        return version;
    }

    public Parameter getParameter(String name, String region) {
        String storageKey = regionKey(region, name);
        return parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("ParameterNotFound",
                        "Parameter " + name + " not found.", 400));
    }

    public List<Parameter> getParameters(List<String> names, String region) {
        List<Parameter> result = new ArrayList<>();
        for (String name : names) {
            parameterStore.get(regionKey(region, name)).ifPresent(result::add);
        }
        return result;
    }

    public List<Parameter> getParametersByPath(String path, boolean recursive, String region) {
        String normalizedPath = path.endsWith("/") ? path : path + "/";
        String prefix = region + "::";

        return parameterStore.scan(key -> {
            if (!key.startsWith(prefix)) {
                return false;
            }
            String paramName = key.substring(prefix.length());
            if (!paramName.startsWith(normalizedPath)) {
                return false;
            }
            if (recursive) {
                return true;
            }
            String remainder = paramName.substring(normalizedPath.length());
            return !remainder.contains("/");
        });
    }

    public void deleteParameter(String name, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }
        parameterStore.delete(storageKey);
        historyStore.delete(storageKey);
        LOG.infov("Deleted parameter: {0}", name);
    }

    public List<String> deleteParameters(List<String> names, String region) {
        List<String> deleted = new ArrayList<>();
        for (String name : names) {
            String storageKey = regionKey(region, name);
            if (parameterStore.get(storageKey).isPresent()) {
                parameterStore.delete(storageKey);
                historyStore.delete(storageKey);
                deleted.add(name);
            }
        }
        return deleted;
    }

    public List<ParameterHistory> getParameterHistory(String name, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }
        return historyStore.get(storageKey).orElse(Collections.emptyList());
    }

    public List<Parameter> describeParameters(String region) {
        return describeParameters(List.of(), region);
    }

    public List<Parameter> describeParameters(List<String> nameFilters, String region) {
        String prefix = region + "::";
        return parameterStore.scan(key -> {
            if (!key.startsWith(prefix)) return false;
            if (nameFilters.isEmpty()) return true;
            String name = key.substring(prefix.length());
            return nameFilters.contains(name);
        });
    }

    /**
     * Attach labels to a parameter version. When {@code parameterVersion} is
     * null, the latest version is used (AWS default). A label is unique per
     * parameter: applying it here removes it from any other version.
     * Returns the version that was labeled.
     */
    public long labelParameterVersion(String name, Long parameterVersion, List<String> labels, String region) {
        String storageKey = regionKey(region, name);
        Parameter current = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("ParameterNotFound",
                        "Parameter " + name + " not found.", 400));

        long version = parameterVersion != null ? parameterVersion : current.getVersion();

        List<ParameterHistory> history = new ArrayList<>(historyStore.get(storageKey)
                .orElse(List.of()));

        boolean found = false;
        for (ParameterHistory h : history) {
            if (h.getVersion() == version) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AwsException("ParameterVersionNotFound", "Parameter version " + version + " not found.", 400);
        }

        Set<String> applying = new HashSet<>();
        for (String label : labels) {
            if (isValidLabel(label)) {
                applying.add(label);
            }
        }

        for (ParameterHistory h : history) {
            List<String> existing = h.getLabels() != null ? new ArrayList<>(h.getLabels()) : new ArrayList<>();
            if (h.getVersion() == version) {
                for (String label : applying) {
                    if (!existing.contains(label)) {
                        existing.add(label);
                    }
                }
            } else {
                existing.removeAll(applying);
            }
            h.setLabels(existing);
        }

        historyStore.put(storageKey, history);
        LOG.infov("Labeled parameter {0} version {1} with labels {2}", name, version, applying);
        return version;
    }

    /**
     * Remove labels from a specific parameter version. Labels that were
     * present are returned in {@code removedLabels}; labels that were not
     * on that version go in {@code invalidLabels}.
     */
    public UnlabelResult unlabelParameterVersion(String name, long parameterVersion, List<String> labels, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }

        List<ParameterHistory> history = new ArrayList<>(historyStore.get(storageKey)
                .orElse(List.of()));

        ParameterHistory target = null;
        for (ParameterHistory h : history) {
            if (h.getVersion() == parameterVersion) {
                target = h;
                break;
            }
        }
        if (target == null) {
            throw new AwsException("ParameterVersionNotFound", "Parameter version " + parameterVersion + " not found.", 400);
        }

        List<String> existing = target.getLabels() != null ? new ArrayList<>(target.getLabels()) : new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String label : labels) {
            if (existing.remove(label)) {
                removed.add(label);
            } else {
                invalid.add(label);
            }
        }
        target.setLabels(existing);
        historyStore.put(storageKey, history);
        LOG.infov("Unlabeled parameter {0} version {1}: removed {2}", name, parameterVersion, removed);
        return new UnlabelResult(removed, invalid);
    }

    public record UnlabelResult(List<String> removedLabels, List<String> invalidLabels) {
    }

    private static boolean isValidLabel(String label) {
        if (label == null || label.isBlank() || label.length() > 100) {
            return false;
        }
        String lower = label.toLowerCase(Locale.ROOT);
        return !lower.startsWith("aws") && !lower.startsWith("ssm");
    }

    public void addTagsToResource(String resourceId, Map<String, String> tags, String region) {
        String storageKey = regionKey(region, resourceId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));

        if (param.getTags() == null) {
            param.setTags(new HashMap<>());
        }
        param.getTags().putAll(tags);
        parameterStore.put(storageKey, param);
        LOG.debugv("Added tags to parameter: {0}", resourceId);
    }

    public Map<String, String> listTagsForResource(String resourceId, String region) {
        String storageKey = regionKey(region, resourceId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));
        return param.getTags() != null ? param.getTags() : Map.of();
    }

    public void removeTagsFromResource(String resourceId, List<String> tagKeys, String region) {
        String storageKey = regionKey(region, resourceId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));

        if (param.getTags() != null) {
            for (String key : tagKeys) {
                param.getTags().remove(key);
            }
            parameterStore.put(storageKey, param);
        }
        LOG.debugv("Removed tags from parameter: {0}", resourceId);
    }

    // ──────────────────────────── Patch Baselines ────────────────────────────
    // AWS provides a fixed set of AWS-owned predefined patch baselines (one default per operating
    // system). These are static reference data, not customer state, so they live in-memory only.

    private static final List<PatchBaselineIdentity> PREDEFINED_BASELINES = buildPredefinedBaselines();

    private static List<PatchBaselineIdentity> buildPredefinedBaselines() {
        String[][] defs = {
                {"WINDOWS", "AWS-DefaultPatchBaseline", "Windows"},
                {"AMAZON_LINUX", "AWS-AmazonLinuxDefaultPatchBaseline", "Amazon Linux"},
                {"AMAZON_LINUX_2", "AWS-AmazonLinux2DefaultPatchBaseline", "Amazon Linux 2"},
                {"AMAZON_LINUX_2022", "AWS-AmazonLinux2022DefaultPatchBaseline", "Amazon Linux 2022"},
                {"AMAZON_LINUX_2023", "AWS-AmazonLinux2023DefaultPatchBaseline", "Amazon Linux 2023"},
                {"UBUNTU", "AWS-UbuntuDefaultPatchBaseline", "Ubuntu"},
                {"REDHAT_ENTERPRISE_LINUX", "AWS-RedHatDefaultPatchBaseline", "Red Hat Enterprise Linux"},
                {"SUSE", "AWS-SuseDefaultPatchBaseline", "SUSE Linux Enterprise Server"},
                {"CENTOS", "AWS-CentOSDefaultPatchBaseline", "CentOS"},
                {"ORACLE_LINUX", "AWS-OracleLinuxDefaultPatchBaseline", "Oracle Linux"},
                {"DEBIAN", "AWS-DebianDefaultPatchBaseline", "Debian Server"},
                {"MACOS", "AWS-MacOSDefaultPatchBaseline", "macOS"},
                {"RASPBIAN", "AWS-RaspbianDefaultPatchBaseline", "Raspbian"},
                {"ROCKY_LINUX", "AWS-RockyLinuxDefaultPatchBaseline", "Rocky Linux"},
                {"ALMA_LINUX", "AWS-AlmaLinuxDefaultPatchBaseline", "AlmaLinux"},
        };
        List<PatchBaselineIdentity> baselines = new ArrayList<>();
        for (String[] def : defs) {
            String os = def[0];
            String name = def[1];
            String description = "Default Patch Baseline for " + def[2] + " Provided by AWS.";
            baselines.add(new PatchBaselineIdentity(stableBaselineId(name), name, os, description, true));
        }
        return List.copyOf(baselines);
    }

    /** Deterministic AWS-style baseline id (pb-<17 hex>) derived from the baseline name. */
    private static String stableBaselineId(String name) {
        long h = 1125899906842597L;
        for (int i = 0; i < name.length(); i++) {
            h = 31 * h + name.charAt(i);
        }
        String hex = String.format("%016x", h & 0x0FFFFFFFFFFFFFFFL);
        return "pb-0" + hex;
    }

    /**
     * Return AWS-owned predefined patch baselines matching the given DescribePatchBaselines filters
     * (supported keys: OWNER, OPERATING_SYSTEM, NAME_PREFIX). There are no customer-owned baselines.
     */
    public List<PatchBaselineIdentity> describePatchBaselines(Map<String, List<String>> filters) {
        List<String> owners = filters.getOrDefault("OWNER", List.of());
        // OWNER=Self matches only customer-owned baselines, of which there are none.
        if (!owners.isEmpty() && !owners.contains("AWS") && !owners.contains("All")) {
            return List.of();
        }

        List<String> operatingSystems = filters.getOrDefault("OPERATING_SYSTEM", List.of());
        List<String> namePrefixes = filters.getOrDefault("NAME_PREFIX", List.of());

        return PREDEFINED_BASELINES.stream()
                .filter(b -> operatingSystems.isEmpty() || operatingSystems.contains(b.operatingSystem()))
                .filter(b -> namePrefixes.isEmpty()
                        || namePrefixes.stream().anyMatch(prefix -> b.baselineName().startsWith(prefix)))
                .toList();
    }

    /** Return the default patch baseline id for an operating system (defaults to WINDOWS). */
    public String getDefaultPatchBaseline(String operatingSystem) {
        String os = (operatingSystem == null || operatingSystem.isBlank()) ? "WINDOWS" : operatingSystem;
        return PREDEFINED_BASELINES.stream()
                .filter(b -> b.operatingSystem().equals(os))
                .findFirst()
                .map(PatchBaselineIdentity::baselineId)
                .orElseThrow(() -> new AwsException("DoesNotExistException",
                        "No default patch baseline exists for operating system " + os, 400));
    }

    /**
     * AWS lazily creates {@code alias/aws/ssm} on the first SecureString that
     * uses the default key. Seed that alias in KMS so DescribeKey resolves a
     * real {@code arn:aws:kms:...:key/...}.
     */
    void ensureAwsManagedSsmKey(String keyId, String region) {
        if (kmsService == null || keyId == null) {
            return;
        }
        if (!AWS_MANAGED_SSM_ALIAS.equals(keyId) && !keyId.endsWith(":" + AWS_MANAGED_SSM_ALIAS)) {
            return;
        }
        try {
            kmsService.describeKey(AWS_MANAGED_SSM_ALIAS, region);
            return;
        } catch (AwsException e) {
            if (!"NotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
        }
        try {
            KmsKey key = kmsService.createKey(
                    "Default key that protects my SSM SecureString parameters when no other key is defined",
                    region);
            kmsService.createAlias(AWS_MANAGED_SSM_ALIAS, key.getKeyId(), region);
        } catch (AwsException e) {
            if (!"AlreadyExistsException".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    private static String regionKey(String region, String name) {
        return region + "::" + name;
    }

    /** AWS ARN resource is always {@code parameter/<name-without-leading-slash>}. */
    static String parameterResource(String name) {
        return name.startsWith("/") ? "parameter" + name : "parameter/" + name;
    }

    private void addHistory(String storageKey, Parameter parameter) {
        List<ParameterHistory> history = historyStore.get(storageKey)
                .orElse(new ArrayList<>());

        history = new ArrayList<>(history);
        history.add(new ParameterHistory(parameter));

        while (history.size() > maxParameterHistory) {
            history.removeFirst();
        }

        historyStore.put(storageKey, history);
    }
}
