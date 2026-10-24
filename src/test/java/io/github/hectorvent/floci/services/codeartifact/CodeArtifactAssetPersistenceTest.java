package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PackageCoordinate;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PackageVersionAssetResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PublishPackageVersionResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.VersionMutation;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackageVersion;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Asset bytes must not ride along in the JSON-backed {@code codeartifact-package-versions.json}
 * store: that file is rewritten whole on every publish, so embedding a growing set of assets
 * there would make every later publish pay to re-serialize every earlier one. This exercises
 * {@link CodeArtifactService} against a real {@link PersistentStorage}, the same backend
 * {@code persistent} storage mode uses in production.
 */
class CodeArtifactAssetPersistenceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";

    @Test
    void assetBytesAreNotEmbeddedInThePersistedPackageVersionJson(@TempDir Path dir) throws IOException {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        byte[] content = "hello world, this is the asset payload".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "false", content);

        String json = Files.readString(dir.resolve("codeartifact-package-versions.json"));
        String base64Content = Base64.getEncoder().encodeToString(content);
        assertFalse(json.contains(base64Content),
                "persisted package-version JSON must not embed asset bytes: " + json);
    }

    @Test
    void assetContentSurvivesRestart(@TempDir Path dir) {
        CodeArtifactService first = newService(dir);
        first.createDomain(REGION, "dom", null, Map.of());
        first.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        PublishPackageVersionResult published = first.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", sha256Hex(content), "false", content);

        CodeArtifactService restarted = newService(dir);
        PackageVersionAssetResult result = restarted.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", null);

        assertEquals("hello world", new String(result.asset().getContent(), StandardCharsets.UTF_8));
        assertEquals(published.packageVersion().getRevision(), result.packageVersionRevision());
    }

    @Test
    void distinctAssetNamesNeverCollideOnDisk(@TempDir Path dir) {
        CodeArtifactService service = newService(dir);
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        String longToken = "p".repeat(255);
        List<String> names = List.of("b", "x/../b", ".", "..", "b/c", "/b", "b/");
        for (String name : names) {
            byte[] content = ("content of " + name).getBytes(StandardCharsets.UTF_8);
            service.publishPackageVersion(REGION, "dom", null, "repo", "generic", longToken, longToken, longToken,
                    name, sha256Hex(content), "true", content);
        }

        CodeArtifactService restarted = newService(dir);
        for (String name : names) {
            PackageVersionAssetResult result = restarted.getPackageVersionAsset(REGION, "dom", null, "repo",
                    "generic", longToken, longToken, longToken, name, null);
            assertEquals("content of " + name, new String(result.asset().getContent(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void copiedVersionsShareMetadataApisAndKeepBytesOutsideJsonAcrossRestart(@TempDir Path dir) throws IOException {
        CodeArtifactService first = newService(dir);
        first.createDomain(REGION, "dom", null, Map.of());
        first.createRepository(REGION, "dom", null, "source", null, null, Map.of());
        first.createRepository(REGION, "dom", null, "mirror", null, null, Map.of());
        byte[] content = "copied payload survives restart".getBytes(StandardCharsets.UTF_8);
        first.publishPackageVersion(REGION, "dom", null, "source", "generic", "scope", "pkg", "1",
                "asset.txt", sha256Hex(content), "false", content);
        PackageCoordinate source = new PackageCoordinate(REGION, "dom", null, "source", "generic", "scope", "pkg");
        VersionMutation selection = new VersionMutation(List.of("1"), Map.of(), null, null, false, false);
        assertEquals(Map.of(), first.copyPackageVersions(source, "mirror", selection).get("failedVersions"));

        String encoded = Base64.getEncoder().encodeToString(content);
        assertFalse(Files.readString(dir.resolve("codeartifact-repositories.json")).contains(encoded));
        assertFalse(Files.readString(dir.resolve("codeartifact-package-versions.json")).contains(encoded));
        CodeArtifactService restarted = newService(dir);
        assertEquals("copied payload survives restart", new String(restarted.getPackageVersionAsset(
                REGION, "dom", null, "mirror", "generic", "scope", "pkg", "1", "asset.txt", null)
                .asset().getContent(), StandardCharsets.UTF_8));
        PackageCoordinate mirror = new PackageCoordinate(REGION, "dom", null, "mirror", "generic", "scope", "pkg");
        restarted.mutatePackageVersions(mirror, "dispose", selection);
        assertThrows(AwsException.class, () -> restarted.getPackageVersionAsset(
                REGION, "dom", null, "mirror", "generic", "scope", "pkg", "1", "asset.txt", null));
        assertEquals("copied payload survives restart", new String(
                restarted.getPackageVersionAsset(source, "1", "asset.txt", null).content(), StandardCharsets.UTF_8));
        restarted.deleteRepository(REGION, "dom", null, "mirror");
        restarted.createRepository(REGION, "dom", null, "mirror", null, null, Map.of());
        assertThrows(AwsException.class, () -> restarted.describePackageVersion(
                REGION, "dom", null, "mirror", "generic", "scope", "pkg", "1"));
    }

    private CodeArtifactService newService(Path dir) {
        AccountAwareStorageBackend<CodeArtifactDomain> domainStore = accountAware(dir, "codeartifact-domains.json",
                new TypeReference<Map<String, CodeArtifactDomain>>() {});
        AccountAwareStorageBackend<CodeArtifactRepository> repoStore = accountAware(dir,
                "codeartifact-repositories.json", new TypeReference<Map<String, CodeArtifactRepository>>() {});
        AccountAwareStorageBackend<CodeArtifactPackageVersion> packageVersionStore = accountAware(dir,
                "codeartifact-package-versions.json", new TypeReference<Map<String, CodeArtifactPackageVersion>>() {});

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                "arn:aws:" + invocation.getArgument(0, String.class) + ":" + invocation.getArgument(1, String.class)
                        + ":" + ACCOUNT_ID + ":" + invocation.getArgument(2, String.class));

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        return new CodeArtifactService(domainStore, repoStore, packageVersionStore, regionResolver, config,
                false, dir.resolve("codeartifact-assets"));
    }

    private <V> AccountAwareStorageBackend<V> accountAware(Path dir, String fileName, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(fileName), type);
        backend.load();
        return new AccountAwareStorageBackend<>(backend, null, ACCOUNT_ID);
    }

    private static String sha256Hex(byte[] content) {
        try {
            return io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator.sha256Hex(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
