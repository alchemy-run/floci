package io.floci.aws.services.lambdaruntimes;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.StorageBackedMap;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.lambda.LambdaImageDigestResolver;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lambda customer-managed runtimes ("Lambda Core", apiVersion 2026-10-01): a runtime holds
 * immutable versions, each made from one container image, and aliases that point at a version.
 * A function with {@code Runtime: custom} runs its .zip code on the image its alias resolves to.
 *
 * <p>Deletion is never immediate: a deleted runtime, version or alias moves to
 * {@code PENDING_DELETION} for a 7–30 day window, keeps its name reserved, and can be restored.
 * The rules Lambda applies to deletes and restores are enforced here with Lambda's messages.
 */
@ApplicationScoped
public class LambdaRuntimesService implements Resettable {

    static final String ACTIVE = "ACTIVE";
    static final String PENDING_DELETION = "PENDING_DELETION";

    private static final Pattern RUNTIME_NAME = Pattern.compile("^[a-z0-9]([a-z0-9_.-]{0,126})?[a-z0-9]$");
    private static final Pattern CHILD_NAME = Pattern.compile("^[A-Za-z0-9_]([A-Za-z0-9_.-]{0,126}[A-Za-z0-9_])?$");
    private static final int DEFAULT_RECOVERY_WINDOW_DAYS = 30;

    private final StorageFactory storageFactory;
    private final RegionResolver regionResolver;
    private final LambdaImageDigestResolver digestResolver;

    private Map<String, Runtime> runtimes = new ConcurrentHashMap<>();
    private Map<String, Version> versions = new ConcurrentHashMap<>();
    private Map<String, Alias> aliases = new ConcurrentHashMap<>();

    @Inject
    public LambdaRuntimesService(StorageFactory storageFactory, RegionResolver regionResolver,
                                 LambdaImageDigestResolver digestResolver) {
        this.storageFactory = storageFactory;
        this.regionResolver = regionResolver;
        this.digestResolver = digestResolver;
    }

