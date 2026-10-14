package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationToken;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.DomainView;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PackageCoordinate;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PackageVersionAssetResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.PublishPackageVersionResult;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.ResourcePolicy;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.VersionMutation;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactDomain;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackage;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackage.Version;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactPackageVersion;
import io.github.hectorvent.floci.services.codeartifact.model.CodeArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeArtifactServiceTest {
    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "us-west-2";
    private static final String ACCOUNT_ID = "123456789012";

    private CodeArtifactService service;
    private RegionResolver regionResolver;
    private AccountAwareStorageBackend<CodeArtifactRepository> repoStore;

    @BeforeEach
    void setUp() {
        AccountAwareStorageBackend<CodeArtifactDomain> domainStore = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        repoStore = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        AccountAwareStorageBackend<CodeArtifactPackageVersion> packageVersionStore =
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID);

        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                "arn:aws:" + invocation.getArgument(0, String.class) + ":" + invocation.getArgument(1, String.class)
                        + ":" + ACCOUNT_ID + ":" + invocation.getArgument(2, String.class));

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        service = new CodeArtifactService(domainStore, repoStore, packageVersionStore, regionResolver, config,
                true, null);
    }

    // -------------------------------------------------------------- domains

    @Test
    void createDomainAssignsArnAndDefaultEncryptionKey() {
        DomainView view = service.createDomain(REGION, "my-domain", null, Map.of());
        assertEquals("arn:aws:codeartifact:" + REGION + ":" + ACCOUNT_ID + ":domain/my-domain", view.domain().getArn());
        assertTrue(view.domain().getEncryptionKey().contains("alias/aws/codeartifact"));
        assertEquals(0, view.repositoryCount());
    }

    @Test
    void createDomainRejectsDuplicateName() {
        service.createDomain(REGION, "dup", null, Map.of());
        AwsException e = assertThrows(AwsException.class, () -> service.createDomain(REGION, "dup", null, Map.of()));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals("dup", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createDomainRejectsInvalidName() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "Not-Valid-Upper", null, Map.of()));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void domainsAreScopedPerRegion() {
        service.createDomain(REGION, "shared-name", null, Map.of());
        DomainView otherRegion = service.createDomain(OTHER_REGION, "shared-name", null, Map.of());
        assertTrue(otherRegion.domain().getArn().contains(OTHER_REGION));
        assertEquals(1, service.listDomains(REGION, null, null).items().size());
        assertEquals(1, service.listDomains(OTHER_REGION, null, null).items().size());
    }

    @Test
    void createDomainRejectsMoreThanTenDomainsPerAccount() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "dom-overflow", null, Map.of()));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals(402, e.getHttpStatus());
        assertEquals("dom-overflow", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createDomainAtCapStillReturnsConflictForDuplicateName() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "dom-0", null, Map.of()));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void deletingDomainFreesRoomForANewOne() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        service.deleteDomain(REGION, "dom-0", null);
        DomainView view = service.createDomain(REGION, "dom-new", null, Map.of());
        assertEquals("dom-new", view.domain().getName());
    }

    @Test
    void domainQuotaIsScopedPerRegion() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        DomainView otherRegion = service.createDomain(OTHER_REGION, "dom-0", null, Map.of());
        assertEquals("dom-0", otherRegion.domain().getName());
    }

    @Test
    void domainQuotaIsScopedPerAccount() {
        for (int i = 0; i < 10; i++) {
            service.createDomain(REGION, "dom-" + i, null, Map.of());
        }
        String otherAccount = "999999999999";
        when(regionResolver.getAccountId()).thenReturn(otherAccount);
        DomainView view = service.createDomain(REGION, "dom-0", null, Map.of());
        assertEquals(otherAccount, view.domain().getOwner());
    }

    @Test
    void deleteDomainFailsWhileRepositoriesExist() {
        service.createDomain(REGION, "with-repo", null, Map.of());
        service.createRepository(REGION, "with-repo", null, "repo-a", null, null, Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.deleteDomain(REGION, "with-repo", null));
        assertEquals("ConflictException", e.getErrorCode());

        service.deleteRepository(REGION, "with-repo", null, "repo-a");
        DomainView deleted = service.deleteDomain(REGION, "with-repo", null);
        assertEquals("with-repo", deleted.domain().getName());
    }

    @Test
    void deleteDomainOfMissingDomainReturnsResourceNotFound() {
        // AWS returns this even though the API reference does not list it on DeleteDomain.
        AwsException e = assertThrows(AwsException.class, () -> service.deleteDomain(REGION, "never-created", null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());

        service.createDomain(REGION, "once", null, Map.of());
        service.deleteDomain(REGION, "once", null);
        AwsException again = assertThrows(AwsException.class, () -> service.deleteDomain(REGION, "once", null));
        assertEquals("ResourceNotFoundException", again.getErrorCode());
    }

    @Test
    void describeDomainReflectsLiveRepositoryCount() {
        service.createDomain(REGION, "counted", null, Map.of());
        assertEquals(0, service.describeDomain(REGION, "counted", null).repositoryCount());
        service.createRepository(REGION, "counted", null, "repo-a", null, null, Map.of());
        service.createRepository(REGION, "counted", null, "repo-b", null, null, Map.of());
        assertEquals(2, service.describeDomain(REGION, "counted", null).repositoryCount());
    }

    @Test
    void concurrentCreateDomainWithSameNameOnlyOneWins() throws InterruptedException {
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        service.createDomain(REGION, "race-domain", null, Map.of());
                        successes.incrementAndGet();
                    } catch (AwsException e) {
                        if ("ConflictException".equals(e.getErrorCode())) {
                            conflicts.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, successes.get());
        assertEquals(attempts - 1, conflicts.get());
    }

    @Test
    void requiredDomainFieldRejectsNullInsteadOfCrashing() {
        AwsException describe = assertThrows(AwsException.class, () -> service.describeDomain(REGION, null, null));
        assertEquals("ValidationException", describe.getErrorCode());

        AwsException delete = assertThrows(AwsException.class, () -> service.deleteDomain(REGION, null, null));
        assertEquals("ValidationException", delete.getErrorCode());

        AwsException getPolicy = assertThrows(AwsException.class,
                () -> service.getDomainPermissionsPolicy(REGION, null, null));
        assertEquals("ValidationException", getPolicy.getErrorCode());
    }

    // ---------------------------------------------------------- repositories

    @Test
    void requiredRepositoryFieldsRejectNullInsteadOfCrashing() {
        service.createDomain(REGION, "dom", null, Map.of());

        AwsException missingDomain = assertThrows(AwsException.class,
                () -> service.describeRepository(REGION, null, null, "repo"));
        assertEquals("ValidationException", missingDomain.getErrorCode());
        assertEquals("domain is required.", missingDomain.getMessage());

        AwsException missingRepository = assertThrows(AwsException.class,
                () -> service.describeRepository(REGION, "dom", null, null));
        assertEquals("ValidationException", missingRepository.getErrorCode());
        assertEquals("repository is required.", missingRepository.getMessage());

        AwsException missingRepositoryEndpoint = assertThrows(AwsException.class,
                () -> service.getRepositoryEndpoint(REGION, null, null, "repo", "npm", null));
        assertEquals("ValidationException", missingRepositoryEndpoint.getErrorCode());
    }

    @Test
    void createRepositoryRequiresExistingDomain() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "missing-domain", null, "repo", null, null, Map.of()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals("missing-domain", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createRepositoryValidatesUpstreamsExistInSameDomain() {
        service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "repo", null, List.of("ghost"), Map.of()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals("ghost", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createRepositoryRejectsSelfAsUpstream() {
        service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "repo", null, List.of("repo"), Map.of()));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createRepositoryAssignsAFreshMavenRepositoryIdEvenAfterDeleteAndRecreate() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository first = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        assertTrue(first.getMavenRepositoryId() != null && !first.getMavenRepositoryId().isBlank());

        service.deleteRepository(REGION, "dom", null, "repo");
        CodeArtifactRepository recreated = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        assertTrue(!first.getMavenRepositoryId().equals(recreated.getMavenRepositoryId()),
                "a recreated repository must never reuse the previous one's Maven repository id, "
                        + "or it would inherit its leftover artifacts");
    }

    @Test
    void ensureMavenRepositoryIdBackfillsALegacyRepositoryMissingOne() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        // Simulates a repository persisted before mavenRepositoryId existed: Jackson would
        // deserialize the missing field as null exactly like this.
        created.setMavenRepositoryId(null);
        repoStore.putForAccount(ACCOUNT_ID, REGION + "::dom::repo", created);

        String backfilled = service.ensureMavenRepositoryId(REGION, "dom", null, "repo");

        assertTrue(backfilled != null && !backfilled.isBlank());
        assertEquals(backfilled, service.describeRepository(REGION, "dom", null, "repo").getMavenRepositoryId());
    }

    @Test
    void ensureMavenRepositoryIdIsIdempotentForAnAlreadyAssignedRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository created = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        String result = service.ensureMavenRepositoryId(REGION, "dom", null, "repo");

        assertEquals(created.getMavenRepositoryId(), result);
    }

    @Test
    void ensureMavenRepositoryIdRequiresExistingRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.ensureMavenRepositoryId(REGION, "dom", null, "missing-repo"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void createRepositoryAcceptsExistingUpstream() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "store", null, null, Map.of());
        CodeArtifactRepository r = service.createRepository(REGION, "dom", null, "consumer", null,
                List.of("store"), Map.of());
        assertEquals(List.of("store"), r.getUpstreams());
    }

    @Test
    void updateRepositoryChangesDescriptionAndUpstreams() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "store", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", "old", null, Map.of());

        CodeArtifactRepository updated = service.updateRepository(REGION, "dom", null, "repo", "new",
                List.of("store"));
        assertEquals("new", updated.getDescription());
        assertEquals(List.of("store"), updated.getUpstreams());
    }

    @Test
    void createRepositoryRejectsMoreThanTenUpstreams() {
        service.createDomain(REGION, "dom", null, Map.of());
        List<String> upstreams = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String name = "store-" + i;
            service.createRepository(REGION, "dom", null, name, null, null, Map.of());
            upstreams.add(name);
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "consumer", null, upstreams, Map.of()));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals("consumer", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createRepositoryRejectsMoreThanOneThousandRepositoriesPerDomain() {
        service.createDomain(REGION, "dom", null, Map.of());
        for (int i = 0; i < 1000; i++) {
            service.createRepository(REGION, "dom", null, "repo-" + i, null, null, Map.of());
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "overflow", null, null, Map.of()));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals(402, e.getHttpStatus());
        assertEquals("overflow", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createRepository(REGION, "dom", null, "repo-0", null, null, Map.of()));
        assertEquals("ConflictException", duplicate.getErrorCode());

        service.deleteRepository(REGION, "dom", null, "repo-0");
        CodeArtifactRepository freed = service.createRepository(REGION, "dom", null, "repo-new", null, null,
                Map.of());
        assertEquals("repo-new", freed.getName());
    }

    @Test
    void repositoryQuotaIsScopedPerDomain() {
        service.createDomain(REGION, "dom", null, Map.of());
        for (int i = 0; i < 1000; i++) {
            service.createRepository(REGION, "dom", null, "repo-" + i, null, null, Map.of());
        }
        service.createDomain(REGION, "dom-2", null, Map.of());
        CodeArtifactRepository r = service.createRepository(REGION, "dom-2", null, "repo-0", null, null, Map.of());
        assertEquals("repo-0", r.getName());
    }

    @Test
    void listRepositoriesInDomainFiltersByPrefixAndDomain() {
        service.createDomain(REGION, "dom-a", null, Map.of());
        service.createDomain(REGION, "dom-b", null, Map.of());
        service.createRepository(REGION, "dom-a", null, "npm-repo", null, null, Map.of());
        service.createRepository(REGION, "dom-a", null, "pypi-repo", null, null, Map.of());
        service.createRepository(REGION, "dom-b", null, "npm-repo", null, null, Map.of());

        PaginatedResult<CodeArtifactRepository> page = service.listRepositoriesInDomain(REGION, "dom-a", null, null,
                "npm", null, null);
        assertEquals(1, page.items().size());
        assertEquals("npm-repo", page.items().get(0).getName());
    }

    @Test
    void getRepositoryEndpointValidatesFormatAndReturnsStableUrl() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        String endpoint = service.getRepositoryEndpoint(REGION, "dom", null, "repo", "npm", null);
        assertEquals("http://localhost:4566/codeartifact/npm/dom/repo/", endpoint);

        AwsException e = assertThrows(AwsException.class,
                () -> service.getRepositoryEndpoint(REGION, "dom", null, "repo", "not-a-format", null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    // ------------------------------------------------------ permissions policy

    @Test
    void putRepositoryPermissionsPolicyEnforcesOptimisticLocking() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        ResourcePolicy first = service.putRepositoryPermissionsPolicy(REGION, "dom", null, "repo", "{}", null);

        AwsException stale = assertThrows(AwsException.class, () -> service.putRepositoryPermissionsPolicy(
                REGION, "dom", null, "repo", "{}", "not-the-current-revision"));
        assertEquals("ConflictException", stale.getErrorCode());

        ResourcePolicy second = service.putRepositoryPermissionsPolicy(REGION, "dom", null, "repo", "{\"v\":2}",
                first.revision());
        assertEquals("{\"v\":2}", second.document());

        ResourcePolicy fetched = service.getRepositoryPermissionsPolicy(REGION, "dom", null, "repo");
        assertEquals(second.revision(), fetched.revision());

        service.deleteRepositoryPermissionsPolicy(REGION, "dom", null, "repo", second.revision());
        AwsException gone = assertThrows(AwsException.class,
                () -> service.getRepositoryPermissionsPolicy(REGION, "dom", null, "repo"));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    // ------------------------------------------------------- external connections

    @Test
    void associateExternalConnectionRejectsUnknownName() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.associateExternalConnection(REGION, "dom", null, "repo", "public:not-real"));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void repositoryCanOnlyHaveOneExternalConnection() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        CodeArtifactRepository r = service.associateExternalConnection(REGION, "dom", null, "repo", "public:npmjs");
        assertEquals("npm", r.getExternalConnections().get(0).getPackageFormat());
        assertEquals("Available", r.getExternalConnections().get(0).getStatus());

        AwsException e = assertThrows(AwsException.class,
                () -> service.associateExternalConnection(REGION, "dom", null, "repo", "public:pypi"));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals("repo", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));

        CodeArtifactRepository disassociated = service.disassociateExternalConnection(REGION, "dom", null, "repo",
                "public:npmjs");
        assertTrue(disassociated.getExternalConnections().isEmpty());
    }

    @Test
    void externalConnectionAndUpstreamsAreMutuallyExclusive() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "store", null, null, Map.of());
        service.createRepository(REGION, "dom", null, "with-upstream", null, List.of("store"), Map.of());

        AwsException viaExternalConnection = assertThrows(AwsException.class, () -> service
                .associateExternalConnection(REGION, "dom", null, "with-upstream", "public:npmjs"));
        assertEquals("ConflictException", viaExternalConnection.getErrorCode());

        service.createRepository(REGION, "dom", null, "with-connection", null, null, Map.of());
        service.associateExternalConnection(REGION, "dom", null, "with-connection", "public:npmjs");
        AwsException viaUpstream = assertThrows(AwsException.class, () -> service
                .updateRepository(REGION, "dom", null, "with-connection", null, List.of("store")));
        assertEquals("ConflictException", viaUpstream.getErrorCode());
    }

    @Test
    void authorizationTokensAreOpaqueUniqueDomainScopedAndBounded() {
        service.createDomain(REGION, "tokens", null, Map.of());
        service.createDomain(REGION, "other-tokens", null, Map.of());
        long before = Instant.now().getEpochSecond();
        AuthorizationToken token = service.getAuthorizationToken(REGION, "tokens", null, 900L);
        assertEquals(256, Base64.getDecoder().decode(token.authorizationToken()).length);
        assertTrue(token.expiration() >= before + 900);
        assertTrue(token.expiration() <= Instant.now().getEpochSecond() + 900);
        assertTrue(service.isAuthorizationTokenValid(REGION, "tokens", null, token.authorizationToken()));
        assertFalse(service.isAuthorizationTokenValid(REGION, "other-tokens", null, token.authorizationToken()));
        AuthorizationToken second = service.getAuthorizationToken(REGION, "tokens", null, null);
        assertNotEquals(token.authorizationToken(), second.authorizationToken());
        assertTrue(second.expiration() >= before + 43200);
        for (long duration : List.of(-1L, 899L, 43201L)) {
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.getAuthorizationToken(REGION, "tokens", null, duration)).getErrorCode());
        }
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getAuthorizationToken(OTHER_REGION, "tokens", null, 900L)).getErrorCode());
        CodeArtifactDomain domain = service.describeDomain(REGION, "tokens", null).domain();
        Map<String, Long> expired = new LinkedHashMap<>(domain.getAuthorizationTokens());
        assertFalse(expired.containsKey(token.authorizationToken()));
        expired.replaceAll((key, value) -> before - 1);
        domain.setAuthorizationTokens(expired);
        assertFalse(service.isAuthorizationTokenValid(REGION, "tokens", null, token.authorizationToken()));
    }

    @Test
    void genericAssetsPersistAndCopiesAreIndependent() throws Exception {
        PackageCoordinate source = packageFixture();
        byte[] content = new byte[] {0, 1, (byte) 255, 13, 10};
        String hash = hash(content);
        Map<String, Object> published = service.publishPackageVersion(source, "1.0.0", "data.bin", content, hash, false);
        assertEquals("Published", published.get("status"));
        assertArrayEquals(content, service.getPackageVersionAsset(source, "1.0.0", "data.bin", null).content());
        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.publishPackageVersion(source, "1.0.0", "other.bin", content, hash, false)).getErrorCode());

        ObjectMapper mapper = new ObjectMapper();
        CodeArtifactRepository stored = service.describeRepository(REGION, "packages", null, "source");
        CodeArtifactRepository restored = mapper.readValue(mapper.writeValueAsString(stored), CodeArtifactRepository.class);
        repoStore.putForAccount(ACCOUNT_ID, REGION + "::packages::source", restored);
        assertArrayEquals(content, service.getPackageVersionAsset(source, "1.0.0", "data.bin", null).content());
        assertEquals(published.get("versionRevision"),
                service.getPackageVersionAsset(source, "1.0.0", "data.bin", null).revision());

        service.copyPackageVersions(source, "mirror", mutation(List.of("1.0.0"), Map.of(), null, null, false));
        PackageCoordinate mirror = new PackageCoordinate(REGION, "packages", null, "mirror", "generic", "scope", "artifact");
        service.mutatePackageVersions(mirror, "dispose", mutation(List.of("1.0.0"), Map.of(), null, null, false));
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getPackageVersionAsset(mirror, "1.0.0", "data.bin", null)).getErrorCode());
        assertArrayEquals(content, service.getPackageVersionAsset(source, "1.0.0", "data.bin", null).content());
        service.mutatePackageVersions(mirror, "delete", mutation(List.of("1.0.0"), Map.of(), null, null, false));
        service.describePackage(mirror);
        service.deletePackage(mirror);
        assertEquals(List.of(), service.listPackages(mirror, null, null, null, null, null).get("packages"));
        service.deleteRepository(REGION, "packages", null, "source");
        service.createRepository(REGION, "packages", null, "source", null, null, Map.of());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describePackage(source)).getErrorCode());
    }

    @Test
    void legacyRepositoryPackagesMigrateIntoTheSharedVersionStore() throws Exception {
        PackageCoordinate source = packageFixture();
        byte[] content = "legacy asset".getBytes(StandardCharsets.UTF_8);
        CodeArtifactRepository repository = service.describeRepository(REGION, "packages", null, "source");
        Version version = new Version("1", "legacy-revision", "Published", 1, "source", Map.of("data", content));
        CodeArtifactPackage pkg = new CodeArtifactPackage("generic", "scope", "artifact",
                Map.of("publish", "ALLOW", "upstream", "BLOCK"), Map.of("1", version));
        repository.setPackages(Map.of("generic:5:scope:artifact", pkg));
        ObjectMapper mapper = new ObjectMapper();
        repoStore.putForAccount(ACCOUNT_ID, REGION + "::packages::source",
                mapper.readValue(mapper.writeValueAsString(repository), CodeArtifactRepository.class));

        assertArrayEquals(content, service.getPackageVersionAsset(source, "1", "data", null).content());
        assertEquals("legacy-revision", service.describePackageVersion(REGION, "packages", null, "source",
                "generic", "scope", "artifact", "1").getRevision());
        assertTrue(service.describeRepository(REGION, "packages", null, "source")
                .getPackages().values().stream().allMatch(stored -> stored.versions().isEmpty()));
    }

    @Test
    void hashesRevisionsStatusesAndOriginRestrictionsAreEnforced() throws Exception {
        PackageCoordinate source = packageFixture();
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.publishPackageVersion(source, "1", "data", content, "0".repeat(64), false)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describePackage(source)).getErrorCode());
        Map<String, Object> published = service.publishPackageVersion(source, "1", "data", content, hash(content), true);
        String originalRevision = (String) published.get("versionRevision");
        assertEquals(originalRevision, service.publishPackageVersion(source, "1", "data", content, hash(content), true)
                .get("versionRevision"));
        service.publishPackageVersion(source, "1", "more", content, hash(content), true);
        String revision = service.getPackageVersionAsset(source, "1", "data", null).revision();
        assertNotEquals(originalRevision, revision);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getPackageVersionAsset(source, "1", "data", originalRevision)).getErrorCode());
        Map<String, Object> failures = service.mutatePackageVersions(source, "status",
                mutation(List.of("1", "missing"), Map.of("1", originalRevision), null, "Published", false));
        assertEquals(Map.of(), failures.get("successfulVersions"));
        assertEquals(Map.of("1", Map.of("errorCode", "MISMATCHED_REVISION",
                        "errorMessage", "Package version operation failed: MISMATCHED_REVISION"),
                "missing", Map.of("errorCode", "NOT_FOUND", "errorMessage", "Package version operation failed: NOT_FOUND")),
                failures.get("failedVersions"));
        Map<String, Object> wrongStatus = service.mutatePackageVersions(source, "status",
                mutation(List.of("1"), Map.of(), "Published", "Archived", false));
        assertEquals(Map.of(), wrongStatus.get("successfulVersions"));
        Map<String, Object> finished = service.mutatePackageVersions(source, "status",
                mutation(List.of("1"), Map.of("1", revision), "Unfinished", "Published", false));
        assertEquals(Map.of(), finished.get("failedVersions"));
        Map<String, Object> skipped = service.mutatePackageVersions(source, "status",
                mutation(List.of("1", "missing"), Map.of(), null, "Archived", false));
        assertEquals(Map.of(), skipped.get("successfulVersions"));
        assertArrayEquals(content, service.getPackageVersionAsset(source, "1", "data", null).content());
        service.putPackageOriginConfiguration(source, Map.of("publish", "BLOCK", "upstream", "ALLOW"));
        assertEquals("AccessDeniedException", assertThrows(AwsException.class,
                () -> service.publishPackageVersion(source, "2", "data", content, hash(content), false)).getErrorCode());
        service.mutatePackageVersions(source, "dispose", mutation(List.of("1"), Map.of(), null, null, false));
        Map<String, Object> resurrection = service.mutatePackageVersions(source, "status",
                mutation(List.of("1"), Map.of(), null, "Published", false));
        assertEquals(Map.of(), resurrection.get("successfulVersions"));
    }

    @Test
    void packageListsPaginateAndCopiesCheckOverwriteAndRevision() throws Exception {
        PackageCoordinate source = packageFixture();
        PackageCoordinate mirror = new PackageCoordinate(REGION, "packages", null, "mirror", "generic", "scope", "artifact");
        byte[] content = "source".getBytes(StandardCharsets.UTF_8);
        byte[] other = "different".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(source, "1", "data", content, hash(content), false);
        service.publishPackageVersion(source, "2", "data", content, hash(content), false);
        service.publishPackageVersion(mirror, "1", "data", other, hash(other), false);
        Map<String, Object> page = service.listPackageVersions(source, null, null, null, 1, null);
        assertTrue(page.containsKey("nextToken"));
        Map<String, Object> last = service.listPackageVersions(source, null, null, null, 1, (String) page.get("nextToken"));
        assertFalse(last.containsKey("nextToken"));
        assertNotEquals(page.get("versions"), last.get("versions"));
        Map<String, Object> conflict = service.copyPackageVersions(source, "mirror",
                mutation(List.of("1", "2"), Map.of(), null, null, false));
        assertEquals(Map.of("1", Map.of("errorCode", "ALREADY_EXISTS",
                        "errorMessage", "Package version operation failed: ALREADY_EXISTS"),
                "2", Map.of("errorCode", "SKIPPED", "errorMessage", "Package version operation failed: SKIPPED")),
                conflict.get("failedVersions"));
        assertEquals(Map.of(), conflict.get("successfulVersions"));
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describePackageVersion(mirror, "2")).getErrorCode());
        assertArrayEquals(other, service.getPackageVersionAsset(mirror, "1", "data", null).content());
        service.copyPackageVersions(source, "mirror", mutation(List.of("1"), Map.of(), null, null, true));
        assertArrayEquals(content, service.getPackageVersionAsset(mirror, "1", "data", null).content());
        assertEquals(Map.of(), service.copyPackageVersions(source, "mirror",
                mutation(List.of("1"), Map.of(), null, null, false)).get("successfulVersions"));
        String revision = service.getPackageVersionAsset(source, "2", "data", null).revision();
        assertEquals(Map.of(), service.copyPackageVersions(source, "mirror",
                mutation(null, Map.of("2", revision), null, null, false)).get("failedVersions"));
        assertArrayEquals(content, service.getPackageVersionAsset(mirror, "2", "data", null).content());
        PackageCoordinate wrongNamespace = new PackageCoordinate(REGION, "packages", null, "source",
                "generic", "other", "artifact");
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describePackage(wrongNamespace)).getErrorCode());
    }

    @Test
    void packageKeysDoNotCollideAndCopyCanResolveStoredUpstreams() throws Exception {
        PackageCoordinate source = packageFixture();
        byte[] content = "upstream".getBytes(StandardCharsets.UTF_8);
        PackageCoordinate first = new PackageCoordinate(REGION, "packages", null, "source", "generic", "a::b", "c");
        PackageCoordinate second = new PackageCoordinate(REGION, "packages", null, "source", "generic", "a", "b::c");
        service.publishPackageVersion(first, "1", "data + file.bin", content, hash(content), false);
        service.publishPackageVersion(second, "1", "other.bin", new byte[0], hash(new byte[0]), false);
        assertArrayEquals(content, service.getPackageVersionAsset(first, "1", "data + file.bin", null).content());
        assertEquals(0, service.getPackageVersionAsset(second, "1", "other.bin", null).content().length);
        service.publishPackageVersion(source, "1", "data", content, hash(content), false);
        service.createRepository(REGION, "packages", null, "consumer", null, List.of("source"), Map.of());
        PackageCoordinate consumer = new PackageCoordinate(REGION, "packages", null, "consumer",
                "generic", "scope", "artifact");
        VersionMutation includeUpstream = new VersionMutation(List.of("1"), Map.of(), null, null, false, true);
        assertEquals(Map.of(), service.copyPackageVersions(consumer, "mirror", includeUpstream).get("failedVersions"));
        PackageCoordinate mirror = new PackageCoordinate(REGION, "packages", null, "mirror",
                "generic", "scope", "artifact");
        assertArrayEquals(content, service.getPackageVersionAsset(mirror, "1", "data", null).content());
    }

    @Test
    void packageVersionChangesArePublishedAsStateChangeEvents() throws Exception {
        CodeArtifactEventPublisher publisher = mock(CodeArtifactEventPublisher.class);
        List<CodeArtifactEventPublisher.PackageVersionChange> changes = new ArrayList<>();
        doAnswer(invocation -> changes.add(invocation.getArgument(0))).when(publisher).publish(any());
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new CodeArtifactService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID), AccountAwareStorageBackend.inMemory(ACCOUNT_ID),
                regionResolver, config, true, null, publisher);
        PackageCoordinate source = packageFixture();
        byte[] content = "event payload".getBytes(StandardCharsets.UTF_8);

        service.publishPackageVersion(source, "3.0.0", "data.bin", content, hash(content), false);
        assertEquals(1, changes.size());
        CodeArtifactEventPublisher.PackageVersionChange published = changes.getFirst();
        assertEquals("Created", published.operationType());
        assertEquals("Published", published.state());
        assertEquals("source", published.repository());
        assertEquals(ACCOUNT_ID, published.domainOwner());
        assertEquals("generic", published.format());
        assertEquals("scope", published.namespace());
        assertEquals("artifact", published.packageName());
        assertEquals("3.0.0", published.version());
        assertEquals(1, published.assetsAdded());
        assertTrue(published.statusChanged());

        service.copyPackageVersions(source, "mirror", mutation(List.of("3.0.0"), Map.of(), null, null, false));
        assertEquals("mirror", changes.get(1).repository());
        assertEquals("Created", changes.get(1).operationType());

        service.mutatePackageVersions(source, "status", mutation(List.of("3.0.0"), Map.of(), null, "Archived", false));
        assertEquals("Archived", changes.get(2).state());
        assertEquals("Updated", changes.get(2).operationType());
        service.mutatePackageVersions(source, "status", mutation(List.of("3.0.0"), Map.of(), null, "Archived", false));
        assertEquals(3, changes.size());

        PackageCoordinate mirror = new PackageCoordinate(REGION, "packages", null, "mirror", "generic", "scope", "artifact");
        service.mutatePackageVersions(mirror, "dispose", mutation(List.of("3.0.0"), Map.of(), null, null, false));
        assertEquals("Disposed", changes.get(3).state());
        assertEquals(1, changes.get(3).assetsRemoved());
        service.mutatePackageVersions(mirror, "delete", mutation(List.of("3.0.0"), Map.of(), null, null, false));
        assertEquals("Deleted", changes.get(4).state());
        assertEquals("Deleted", changes.get(4).operationType());

        service.publishPackageVersion(source, "4.0.0", "data.bin", content, hash(content), true);
        service.publishPackageVersion(source, "4.0.0", "data.bin", content, hash(content), true);
        assertEquals(6, changes.size());
        assertEquals("Unfinished", changes.get(5).state());
        service.publishPackageVersion(source, "4.0.0", "data.bin", content, hash(content), false);
        assertEquals("Updated", changes.get(6).operationType());
        assertEquals("Published", changes.get(6).state());
        assertEquals(0, changes.get(6).assetsAdded());
    }

    private PackageCoordinate packageFixture() {
        service.createDomain(REGION, "packages", null, Map.of());
        service.createRepository(REGION, "packages", null, "source", null, null, Map.of());
        service.createRepository(REGION, "packages", null, "mirror", null, null, Map.of());
        return new PackageCoordinate(REGION, "packages", null, "source", "generic", "scope", "artifact");
    }

    private static VersionMutation mutation(List<String> versions, Map<String, String> revisions,
                                             String expected, String target, boolean overwrite) {
        return new VersionMutation(versions, revisions, expected, target, overwrite, false);
    }

    private static String hash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    // ------------------------------------------------------------------- tags

    @Test
    void tagAndUntagResourceRoundTripForDomainAndRepository() {
        DomainView domain = service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository repo = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());

        service.tagResource(domain.domain().getArn(), Map.of("owner", "platform"));
        assertEquals(Map.of("owner", "platform"), service.listTagsForResource(domain.domain().getArn()));
        service.untagResource(domain.domain().getArn(), List.of("owner"));
        assertTrue(service.listTagsForResource(domain.domain().getArn()).isEmpty());

        service.tagResource(repo.getArn(), Map.of("team", "data"));
        assertEquals(Map.of("team", "data"), service.listTagsForResource(repo.getArn()));
    }

    @Test
    void tagResourceRejectsAwsReservedPrefix() {
        DomainView domain = service.createDomain(REGION, "dom", null, Map.of());
        AwsException e = assertThrows(AwsException.class,
                () -> service.tagResource(domain.domain().getArn(), Map.of("aws:reserved", "x")));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void tagResourceRejectsUnknownResourceArn() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.tagResource("arn:aws:codeartifact:" + REGION + ":" + ACCOUNT_ID + ":domain/ghost",
                        Map.of("k", "v")));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals("ghost", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
    }

    @Test
    void createDomainRejectsMoreThanTwoHundredTags() {
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < 201; i++) {
            tags.put("key-" + i, "value-" + i);
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "dom", null, tags));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals("dom", e.getExtendedData().get("resourceId"));
        assertEquals("domain", e.getExtendedData().get("resourceType"));
    }

    @Test
    void tagResourceRejectsMoreThanTwoHundredTagsOnRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        CodeArtifactRepository repo = service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < 201; i++) {
            tags.put("key-" + i, "value-" + i);
        }
        AwsException e = assertThrows(AwsException.class, () -> service.tagResource(repo.getArn(), tags));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals("repo", e.getExtendedData().get("resourceId"));
        assertEquals("repository", e.getExtendedData().get("resourceType"));
    }

    @Test
    void clearRemovesAllPersistedState() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        service.clear();
        assertTrue(service.listDomains(REGION, null, null).items().isEmpty());
        AwsException e = assertThrows(AwsException.class,
                () -> service.describeRepository(REGION, "dom", null, "repo"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    // ---------------------------------------------------- authorization tokens

    @Test
    void getAuthorizationTokenRequiresExistingDomain() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.getAuthorizationToken(REGION, "missing-domain", null, null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void getAuthorizationTokenDefaultsToTwelveHoursAndValidatesForItsDomain() {
        service.createDomain(REGION, "dom", null, Map.of());
        AuthorizationToken token = service.getAuthorizationToken(REGION, "dom", null, null);

        Optional<AuthorizationTokenScope> scope = service.resolveAuthorizationToken(token.token(), "dom");
        assertTrue(scope.isPresent());
        assertEquals(REGION, scope.get().region());
        assertEquals(ACCOUNT_ID, scope.get().owner());
        assertTrue(service.resolveAuthorizationToken(token.token(), "other-dom").isEmpty());
        assertTrue(service.resolveAuthorizationToken("not-a-real-token", "dom").isEmpty());
        assertTrue(service.resolveAuthorizationToken(null, "dom").isEmpty());
    }

    @Test
    void getAuthorizationTokenAcceptsZeroAndTheDocumentedRange() {
        service.createDomain(REGION, "dom", null, Map.of());

        service.getAuthorizationToken(REGION, "dom", null, 0L);
        service.getAuthorizationToken(REGION, "dom", null, 900L);
        service.getAuthorizationToken(REGION, "dom", null, 43200L);
    }

    @Test
    void getAuthorizationTokenRejectsDurationsOutsideTheDocumentedRange() {
        service.createDomain(REGION, "dom", null, Map.of());

        AwsException tooShort = assertThrows(AwsException.class,
                () -> service.getAuthorizationToken(REGION, "dom", null, 899L));
        assertEquals("ValidationException", tooShort.getErrorCode());

        AwsException tooLong = assertThrows(AwsException.class,
                () -> service.getAuthorizationToken(REGION, "dom", null, 43201L));
        assertEquals("ValidationException", tooLong.getErrorCode());
    }

    @Test
    void clearInvalidatesOutstandingAuthorizationTokens() {
        service.createDomain(REGION, "dom", null, Map.of());
        AuthorizationToken token = service.getAuthorizationToken(REGION, "dom", null, null);
        service.clear();
        assertTrue(service.resolveAuthorizationToken(token.token(), "dom").isEmpty());
    }

    // -------------------------------------------------------- package versions

    @Test
    void publishPackageVersionComputesRealHashesAndPublishesByDefault() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        PublishPackageVersionResult result = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                "my-ns", "my-pkg", "1.0.0", "asset.txt", sha256, "false", content);

        assertEquals("Published", result.packageVersion().getStatus());
        assertEquals("asset.txt", result.asset().getName());
        assertEquals(content.length, result.asset().getSize());
        assertEquals(sha256, result.asset().getHashes().get("SHA-256"));
        assertTrue(result.asset().getHashes().containsKey("MD5"));
        assertTrue(result.asset().getHashes().containsKey("SHA-1"));
        assertTrue(result.asset().getHashes().containsKey("SHA-512"));
    }

    @Test
    void publishPackageVersionRejectsMismatchedSha256() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", "0".repeat(64), "false", content));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void publishPackageVersionRequiresAssetSha256() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        AwsException missing = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom",
                null, "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", null, "false", content));
        assertEquals("ValidationException", missing.getErrorCode());

        AwsException malformed = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom",
                null, "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", "not-hex", "false", content));
        assertEquals("ValidationException", malformed.getErrorCode());
    }

    @Test
    void publishPackageVersionRejectsAssetNameWithControlCharacters() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        // A newline here would land verbatim in the X-AssetName response header on GetPackageVersionAsset.
        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt\r\nX-Injected: true", sha256, "false",
                content));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void publishPackageVersionRejectsMalformedUnfinishedFlag() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", sha256Hex(content), "yes", content));
        assertEquals("ValidationException", e.getErrorCode());

        PublishPackageVersionResult omitted = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "asset.txt", sha256Hex(content), null, content);
        assertEquals("Published", omitted.packageVersion().getStatus());
    }

    @Test
    void publishPackageVersionRejectsNonGenericFormat() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "npm", null, "my-pkg", "1.0.0", "asset.txt", sha256Hex(content), "false", content));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void publishPackageVersionRequiresExistingRepository() {
        service.createDomain(REGION, "dom", null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "no-such-repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", sha256Hex(content), "false", content));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void requiredPackageVersionFieldsRejectNullInsteadOfCrashing() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        AwsException publishMissingDomain = assertThrows(AwsException.class, () -> service.publishPackageVersion(
                REGION, null, null, "repo", "generic", null, "my-pkg", "1.0.0", "asset.txt", sha256Hex(content),
                "false", content));
        assertEquals("ValidationException", publishMissingDomain.getErrorCode());
        assertEquals("domain is required.", publishMissingDomain.getMessage());

        AwsException describeMissingRepository = assertThrows(AwsException.class,
                () -> service.describePackageVersion(REGION, "dom", null, null, "generic", null, "my-pkg", "1.0.0"));
        assertEquals("ValidationException", describeMissingRepository.getErrorCode());
        assertEquals("repository is required.", describeMissingRepository.getMessage());

        AwsException getAssetMissingVersion = assertThrows(AwsException.class,
                () -> service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic", null, "my-pkg", null,
                        "asset.txt", null));
        assertEquals("ValidationException", getAssetMissingVersion.getErrorCode());
        assertEquals("packageVersion is required.", getAssetMissingVersion.getMessage());
    }

    @Test
    void unfinishedPublishAllowsMultipleAssetsThenFinalizes() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);

        PublishPackageVersionResult r1 = service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null,
                "my-pkg", "1.0.0", "a.txt", sha256Hex(first), "true", first);
        assertEquals("Unfinished", r1.packageVersion().getStatus());

        PublishPackageVersionResult r2 = service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null,
                "my-pkg", "1.0.0", "b.txt", sha256Hex(second), "false", second);
        assertEquals("Published", r2.packageVersion().getStatus());
        assertEquals(2, r2.packageVersion().getAssets().size());
        assertTrue(r2.packageVersion().getAssets().containsKey("a.txt"));
        assertTrue(r2.packageVersion().getAssets().containsKey("b.txt"));
    }

    @Test
    void publishingToAnAlreadyPublishedVersionConflicts() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "false", content);

        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "b.txt", sha256Hex(content), "false", content));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals("1.0.0", e.getExtendedData().get("resourceId"));
        assertEquals("package-version", e.getExtendedData().get("resourceType"));
    }

    @Test
    void publishPackageVersionCapsAssetsPerVersionAtThreeHundredFifty() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        for (int i = 0; i < 350; i++) {
            byte[] content = ("content-" + i).getBytes(StandardCharsets.UTF_8);
            service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0",
                    "asset-" + i + ".txt", sha256Hex(content), "true", content);
        }
        byte[] oneMore = "one-more".getBytes(StandardCharsets.UTF_8);
        AwsException e = assertThrows(AwsException.class, () -> service.publishPackageVersion(REGION, "dom", null,
                "repo", "generic", null, "my-pkg", "1.0.0", "asset-350.txt", sha256Hex(oneMore), "true", oneMore));
        assertEquals("ServiceQuotaExceededException", e.getErrorCode());
        assertEquals("1.0.0", e.getExtendedData().get("resourceId"));
        assertEquals("package-version", e.getExtendedData().get("resourceType"));

        // Re-publishing an asset name already on the version must not itself trip the cap.
        byte[] resend = "content-0".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "asset-0.txt",
                sha256Hex(resend), "true", resend);
    }

    @Test
    void describePackageVersionRoundTripsAndReportsNotFound() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", "ns", "my-pkg", "1.0.0", "a.txt",
                sha256Hex(content), "false", content);

        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "generic", "ns",
                "my-pkg", "1.0.0");
        assertEquals("Published", pv.getStatus());
        assertEquals("ns", pv.getNamespace());

        AwsException e = assertThrows(AwsException.class, () -> service.describePackageVersion(REGION, "dom", null,
                "repo", "generic", "ns", "my-pkg", "2.0.0"));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void getPackageVersionAssetReturnsExactBytesAndValidatesRevision() {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        PublishPackageVersionResult published = service.publishPackageVersion(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", sha256Hex(content), "false", content);

        PackageVersionAssetResult result = service.getPackageVersionAsset(REGION, "dom", null, "repo", "generic",
                null, "my-pkg", "1.0.0", "a.txt", null);
        assertEquals(content.length, result.asset().getContent().length);
        assertEquals("hello world", new String(result.asset().getContent(), StandardCharsets.UTF_8));
        assertEquals(published.packageVersion().getRevision(), result.packageVersionRevision());

        AwsException wrongAsset = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION,
                "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "missing.txt", null));
        assertEquals("ResourceNotFoundException", wrongAsset.getErrorCode());
        assertEquals("missing.txt", wrongAsset.getExtendedData().get("resourceId"));
        assertEquals("asset", wrongAsset.getExtendedData().get("resourceType"));

        AwsException wrongRevision = assertThrows(AwsException.class, () -> service.getPackageVersionAsset(REGION,
                "dom", null, "repo", "generic", null, "my-pkg", "1.0.0", "a.txt", "not-the-current-revision"));
        assertEquals("ResourceNotFoundException", wrongRevision.getErrorCode());
        assertEquals("1.0.0", wrongRevision.getExtendedData().get("resourceId"));
        assertEquals("package-version", wrongRevision.getExtendedData().get("resourceType"));
    }

    @Test
    void concurrentUnfinishedPublishesOfDifferentAssetsBothPersist() throws InterruptedException {
        service.createDomain(REGION, "dom", null, Map.of());
        service.createRepository(REGION, "dom", null, "repo", null, null, Map.of());
        int assetCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(assetCount);
        CountDownLatch ready = new CountDownLatch(assetCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < assetCount; i++) {
                int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        byte[] content = ("content-" + index).getBytes(StandardCharsets.UTF_8);
                        service.publishPackageVersion(REGION, "dom", null, "repo", "generic", null, "my-pkg",
                                "1.0.0", "asset-" + index + ".txt", sha256Hex(content), "true", content);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        CodeArtifactPackageVersion pv = service.describePackageVersion(REGION, "dom", null, "repo", "generic", null,
                "my-pkg", "1.0.0");
        assertEquals(assetCount, pv.getAssets().size());
    }

    private static String sha256Hex(byte[] content) {
        try {
            return SigV4RequestValidator.sha256Hex(content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
