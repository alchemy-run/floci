package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codeartifact.model.Asset;
import io.github.hectorvent.floci.services.codeartifact.model.CodePackage;
import io.github.hectorvent.floci.services.codeartifact.model.Domain;
import io.github.hectorvent.floci.services.codeartifact.model.PackageVersion;
import io.github.hectorvent.floci.services.codeartifact.model.Repository;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CodeArtifact restJson1 control plane: domains, repositories, generic package
 * publish/copy/dispose, authorization tokens, and repository endpoints.
 */
@ApplicationScoped
public class CodeArtifactService {

    static final String SERVICE = "codeartifact";
    private static final Logger LOG = Logger.getLogger(CodeArtifactService.class);
    private static final Pattern DOMAIN_NAME = Pattern.compile("[a-z][a-z0-9-]{0,48}[a-z0-9]");
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{1,99}");
    private static final long DEFAULT_TOKEN_SECONDS = 43_200L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StorageBackend<String, Domain> domains;
    private final StorageBackend<String, Repository> repositories;
    private final StorageBackend<String, CodePackage> packages;
    private final RegionResolver regionResolver;
    private final EventBridgeService eventBridgeService;

    @Inject
    public CodeArtifactService(StorageFactory storageFactory,
                               RegionResolver regionResolver,
                               EventBridgeService eventBridgeService) {
        this(storageFactory.create("codeartifact", "codeartifact-domains.json",
                        new TypeReference<Map<String, Domain>>() {
                        }),
                storageFactory.create("codeartifact", "codeartifact-repositories.json",
                        new TypeReference<Map<String, Repository>>() {
                        }),
                storageFactory.create("codeartifact", "codeartifact-packages.json",
                        new TypeReference<Map<String, CodePackage>>() {
                        }),
                regionResolver,
                eventBridgeService);
    }

    CodeArtifactService(StorageBackend<String, Domain> domains,
                        StorageBackend<String, Repository> repositories,
                        StorageBackend<String, CodePackage> packages,
                        RegionResolver regionResolver) {
        this(domains, repositories, packages, regionResolver, null);
    }

    CodeArtifactService(StorageBackend<String, Domain> domains,
                        StorageBackend<String, Repository> repositories,
                        StorageBackend<String, CodePackage> packages,
                        RegionResolver regionResolver,
                        EventBridgeService eventBridgeService) {
        this.domains = domains;
        this.repositories = repositories;
        this.packages = packages;
        this.regionResolver = regionResolver;
        this.eventBridgeService = eventBridgeService;
    }

    public synchronized Map<String, Object> createDomain(String region, String domainName, JsonNode body) {
        requireDomainName(domainName);
        String account = regionResolver.getAccountId();
        String key = domainKey(region, domainName);
        if (findDomain(region, domainName, account).isPresent()) {
            throw conflict(domainName, "domain", "Domain " + domainName + " already exists.");
        }
        long now = Instant.now().getEpochSecond();
        Domain domain = new Domain();
        domain.setName(domainName);
        domain.setOwner(account);
        domain.setArn(domainArn(region, account, domainName));
        domain.setStatus("Active");
        domain.setCreatedTime(now);
        domain.setRegion(region);
        String encryptionKey = textOrNull(body, "encryptionKey");
        if (encryptionKey == null || encryptionKey.isBlank()) {
            encryptionKey = AwsArnUtils.Arn.of("kms", region, account, "alias/aws/codeartifact").toString();
        }
        domain.setEncryptionKey(encryptionKey);
        domain.setS3BucketArn("arn:aws:s3:::codeartifact-assets-" + account + "-" + region);
        domain.setTags(readTags(body));
        domains.put(key, domain);
        return domainDescription(domain);
    }

    public synchronized Map<String, Object> describeDomain(String region, String domainName, String domainOwner) {
        return domainDescription(requireDomain(region, domainName, domainOwner));
    }

    public synchronized Map<String, Object> deleteDomain(String region, String domainName, String domainOwner) {
        Optional<Domain> existing = findDomain(region, domainName, domainOwner);
        if (existing.isEmpty()) {
            return Map.of();
        }
        Domain domain = existing.get();
        String prefix = repoPrefix(region, domain.getName());
        if (!repositories.scan(key -> key.startsWith(prefix)).isEmpty()) {
            throw conflict(domain.getName(), "domain",
                    "Domain " + domain.getName() + " cannot be deleted because it contains repositories.");
        }
        domains.delete(domainKey(region, domain.getName()));
        Map<String, Object> description = domainDescription(domain);
        description.put("status", "Deleted");
        return description;
    }