    @PostConstruct
    void init() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        // Runtimes are part of the lambda service, so they share its storage key and mode.
        runtimes = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-runtimes.json", new TypeReference<Map<String, Runtime>>() {}));
        versions = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-runtime-versions.json", new TypeReference<Map<String, Version>>() {}));
        aliases = new StorageBackedMap<>(storageFactory.create("lambda",
                "lambda-runtime-aliases.json", new TypeReference<Map<String, Alias>>() {}));
    }

    @Override
    public void clear() {
        runtimes.clear();
        versions.clear();
        aliases.clear();
    }

    // ----------------------------------------------------------------- model

    @RegisterForReflection
    public static final class Lifecycle {
        public String releaseState;
        public String deprecationDate;
        public String functionCreateBlockDate;
        public String functionUpdateBlockDate;

        boolean isEmpty() {
            return releaseState == null && deprecationDate == null
                    && functionCreateBlockDate == null && functionUpdateBlockDate == null;
        }
    }

    @RegisterForReflection
    public static final class Runtime {
        public String id;
        public String name;
        public String arn;
        public String description;
        public String licenseInfo;
        public Lifecycle releaseLifecycle;
        public String state = ACTIVE;
        public Instant pendingDeletionAt;
        public Instant created;
        public Instant lastModified;
        public final Map<String, String> tags = new LinkedHashMap<>();
        public String policy;
        public String policyRevisionId;
    }

    @RegisterForReflection
    public static final class Version {
        public String runtimeName;
        public String name;
        public String arn;
        public String imageUri;
        public String resolvedUri;
        public List<String> architectures = List.of();
        public String state = ACTIVE;
        public Instant pendingDeletionAt;
        public String releaseNotes;
        public Instant created;
        public Instant lastModified;
    }

    @RegisterForReflection
    public static final class Alias {
        public String runtimeName;
        public String name;
        public String arn;
        public String runtimeVersion;
        public String state = ACTIVE;
        public Instant pendingDeletionAt;
        public String description;
        public Lifecycle releaseLifecycle;
        public Instant created;
        public Instant lastModified;
    }

    // -------------------------------------------------------------- runtimes

    public Runtime createRuntime(String region, String name, String description, String licenseInfo,
                                 Lifecycle lifecycle, Map<String, String> tags) {
        if (name == null || !RUNTIME_NAME.matcher(name).matches()) {
            throw invalid("Runtime name must be 2 to 128 lowercase letters, numbers, '-', '_' or '.'");
        }
        if (runtimes.containsKey(key(region, name))) {
            throw new AwsException("ResourceConflictException", "Runtime already exists: " + name, 409);
        }
        Runtime runtime = new Runtime();
        runtime.id = UUID.randomUUID().toString();
        runtime.name = name;
        runtime.arn = regionResolver.buildArn("lambda", region, "runtime/" + name);
        runtime.description = description;
        runtime.licenseInfo = licenseInfo;
        runtime.releaseLifecycle = lifecycle == null || lifecycle.isEmpty() ? null : lifecycle;
        runtime.created = Instant.now();
        runtime.lastModified = runtime.created;
        if (tags != null) {
            runtime.tags.putAll(tags);
        }
        runtimes.put(key(region, name), runtime);
        return runtime;
    }

    public Runtime getRuntime(String region, String identifier) {
        Runtime runtime = runtimes.get(key(region, runtimeName(identifier)));
        if (runtime == null || expired(runtime.pendingDeletionAt)) {
            if (runtime != null) {
                purgeRuntime(region, runtime.name);
            }
            throw notFound("Runtime not found: " + identifier);
        }
        return runtime;
    }

    public List<Runtime> listRuntimes(String region) {
        return in(runtimes, region)
                .filter(r -> !expired(r.pendingDeletionAt))
                .sorted(Comparator.comparing(r -> r.name))
                .toList();
    }

    /** UpdateRuntime replaces all three fields; an omitted or empty lifecycle removes it. */
    public Runtime updateRuntime(String region, String identifier, String description, String licenseInfo,
                                 Lifecycle lifecycle) {
        Runtime runtime = requireMutable(getRuntime(region, identifier));
        runtime.description = blankToNull(description);
        runtime.licenseInfo = blankToNull(licenseInfo);
        runtime.releaseLifecycle = lifecycle == null || lifecycle.isEmpty() ? null : lifecycle;
        runtime.lastModified = Instant.now();
        save(region, runtime);
        return runtime;
    }

    public void deleteRuntime(String region, String identifier, Integer recoveryWindowInDays) {
        Runtime runtime = getRuntime(region, identifier);
        // Deleting a resource already scheduled for deletion succeeds and keeps its window.
        if (PENDING_DELETION.equals(runtime.state)) {
            return;
        }
        boolean live = versionsOf(region, runtime.name).anyMatch(v -> !PENDING_DELETION.equals(v.state))
                || aliasesOf(region, runtime.name).anyMatch(a -> !PENDING_DELETION.equals(a.state));
        if (live) {
            throw conflict("Delete all versions and aliases of the runtime first");
        }
        Instant at = deletionAt(recoveryWindowInDays);
        // A runtime's window never ends before its children's.
        for (Instant child : Stream.concat(
                versionsOf(region, runtime.name).map(v -> v.pendingDeletionAt),
                aliasesOf(region, runtime.name).map(a -> a.pendingDeletionAt)).toList()) {
            if (child != null && child.isAfter(at)) {
                at = child;
            }
        }
        runtime.state = PENDING_DELETION;
        runtime.pendingDeletionAt = at;
        runtime.lastModified = Instant.now();
        save(region, runtime);
    }

    // -------------------------------------------------------------- versions

    public Version createVersion(String region, String runtimeIdentifier, String name, String imageUri,
                                 String releaseNotes, boolean dryRun) {
        Runtime runtime = requireMutable(getRuntime(region, runtimeIdentifier));
        if (name == null || !CHILD_NAME.matcher(name).matches()) {
            throw invalid("Runtime version name is not valid: " + name);
        }
        if (imageUri == null || imageUri.isBlank()) {
            throw invalid("ImageUri is required");
        }
        if (versions.containsKey(childKey(region, runtime.name, name))) {
            throw conflict("Runtime version already exists: " + name);
        }
        String resolved = digestResolver.resolve(imageUri);
        if (resolved == null) {
            throw invalid("Lambda can't read the image " + imageUri
                    + ". Check that it exists in Amazon ECR in this Region.");
        }
        Version version = new Version();
        version.runtimeName = runtime.name;
        version.name = name;
        version.arn = runtime.arn + "/version/" + name;
        version.imageUri = imageUri;
        version.resolvedUri = resolved;
        // Floci does not read the image's platform; the version advertises x86_64, as an
        // image built for a single default platform does.
        version.architectures = List.of("x86_64");
        version.releaseNotes = blankToNull(releaseNotes);
        version.created = Instant.now();
        version.lastModified = version.created;
        if (!dryRun) {
            versions.put(childKey(region, runtime.name, name), version);
        }
        return version;
    }

    public Version getVersion(String region, String runtimeIdentifier, String name) {
        Runtime runtime = getRuntime(region, runtimeIdentifier);
        Version version = versions.get(childKey(region, runtime.name, name));
        if (version == null || expired(version.pendingDeletionAt)) {
            if (version != null) {
                versions.remove(childKey(region, runtime.name, name));
            }
            throw notFound("Runtime version not found: " + name);
        }
        return version;
    }

    public List<Version> listVersions(String region, String runtimeIdentifier) {
        Runtime runtime = getRuntime(region, runtimeIdentifier);
        return versionsOf(region, runtime.name)
                .filter(v -> !expired(v.pendingDeletionAt))
                .sorted(Comparator.comparing(v -> v.created))
                .toList();
    }

    public Version updateVersion(String region, String runtimeIdentifier, String name, String releaseNotes) {
        Version version = getVersion(region, runtimeIdentifier, name);
        if (PENDING_DELETION.equals(version.state)) {
            throw conflict("Runtime version is scheduled for deletion and can't be modified");
        }
        version.releaseNotes = blankToNull(releaseNotes);
        version.lastModified = Instant.now();
        versions.put(childKey(region, version.runtimeName, name), version);
        return version;
    }

    public void deleteVersion(String region, String runtimeIdentifier, String name, Integer recoveryWindowInDays) {
        Version version = getVersion(region, runtimeIdentifier, name);
        if (PENDING_DELETION.equals(version.state)) {
            return;
        }
        List<Alias> pointing = aliasesOf(region, version.runtimeName)
                .filter(a -> name.equals(a.runtimeVersion))
                .toList();
        if (pointing.stream().anyMatch(a -> !PENDING_DELETION.equals(a.state))) {
            throw conflict("Runtime version is referenced by an alias; update or delete the alias first");
        }
        Instant at = deletionAt(recoveryWindowInDays);
        for (Alias alias : pointing) {
            if (alias.pendingDeletionAt != null && alias.pendingDeletionAt.isAfter(at)) {
                at = alias.pendingDeletionAt;
            }
        }
        version.state = PENDING_DELETION;
        version.pendingDeletionAt = at;
        version.lastModified = Instant.now();
        versions.put(childKey(region, version.runtimeName, name), version);
    }

    // --------------------------------------------------------------- aliases

    public Alias createAlias(String region, String runtimeIdentifier, String name, String runtimeVersion,
                             String description, Lifecycle lifecycle) {
        Runtime runtime = requireMutable(getRuntime(region, runtimeIdentifier));
        if (name == null || !CHILD_NAME.matcher(name).matches()) {
            throw invalid("Runtime alias name is not valid: " + name);
        }
        if (aliases.containsKey(childKey(region, runtime.name, name))) {
            throw conflict("Runtime alias already exists: " + name);
        }
        Version version = requireLiveVersion(region, runtime.name, runtimeVersion);
        Alias alias = new Alias();
        alias.runtimeName = runtime.name;
        alias.name = name;
        alias.arn = runtime.arn + "/alias/" + name;
        alias.runtimeVersion = version.name;
        alias.description = blankToNull(description);
        alias.releaseLifecycle = lifecycle == null || lifecycle.isEmpty() ? null : lifecycle;
        alias.created = Instant.now();
        alias.lastModified = alias.created;
        aliases.put(childKey(region, runtime.name, name), alias);
        return alias;
    }

    public Alias getAlias(String region, String runtimeIdentifier, String name) {
        Runtime runtime = getRuntime(region, runtimeIdentifier);
        Alias alias = aliases.get(childKey(region, runtime.name, name));
        if (alias == null || expired(alias.pendingDeletionAt)) {
            if (alias != null) {
                aliases.remove(childKey(region, runtime.name, name));
            }
            throw notFound("Runtime alias not found: " + name);
        }
        return alias;
    }

    public List<Alias> listAliases(String region, String runtimeIdentifier) {
        Runtime runtime = getRuntime(region, runtimeIdentifier);
        return aliasesOf(region, runtime.name)
                .filter(a -> !expired(a.pendingDeletionAt))
                .sorted(Comparator.comparing(a -> a.name))
                .toList();
    }

    public Alias updateAlias(String region, String runtimeIdentifier, String name, String runtimeVersion,
                             String description, Lifecycle lifecycle) {
        Alias alias = getAlias(region, runtimeIdentifier, name);
        if (PENDING_DELETION.equals(alias.state)) {
            throw conflict("Runtime alias is scheduled for deletion and can't be modified");
        }
        Version version = requireLiveVersion(region, alias.runtimeName, runtimeVersion);
        alias.runtimeVersion = version.name;
        alias.description = blankToNull(description);
        alias.releaseLifecycle = lifecycle == null || lifecycle.isEmpty() ? null : lifecycle;
        alias.lastModified = Instant.now();
        aliases.put(childKey(region, alias.runtimeName, name), alias);
        return alias;
    }

    public void deleteAlias(String region, String runtimeIdentifier, String name, Integer recoveryWindowInDays) {
        Alias alias = getAlias(region, runtimeIdentifier, name);
        if (PENDING_DELETION.equals(alias.state)) {
            return;
        }
        alias.state = PENDING_DELETION;
        alias.pendingDeletionAt = deletionAt(recoveryWindowInDays);
        alias.lastModified = Instant.now();
        aliases.put(childKey(region, alias.runtimeName, name), alias);
    }

    // --------------------------------------------------------------- restore

    public record Restored(String runtimeName, String runtimeVersion, String runtimeAlias, String arn,
                           String state, Instant lastModified) {}

    /** Restores the runtime, or one of its versions or aliases, from pending deletion. */
    public Restored restore(String region, String runtimeIdentifier, String versionName, String aliasName) {
        if (versionName != null && aliasName != null) {
            throw invalid("Specify either RuntimeVersion or RuntimeAlias, not both");
        }
        Runtime runtime = getRuntime(region, runtimeIdentifier);
        if (versionName == null && aliasName == null) {
            requirePending(runtime.state);
            runtime.state = ACTIVE;
            runtime.pendingDeletionAt = null;
            runtime.lastModified = Instant.now();
            save(region, runtime);
            return new Restored(runtime.name, null, null, runtime.arn, ACTIVE, runtime.lastModified);
        }
        if (PENDING_DELETION.equals(runtime.state)) {
            throw conflict("Could not restore! Restore the runtime first.");
        }
        if (versionName != null) {
            Version version = getVersion(region, runtime.name, versionName);
            requirePending(version.state);
            version.state = ACTIVE;
            version.pendingDeletionAt = null;
            version.lastModified = Instant.now();
            versions.put(childKey(region, runtime.name, versionName), version);
            return new Restored(runtime.name, versionName, null, version.arn, ACTIVE, version.lastModified);
        }
        Alias alias = getAlias(region, runtime.name, aliasName);
        requirePending(alias.state);
        Version target = versions.get(childKey(region, runtime.name, alias.runtimeVersion));
        if (target == null || PENDING_DELETION.equals(target.state)) {
            throw conflict("Could not restore! Restore the runtime version first.");
        }
        alias.state = ACTIVE;
        alias.pendingDeletionAt = null;
        alias.lastModified = Instant.now();
        aliases.put(childKey(region, runtime.name, aliasName), alias);
        return new Restored(runtime.name, null, aliasName, alias.arn, ACTIVE, alias.lastModified);
    }

    // ----------------------------------------------------------- functions

    /** A function's resolved runtime: the version its alias points to and that version's image. */
    public record ResolvedRuntime(String versionArn, String imageUri) {}

    /**
     * Resolves {@code RuntimeConfig} to the version and image a function runs on. Rejects a runtime
     * or alias that is missing or scheduled for deletion, as Lambda does at CreateFunction.
     */
    public ResolvedRuntime resolve(String region, String runtimeArn, String aliasName) {
        if (runtimeArn == null || runtimeArn.isBlank() || aliasName == null || aliasName.isBlank()) {
            throw invalid("RuntimeConfig requires RuntimeArn and RuntimeAlias when Runtime is custom");
        }
        Runtime runtime;
        Alias alias;
        try {
            runtime = getRuntime(region, runtimeArn);
            alias = getAlias(region, runtime.name, aliasName);
        } catch (AwsException e) {
            throw invalid("The runtime alias " + aliasName + " of " + runtimeArn + " doesn't exist.");
        }
        if (PENDING_DELETION.equals(runtime.state) || PENDING_DELETION.equals(alias.state)) {
            throw invalid("The runtime alias " + aliasName + " of " + runtimeArn + " is scheduled for deletion.");
        }
        Version version = versions.get(childKey(region, runtime.name, alias.runtimeVersion));
        if (version == null || PENDING_DELETION.equals(version.state)) {
            throw invalid("The runtime version behind alias " + aliasName + " is not available.");
        }
        return new ResolvedRuntime(version.arn, version.resolvedUri);
    }

    /** Like {@link #resolve} but never throws, for launch paths that report their own failure. */
    public Optional<ResolvedRuntime> tryResolve(String region, String runtimeArn, String aliasName) {
        try {
            return Optional.of(resolve(region, runtimeArn, aliasName));
        } catch (AwsException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------- tags & policies

    /** True when the ARN names a runtime (or one of its versions or aliases). */
    public static boolean ownsArn(String arn) {
        return AwsArnUtils.resourceIfArnFor(arn, "lambda").map(r -> r.startsWith("runtime/")).orElse(false);
    }

    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(runtimeForTags(region, arn).tags);
    }

    public void tagResource(String region, String arn, Map<String, String> tags) {
        Runtime runtime = runtimeForTags(region, arn);
        runtime.tags.putAll(tags);
        save(region, runtime);
    }

    public void untagResource(String region, String arn, List<String> tagKeys) {
        Runtime runtime = runtimeForTags(region, arn);
        if (tagKeys != null) {
            tagKeys.forEach(runtime.tags::remove);
        }
        save(region, runtime);
    }

    public record Policy(String policy, String revisionId) {}

    public Policy getPolicy(String region, String arn) {
        Runtime runtime = runtimeForPolicy(region, arn);
        if (runtime.policy == null) {
            throw notFound("The resource does not have a policy.");
        }
        return new Policy(runtime.policy, runtime.policyRevisionId);
    }

    public Policy putPolicy(String region, String arn, String policy, String revisionId) {
        Runtime runtime = runtimeForPolicy(region, arn);
        if (policy == null || policy.isBlank()) {
            throw invalid("Policy is required");
        }
        checkRevision(runtime, revisionId);
        runtime.policy = policy;
        runtime.policyRevisionId = UUID.randomUUID().toString();
        save(region, runtime);
        return new Policy(runtime.policy, runtime.policyRevisionId);
    }

    public void deletePolicy(String region, String arn, String revisionId) {
        Runtime runtime = runtimeForPolicy(region, arn);
        if (runtime.policy == null) {
            throw notFound("The resource does not have a policy.");
        }
        checkRevision(runtime, revisionId);
        runtime.policy = null;
        runtime.policyRevisionId = null;
        save(region, runtime);
    }

    // --------------------------------------------------------------- helpers

    private Runtime runtimeForTags(String region, String arn) {
        String resource = AwsArnUtils.resourceIfArnFor(arn, "lambda").orElse("");
        if (resource.contains("/version/") || resource.contains("/alias/")) {
            throw invalid("Runtime versions and runtime aliases don't support tags.");
        }
        return getRuntime(region, arn);
    }

    private Runtime runtimeForPolicy(String region, String arn) {
        String resource = AwsArnUtils.resourceIfArnFor(arn, "lambda").orElse("");
        if (resource.contains("/version/") || resource.contains("/alias/")) {
            throw invalid("Attach the resource-based policy to the runtime, not to a version or alias.");
        }
        return getRuntime(region, arn);
    }

    private static void checkRevision(Runtime runtime, String revisionId) {
        if (revisionId != null && !revisionId.isBlank() && !revisionId.equals(runtime.policyRevisionId)) {
            throw new AwsException("PreconditionFailedException",
                    "The revision ID provided does not match the latest revision ID.", 412);
        }
    }

    private Version requireLiveVersion(String region, String runtimeName, String versionName) {
        if (versionName == null || versionName.isBlank()) {
            throw invalid("RuntimeVersion is required");
        }
        Version version = versions.get(childKey(region, runtimeName, versionName));
        if (version == null || expired(version.pendingDeletionAt)) {
            throw notFound("Runtime version not found: " + versionName);
        }
        if (PENDING_DELETION.equals(version.state)) {
            throw conflict("Runtime version " + versionName + " is scheduled for deletion");
        }
        return version;
    }

    private static Runtime requireMutable(Runtime runtime) {
        if (PENDING_DELETION.equals(runtime.state)) {
            throw conflict("Runtime is scheduled for deletion and can't be modified");
        }
        return runtime;
    }

    private static void requirePending(String state) {
        if (!PENDING_DELETION.equals(state)) {
            throw conflict("The resource is not scheduled for deletion.");
        }
    }

    private void purgeRuntime(String region, String name) {
        runtimes.remove(key(region, name));
        versionsOf(region, name).toList().forEach(v -> versions.remove(childKey(region, name, v.name)));
        aliasesOf(region, name).toList().forEach(a -> aliases.remove(childKey(region, name, a.name)));
    }

    private void save(String region, Runtime runtime) {
        runtimes.put(key(region, runtime.name), runtime);
    }

    private Stream<Version> versionsOf(String region, String runtimeName) {
        String prefix = region + "/" + runtimeName + "/";
        return versions.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).map(Map.Entry::getValue);
    }

    private Stream<Alias> aliasesOf(String region, String runtimeName) {
        String prefix = region + "/" + runtimeName + "/";
        return aliases.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).map(Map.Entry::getValue);
    }

    /** A runtime identifier is its name or its ARN. */
    static String runtimeName(String identifier) {
        if (identifier == null) {
            return "";
        }
        return AwsArnUtils.resourceIfArnFor(identifier, "lambda")
                .filter(r -> r.startsWith("runtime/"))
                .map(r -> r.substring("runtime/".length()).split("/", 2)[0])
                .orElse(identifier);
    }

    static Instant deletionAt(Integer recoveryWindowInDays) {
        int days = recoveryWindowInDays == null ? DEFAULT_RECOVERY_WINDOW_DAYS : recoveryWindowInDays;
        if (days < 7 || days > 30) {
            throw invalid("RecoveryWindowInDays must be between 7 and 30");
        }
        return Instant.now().plus(Duration.ofDays(days));
    }

    private static boolean expired(Instant pendingDeletionAt) {
        return pendingDeletionAt != null && pendingDeletionAt.isBefore(Instant.now());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private static String childKey(String region, String runtimeName, String name) {
        return region + "/" + runtimeName + "/" + name;
    }

    private static <V> Stream<V> in(Map<String, V> store, String region) {
        String prefix = region + "/";
        return store.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix) && entry.getKey().indexOf('/', prefix.length()) < 0)
                .map(Map.Entry::getValue);
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException conflict(String message) {
        return new AwsException("ResourceConflictException", message, 409);
    }
}
