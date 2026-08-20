package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.model.Image;
import io.github.hectorvent.floci.services.ecr.model.ImageDetail;
import io.github.hectorvent.floci.services.ecr.model.ImageIdentifier;
import io.github.hectorvent.floci.services.ecr.model.AuthorizationData;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcrService}. Uses an in-memory storage backend and a
 * mocked {@link EcrRegistryManager} so the test never touches Docker.
 */
class EcrServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String REPO = "floci-it/svc-test";

    private EcrService service;
    private EcrRegistryManager registryManager;
    private EventBridgeService eventBridgeService;

    @BeforeEach
    void setUp() {
        registryManager = Mockito.mock(EcrRegistryManager.class);
        when(registryManager.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + ".dkr.ecr." + inv.getArgument(1)
                        + ".localhost:5000/" + inv.getArgument(2));
        when(registryManager.getProxyEndpoint()).thenReturn(
                "http://" + ACCOUNT + ".dkr.ecr." + REGION + ".localhost:5000");
        when(registryManager.internalRepoName(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + "/" + inv.getArgument(1) + "/" + inv.getArgument(2));
        // ensureStarted() is a no-op on the mock — no Docker calls in any test below.

        eventBridgeService = Mockito.mock(EventBridgeService.class);
        when(eventBridgeService.putEvents(any(), anyString()))
                .thenReturn(new EventBridgeService.PutEventsResult(0, List.of()));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);

        service = new EcrService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                registryManager,
                config,
                regionResolver,
                eventBridgeService);
    }

    // ------------------------------------------------------------
    // CreateRepository
    // ------------------------------------------------------------

    @Test
    void createRepository_returnsLoopbackUri() {
        Repository repo = service.createRepository(REPO, null, null, null, null, null, null, REGION);
        assertEquals(REPO, repo.getRepositoryName());
        assertEquals(ACCOUNT, repo.getRegistryId());
        assertTrue(repo.getRepositoryArn().startsWith("arn:aws:ecr:us-east-1:000000000000:repository/"));
        assertTrue(repo.getRepositoryUri().contains("localhost:"));
        assertEquals("MUTABLE", repo.getImageTagMutability());
        Mockito.verify(registryManager).ensureStarted();
    }

    @Test
    void createRepository_duplicate_throwsAlreadyExists() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRepository(REPO, null, null, null, null, null, null, REGION));
        assertEquals("RepositoryAlreadyExistsException", ex.getErrorCode());
    }

    @Test
    void createRepository_invalidName_throwsInvalidParameter() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRepository("Invalid_Caps", null, null, null, null, null, null, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void createRepository_acceptsDoubleHyphenLikeAws() {
        // Verified against LIVE ECR (2026-08-18): CreateRepository accepted
        // "alchemy-probe--double-dash". AWS's real constraint (quoted in its
        // own InvalidParameterException) is
        // [a-z0-9]+((\.|_|__|-+)[a-z0-9]+)* — hyphen runs are `-+`, so
        // consecutive hyphens are LEGAL despite the older documented pattern.
        assertEquals("aws-ecs-service-image-form--task",
                service.createRepository(
                        "aws-ecs-service-image-form--task",
                        null, null, null, null, null, null, REGION)
                        .getRepositoryName());
    }

    @Test
    void createRepository_rejectsTrailingSeparatorLikeAws() {
        // Verified against LIVE ECR: "trailing-dash-" and "dot..dot" are
        // rejected — separators must sit between alphanumeric runs.
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createRepository(
                        "trailing-dash-",
                        null, null, null, null, null, null, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        AwsException dots = assertThrows(AwsException.class,
                () -> service.createRepository(
                        "dot..dot",
                        null, null, null, null, null, null, REGION));
        assertEquals("InvalidParameterException", dots.getErrorCode());
    }

    @Test
    void createRepository_acceptsAwsLegalSeparators() {
        Repository repo = service.createRepository(
                "foo-bar.baz_qux/a-b", null, null, null, null, null, null, REGION);
        assertEquals("foo-bar.baz_qux/a-b", repo.getRepositoryName());
    }

    @Test
    void createRepository_emptyName_throwsInvalidParameter() {
        assertThrows(AwsException.class,
                () -> service.createRepository("", null, null, null, null, null, null, REGION));
        assertThrows(AwsException.class,
                () -> service.createRepository(null, null, null, null, null, null, null, REGION));
    }

    @Test
    void createRepository_persistsTagsAndMutability() {
        Repository repo = service.createRepository(REPO, null, "IMMUTABLE", true, null, null,
                Map.of("env", "dev", "team", "platform"), REGION);
        assertEquals("IMMUTABLE", repo.getImageTagMutability());
        assertTrue(repo.isScanOnPush());
        assertEquals("dev", repo.getTags().get("env"));
        assertEquals("platform", repo.getTags().get("team"));
    }

    // ------------------------------------------------------------
    // DescribeRepositories
    // ------------------------------------------------------------

    @Test
    void describeRepositories_byName() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        List<Repository> repos = service.describeRepositories(List.of(REPO), null, REGION);
        assertEquals(1, repos.size());
        assertEquals(REPO, repos.get(0).getRepositoryName());
    }

    @Test
    void describeRepositories_emptyList_returnsAllInRegion() {
        service.createRepository("a/one", null, null, null, null, null, null, REGION);
        service.createRepository("a/two", null, null, null, null, null, null, REGION);
        service.createRepository("a/three", null, null, null, null, null, null, "eu-west-1");
        List<Repository> repos = service.describeRepositories(null, null, REGION);
        assertEquals(2, repos.size());
    }

    @Test
    void describeRepositories_findsRepoCreatedUnderDifferentRegistryId() {
        service.createRepository(REPO, "391965393224", null, null, null, null, null, REGION);
        List<Repository> repos = service.describeRepositories(List.of(REPO), "000000000000", REGION);
        assertEquals(1, repos.size());
        assertEquals(REPO, repos.get(0).getRepositoryName());
        assertEquals("391965393224", repos.get(0).getRegistryId());
        List<Repository> all = service.describeRepositories(null, "000000000000", REGION);
        assertTrue(all.stream().anyMatch(r -> REPO.equals(r.getRepositoryName())));
    }

    @Test
    void describeRepositories_missing_throwsNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeRepositories(List.of("does-not-exist"), null, REGION));
        assertEquals("RepositoryNotFoundException", ex.getErrorCode());
    }

    // ------------------------------------------------------------
    // DeleteRepository
    // ------------------------------------------------------------

    @Test
    void deleteRepository_force_removesEntry() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        Repository deleted = service.deleteRepository(REPO, null, true, REGION);
        assertEquals(REPO, deleted.getRepositoryName());
        assertThrows(AwsException.class,
                () -> service.describeRepositories(List.of(REPO), null, REGION));
    }

    @Test
    void deleteRepository_missing_throwsNotFound() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteRepository(REPO, null, false, REGION));
        assertEquals("RepositoryNotFoundException", ex.getErrorCode());
    }

    // ------------------------------------------------------------
    // GetAuthorizationToken
    // ------------------------------------------------------------

    @Test
    void getAuthorizationToken_decodesToAwsPrefix() {
        AuthorizationData data = service.getAuthorizationToken();
        assertNotNull(data.getAuthorizationToken());
        assertTrue(data.getAuthorizationToken().length() > 100,
                "authorization token must be longer than 100 chars (AWS-shaped)");
        assertTrue(data.getProxyEndpoint().startsWith("http"));
        assertTrue(data.getProxyEndpoint().contains(".ecr."),
                "proxyEndpoint must be an ECR hostname, was: " + data.getProxyEndpoint());
        assertNotNull(data.getExpiresAt());
        String decoded = new String(Base64.getDecoder().decode(data.getAuthorizationToken()));
        assertTrue(decoded.startsWith("AWS:"), "decoded token should start with AWS: but was: " + decoded);
        Mockito.verify(registryManager).ensureStarted();
    }

    @Test
    void pathStyleSeededRegistryEntriesAreVisibleViaListAndDescribeImages() throws Exception {
        String repositoryName = "backend-user";
        String internalRepository = ACCOUNT + "/" + REGION + "/" + repositoryName;
        String tag = "1";
        String digest = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": 123,
                    "digest": "sha256:config"
                  },
                  "layers": [
                    {
                      "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                      "size": 456,
                      "digest": "sha256:layer"
                    }
                  ]
                }
                """;

        try (FakeRegistryServer registry = new FakeRegistryServer(internalRepository, tag, digest, manifest)) {
            when(registryManager.getRepositoryUri(ACCOUNT, REGION, repositoryName))
                    .thenReturn("localhost:" + registry.port() + "/" + internalRepository);
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));

            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            List<ImageIdentifier> imageIds = service.listImages(repositoryName, null, REGION);
            assertEquals(1, imageIds.size());
            assertEquals(tag, imageIds.get(0).getImageTag());
            assertEquals(digest, imageIds.get(0).getImageDigest());

            EcrService.DescribeImagesResult described = service.describeImages(repositoryName, null, null, REGION);
            assertTrue(described.failures().isEmpty());
            assertEquals(1, described.imageDetails().size());

            ImageDetail detail = described.imageDetails().get(0);
            assertEquals(ACCOUNT, detail.getRegistryId());
            assertEquals(repositoryName, detail.getRepositoryName());
            assertEquals(digest, detail.getImageDigest());
            assertEquals(List.of(tag), detail.getImageTags());
            assertEquals(579L, detail.getImageSizeInBytes());
            assertEquals("application/vnd.docker.distribution.manifest.v2+json", detail.getImageManifestMediaType());
            assertEquals("application/vnd.docker.container.image.v1+json", detail.getArtifactMediaType());
            assertNotNull(detail.getImagePushedAt());
        }
    }

    @Test
    void hostnameStyleDockerPushIsVisibleViaListAndDescribeImages() throws Exception {
        // docker push to <account>.dkr.ecr.<region>.localhost:<port>/<repo> writes
        // /v2/<repo>/... — not the internal <account>/<region>/<repo> namespace.
        String repositoryName = "alchemy-test-ecr-image";
        String tag = "abc123";
        String digest = "sha256:2222222222222222222222222222222222222222222222222222222222222222";
        String manifest = """
                {
                  "schemaVersion": 2,
                  "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
                  "config": {
                    "mediaType": "application/vnd.docker.container.image.v1+json",
                    "size": 123,
                    "digest": "sha256:config"
                  },
                  "layers": [
                    {
                      "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
                      "size": 456,
                      "digest": "sha256:layer"
                    }
                  ]
                }
                """;

        try (FakeRegistryServer registry = new FakeRegistryServer(repositoryName, tag, digest, manifest)) {
            when(registryManager.httpClient())
                    .thenReturn(new RegistryHttpClient("http://localhost:" + registry.port()));

            service.createRepository(repositoryName, null, null, null, null, null, null, REGION);

            List<ImageIdentifier> imageIds = service.listImages(repositoryName, null, REGION);
            assertEquals(1, imageIds.size());
            assertEquals(tag, imageIds.get(0).getImageTag());
            assertEquals(digest, imageIds.get(0).getImageDigest());

            EcrService.DescribeImagesResult described = service.describeImages(
                    repositoryName, List.of(new ImageIdentifier(tag, null)), null, REGION);
            assertEquals(1, described.imageDetails().size());
            assertEquals(digest, described.imageDetails().get(0).getImageDigest());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void putImage_publishesEcrImageActionEvent() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        byte[] layer = "layer\n".getBytes();
        String layerDigest = sha256(layer);
        EcrService.InitiateLayerUploadResult init = service.initiateLayerUpload(REPO, null, REGION);
        service.uploadLayerPart(REPO, null, REGION, init.uploadId(), 0, layer.length - 1, layer);
        service.completeLayerUpload(REPO, null, REGION, init.uploadId(), List.of(layerDigest));

        String manifest = "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\"}";
        Image image = service.putImage(REPO, null, REGION, manifest,
                "application/vnd.docker.distribution.manifest.v2+json", "1.0.0", null);

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(captor.capture(), eq(REGION));
        Map<String, Object> entry = captor.getValue().get(0);
        assertEquals("aws.ecr", entry.get("Source"));
        assertEquals("ECR Image Action", entry.get("DetailType"));
        JsonNode detail = assertDoesNotThrow(
                () -> new ObjectMapper().readTree((String) entry.get("Detail")));
        assertEquals("PUSH", detail.get("action-type").asText());
        assertEquals("SUCCESS", detail.get("result").asText());
        assertEquals(REPO, detail.get("repository-name").asText());
        assertEquals("1.0.0", detail.get("image-tag").asText());
        assertEquals(image.getImageId().getImageDigest(), detail.get("image-digest").asText());
    }

    // ------------------------------------------------------------
    // PutImageTagMutability
    // ------------------------------------------------------------

    @Test
    void putImageTagMutability_roundTrips() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        Repository updated = service.putImageTagMutability(REPO, null, "IMMUTABLE", REGION);
        assertEquals("IMMUTABLE", updated.getImageTagMutability());
        Repository fetched = service.describeRepositories(List.of(REPO), null, REGION).get(0);
        assertEquals("IMMUTABLE", fetched.getImageTagMutability());
    }

    @Test
    void putImageTagMutability_invalid_throws() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        assertThrows(AwsException.class,
                () -> service.putImageTagMutability(REPO, null, "WHATEVER", REGION));
    }

    // ------------------------------------------------------------
    // Resource tags
    // ------------------------------------------------------------

    @Test
    void tagResource_addsTags_listReturnsThem() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        service.tagResource(REPO, null, Map.of("env", "prod"), REGION);
        Map<String, String> tags = service.listTagsForResource(REPO, null, REGION);
        assertEquals("prod", tags.get("env"));
    }

    @Test
    void untagResource_removesTags() {
        service.createRepository(REPO, null, null, null, null, null,
                Map.of("env", "prod", "team", "platform"), REGION);
        service.untagResource(REPO, null, List.of("env"), REGION);
        Map<String, String> tags = service.listTagsForResource(REPO, null, REGION);
        assertNull(tags.get("env"));
        assertEquals("platform", tags.get("team"));
    }

    // ------------------------------------------------------------
    // Lifecycle policy
    // ------------------------------------------------------------

    @Test
    void lifecyclePolicy_roundTrip() {
        String policy = "{\"rules\":[]}";
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        service.putLifecyclePolicy(REPO, null, policy, REGION);
        Repository fetched = service.getLifecyclePolicy(REPO, null, REGION);
        assertEquals(policy, fetched.getLifecyclePolicyText());
        service.deleteLifecyclePolicy(REPO, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getLifecyclePolicy(REPO, null, REGION));
        assertEquals("LifecyclePolicyNotFoundException", ex.getErrorCode());
    }

    @Test
    void getLifecyclePolicy_unset_throws() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getLifecyclePolicy(REPO, null, REGION));
        assertEquals("LifecyclePolicyNotFoundException", ex.getErrorCode());
    }

    // ------------------------------------------------------------
    // Repository policy
    // ------------------------------------------------------------

    @Test
    void repositoryPolicy_roundTrip() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        service.setRepositoryPolicy(REPO, null, policy, REGION);
        Repository fetched = service.getRepositoryPolicy(REPO, null, REGION);
        assertEquals(policy, fetched.getRepositoryPolicyText());
        service.deleteRepositoryPolicy(REPO, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.getRepositoryPolicy(REPO, null, REGION));
        assertEquals("RepositoryPolicyNotFoundException", ex.getErrorCode());
    }

    // ------------------------------------------------------------
    // Reconcile
    // ------------------------------------------------------------

    @Test
    void putImageScanningConfiguration_roundTrips() {
        service.createRepository(REPO, null, null, false, null, null, null, REGION);
        Repository updated = service.putImageScanningConfiguration(REPO, null, true, REGION);
        assertTrue(updated.isScanOnPush());
        Repository fetched = service.describeRepositories(List.of(REPO), null, REGION).get(0);
        assertTrue(fetched.isScanOnPush());
    }

    @Test
    void registryPolicy_roundTrip() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        EcrService.RegistryPolicyResult put = service.putRegistryPolicy(policy, REGION);
        assertEquals(ACCOUNT, put.registryId());
        assertEquals(policy, put.policyText());
        EcrService.RegistryPolicyResult got = service.getRegistryPolicy(REGION);
        assertEquals(policy, got.policyText());
        service.deleteRegistryPolicy(REGION);
        AwsException ex = assertThrows(AwsException.class, () -> service.getRegistryPolicy(REGION));
        assertEquals("RegistryPolicyNotFoundException", ex.getErrorCode());
    }

    @Test
    void layerUpload_putImage_describe_download_check_scan() {
        service.createRepository(REPO, null, null, null, null, null, null, REGION);
        byte[] layer = "alchemy ecr layer blob for 1.0.0\n".repeat(8).getBytes();
        String layerDigest = sha256(layer);
        byte[] config = "{\"architecture\":\"amd64\"}".getBytes();
        String configDigest = sha256(config);

        EcrService.InitiateLayerUploadResult init = service.initiateLayerUpload(REPO, null, REGION);
        assertNotNull(init.uploadId());
        assertTrue(init.partSize() > 0);
        service.uploadLayerPart(REPO, null, REGION, init.uploadId(), 0, layer.length - 1, layer);
        EcrService.CompleteLayerUploadResult completed =
                service.completeLayerUpload(REPO, null, REGION, init.uploadId(), List.of(layerDigest));
        assertEquals(layerDigest, completed.layerDigest());

        EcrService.InitiateLayerUploadResult initCfg = service.initiateLayerUpload(REPO, null, REGION);
        service.uploadLayerPart(REPO, null, REGION, initCfg.uploadId(), 0, config.length - 1, config);
        service.completeLayerUpload(REPO, null, REGION, initCfg.uploadId(), List.of(configDigest));

        AwsException already = assertThrows(AwsException.class,
                () -> {
                    EcrService.InitiateLayerUploadResult again = service.initiateLayerUpload(REPO, null, REGION);
                    service.uploadLayerPart(REPO, null, REGION, again.uploadId(), 0, layer.length - 1, layer);
                    service.completeLayerUpload(REPO, null, REGION, again.uploadId(), List.of(layerDigest));
                });
        assertEquals("LayerAlreadyExistsException", already.getErrorCode());

        String manifest = """
                {"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json",\
                "config":{"mediaType":"application/vnd.docker.container.image.v1+json","size":%d,"digest":"%s"},\
                "layers":[{"mediaType":"application/vnd.docker.image.rootfs.diff.tar.gzip","size":%d,"digest":"%s"}]}
                """.formatted(config.length, configDigest, layer.length, layerDigest);
        Image image = service.putImage(REPO, null, REGION, manifest,
                "application/vnd.docker.distribution.manifest.v2+json", "1.0.0", null);
        assertEquals("1.0.0", image.getImageId().getImageTag());
        assertTrue(image.getImageId().getImageDigest().startsWith("sha256:"));

        AwsException exists = assertThrows(AwsException.class,
                () -> service.putImage(REPO, null, REGION, manifest,
                        "application/vnd.docker.distribution.manifest.v2+json", "1.0.0",
                        image.getImageId().getImageDigest()));
        assertEquals("ImageAlreadyExistsException", exists.getErrorCode());

        List<ImageIdentifier> ids = service.listImages(REPO, null, REGION);
        assertTrue(ids.stream().anyMatch(id -> "1.0.0".equals(id.getImageTag())));

        EcrService.DescribeImagesResult described = service.describeImages(
                REPO, List.of(new ImageIdentifier("1.0.0", null)), null, REGION);
        assertEquals(1, described.imageDetails().size());
        assertEquals(image.getImageId().getImageDigest(), described.imageDetails().get(0).getImageDigest());

        EcrService.DownloadUrl url = service.getDownloadUrlForLayer(REPO, null, REGION, layerDigest);
        assertTrue(url.downloadUrl().startsWith("https://"));
        assertEquals(layerDigest, url.layerDigest());

        EcrService.LayerAvailabilityResult availability =
                service.batchCheckLayerAvailability(REPO, null, REGION, List.of(layerDigest));
        assertEquals("AVAILABLE", availability.layers().get(0).layerAvailability());

        AwsException scan = assertThrows(AwsException.class,
                () -> service.startImageScan(REPO, null, REGION, new ImageIdentifier("1.0.0", null)));
        assertEquals("UnsupportedImageTypeException", scan.getErrorCode());

        AwsException findings = assertThrows(AwsException.class,
                () -> service.describeImageScanFindings(REPO, null, REGION, new ImageIdentifier("1.0.0", null)));
        assertEquals("ScanNotFoundException", findings.getErrorCode());
    }

    private static String sha256(byte[] data) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            return "sha256:" + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void reconcileFromCatalog_recreatesMissingMetadata() {
        // Internal namespace pattern: <account>/<region>/<repoName>
        service.reconcileFromCatalog(List.of(
                ACCOUNT + "/" + REGION + "/recovered/one",
                ACCOUNT + "/" + REGION + "/recovered/two",
                "malformed-no-slashes"));
        List<Repository> repos = service.describeRepositories(null, null, REGION);
        assertEquals(2, repos.size());
        assertTrue(repos.stream().anyMatch(r -> "recovered/one".equals(r.getRepositoryName())));
        assertTrue(repos.stream().anyMatch(r -> "recovered/two".equals(r.getRepositoryName())));
    }

    @Test
    void reconcileFromCatalog_skipsExistingEntries() {
        service.createRepository(REPO, null, null, null, null, null,
                Map.of("preserved", "yes"), REGION);
        service.reconcileFromCatalog(List.of(ACCOUNT + "/" + REGION + "/" + REPO));
        Repository existing = service.describeRepositories(List.of(REPO), null, REGION).get(0);
        // Tag is still present → existing entry was NOT overwritten by reconcile
        assertEquals("yes", existing.getTags().get("preserved"));
    }

    private static final class FakeRegistryServer implements AutoCloseable {
        private final HttpServer server;
        private final String repository;
        private final String tag;
        private final String digest;
        private final String manifest;

        private FakeRegistryServer(String repository, String tag, String digest, String manifest) throws IOException {
            this.repository = repository;
            this.tag = tag;
            this.digest = digest;
            this.manifest = manifest;
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/v2/", this::handle);
            this.server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/v2/".equals(path) && "GET".equals(method)) {
                send(exchange, 200, "");
                return;
            }
            if (("/v2/" + repository + "/tags/list").equals(path) && "GET".equals(method)) {
                sendJson(exchange, 200, "{\"name\":\"" + repository + "\",\"tags\":[\"" + tag + "\"]}");
                return;
            }
            if (("/v2/" + repository + "/manifests/" + tag).equals(path) && "HEAD".equals(method)) {
                exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            if ((("/v2/" + repository + "/manifests/" + tag).equals(path)
                    || ("/v2/" + repository + "/manifests/" + digest).equals(path))
                    && "GET".equals(method)) {
                exchange.getResponseHeaders().add("Docker-Content-Digest", digest);
                exchange.getResponseHeaders().add("Content-Type",
                        "application/vnd.docker.distribution.manifest.v2+json");
                send(exchange, 200, manifest);
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            send(exchange, status, body);
        }

        private static void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