    public synchronized Map<String, Object> listDomains(String region) {
        String account = regionResolver.getAccountId();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Domain domain : domains.scan(key -> key.startsWith(region + "::"))) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", domain.getName());
            summary.put("owner", domain.getOwner());
            summary.put("arn", domain.getArn());
            summary.put("status", domain.getStatus());
            summary.put("createdTime", domain.getCreatedTime());
            summary.put("encryptionKey", domain.getEncryptionKey());
            summaries.add(summary);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domains", summaries);
        return result;
    }

    public synchronized Map<String, Object> createRepository(String region, String domainName, String domainOwner,
                                                             String repositoryName, JsonNode body) {
        Domain domain = requireDomain(region, domainName, domainOwner);
        requireRepositoryName(repositoryName);
        String key = repoKey(region, domain.getName(), repositoryName);
        if (repositories.get(key).isPresent()) {
            throw conflict(repositoryName, "repository", "Repository " + repositoryName + " already exists.");
        }
        long now = Instant.now().getEpochSecond();
        Repository repository = new Repository();
        repository.setName(repositoryName);
        repository.setDomainName(domain.getName());
        repository.setDomainOwner(domain.getOwner());
        repository.setAdministratorAccount(regionResolver.getAccountId());
        repository.setArn(repositoryArn(region, domain.getOwner(), domain.getName(), repositoryName));
        repository.setDescription(textOrNull(body, "description"));
        repository.setCreatedTime(now);
        repository.setRegion(region);
        repository.setUpstreams(readUpstreamNames(body));
        repository.setTags(readTags(body));
        repositories.put(key, repository);
        return repositoryDescription(repository);
    }

    public synchronized Map<String, Object> describeRepository(String region, String domainName, String domainOwner,
                                                               String repositoryName) {
        return repositoryDescription(requireRepository(region, domainName, domainOwner, repositoryName));
    }

    public synchronized Map<String, Object> updateRepository(String region, String domainName, String domainOwner,
                                                             String repositoryName, JsonNode body) {
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        if (body != null && body.has("description") && !body.get("description").isNull()) {
            repository.setDescription(body.get("description").asText());
        }
        if (body != null && body.has("upstreams")) {
            repository.setUpstreams(readUpstreamNames(body));
        }
        repositories.put(repoKey(region, repository.getDomainName(), repository.getName()),
                repository);
        return repositoryDescription(repository);
    }

    public synchronized Map<String, Object> deleteRepository(String region, String domainName, String domainOwner,
                                                             String repositoryName) {
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        String siblingPrefix = repoPrefix(region, repository.getDomainName());
        for (Repository other : repositories.scan(key -> key.startsWith(siblingPrefix))) {
            if (other.getUpstreams() != null && other.getUpstreams().contains(repository.getName())) {
                throw conflict(repository.getName(), "repository",
                        "Repository " + repository.getName() + " is being used as an upstream repository.");
            }
        }
        String prefix = packagePrefix(region, repository.getDomainName(),
                repository.getName());
        for (String key : new ArrayList<>(packages.keys())) {
            if (key.startsWith(prefix)) {
                packages.delete(key);
            }
        }
        repositories.delete(repoKey(region, repository.getDomainName(),
                repository.getName()));
        return repositoryDescription(repository);
    }

    public synchronized Map<String, Object> associateExternalConnection(String region, String domainName,
                                                                        String domainOwner, String repositoryName,
                                                                        String externalConnection) {
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        if (externalConnection == null || externalConnection.isBlank()) {
            throw validation("externalConnection is required.");
        }
        List<String> connections = new ArrayList<>(repository.getExternalConnections());
        if (!connections.contains(externalConnection)) {
            if (!connections.isEmpty()) {
                throw validation("A repository can have at most one external connection.");
            }
            connections.add(externalConnection);
            repository.setExternalConnections(connections);
            repositories.put(repoKey(region, repository.getDomainName(),
                    repository.getName()), repository);
        }
        return repositoryDescription(repository);
    }

    public synchronized Map<String, Object> disassociateExternalConnection(String region, String domainName,
                                                                           String domainOwner, String repositoryName,
                                                                           String externalConnection) {
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        List<String> connections = new ArrayList<>(repository.getExternalConnections());
        connections.remove(externalConnection);
        repository.setExternalConnections(connections);
        repositories.put(repoKey(region, repository.getDomainName(),
                repository.getName()), repository);
        return repositoryDescription(repository);
    }

    public synchronized Map<String, Object> listRepositories(String region, String domainName, String domainOwner) {
        Domain domain = domainName == null || domainName.isBlank()
                ? null
                : requireDomain(region, domainName, domainOwner);
        String prefix = domain != null
                ? repoPrefix(region, domain.getName())
                : region + "::";
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Repository repository : repositories.scan(key -> key.startsWith(prefix))) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", repository.getName());
            summary.put("administratorAccount", repository.getAdministratorAccount());
            summary.put("domainName", repository.getDomainName());
            summary.put("domainOwner", repository.getDomainOwner());
            summary.put("arn", repository.getArn());
            if (repository.getDescription() != null) {
                summary.put("description", repository.getDescription());
            }
            summary.put("createdTime", repository.getCreatedTime());
            summaries.add(summary);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repositories", summaries);
        return result;
    }

    public synchronized Map<String, Object> getAuthorizationToken(String region, String domainName,
                                                                  String domainOwner, Long durationSeconds) {
        requireDomain(region, domainName, domainOwner);
        long duration = durationSeconds == null || durationSeconds == 0 ? DEFAULT_TOKEN_SECONDS : durationSeconds;
        byte[] bytes = new byte[96];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorizationToken", token);
        result.put("expiration", Instant.now().getEpochSecond() + duration);
        return result;
    }

    public synchronized Map<String, Object> getRepositoryEndpoint(String region, String domainName, String domainOwner,
                                                                  String repositoryName, String format) {
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        if (format == null || format.isBlank()) {
            throw validation("format is required.");
        }
        String endpoint = "https://" + repository.getDomainName() + "-" + repository.getDomainOwner()
                + ".d.codeartifact." + region + ".amazonaws.com/" + format + "/" + repository.getName() + "/";
        return Map.of("repositoryEndpoint", endpoint);
    }

    public synchronized Map<String, Object> publishPackageVersion(String region, String domainName, String domainOwner,
                                                                  String repositoryName, String format,
                                                                  String namespace, String packageName,
                                                                  String version, String assetName,
                                                                  String assetSha256, Boolean unfinished,
                                                                  byte[] content) {
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        requireText(format, "format");
        requireText(packageName, "package");
        requireText(version, "version");
        requireText(assetName, "asset");
        byte[] body = content != null ? content : new byte[0];
        String sha = sha256Hex(body);
        if (assetSha256 != null && looksLikeSha256(assetSha256) && !assetSha256.equalsIgnoreCase(sha)) {
            throw validation("The provided asset SHA-256 does not match the uploaded content.");
        }
        String ns = namespace == null ? "" : namespace;
        String key = packageKey(region, repository.getDomainName(),
                repository.getName(), format, ns, packageName);
        CodePackage pkg = packages.get(key).orElseGet(() -> {
            CodePackage created = new CodePackage();
            created.setFormat(format);
            created.setNamespace(ns);
            created.setName(packageName);
            created.setDomainName(repository.getDomainName());
            created.setRepositoryName(repository.getName());
            created.setPublishRestriction("ALLOW");
            created.setUpstreamRestriction("ALLOW");
            return created;
        });
        PackageVersion existing = pkg.getVersions().get(version);
        if (existing != null && "Published".equals(existing.getStatus()) && "generic".equals(format)) {
            throw conflict(packageName, "package-version",
                    "Version " + version + " of package " + packageName + " is already Published.");
        }
        PackageVersion pkgVersion = existing != null ? existing : new PackageVersion();
        pkgVersion.setVersion(version);
        boolean finish = unfinished == null || !unfinished;
        String previousStatus = pkgVersion.getStatus();
        pkgVersion.setStatus(finish ? "Published" : "Unfinished");
        pkgVersion.setRevision(newRevision());
        pkgVersion.setPublishedTime(Instant.now().getEpochSecond());
        Asset asset = new Asset();
        asset.setName(assetName);
        asset.setSize(body.length);
        asset.setSha256(sha);
        asset.setContentBase64(Base64.getEncoder().encodeToString(body));
        pkgVersion.getAssets().put(assetName, asset);
        pkg.getVersions().put(version, pkgVersion);
        packages.put(key, pkg);
        String operation = existing == null ? "Created" : "Updated";
        emitPackageEvent(repository, pkg, pkgVersion, operation, previousStatus);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", format);
        putNamespace(result, ns);
        result.put("package", packageName);
        result.put("version", version);
        result.put("versionRevision", pkgVersion.getRevision());
        result.put("status", pkgVersion.getStatus());
        result.put("asset", assetSummary(asset));
        return result;
    }

    public synchronized Map<String, Object> describePackage(String region, String domainName, String domainOwner,
                                                            String repositoryName, String format, String namespace,
                                                            String packageName) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("package", packageDescription(pkg));
        return result;
    }

    public synchronized Map<String, Object> describePackageVersion(String region, String domainName, String domainOwner,
                                                                   String repositoryName, String format,
                                                                   String namespace, String packageName,
                                                                   String version) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        PackageVersion pkgVersion = requireVersion(pkg, version);
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("format", pkg.getFormat());
        putNamespace(description, pkg.getNamespace());
        description.put("packageName", pkg.getName());
        description.put("version", pkgVersion.getVersion());
        description.put("revision", pkgVersion.getRevision());
        description.put("status", pkgVersion.getStatus());
        description.put("publishedTime", pkgVersion.getPublishedTime());
        description.put("origin", Map.of("originType", "INTERNAL"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageVersion", description);
        return result;
    }

    public synchronized Map<String, Object> listPackages(String region, String domainName, String domainOwner,
                                                         String repositoryName, String format, String namespace) {
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        String prefix = packagePrefix(region, repository.getDomainName(),
                repository.getName());
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (CodePackage pkg : packages.scan(key -> key.startsWith(prefix))) {
            if (format != null && !format.isBlank() && !format.equals(pkg.getFormat())) {
                continue;
            }
            if (namespace != null && !namespace.isBlank() && !namespace.equals(pkg.getNamespace())) {
                continue;
            }
            summaries.add(packageSummary(pkg));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packages", summaries);
        return result;
    }

    public synchronized Map<String, Object> listPackageVersions(String region, String domainName, String domainOwner,
                                                                String repositoryName, String format, String namespace,
                                                                String packageName, String status) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        List<Map<String, Object>> versions = new ArrayList<>();
        for (PackageVersion pkgVersion : pkg.getVersions().values()) {
            if (status != null && !status.isBlank() && !status.equals(pkgVersion.getStatus())) {
                continue;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("version", pkgVersion.getVersion());
            summary.put("revision", pkgVersion.getRevision());
            summary.put("status", pkgVersion.getStatus());
            versions.add(summary);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", pkg.getFormat());
        putNamespace(result, pkg.getNamespace());
        result.put("package", pkg.getName());
        result.put("versions", versions);
        if (!versions.isEmpty()) {
            result.put("defaultDisplayVersion", versions.get(versions.size() - 1).get("version"));
        }
        return result;
    }

    public synchronized Map<String, Object> listPackageVersionAssets(String region, String domainName,
                                                                     String domainOwner, String repositoryName,
                                                                     String format, String namespace,
                                                                     String packageName, String version) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        PackageVersion pkgVersion = requireVersion(pkg, version);
        List<Map<String, Object>> assets = new ArrayList<>();
        for (Asset asset : pkgVersion.getAssets().values()) {
            assets.add(assetSummary(asset));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", pkg.getFormat());
        putNamespace(result, pkg.getNamespace());
        result.put("package", pkg.getName());
        result.put("version", pkgVersion.getVersion());
        result.put("versionRevision", pkgVersion.getRevision());
        result.put("assets", assets);
        return result;
    }

    public synchronized DownloadedAsset getPackageVersionAsset(String region, String domainName, String domainOwner,
                                                               String repositoryName, String format, String namespace,
                                                               String packageName, String version, String assetName) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        PackageVersion pkgVersion = requireVersion(pkg, version);
        if (assetName == null || assetName.isBlank()) {
            throw validation("asset is required.");
        }
        Asset asset = pkgVersion.getAssets().get(assetName);
        if (asset == null) {
            throw notFound(assetName, "asset", "Asset " + assetName + " was not found.");
        }
        byte[] content = asset.getContentBase64() == null
                ? new byte[0]
                : Base64.getDecoder().decode(asset.getContentBase64());
        return new DownloadedAsset(asset.getName(), pkgVersion.getVersion(), pkgVersion.getRevision(), content);
    }

    public synchronized Map<String, Object> getPackageVersionReadme(String region, String domainName,
                                                                    String domainOwner, String repositoryName,
                                                                    String format, String namespace,
                                                                    String packageName, String version) {
        requirePackage(region, domainName, domainOwner, repositoryName, format, namespace, packageName);
        requireVersion(requirePackage(region, domainName, domainOwner, repositoryName, format, namespace, packageName),
                version);
        throw notFound(packageName, "package-version",
                "The readme file of this package version is not found.");
    }

    public synchronized Map<String, Object> listPackageVersionDependencies(String region, String domainName,
                                                                           String domainOwner, String repositoryName,
                                                                           String format, String namespace,
                                                                           String packageName, String version) {
        requirePackage(region, domainName, domainOwner, repositoryName, format, namespace, packageName);
        requireVersion(requirePackage(region, domainName, domainOwner, repositoryName, format, namespace, packageName),
                version);
        throw notFound(packageName, "package-version",
                "The dependency file of this package version is not found.");
    }

    public synchronized Map<String, Object> updatePackageVersionsStatus(String region, String domainName,
                                                                        String domainOwner, String repositoryName,
                                                                        String format, String namespace,
                                                                        String packageName, JsonNode body) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        String target = requiredText(body, "targetStatus");
        String expected = textOrNull(body, "expectedStatus");
        List<String> versions = readStringList(body, "versions");
        Map<String, Object> successful = new LinkedHashMap<>();
        Map<String, Object> failed = new LinkedHashMap<>();
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        for (String version : versions) {
            PackageVersion pkgVersion = pkg.getVersions().get(version);
            if (pkgVersion == null) {
                failed.put(version, error("NOT_FOUND", "Version " + version + " was not found."));
                continue;
            }
            if (expected != null && !expected.equals(pkgVersion.getStatus())) {
                failed.put(version, error("MISMATCHED_STATUS",
                        "Version " + version + " has status " + pkgVersion.getStatus() + "."));
                continue;
            }
            String previous = pkgVersion.getStatus();
            pkgVersion.setStatus(target);
            pkgVersion.setRevision(newRevision());
            successful.put(version, successInfo(pkgVersion));
            emitPackageEvent(repository, pkg, pkgVersion, "Updated", previous);
        }
        packages.put(packageKey(region, repository.getDomainName(),
                repository.getName(), pkg.getFormat(), ns(pkg), pkg.getName()), pkg);
        return versionBatchResult(successful, failed);
    }

    public synchronized Map<String, Object> putPackageOriginConfiguration(String region, String domainName,
                                                                          String domainOwner, String repositoryName,
                                                                          String format, String namespace,
                                                                          String packageName, JsonNode body) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        JsonNode restrictions = body != null ? body.get("restrictions") : null;
        if (restrictions == null || !restrictions.isObject()) {
            throw validation("restrictions is required.");
        }
        String publish = restrictions.path("publish").asText("ALLOW");
        String upstream = restrictions.path("upstream").asText("ALLOW");
        pkg.setPublishRestriction(publish);
        pkg.setUpstreamRestriction(upstream);
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        packages.put(packageKey(region, repository.getDomainName(),
                repository.getName(), pkg.getFormat(), ns(pkg), pkg.getName()), pkg);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originConfiguration", originConfiguration(pkg));
        return result;
    }

    public synchronized Map<String, Object> copyPackageVersions(String region, String domainName, String domainOwner,
                                                                String sourceRepository, String destinationRepository,
                                                                String format, String namespace, String packageName,
                                                                JsonNode body) {
        Repository source = requireRepository(region, domainName, domainOwner, sourceRepository);
        Repository destination = requireRepository(region, domainName, domainOwner, destinationRepository);
        CodePackage sourcePkg = requirePackage(region, domainName, domainOwner, sourceRepository, format, namespace,
                packageName);
        boolean allowOverwrite = body != null && body.path("allowOverwrite").asBoolean(false);
        List<String> versions = readStringList(body, "versions");
        Map<String, Object> successful = new LinkedHashMap<>();
        Map<String, Object> failed = new LinkedHashMap<>();
        String destKey = packageKey(region, destination.getDomainName(),
                destination.getName(), sourcePkg.getFormat(), ns(sourcePkg), sourcePkg.getName());
        CodePackage destPkg = packages.get(destKey).orElseGet(() -> {
            CodePackage created = new CodePackage();
            created.setFormat(sourcePkg.getFormat());
            created.setNamespace(sourcePkg.getNamespace());
            created.setName(sourcePkg.getName());
            created.setDomainName(destination.getDomainName());
            created.setRepositoryName(destination.getName());
            created.setPublishRestriction(sourcePkg.getPublishRestriction());
            created.setUpstreamRestriction(sourcePkg.getUpstreamRestriction());
            return created;
        });
        for (String version : versions) {
            PackageVersion sourceVersion = sourcePkg.getVersions().get(version);
            if (sourceVersion == null) {
                failed.put(version, error("NOT_FOUND", "Version " + version + " was not found."));
                continue;
            }
            if (!allowOverwrite && destPkg.getVersions().containsKey(version)) {
                failed.put(version, error("ALREADY_EXISTS", "Version " + version + " already exists."));
                continue;
            }
            PackageVersion copied = copyVersion(sourceVersion);
            destPkg.getVersions().put(version, copied);
            successful.put(version, successInfo(copied));
            emitPackageEvent(destination, destPkg, copied, "Created", null);
        }
        packages.put(destKey, destPkg);
        return versionBatchResult(successful, failed);
    }

    public synchronized Map<String, Object> disposePackageVersions(String region, String domainName, String domainOwner,
                                                                   String repositoryName, String format,
                                                                   String namespace, String packageName,
                                                                   JsonNode body) {
        return mutateVersions(region, domainName, domainOwner, repositoryName, format, namespace, packageName, body,
                "Disposed");
    }

    public synchronized Map<String, Object> deletePackageVersions(String region, String domainName, String domainOwner,
                                                                  String repositoryName, String format,
                                                                  String namespace, String packageName,
                                                                  JsonNode body) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        String expected = textOrNull(body, "expectedStatus");
        List<String> versions = readStringList(body, "versions");
        Map<String, Object> successful = new LinkedHashMap<>();
        Map<String, Object> failed = new LinkedHashMap<>();
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        for (String version : versions) {
            PackageVersion pkgVersion = pkg.getVersions().get(version);
            if (pkgVersion == null) {
                failed.put(version, error("NOT_FOUND", "Version " + version + " was not found."));
                continue;
            }
            if (expected != null && !expected.equals(pkgVersion.getStatus())) {
                failed.put(version, error("MISMATCHED_STATUS",
                        "Version " + version + " has status " + pkgVersion.getStatus() + "."));
                continue;
            }
            if (!"Disposed".equals(pkgVersion.getStatus()) && expected == null) {
                failed.put(version, error("MISMATCHED_STATUS",
                        "Version " + version + " must be Disposed before it can be deleted."));
                continue;
            }
            pkg.getVersions().remove(version);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("revision", pkgVersion.getRevision());
            info.put("status", "Deleted");
            successful.put(version, info);
            pkgVersion.setStatus("Deleted");
            emitPackageEvent(repository, pkg, pkgVersion, "Deleted", "Disposed");
        }
        packages.put(packageKey(region, repository.getDomainName(),
                repository.getName(), pkg.getFormat(), ns(pkg), pkg.getName()), pkg);
        return versionBatchResult(successful, failed);
    }

    public synchronized Map<String, Object> deletePackage(String region, String domainName, String domainOwner,
                                                          String repositoryName, String format, String namespace,
                                                          String packageName) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        packages.delete(packageKey(region, repository.getDomainName(),
                repository.getName(), pkg.getFormat(), ns(pkg), pkg.getName()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deletedPackage", packageSummary(pkg));
        return result;
    }

    public synchronized Map<String, Object> listTagsForResource(String region, String resourceArn) {
        TaggedResource resource = requireTagged(region, resourceArn);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tags", toTagList(resource.tags()));
        return result;
    }

    public synchronized void tagResource(String region, String resourceArn, JsonNode body) {
        TaggedResource resource = requireTagged(region, resourceArn);
        Map<String, String> tags = new LinkedHashMap<>(resource.tags());
        tags.putAll(readTags(body));
        resource.applyTags(tags);
    }

    public synchronized void untagResource(String region, String resourceArn, JsonNode body) {
        TaggedResource resource = requireTagged(region, resourceArn);
        Map<String, String> tags = new LinkedHashMap<>(resource.tags());
        for (String key : readStringList(body, "tagKeys")) {
            tags.remove(key);
        }
        resource.applyTags(tags);
    }

    private Map<String, Object> mutateVersions(String region, String domainName, String domainOwner,
                                               String repositoryName, String format, String namespace,
                                               String packageName, JsonNode body, String targetStatus) {
        CodePackage pkg = requirePackage(region, domainName, domainOwner, repositoryName, format, namespace,
                packageName);
        String expected = textOrNull(body, "expectedStatus");
        List<String> versions = readStringList(body, "versions");
        Map<String, Object> successful = new LinkedHashMap<>();
        Map<String, Object> failed = new LinkedHashMap<>();
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        for (String version : versions) {
            PackageVersion pkgVersion = pkg.getVersions().get(version);
            if (pkgVersion == null) {
                failed.put(version, error("NOT_FOUND", "Version " + version + " was not found."));
                continue;
            }
            if (expected != null && !expected.equals(pkgVersion.getStatus())) {
                failed.put(version, error("MISMATCHED_STATUS",
                        "Version " + version + " has status " + pkgVersion.getStatus() + "."));
                continue;
            }
            String previous = pkgVersion.getStatus();
            pkgVersion.setStatus(targetStatus);
            pkgVersion.setRevision(newRevision());
            successful.put(version, successInfo(pkgVersion));
            emitPackageEvent(repository, pkg, pkgVersion, "Updated", previous);
        }
        packages.put(packageKey(region, repository.getDomainName(),
                repository.getName(), pkg.getFormat(), ns(pkg), pkg.getName()), pkg);
        return versionBatchResult(successful, failed);
    }

    private Domain requireDomain(String region, String domainName, String domainOwner) {
        return findDomain(region, domainName, domainOwner)
                .orElseThrow(() -> notFound(domainName, "domain", "Domain " + domainName + " was not found."));
    }

    private Optional<Domain> findDomain(String region, String domainName, String domainOwner) {
        requireDomainName(domainName);
        Optional<Domain> direct = domains.get(domainKey(region, domainName));
        if (direct.isPresent() && ownerMatches(direct.get(), domainOwner)) {
            return direct;
        }
        Optional<Domain> scanned = domains.scan(key -> key.equals(domainKey(region, domainName))
                        || key.endsWith("::" + domainName)).stream()
                .filter(domain -> ownerMatches(domain, domainOwner))
                .findFirst();
        if (scanned.isPresent()) {
            return scanned;
        }
        if (domains instanceof AccountAwareStorageBackend<Domain> aware) {
            return aware.scanAllAccounts().stream()
                    .filter(domain -> domainName.equals(domain.getName()))
                    .filter(domain -> ownerMatches(domain, domainOwner))
                    .findFirst();
        }
        return Optional.empty();
    }

    private static boolean ownerMatches(Domain domain, String domainOwner) {
        return domainOwner == null || domainOwner.isBlank() || domainOwner.equals(domain.getOwner());
    }

    private Repository requireRepository(String region, String domainName, String domainOwner, String repositoryName) {
        Domain domain = requireDomain(region, domainName, domainOwner);
        requireRepositoryName(repositoryName);
        return repositories.get(repoKey(region, domain.getName(), repositoryName))
                .orElseThrow(() -> notFound(repositoryName, "repository",
                        "Repository " + repositoryName + " was not found."));
    }

    private CodePackage requirePackage(String region, String domainName, String domainOwner, String repositoryName,
                                       String format, String namespace, String packageName) {
        Repository repository = requireRepository(region, domainName, domainOwner, repositoryName);
        requireText(format, "format");
        requireText(packageName, "package");
        String ns = namespace == null ? "" : namespace;
        return packages.get(packageKey(region, repository.getDomainName(),
                        repository.getName(), format, ns, packageName))
                .orElseThrow(() -> notFound(packageName, "package", "Package " + packageName + " was not found."));
    }

    private PackageVersion requireVersion(CodePackage pkg, String version) {
        requireText(version, "version");
        PackageVersion pkgVersion = pkg.getVersions().get(version);
        if (pkgVersion == null) {
            throw notFound(version, "package-version", "Package version " + version + " was not found.");
        }
        return pkgVersion;
    }

    private TaggedResource requireTagged(String region, String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw validation("resourceArn is required.");
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn is invalid.");
        }
        if (!SERVICE.equals(arn.service())) {
            throw validation("resourceArn is not a CodeArtifact ARN.");
        }
        String resource = arn.resource();
        if (resource.startsWith("domain/")) {
            String name = resource.substring("domain/".length());
            Domain domain = requireDomain(region, name, arn.accountId());
            return new TaggedResource(tagsOf(domain.getTags()), tags -> {
                domain.setTags(tags);
                domains.put(domainKey(region, domain.getName()), domain);
            });
        }
        if (resource.startsWith("repository/")) {
            String rest = resource.substring("repository/".length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                throw validation("resourceArn is not a repository ARN.");
            }
            String domainName = rest.substring(0, slash);
            String repositoryName = rest.substring(slash + 1);
            Repository repository = requireRepository(region, domainName, arn.accountId(), repositoryName);
            return new TaggedResource(tagsOf(repository.getTags()), tags -> {
                repository.setTags(tags);
                repositories.put(repoKey(region, repository.getDomainName(),
                        repository.getName()), repository);
            });
        }
        throw notFound(resourceArn, "domain", "Resource " + resourceArn + " was not found.");
    }

    private Map<String, Object> domainDescription(Domain domain) {
        String prefix = repoPrefix(domain.getRegion() != null ? domain.getRegion() : regionResolver.resolveRegion(null),
                domain.getName());
        int repositoryCount = repositories.scan(key -> key.startsWith(prefix)).size();
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("name", domain.getName());
        description.put("owner", domain.getOwner());
        description.put("arn", domain.getArn());
        description.put("status", domain.getStatus());
        description.put("createdTime", domain.getCreatedTime());
        description.put("encryptionKey", domain.getEncryptionKey());
        description.put("repositoryCount", repositoryCount);
        description.put("assetSizeBytes", 0);
        description.put("s3BucketArn", domain.getS3BucketArn());
        return description;
    }

    private Map<String, Object> repositoryDescription(Repository repository) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("name", repository.getName());
        description.put("administratorAccount", repository.getAdministratorAccount());
        description.put("domainName", repository.getDomainName());
        description.put("domainOwner", repository.getDomainOwner());
        description.put("arn", repository.getArn());
        if (repository.getDescription() != null) {
            description.put("description", repository.getDescription());
        }
        description.put("createdTime", repository.getCreatedTime());
        List<Map<String, Object>> upstreams = new ArrayList<>();
        for (String upstream : listOf(repository.getUpstreams())) {
            upstreams.add(Map.of("repositoryName", upstream));
        }
        description.put("upstreams", upstreams);
        List<Map<String, Object>> connections = new ArrayList<>();
        for (String connection : listOf(repository.getExternalConnections())) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("externalConnectionName", connection);
            info.put("status", "Available");
            connections.add(info);
        }
        description.put("externalConnections", connections);
        return description;
    }

    private Map<String, Object> packageDescription(CodePackage pkg) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("format", pkg.getFormat());
        putNamespace(description, pkg.getNamespace());
        description.put("name", pkg.getName());
        description.put("originConfiguration", originConfiguration(pkg));
        return description;
    }

    private Map<String, Object> packageSummary(CodePackage pkg) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("format", pkg.getFormat());
        putNamespace(summary, pkg.getNamespace());
        summary.put("package", pkg.getName());
        summary.put("originConfiguration", originConfiguration(pkg));
        return summary;
    }

    private Map<String, Object> originConfiguration(CodePackage pkg) {
        Map<String, Object> restrictions = new LinkedHashMap<>();
        restrictions.put("publish", pkg.getPublishRestriction() == null ? "ALLOW" : pkg.getPublishRestriction());
        restrictions.put("upstream", pkg.getUpstreamRestriction() == null ? "ALLOW" : pkg.getUpstreamRestriction());
        return Map.of("restrictions", restrictions);
    }

    private Map<String, Object> assetSummary(Asset asset) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", asset.getName());
        summary.put("size", asset.getSize());
        Map<String, Object> hashes = new LinkedHashMap<>();
        hashes.put("SHA-256", asset.getSha256());
        summary.put("hashes", hashes);
        return summary;
    }

    private void emitPackageEvent(Repository repository, CodePackage pkg, PackageVersion version,
                                  String operationType, String previousStatus) {
        if (eventBridgeService == null) {
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("domainName", repository.getDomainName());
            detail.put("domainOwner", repository.getDomainOwner());
            detail.put("repositoryName", repository.getName());
            detail.put("packageFormat", pkg.getFormat());
            if (pkg.getNamespace() != null && !pkg.getNamespace().isBlank()) {
                detail.put("packageNamespace", pkg.getNamespace());
            }
            detail.put("packageName", pkg.getName());
            detail.put("packageVersion", version.getVersion());
            detail.put("packageVersionState", version.getStatus());
            detail.put("packageVersionRevision", version.getRevision());
            detail.put("operationType", operationType);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("assetsAdded", "Created".equals(operationType) ? version.getAssets().size() : 0);
            changes.put("statusChanged", previousStatus != null && !previousStatus.equals(version.getStatus()));
            detail.put("changes", changes);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Source", "aws.codeartifact");
            entry.put("DetailType", "CodeArtifact Package Version State Change");
            entry.put("Detail", JSON.writeValueAsString(detail));
            entry.put("Resources", List.of(repository.getArn()));
            eventBridgeService.putEvents(List.of(entry), repository.getRegion());
        } catch (Exception e) {
            LOG.warnv("Failed to publish CodeArtifact package event: {0}", e.getMessage());
        }
    }

    private static Map<String, Object> versionBatchResult(Map<String, Object> successful, Map<String, Object> failed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("successfulVersions", successful);
        if (!failed.isEmpty()) {
            result.put("failedVersions", failed);
        }
        return result;
    }

    private static Map<String, Object> successInfo(PackageVersion version) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("revision", version.getRevision());
        info.put("status", version.getStatus());
        return info;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorCode", code);
        error.put("errorMessage", message);
        return error;
    }

    private static PackageVersion copyVersion(PackageVersion source) {
        PackageVersion copy = new PackageVersion();
        copy.setVersion(source.getVersion());
        copy.setStatus(source.getStatus());
        copy.setRevision(source.getRevision());
        copy.setPublishedTime(source.getPublishedTime());
        Map<String, Asset> assets = new LinkedHashMap<>();
        for (Map.Entry<String, Asset> entry : source.getAssets().entrySet()) {
            Asset original = entry.getValue();
            Asset asset = new Asset();
            asset.setName(original.getName());
            asset.setSize(original.getSize());
            asset.setSha256(original.getSha256());
            asset.setContentBase64(original.getContentBase64());
            assets.put(entry.getKey(), asset);
        }
        copy.setAssets(assets);
        return copy;
    }

    private static void putNamespace(Map<String, Object> target, String namespace) {
        if (namespace != null && !namespace.isBlank()) {
            target.put("namespace", namespace);
        }
    }

    private static String ns(CodePackage pkg) {
        return pkg.getNamespace() == null ? "" : pkg.getNamespace();
    }

    private static Map<String, String> tagsOf(Map<String, String> tags) {
        return tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    private static List<String> listOf(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static List<Map<String, String>> toTagList(Map<String, String> tags) {
        List<Map<String, String>> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : tagsOf(tags).entrySet()) {
            Map<String, String> tag = new LinkedHashMap<>();
            tag.put("key", entry.getKey());
            tag.put("value", entry.getValue());
            list.add(tag);
        }
        return list;
    }

    private static Map<String, String> readTags(JsonNode body) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (body == null || !body.has("tags") || !body.get("tags").isArray()) {
            return tags;
        }
        for (JsonNode node : body.get("tags")) {
            if (node.hasNonNull("key") && node.hasNonNull("value")) {
                tags.put(node.get("key").asText(), node.get("value").asText());
            }
        }
        return tags;
    }

    private static List<String> readUpstreamNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        if (body == null || !body.has("upstreams") || !body.get("upstreams").isArray()) {
            return names;
        }
        for (JsonNode node : body.get("upstreams")) {
            if (node.hasNonNull("repositoryName")) {
                names.add(node.get("repositoryName").asText());
            } else if (node.isTextual()) {
                names.add(node.asText());
            }
        }
        return names;
    }

    private static List<String> readStringList(JsonNode body, String field) {
        List<String> values = new ArrayList<>();
        if (body == null || !body.has(field) || !body.get(field).isArray()) {
            return values;
        }
        for (JsonNode node : body.get(field)) {
            if (!node.isNull()) {
                values.add(node.asText());
            }
        }
        return values;
    }

    private static String textOrNull(JsonNode body, String field) {
        if (body == null || !body.has(field) || body.get(field).isNull()) {
            return null;
        }
        String value = body.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private static String requiredText(JsonNode body, String field) {
        String value = textOrNull(body, field);
        if (value == null) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static void requireDomainName(String domainName) {
        requireText(domainName, "domain");
        if (!DOMAIN_NAME.matcher(domainName).matches()) {
            throw validation("domain must be 2-50 characters of lowercase letters, digits, and hyphens.");
        }
    }

    private static void requireRepositoryName(String repositoryName) {
        requireText(repositoryName, "repository");
        if (!REPOSITORY_NAME.matcher(repositoryName).matches()) {
            throw validation("repository must be 2-100 characters.");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
    }

    private static AwsException notFound(String resourceId, String resourceType, String message) {
        return new AwsException("ResourceNotFoundException", message, 404,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static AwsException conflict(String resourceId, String resourceType, String message) {
        return new AwsException("ConflictException", message, 409,
                Map.of("resourceId", resourceId, "resourceType", resourceType));
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400,
                Map.of("reason", "FIELD_VALIDATION_FAILED"));
    }

    private static String domainKey(String region, String domain) {
        return region + "::" + domain;
    }

    private static String repoKey(String region, String domain, String repository) {
        return region + "::" + domain + "::" + repository;
    }

    private static String repoPrefix(String region, String domain) {
        return region + "::" + domain + "::";
    }

    private static String packageKey(String region, String domain, String repository,
                                     String format, String namespace, String name) {
        return region + "::" + domain + "::" + repository + "::" + format + "::"
                + namespace + "::" + name;
    }

    private static String packagePrefix(String region, String domain, String repository) {
        return region + "::" + domain + "::" + repository + "::";
    }

    private static String domainArn(String region, String account, String domain) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "domain/" + domain).toString();
    }

    private static String repositoryArn(String region, String account, String domain, String repository) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "repository/" + domain + "/" + repository).toString();
    }

    private static String newRevision() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean looksLikeSha256(String value) {
        return value.length() == 64 && value.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            return HexFormat.of().formatHex(content);
        }
    }

    public record DownloadedAsset(String name, String version, String revision, byte[] content) {
    }

    private record TaggedResource(Map<String, String> tags, TagApplier applier) {
        void applyTags(Map<String, String> next) {
            applier.apply(next);
        }
    }

    @FunctionalInterface
    private interface TagApplier {
        void apply(Map<String, String> tags);
    }
}
