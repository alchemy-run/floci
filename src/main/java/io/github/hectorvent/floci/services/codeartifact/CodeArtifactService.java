package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackage;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackage.Version;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import io.github.hectorvent.floci.services.codeartifact.model.ExternalConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class CodeArtifactService implements Resettable {

    /** name -> packageFormat, the fixed real set of AWS-hosted public upstream connections. */
    private static final Map<String, String> EXTERNAL_CONNECTIONS = Map.ofEntries(
            Map.entry("public:maven-clojars", "maven"),
            Map.entry("public:maven-commonsware", "maven"),
            Map.entry("public:maven-googleandroid", "maven"),
            Map.entry("public:maven-gradleplugins", "maven"),
            Map.entry("public:maven-central", "maven"),
            Map.entry("public:npmjs", "npm"),
            Map.entry("public:nuget-org", "nuget"),
            Map.entry("public:pypi", "pypi"),
            Map.entry("public:ruby-gems-org", "ruby"),
            Map.entry("public:crates-io", "cargo"));

    private static final Set<String> PACKAGE_FORMATS =
            Set.of("npm", "pypi", "maven", "nuget", "generic", "ruby", "swift", "cargo");
    private static final Set<String> ENDPOINT_TYPES = Set.of("dualstack", "ipv4");
    private static final Set<String> VERSION_STATUSES =
            Set.of("Published", "Unfinished", "Unlisted", "Archived", "Disposed");

    private static final Pattern DOMAIN_NAME = Pattern.compile("[a-z][a-z0-9\\-]{0,48}[a-z0-9]");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{1,99}");
    private static final Pattern ACCOUNT_ID = Pattern.compile("[0-9]{12}");

    public record DomainView(CodeArtifactDomain domain, int repositoryCount) {}
    public record ResourcePolicy(String resourceArn, String revision, String document) {}

    private final AccountAwareStorageBackend<CodeArtifactDomain> domains;
    private final AccountAwareStorageBackend<CodeArtifactRepository> repositories;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;

    @Inject
    public CodeArtifactService(StorageFactory storageFactory, RegionResolver regionResolver, EmulatorConfig config) {
        this.domains = storageFactory.create("codeartifact", "codeartifact-domains.json",
                new TypeReference<Map<String, CodeArtifactDomain>>() {});
        this.repositories = storageFactory.create("codeartifact", "codeartifact-repositories.json",
                new TypeReference<Map<String, CodeArtifactRepository>>() {});
        this.regionResolver = regionResolver;
        this.config = config;
    }

    // ---------------------------------------------------------------- domains

    public synchronized DomainView createDomain(String region, String domain, String encryptionKey,
                                                 Map<String, String> tags) {
        validateDomainName(domain);
        String owner = regionResolver.getAccountId();
        String key = domainKey(region, domain);
        if (domains.getForAccount(owner, key).isPresent()) {
            throw conflict("Domain with name '" + domain + "' already exists.");
        }
        CodeArtifactDomain d = new CodeArtifactDomain();
        d.setName(domain);
        d.setOwner(owner);
        d.setRegion(region);
        d.setArn(regionResolver.buildArn("codeartifact", region, "domain/" + domain));
        d.setEncryptionKey(encryptionKey != null ? encryptionKey
                : regionResolver.buildArn("kms", region, "alias/aws/codeartifact"));
        d.setCreatedTime(Instant.now().getEpochSecond());
        d.setTags(validateTags(tags, Map.of()));
        domains.putForAccount(owner, key, d);
        return new DomainView(d, 0);
    }

    public synchronized DomainView deleteDomain(String region, String domain, String domainOwner) {
        String owner = effectiveOwner(domainOwner);
        String key = domainKey(region, domain);
        CodeArtifactDomain d = requireDomain(owner, key);
        int repoCount = repositoryCountForDomain(owner, region, domain);
        if (repoCount > 0) {
            throw conflict("Domain '" + domain + "' contains repositories and cannot be deleted "
                    + "until they are deleted.");
        }
        domains.deleteForAccount(owner, key);
        return new DomainView(d, 0);
    }

    public DomainView describeDomain(String region, String domain, String domainOwner) {
        String owner = effectiveOwner(domainOwner);
        CodeArtifactDomain d = requireDomain(owner, domainKey(region, domain));
        return new DomainView(d, repositoryCountForDomain(owner, region, domain));
    }

    public PaginatedResult<DomainView> listDomains(String region, Integer maxResults, String nextToken) {
        String owner = regionResolver.getAccountId();
        List<CodeArtifactDomain> matching = domains.scanForAccount(owner, k -> k.startsWith(region + "::"));
        PaginatedResult<CodeArtifactDomain> page = Pagination.paginate(matching, CodeArtifactDomain::getName,
                maxResults, nextToken, 100, 1000, "ValidationException");
        List<DomainView> views = page.items().stream()
                .map(d -> new DomainView(d, repositoryCountForDomain(owner, region, d.getName())))
                .toList();
        return new PaginatedResult<>(views, page.nextToken());
    }

    public synchronized ResourcePolicy putDomainPermissionsPolicy(String region, String domain, String domainOwner,
                                                                   String policyDocument, String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = domainKey(region, domain);
        CodeArtifactDomain d = requireDomain(owner, key);
        checkRevision(d.getPolicyRevision(), policyRevision);
        validatePolicyDocument(policyDocument);
        d.setPolicyDocument(policyDocument);
        d.setPolicyRevision(newRevision());
        domains.putForAccount(owner, key, d);
        return new ResourcePolicy(d.getArn(), d.getPolicyRevision(), d.getPolicyDocument());
    }

    public ResourcePolicy getDomainPermissionsPolicy(String region, String domain, String domainOwner) {
        String owner = effectiveOwner(domainOwner);
        CodeArtifactDomain d = requireDomain(owner, domainKey(region, domain));
        if (d.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with domain '" + domain + "'.");
        }
        return new ResourcePolicy(d.getArn(), d.getPolicyRevision(), d.getPolicyDocument());
    }

    public synchronized ResourcePolicy deleteDomainPermissionsPolicy(String region, String domain, String domainOwner,
                                                                      String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = domainKey(region, domain);
        CodeArtifactDomain d = requireDomain(owner, key);
        if (d.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with domain '" + domain + "'.");
        }
        checkRevision(d.getPolicyRevision(), policyRevision);
        ResourcePolicy removed = new ResourcePolicy(d.getArn(), d.getPolicyRevision(), d.getPolicyDocument());
        d.setPolicyDocument(null);
        d.setPolicyRevision(null);
        domains.putForAccount(owner, key, d);
        return removed;
    }

    // ------------------------------------------------------------ repositories

    public synchronized CodeArtifactRepository createRepository(String region, String domain, String domainOwner,
                                                                  String repository, String description,
                                                                  List<String> upstreams, Map<String, String> tags) {
        validateRepositoryName(repository);
        String owner = effectiveOwner(domainOwner);
        requireDomain(owner, domainKey(region, domain));
        String key = repositoryKey(region, domain, repository);
        if (repositories.getForAccount(owner, key).isPresent()) {
            throw conflict("Repository with name '" + repository + "' already exists in domain '" + domain + "'.");
        }
        validateUpstreams(owner, region, domain, repository, upstreams);
        validateDescription(description);

        CodeArtifactRepository r = new CodeArtifactRepository();
        r.setName(repository);
        r.setDomainName(domain);
        r.setDomainOwner(owner);
        r.setAdministratorAccount(regionResolver.getAccountId());
        r.setRegion(region);
        r.setArn(regionResolver.buildArn("codeartifact", region, "repository/" + domain + "/" + repository));
        r.setDescription(description);
        r.setUpstreams(upstreams != null ? new ArrayList<>(upstreams) : new ArrayList<>());
        r.setCreatedTime(Instant.now().getEpochSecond());
        r.setTags(validateTags(tags, Map.of()));
        repositories.putForAccount(owner, key, r);
        return r;
    }

    public synchronized CodeArtifactRepository deleteRepository(String region, String domain, String domainOwner,
                                                                  String repository) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        repositories.deleteForAccount(owner, key);
        return r;
    }

    public CodeArtifactRepository describeRepository(String region, String domain, String domainOwner,
                                                       String repository) {
        String owner = effectiveOwner(domainOwner);
        return requireRepository(owner, repositoryKey(region, domain, repository));
    }

    public synchronized CodeArtifactRepository updateRepository(String region, String domain, String domainOwner,
                                                                  String repository, String description,
                                                                  List<String> upstreams) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        if (upstreams != null) {
            if (!upstreams.isEmpty() && !r.getExternalConnections().isEmpty()) {
                throw conflict("Repository '" + repository + "' has an external connection; "
                        + "a repository cannot have both an external connection and upstream repositories.");
            }
            validateUpstreams(owner, region, domain, repository, upstreams);
            r.setUpstreams(new ArrayList<>(upstreams));
        }
        if (description != null) {
            validateDescription(description);
            r.setDescription(description);
        }
        repositories.putForAccount(owner, key, r);
        return r;
    }

    public PaginatedResult<CodeArtifactRepository> listRepositories(String region, String repositoryPrefix,
                                                                      Integer maxResults, String nextToken) {
        String owner = regionResolver.getAccountId();
        List<CodeArtifactRepository> matching = repositories.scanForAccount(owner, k -> k.startsWith(region + "::"))
                .stream()
                .filter(r -> repositoryPrefix == null || r.getName().startsWith(repositoryPrefix))
                .toList();
        return Pagination.paginate(matching, r -> r.getDomainName() + "::" + r.getName(),
                maxResults, nextToken, 100, 1000, "ValidationException");
    }

    public PaginatedResult<CodeArtifactRepository> listRepositoriesInDomain(String region, String domain,
                                                                             String domainOwner,
                                                                             String administratorAccount,
                                                                             String repositoryPrefix,
                                                                             Integer maxResults, String nextToken) {
        String owner = effectiveOwner(domainOwner);
        requireDomain(owner, domainKey(region, domain));
        List<CodeArtifactRepository> matching = repositories
                .scanForAccount(owner, k -> k.startsWith(region + "::" + domain + "::"))
                .stream()
                .filter(r -> repositoryPrefix == null || r.getName().startsWith(repositoryPrefix))
                .filter(r -> administratorAccount == null || administratorAccount.equals(r.getAdministratorAccount()))
                .toList();
        return Pagination.paginate(matching, CodeArtifactRepository::getName,
                maxResults, nextToken, 100, 1000, "ValidationException");
    }

    public String getRepositoryEndpoint(String region, String domain, String domainOwner, String repository,
                                         String format, String endpointType) {
        if (format == null || !PACKAGE_FORMATS.contains(format)) {
            throw validation("format must be one of " + PACKAGE_FORMATS + ".");
        }
        if (endpointType != null && !ENDPOINT_TYPES.contains(endpointType)) {
            throw validation("endpointType must be one of " + ENDPOINT_TYPES + ".");
        }
        String owner = effectiveOwner(domainOwner);
        requireRepository(owner, repositoryKey(region, domain, repository));
        return config.effectiveBaseUrl() + "/codeartifact/" + format + "/" + domain + "/" + repository + "/";
    }

    public synchronized ResourcePolicy putRepositoryPermissionsPolicy(String region, String domain,
                                                                       String domainOwner, String repository,
                                                                       String policyDocument, String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        checkRevision(r.getPolicyRevision(), policyRevision);
        validatePolicyDocument(policyDocument);
        r.setPolicyDocument(policyDocument);
        r.setPolicyRevision(newRevision());
        repositories.putForAccount(owner, key, r);
        return new ResourcePolicy(r.getArn(), r.getPolicyRevision(), r.getPolicyDocument());
    }

    public ResourcePolicy getRepositoryPermissionsPolicy(String region, String domain, String domainOwner,
                                                          String repository) {
        String owner = effectiveOwner(domainOwner);
        CodeArtifactRepository r = requireRepository(owner, repositoryKey(region, domain, repository));
        if (r.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with repository '" + repository + "'.");
        }
        return new ResourcePolicy(r.getArn(), r.getPolicyRevision(), r.getPolicyDocument());
    }

    public synchronized ResourcePolicy deleteRepositoryPermissionsPolicy(String region, String domain,
                                                                          String domainOwner, String repository,
                                                                          String policyRevision) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        if (r.getPolicyDocument() == null) {
            throw notFound("No resource policy is associated with repository '" + repository + "'.");
        }
        checkRevision(r.getPolicyRevision(), policyRevision);
        ResourcePolicy removed = new ResourcePolicy(r.getArn(), r.getPolicyRevision(), r.getPolicyDocument());
        r.setPolicyDocument(null);
        r.setPolicyRevision(null);
        repositories.putForAccount(owner, key, r);
        return removed;
    }

    public synchronized CodeArtifactRepository associateExternalConnection(String region, String domain,
                                                                            String domainOwner, String repository,
                                                                            String externalConnection) {
        String format = EXTERNAL_CONNECTIONS.get(externalConnection);
        if (format == null) {
            throw validation("externalConnection must be one of " + EXTERNAL_CONNECTIONS.keySet() + ".");
        }
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        if (!r.getExternalConnections().isEmpty()) {
            throw conflict("Repository '" + repository + "' already has an external connection; "
                    + "a repository can only have one.");
        }
        if (!r.getUpstreams().isEmpty()) {
            throw conflict("Repository '" + repository + "' has upstream repositories; "
                    + "a repository cannot have both an external connection and upstream repositories.");
        }
        List<ExternalConnection> connections = new ArrayList<>(r.getExternalConnections());
        connections.add(new ExternalConnection(externalConnection, format, "Available"));
        r.setExternalConnections(connections);
        repositories.putForAccount(owner, key, r);
        return r;
    }

    public synchronized CodeArtifactRepository disassociateExternalConnection(String region, String domain,
                                                                               String domainOwner, String repository,
                                                                               String externalConnection) {
        String owner = effectiveOwner(domainOwner);
        String key = repositoryKey(region, domain, repository);
        CodeArtifactRepository r = requireRepository(owner, key);
        List<ExternalConnection> connections = new ArrayList<>(r.getExternalConnections());
        boolean removed = connections.removeIf(ec -> ec.getExternalConnectionName().equals(externalConnection));
        if (!removed) {
            throw notFound("Repository '" + repository + "' has no external connection named '"
                    + externalConnection + "'.");
        }
        r.setExternalConnections(connections);
        repositories.putForAccount(owner, key, r);
        return r;
    }

    public record PackageCoordinate(String region, String domain, String owner, String repository,
                                    String format, String namespace, String name) {}
    public record AuthorizationToken(String authorizationToken, long expiration) {}
    public record AssetDownload(byte[] content, String name, String version, String revision) {}
    public record VersionMutation(List<String> versions, Map<String, String> revisions, String expectedStatus,
                                  String targetStatus, boolean allowOverwrite, boolean includeFromUpstream) {}

    public synchronized AuthorizationToken getAuthorizationToken(String region, String domain, String domainOwner,
                                                                  Long durationSeconds) {
        validateDomainName(domain);
        long duration = durationSeconds == null ? 43200 : durationSeconds;
        if (duration < 900 || duration > 43200) {
            throw validation("duration must be between 900 and 43200 seconds; session-bound duration 0 requires "
                    + "an assumed-role session expiration.");
        }
        String owner = effectiveOwner(domainOwner);
        CodeArtifactDomain stored = requireDomain(owner, domainKey(region, domain));
        byte[] random = new byte[256];
        new SecureRandom().nextBytes(random);
        String token = Base64.getEncoder().encodeToString(random);
        long now = Instant.now().getEpochSecond();
        long expiration = now + duration;
        Map<String, Long> tokens = new LinkedHashMap<>(stored.getAuthorizationTokens());
        tokens.entrySet().removeIf(entry -> entry.getValue() <= now);
        tokens.put(sha256(token.getBytes(StandardCharsets.UTF_8)), expiration);
        stored.setAuthorizationTokens(tokens);
        domains.putForAccount(owner, domainKey(region, domain), stored);
        return new AuthorizationToken(token, expiration);
    }

    public boolean isAuthorizationTokenValid(String region, String domain, String domainOwner, String token) {
        CodeArtifactDomain stored = requireDomain(effectiveOwner(domainOwner), domainKey(region, domain));
        return token != null && stored.getAuthorizationTokens()
                .getOrDefault(sha256(token.getBytes(StandardCharsets.UTF_8)), 0L) > Instant.now().getEpochSecond();
    }

    public synchronized Map<String, Object> publishPackageVersion(PackageCoordinate coordinate, String version,
                                                                  String asset, byte[] content, String hash,
                                                                  boolean unfinished) {
        validatePackageCoordinate(coordinate);
        if (!"generic".equals(coordinate.format())) {
            throw validation("PublishPackageVersion supports only the generic format.");
        }
        validateComponent(version, "version", 255);
        validateAssetName(asset);
        if (content == null) {
            content = new byte[0];
        }
        if (hash == null || !hash.matches("[a-f0-9]{64}") || !sha256(content).equals(hash)) {
            throw validation("The asset SHA-256 does not match the supplied asset content.");
        }
        CodeArtifactRepository repository = packageRepository(coordinate);
        CodeArtifactPackage pkg = repository.getPackages().get(packageKey(coordinate));
        if (pkg == null) {
            pkg = emptyPackage(coordinate);
        }
        if ("BLOCK".equals(pkg.restrictions().get("publish"))) {
            throw new AwsException("AccessDeniedException", "Publishing is blocked for this package.", 403);
        }
        Version previous = pkg.versions().get(version);
        if (previous != null && !"Unfinished".equals(previous.status())) {
            throw conflict("Only Unfinished package versions can accept additional assets.");
        }
        Map<String, byte[]> assets = previous == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(previous.assets());
        byte[] existingAsset = assets.get(asset);
        if (existingAsset != null && !Arrays.equals(existingAsset, content)) {
            throw conflict("An asset with this name and different content already exists in the package version.");
        }
        assets.put(asset, content.clone());
        String revision = existingAsset == null ? newRevision() : previous.revision();
        Version next = new Version(version, revision, unfinished ? "Unfinished" : "Published",
                Instant.now().getEpochSecond(), coordinate.repository(), assets);
        Map<String, Version> versions = new LinkedHashMap<>(pkg.versions());
        versions.put(version, next);
        savePackage(coordinate, repository, withVersions(pkg, versions));
        Map<String, Object> result = versionIdentity(pkg, next);
        result.put("status", next.status());
        result.put("asset", assetSummary(asset, content));
        return result;
    }

    public synchronized Map<String, Object> describePackage(PackageCoordinate coordinate) {
        return Map.of("package", packageDescription(requirePackage(coordinate), false));
    }

    public synchronized Map<String, Object> deletePackage(PackageCoordinate coordinate) {
        CodeArtifactRepository repository = packageRepository(coordinate);
        CodeArtifactPackage pkg = requirePackage(repository, coordinate);
        Map<String, CodeArtifactPackage> packages = new LinkedHashMap<>(repository.getPackages());
        packages.remove(packageKey(coordinate));
        repository.setPackages(packages);
        saveRepository(coordinate, repository);
        return Map.of("deletedPackage", packageDescription(pkg, true));
    }

    public synchronized Map<String, Object> putPackageOriginConfiguration(PackageCoordinate coordinate,
                                                                         Map<String, String> restrictions) {
        if (restrictions == null || restrictions.size() != 2
                || !Set.of("ALLOW", "BLOCK").contains(restrictions.getOrDefault("publish", ""))
                || !Set.of("ALLOW", "BLOCK").contains(restrictions.getOrDefault("upstream", ""))) {
            throw validation("restrictions must specify publish and upstream as ALLOW or BLOCK.");
        }
        CodeArtifactRepository repository = packageRepository(coordinate);
        CodeArtifactPackage pkg = repository.getPackages().getOrDefault(packageKey(coordinate), emptyPackage(coordinate));
        savePackage(coordinate, repository, new CodeArtifactPackage(pkg.format(), pkg.namespace(), pkg.name(),
                new LinkedHashMap<>(restrictions), pkg.versions()));
        return Map.of("originConfiguration", Map.of("restrictions", restrictions));
    }

    public synchronized Map<String, Object> describePackageVersion(PackageCoordinate coordinate, String version) {
        CodeArtifactPackage pkg = requirePackage(coordinate);
        Version stored = requireVersion(pkg, version);
        Map<String, Object> description = packageIdentity(pkg);
        description.remove("package");
        description.put("packageName", pkg.name());
        description.put("displayName", pkg.name());
        description.put("version", stored.version());
        description.put("revision", stored.revision());
        description.put("status", stored.status());
        description.put("publishedTime", stored.publishedTime());
        description.put("origin", versionOrigin(stored));
        return Map.of("packageVersion", description);
    }

    public synchronized Map<String, Object> listPackages(PackageCoordinate coordinate, String prefix,
                                                         String publish, String upstream,
                                                         Integer maxResults, String nextToken) {
        validateDomainName(coordinate.domain());
        validateRepositoryName(coordinate.repository());
        if (coordinate.format() != null && !PACKAGE_FORMATS.contains(coordinate.format())) {
            throw validation("Invalid package format.");
        }
        if ((publish != null && !Set.of("ALLOW", "BLOCK").contains(publish))
                || (upstream != null && !Set.of("ALLOW", "BLOCK").contains(upstream))) {
            throw validation("publish and upstream must be ALLOW or BLOCK.");
        }
        CodeArtifactRepository repository = describeRepository(coordinate.region(), coordinate.domain(),
                coordinate.owner(), coordinate.repository());
        List<CodeArtifactPackage> matching = repository.getPackages().values().stream()
                .filter(pkg -> coordinate.format() == null || coordinate.format().equals(pkg.format()))
                .filter(pkg -> coordinate.namespace() == null || coordinate.namespace().equals(pkg.namespace()))
                .filter(pkg -> prefix == null || pkg.name().startsWith(prefix))
                .filter(pkg -> publish == null || publish.equals(pkg.restrictions().get("publish")))
                .filter(pkg -> upstream == null || upstream.equals(pkg.restrictions().get("upstream")))
                .toList();
        PaginatedResult<CodeArtifactPackage> page = Pagination.paginate(matching,
                pkg -> packageKey(pkg.format(), pkg.namespace(), pkg.name()), maxResults, nextToken,
                100, 1000, "ValidationException");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packages", page.items().stream().map(pkg -> packageDescription(pkg, true)).toList());
        addNextToken(result, page.nextToken());
        return result;
    }

    public synchronized Map<String, Object> listPackageVersions(PackageCoordinate coordinate, String status,
                                                                String originType, String sortBy,
                                                                Integer maxResults, String nextToken) {
        CodeArtifactPackage pkg = requirePackage(coordinate);
        if (status != null && !VERSION_STATUSES.contains(status)) {
            throw validation("Invalid package version status.");
        }
        if (originType != null && !Set.of("INTERNAL", "EXTERNAL", "UNKNOWN").contains(originType)) {
            throw validation("Invalid package version origin type.");
        }
        if (sortBy != null && !"PUBLISHED_TIME".equals(sortBy)) {
            throw validation("sortBy must be PUBLISHED_TIME.");
        }
        List<Version> matching = pkg.versions().values().stream()
                .filter(version -> status == null || status.equals(version.status()))
                .filter(version -> originType == null || "INTERNAL".equals(originType)).toList();
        PaginatedResult<Version> page = Pagination.paginate(matching,
                version -> sortBy == null ? version.version()
                        : String.format("%019d:%s", Long.MAX_VALUE - version.publishedTime(), version.version()),
                maxResults, nextToken, 100, 1000, "ValidationException");
        Map<String, Object> result = packageIdentity(pkg);
        result.put("versions", page.items().stream().map(version -> Map.of("version", version.version(),
                "revision", version.revision(), "status", version.status(), "origin", versionOrigin(version))).toList());
        addNextToken(result, page.nextToken());
        return result;
    }

    public synchronized Map<String, Object> listPackageVersionAssets(PackageCoordinate coordinate, String version,
                                                                     Integer maxResults, String nextToken) {
        CodeArtifactPackage pkg = requirePackage(coordinate);
        Version stored = requireVersion(pkg, version);
        PaginatedResult<String> page = Pagination.paginate(new ArrayList<>(stored.assets().keySet()),
                name -> name, maxResults, nextToken, 100, 1000, "ValidationException");
        Map<String, Object> result = versionIdentity(pkg, stored);
        result.put("assets", page.items().stream().map(name -> assetSummary(name, stored.assets().get(name))).toList());
        addNextToken(result, page.nextToken());
        return result;
    }

    public synchronized AssetDownload getPackageVersionAsset(PackageCoordinate coordinate, String version,
                                                              String asset, String revision) {
        validateAssetName(asset);
        Version stored = requireVersion(requirePackage(coordinate), version);
        if (revision != null && !revision.equals(stored.revision())) {
            throw conflict("The requested revision does not match the package version revision.");
        }
        byte[] content = stored.assets().get(asset);
        if (content == null || "Disposed".equals(stored.status()) || "Archived".equals(stored.status())) {
            throw notFound("The asset is not available for this package version.");
        }
        return new AssetDownload(content.clone(), asset, version, stored.revision());
    }

    public synchronized Map<String, Object> getPackageVersionReadme(PackageCoordinate coordinate, String version) {
        requireVersion(requirePackage(coordinate), version);
        throw notFound("The readme file of this package version is not found.");
    }

    public synchronized Map<String, Object> listPackageVersionDependencies(PackageCoordinate coordinate,
                                                                           String version) {
        CodeArtifactPackage pkg = requirePackage(coordinate);
        Version stored = requireVersion(pkg, version);
        Map<String, Object> result = versionIdentity(pkg, stored);
        result.put("dependencies", List.of());
        return result;
    }

    public synchronized Map<String, Object> mutatePackageVersions(PackageCoordinate coordinate, String operation,
                                                                   VersionMutation request) {
        validateVersionSelection(request, false);
        if (request.expectedStatus() != null && !VERSION_STATUSES.contains(request.expectedStatus())) {
            throw validation("Invalid expected package version status.");
        }
        if ("status".equals(operation) && (request.targetStatus() == null
                || !Set.of("Published", "Unlisted", "Archived").contains(request.targetStatus()))) {
            throw validation("targetStatus must be Published, Unlisted, or Archived.");
        }
        CodeArtifactRepository repository = packageRepository(coordinate);
        CodeArtifactPackage pkg = requirePackage(repository, coordinate);
        Map<String, Version> versions = new LinkedHashMap<>(pkg.versions());
        Map<String, Object> successful = new LinkedHashMap<>();
        Map<String, Object> failed = new LinkedHashMap<>();
        for (String version : request.versions()) {
            Version stored = versions.get(version);
            String failure = versionFailure(stored, version, request);
            if (failure == null && "status".equals(operation) && "Disposed".equals(stored.status())) {
                failure = "NOT_ALLOWED";
            }
            if (failure != null) {
                failed.put(version, versionError(failure));
                continue;
            }
            String status = switch (operation) {
                case "delete" -> "Deleted";
                case "dispose" -> "Disposed";
                case "status" -> request.targetStatus();
                default -> throw validation("Unknown package version operation.");
            };
            if ("delete".equals(operation)) {
                versions.remove(version);
            } else {
                versions.put(version, new Version(stored.version(), stored.revision(), status, stored.publishedTime(),
                        stored.originRepository(), "dispose".equals(operation) ? Map.of() : stored.assets()));
            }
            successful.put(version, Map.of("revision", stored.revision(), "status", status));
        }
        if (!failed.isEmpty() && !"delete".equals(operation)) {
            successful.keySet().forEach(version -> failed.put(version, versionError("SKIPPED")));
            return Map.of("successfulVersions", Map.of(), "failedVersions", failed);
        }
        savePackage(coordinate, repository, withVersions(pkg, versions));
        return Map.of("successfulVersions", successful, "failedVersions", failed);
    }

    public synchronized Map<String, Object> copyPackageVersions(PackageCoordinate source, String destination,
                                                                VersionMutation request) {
        validateVersionSelection(request, true);
        validateRepositoryName(source.repository());
        validateRepositoryName(destination);
        if (source.repository().equals(destination)) {
            throw validation("Source and destination repositories must be different.");
        }
        CodeArtifactRepository sourceRepository = packageRepository(source);
        PackageCoordinate target = new PackageCoordinate(source.region(), source.domain(), source.owner(), destination,
                source.format(), source.namespace(), source.name());
        CodeArtifactRepository repository = packageRepository(target);
        CodeArtifactPackage pkg = repository.getPackages().getOrDefault(packageKey(target), emptyPackage(target));
        Map<String, Version> versions = new LinkedHashMap<>(pkg.versions());
        Map<String, Object> successful = new LinkedHashMap<>();
        Map<String, Object> failed = new LinkedHashMap<>();
        List<String> selection = request.versions() == null ? new ArrayList<>(request.revisions().keySet())
                : request.versions();
        for (String version : selection) {
            Version stored = findCopyVersion(source, sourceRepository, version, request.includeFromUpstream(),
                    new HashSet<>());
            String failure = versionFailure(stored, version, request);
            if (failure == null && Set.of("Unfinished", "Disposed").contains(stored.status())) {
                failure = "NOT_ALLOWED";
            }
            Version existing = versions.get(version);
            if (failure == null && existing != null && sameAssets(existing, stored)) {
                continue;
            }
            if (failure == null && existing != null && !request.allowOverwrite()) {
                failure = "ALREADY_EXISTS";
            }
            if (failure != null) {
                failed.put(version, versionError(failure));
                continue;
            }
            Map<String, byte[]> assets = new LinkedHashMap<>();
            stored.assets().forEach((name, content) -> assets.put(name, content.clone()));
            versions.put(version, new Version(stored.version(), stored.revision(), stored.status(),
                    stored.publishedTime(), stored.originRepository(), assets));
            successful.put(version, Map.of("revision", stored.revision(), "status", stored.status()));
        }
        if (!failed.isEmpty()) {
            successful.keySet().forEach(version -> failed.put(version, versionError("SKIPPED")));
            return Map.of("successfulVersions", Map.of(), "failedVersions", failed);
        }
        if (!successful.isEmpty()) {
            savePackage(target, repository, withVersions(pkg, versions));
        }
        return Map.of("successfulVersions", successful, "failedVersions", failed);
    }

    private static boolean sameAssets(Version left, Version right) {
        return left.assets().keySet().equals(right.assets().keySet())
                && left.assets().entrySet().stream()
                .allMatch(entry -> Arrays.equals(entry.getValue(), right.assets().get(entry.getKey())));
    }

    private Version findCopyVersion(PackageCoordinate coordinate, CodeArtifactRepository repository, String version,
                                    boolean includeUpstream, Set<String> visited) {
        if (!visited.add(repository.getName())) {
            return null;
        }
        CodeArtifactPackage pkg = repository.getPackages().get(packageKey(coordinate));
        if (pkg != null && pkg.versions().containsKey(version)) {
            return pkg.versions().get(version);
        }
        if (includeUpstream) {
            for (String upstream : repository.getUpstreams()) {
                CodeArtifactRepository upstreamRepository = describeRepository(coordinate.region(), coordinate.domain(),
                        coordinate.owner(), upstream);
                Version found = findCopyVersion(coordinate, upstreamRepository, version, true, visited);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void validateVersionSelection(VersionMutation request, boolean copy) {
        List<String> selection = request.versions();
        if (copy && selection != null && request.revisions() != null && !request.revisions().isEmpty()) {
            throw validation("Specify either versions or versionRevisions, not both.");
        }
        if (selection == null && copy && request.revisions() != null) {
            selection = new ArrayList<>(request.revisions().keySet());
        }
        if (selection == null || selection.isEmpty() || selection.size() > 100) {
            throw validation("Between 1 and 100 package versions must be specified.");
        }
        selection.forEach(version -> validateComponent(version, "version", 255));
        if (request.revisions() != null && !selection.containsAll(request.revisions().keySet())) {
            throw validation("versionRevisions must refer to selected versions.");
        }
    }

    private static String versionFailure(Version version, String name, VersionMutation request) {
        if (version == null) {
            return "NOT_FOUND";
        }
        String revision = request.revisions() == null ? null : request.revisions().get(name);
        if (revision != null && !revision.equals(version.revision())) {
            return "MISMATCHED_REVISION";
        }
        if (request.expectedStatus() != null && !request.expectedStatus().equals(version.status())) {
            return "MISMATCHED_STATUS";
        }
        return null;
    }

    private static Map<String, String> versionError(String code) {
        return Map.of("errorCode", code, "errorMessage", "Package version operation failed: " + code);
    }

    private CodeArtifactRepository packageRepository(PackageCoordinate coordinate) {
        validatePackageCoordinate(coordinate);
        return describeRepository(coordinate.region(), coordinate.domain(), coordinate.owner(), coordinate.repository());
    }

    private CodeArtifactPackage requirePackage(PackageCoordinate coordinate) {
        return requirePackage(packageRepository(coordinate), coordinate);
    }

    private static CodeArtifactPackage requirePackage(CodeArtifactRepository repository, PackageCoordinate coordinate) {
        CodeArtifactPackage pkg = repository.getPackages().get(packageKey(coordinate));
        if (pkg == null) {
            throw notFound("Package not found.");
        }
        return pkg;
    }

    private static Version requireVersion(CodeArtifactPackage pkg, String version) {
        validateComponent(version, "version", 255);
        Version stored = pkg.versions().get(version);
        if (stored == null) {
            throw notFound("Package version not found.");
        }
        return stored;
    }

    private void savePackage(PackageCoordinate coordinate, CodeArtifactRepository repository, CodeArtifactPackage pkg) {
        Map<String, CodeArtifactPackage> packages = new LinkedHashMap<>(repository.getPackages());
        packages.put(packageKey(coordinate), pkg);
        repository.setPackages(packages);
        saveRepository(coordinate, repository);
    }

    private void saveRepository(PackageCoordinate coordinate, CodeArtifactRepository repository) {
        repositories.putForAccount(effectiveOwner(coordinate.owner()),
                repositoryKey(coordinate.region(), coordinate.domain(), coordinate.repository()), repository);
    }

    private static CodeArtifactPackage emptyPackage(PackageCoordinate coordinate) {
        return new CodeArtifactPackage(coordinate.format(), coordinate.namespace(), coordinate.name(),
                Map.of("publish", "ALLOW", "upstream", "BLOCK"), Map.of());
    }

    private static CodeArtifactPackage withVersions(CodeArtifactPackage pkg, Map<String, Version> versions) {
        return new CodeArtifactPackage(pkg.format(), pkg.namespace(), pkg.name(), pkg.restrictions(), versions);
    }

    private static void validatePackageCoordinate(PackageCoordinate coordinate) {
        validateDomainName(coordinate.domain());
        validateRepositoryName(coordinate.repository());
        if (coordinate.format() == null || !PACKAGE_FORMATS.contains(coordinate.format())) {
            throw validation("Invalid package format.");
        }
        validateComponent(coordinate.name(), "package", 255);
        if (coordinate.namespace() != null || Set.of("generic", "maven", "swift").contains(coordinate.format())) {
            validateComponent(coordinate.namespace(), "namespace", 255);
        }
    }

    private static void validateComponent(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)
                || c == '/' || c == '#')) {
            throw validation(field + " is not a valid package identifier.");
        }
    }

    private static void validateAssetName(String asset) {
        if (asset == null || asset.isEmpty() || asset.length() > 255 || !asset.matches("\\P{C}+")) {
            throw validation("asset must contain 1-255 non-control characters.");
        }
    }

    private static String packageKey(PackageCoordinate coordinate) {
        return packageKey(coordinate.format(), coordinate.namespace(), coordinate.name());
    }

    private static String packageKey(String format, String namespace, String name) {
        String scope = namespace == null ? "" : namespace;
        return format + ":" + scope.length() + ":" + scope + ":" + name;
    }

    private static Map<String, Object> packageIdentity(CodeArtifactPackage pkg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", pkg.format());
        if (pkg.namespace() != null) {
            result.put("namespace", pkg.namespace());
        }
        result.put("package", pkg.name());
        return result;
    }

    private static Map<String, Object> packageDescription(CodeArtifactPackage pkg, boolean summary) {
        Map<String, Object> result = packageIdentity(pkg);
        if (!summary) {
            result.remove("package");
            result.put("name", pkg.name());
        }
        result.put("originConfiguration", Map.of("restrictions", pkg.restrictions()));
        return result;
    }

    private static Map<String, Object> versionIdentity(CodeArtifactPackage pkg, Version version) {
        Map<String, Object> result = packageIdentity(pkg);
        result.put("version", version.version());
        result.put("versionRevision", version.revision());
        return result;
    }

    private static Map<String, Object> versionOrigin(Version version) {
        return Map.of("originType", "INTERNAL", "domainEntryPoint",
                Map.of("repositoryName", version.originRepository()));
    }

    private static Map<String, Object> assetSummary(String name, byte[] content) {
        return Map.of("name", name, "size", content.length, "hashes", Map.of("SHA-256", sha256(content)));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private static void addNextToken(Map<String, Object> result, String nextToken) {
        if (nextToken != null) {
            result.put("nextToken", nextToken);
        }
    }

    // -------------------------------------------------------------------- tags

    public synchronized void tagResource(String resourceArn, Map<String, String> newTags) {
        ResourceRef ref = parseResourceArn(resourceArn);
        if (ref.repository() == null) {
            String key = domainKey(ref.region(), ref.domain());
            CodeArtifactDomain d = requireDomain(ref.owner(), key);
            d.setTags(validateTags(newTags, d.getTags()));
            domains.putForAccount(ref.owner(), key, d);
        } else {
            String key = repositoryKey(ref.region(), ref.domain(), ref.repository());
            CodeArtifactRepository r = requireRepository(ref.owner(), key);
            r.setTags(validateTags(newTags, r.getTags()));
            repositories.putForAccount(ref.owner(), key, r);
        }
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeys) {
        ResourceRef ref = parseResourceArn(resourceArn);
        if (ref.repository() == null) {
            String key = domainKey(ref.region(), ref.domain());
            CodeArtifactDomain d = requireDomain(ref.owner(), key);
            Map<String, String> tags = new LinkedHashMap<>(d.getTags());
            tagKeys.forEach(tags::remove);
            d.setTags(tags);
            domains.putForAccount(ref.owner(), key, d);
        } else {
            String key = repositoryKey(ref.region(), ref.domain(), ref.repository());
            CodeArtifactRepository r = requireRepository(ref.owner(), key);
            Map<String, String> tags = new LinkedHashMap<>(r.getTags());
            tagKeys.forEach(tags::remove);
            r.setTags(tags);
            repositories.putForAccount(ref.owner(), key, r);
        }
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        ResourceRef ref = parseResourceArn(resourceArn);
        if (ref.repository() == null) {
            return requireDomain(ref.owner(), domainKey(ref.region(), ref.domain())).getTags();
        }
        return requireRepository(ref.owner(), repositoryKey(ref.region(), ref.domain(), ref.repository())).getTags();
    }

    @Override
    public void clear() {
        domains.clear();
        repositories.clear();
    }

    // ----------------------------------------------------------------- helpers

    private record ResourceRef(String type, String region, String owner, String domain, String repository) {}

    private ResourceRef parseResourceArn(String resourceArn) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is not a valid ARN.");
        }
        if (!"codeartifact".equals(arn.service())) {
            throw validation("resourceArn must be a CodeArtifact ARN.");
        }
        String[] parts = arn.resource().split("/");
        if (parts.length == 2 && "domain".equals(parts[0])) {
            return new ResourceRef("domain", arn.region(), arn.accountId(), parts[1], null);
        }
        if (parts.length == 3 && "repository".equals(parts[0])) {
            return new ResourceRef("repository", arn.region(), arn.accountId(), parts[1], parts[2]);
        }
        throw validation("resourceArn must reference a domain or a repository.");
    }

    private String effectiveOwner(String domainOwner) {
        if (domainOwner == null || domainOwner.isBlank()) {
            return regionResolver.getAccountId();
        }
        if (!ACCOUNT_ID.matcher(domainOwner).matches()) {
            throw validation("domainOwner must be a 12-digit account ID.");
        }
        return domainOwner;
    }

    private int repositoryCountForDomain(String owner, String region, String domain) {
        return repositories.scanForAccount(owner, k -> k.startsWith(region + "::" + domain + "::")).size();
    }

    private void validateUpstreams(String owner, String region, String domain, String repository,
                                    List<String> upstreams) {
        if (upstreams == null) {
            return;
        }
        if (upstreams.size() > 10) {
            throw new AwsException("ServiceQuotaExceededException",
                    "A repository can have a maximum of 10 direct upstream repositories.", 402);
        }
        for (String upstream : upstreams) {
            if (upstream.equals(repository)) {
                throw validation("A repository cannot be its own upstream.");
            }
            if (repositories.getForAccount(owner, repositoryKey(region, domain, upstream)).isEmpty()) {
                throw notFound("Upstream repository '" + upstream + "' was not found in domain '" + domain + "'.");
            }
        }
    }

    private CodeArtifactDomain requireDomain(String owner, String key) {
        return domains.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Domain not found."));
    }

    private CodeArtifactRepository requireRepository(String owner, String key) {
        return repositories.getForAccount(owner, key)
                .orElseThrow(() -> notFound("Repository not found."));
    }

    private void checkRevision(String currentRevision, String requestedRevision) {
        if (requestedRevision != null && !requestedRevision.equals(currentRevision)) {
            throw conflict("The policy revision does not match the current policy revision.");
        }
    }

    private static String newRevision() {
        return UUID.randomUUID().toString();
    }

    private static void validatePolicyDocument(String policyDocument) {
        if (policyDocument == null || policyDocument.isBlank() || policyDocument.length() > 7168) {
            throw validation("policyDocument must be 1-7168 characters.");
        }
    }

    private static void validateDescription(String description) {
        if (description != null && description.length() > 1000) {
            throw validation("description must be at most 1000 characters.");
        }
    }

    private static void validateDomainName(String domain) {
        if (domain == null || !DOMAIN_NAME.matcher(domain).matches()) {
            throw validation("domain must be 2-50 characters and match [a-z][a-z0-9-]*[a-z0-9].");
        }
    }

    private static void validateRepositoryName(String repository) {
        if (repository == null || !REPOSITORY_NAME.matcher(repository).matches()) {
            throw validation("repository must be 2-100 characters and match [A-Za-z0-9][A-Za-z0-9._-]*.");
        }
    }

    private static Map<String, String> validateTags(Map<String, String> newTags, Map<String, String> existing) {
        Map<String, String> merged = new LinkedHashMap<>(existing);
        if (newTags == null) {
            return merged;
        }
        newTags.forEach((k, v) -> {
            if (k == null || k.isBlank() || k.length() > 128 || k.startsWith("aws:")) {
                throw validation("Tag key '" + k + "' is invalid.");
            }
            if (v == null || v.length() > 256) {
                throw validation("Tag value for key '" + k + "' is invalid.");
            }
            merged.put(k, v);
        });
        if (merged.size() > 200) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The maximum number of tags (200) for this resource has been exceeded.", 402);
        }
        return merged;
    }

    private static String domainKey(String region, String domain) {
        return region + "::" + domain;
    }

    private static String repositoryKey(String region, String domain, String repository) {
        return region + "::" + domain + "::" + repository;
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
}
