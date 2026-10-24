package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.kms.model.KmsKeySpec;
import io.github.hectorvent.floci.services.kms.model.KmsKeyUsage;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskConfiguration;
import io.github.hectorvent.floci.services.msk.model.MskScramAssociations;
import io.github.hectorvent.floci.services.msk.model.ScramSecretAssociation;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService.BatchGetSecretValueError;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService.BatchGetSecretValueResult;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService.BatchSecretValue;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MskScramServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String SECRET =
        "arn:aws:secretsmanager:us-east-1:000000000000:secret:AmazonMSK_alice";
    private AccountAwareStorageBackend<MskCluster> clusters;
    private AccountAwareStorageBackend<MskScramAssociations> associations;
    private StorageFactory storageFactory;
    private EmulatorConfig config;
    private RegionResolver regionResolver;
    private RedpandaManager redpanda;
    private SecretsManagerService secrets;
    private KmsService kms;
    private ObjectMapper mapper;
    private MskService clusterService;
    private MskScramSecretsService service;
    private MskCluster cluster;

    @BeforeEach
    void setUp() {
        clusters = AccountAwareStorageBackend.inMemory(ACCOUNT);
        associations = spy(AccountAwareStorageBackend.<MskScramAssociations>inMemory(ACCOUNT));
        storageFactory = mock(StorageFactory.class);
        when(storageFactory.<MskCluster>create(eq("msk"), eq("msk-clusters.json"), any()))
            .thenReturn(clusters);
        when(storageFactory.<MskScramAssociations>create(eq("msk"), eq("msk-scram-associations.json"), any()))
            .thenReturn(associations);
        when(storageFactory.<MskConfiguration>create(eq("msk"), eq("msk-configurations.json"), any()))
            .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().msk().mock()).thenReturn(true);
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getRegion()).thenReturn("us-east-1");
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        redpanda = mock(RedpandaManager.class);
        secrets = mock(SecretsManagerService.class);
        kms = mock(KmsService.class);
        when(secrets.describeSecret(anyString(), anyString())).thenAnswer(invocation -> {
            Secret metadata = new Secret();
            metadata.setArn(invocation.getArgument(0));
            metadata.setKmsKeyId("customer-key");
            return metadata;
        });
        when(kms.describeKey(eq("customer-key"), anyString()))
            .thenReturn(new KmsKey());
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        clusterService = spy(new MskService(storageFactory, config, regionResolver, redpanda));
        service = new MskScramSecretsService(storageFactory, config, clusterService, redpanda, secrets, kms, mapper);
        cluster = clusterService.createCluster("scram");
        cluster.setContainerId("broker-1");
        when(config.services().msk().mock())
            .thenReturn(false);
    }

    private SecretVersion version(String username, String password) {
        SecretVersion version = new SecretVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setSecretString(
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        return version;
    }

    @Test
    void brokerOperationDoesNotBlockAnotherCluster() throws Exception {
        when(config.services().msk().mock())
            .thenReturn(true);
        MskCluster otherCluster = clusterService.createCluster("other-scram");
        when(config.services().msk().mock())
            .thenReturn(false);
        CountDownLatch operationInFirstClusterStarted = new CountDownLatch(1);
        CountDownLatch operationInSecondClusterDone = new CountDownLatch(1);
        doAnswer(invocation -> {
            operationInFirstClusterStarted.countDown();
            assertTrue(operationInSecondClusterDone.await(10, TimeUnit.SECONDS));
            return null;
        }).when(redpanda).createUser(cluster, "alice", "password");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> associationInFirstCluster = executor.submit(
                () -> associate(SECRET, version("alice", "password")));
            try {
                assertTrue(operationInFirstClusterStarted.await(5, TimeUnit.SECONDS));
                Future<List<String>> listInSecondCluster = executor.submit(
                    () -> service.listScramSecrets(otherCluster.getClusterArn(), null, null).items());
                assertTrue(listInSecondCluster.get(5, TimeUnit.SECONDS).isEmpty());
            } finally {
                operationInSecondClusterDone.countDown();
            }
            associationInFirstCluster.get(5, TimeUnit.SECONDS);
        }
    }

    private void associate(String arn, SecretVersion version) {
        setSecretValue(arn, version);
        assertTrue(
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(arn)).isEmpty());
    }

    private void setSecretValue(String arn, SecretVersion version) {
        BatchGetSecretValueResult batchGetSecretValueResult = new BatchGetSecretValueResult(List.of(new BatchSecretValue(
            arn, "AmazonMSK_fixture", version.getSecretString(), version.getSecretBinary(),
            version.getVersionId(), List.of("AWSCURRENT"), version.getCreatedDate())
        ), List.of());
        doReturn(batchGetSecretValueResult)
            .when(secrets)
            .batchGetSecretValue(List.of(arn), AwsArnUtils.parse(arn).region());
    }

    private void setSecretReadError(String errorCode, String message) {
        doReturn(new BatchGetSecretValueResult(List.of(), List.of(new BatchGetSecretValueError(SECRET, errorCode, message))))
            .when(secrets).batchGetSecretValue(List.of(SECRET), "us-east-1");
    }

    private MskScramAssociations state() {
        return associations
            .getForAccount(ACCOUNT, cluster.getClusterArn())
            .orElseGet(MskScramAssociations::new);
    }

    private ScramSecretAssociation association(String secretArn) {
        return state().getAssociations().get(secretArn);
    }

    private static Stream<Arguments> invalidCredentials() {
        return Stream.of(
            Arguments.of(Named.of("empty content", "")),
            Arguments.of(Named.of("malformed JSON", "{")),
            Arguments.of(Named.of("array instead of object", "[]")),
            Arguments.of(Named.of("string instead of object", "\"alice\"")),
            Arguments.of(Named.of("number instead of object", "42")),
            Arguments.of(Named.of("empty object", "{}")),
            Arguments.of(Named.of("missing username", "{\"password\":\"pw\"}")),
            Arguments.of(Named.of("missing password", "{\"username\":\"alice\"}")),
            Arguments.of(Named.of("null username", "{\"username\":null,\"password\":\"pw\"}")),
            Arguments.of(Named.of("empty username", "{\"username\":\"\",\"password\":\"pw\"}")),
            Arguments.of(Named.of("blank username", "{\"username\":\"   \",\"password\":\"pw\"}")),
            Arguments.of(Named.of("empty password", "{\"username\":\"alice\",\"password\":\"\"}")),
            Arguments.of(Named.of("blank password", "{\"username\":\"alice\",\"password\":\"   \"}")),
            Arguments.of(Named.of("array username", "{\"username\":[],\"password\":\"pw\"}")),
            Arguments.of(Named.of("object username", "{\"username\":{},\"password\":\"pw\"}")),
            Arguments.of(Named.of("boolean username", "{\"username\":true,\"password\":\"pw\"}")),
            Arguments.of(Named.of("null password", "{\"username\":\"alice\",\"password\":null}")),
            Arguments.of(Named.of("array password", "{\"username\":\"alice\",\"password\":[]}")),
            Arguments.of(Named.of("object password", "{\"username\":\"alice\",\"password\":{}}")),
            Arguments.of(Named.of("numeric password", "{\"username\":\"alice\",\"password\":123}")));
    }

    private static Stream<Arguments> validCredentials() {
        return Stream.of(
            Arguments.of(Named.of("string credentials", "{\"username\":\"alice\",\"password\":\"pw\"}"), "alice", "pw"),
            Arguments.of(Named.of("numeric username converted to string", "{\"username\":123,\"password\":\"pw\"}"), "123", "pw"),
            Arguments.of(Named.of("boolean password converted to string", "{\"username\":\"alice\",\"password\":true}"), "alice", "true"),
            Arguments.of(Named.of("extra fields ignored", "{\"username\":\"alice\",\"password\":\"pw\",\"extra\":42}"), "alice", "pw"));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("validCredentials")
    void readScramCredentialsWithValidValues(
        String content, String username, String password) {
        // Supported scalar values become strings, while unrelated JSON fields are ignored.
        MskScramSecretsService.ScramCredentials credentials = service.readScramCredentials(content);

        assertEquals(username, credentials.username());
        assertEquals(password, credentials.password());
    }

    @Test
    void reconciliationRemovesOnlyDeletedClusterAssociationsAcrossAccounts() {
        associate(SECRET, version("alice", "password"));
        String otherAccount = "111111111111";
        String deletedCluster = cluster.getClusterArn().replace(ACCOUNT, otherAccount);
        associations.putForAccount(otherAccount, deletedCluster, new MskScramAssociations());

        service.reconcileScramSecrets();

        assertTrue(associations.getForAccount(ACCOUNT, cluster.getClusterArn()).isPresent());
        assertTrue(associations.getForAccount(otherAccount, deletedCluster).isEmpty());
        verify(clusterService).getClusterForAccount(otherAccount, deletedCluster);
    }

    @Test
    void reconciliationPreservesAssociationsWhenClusterLookupFails() {
        associate(SECRET, version("alice", "password"));
        AwsException failure = new AwsException("InternalServerErrorException", "Lookup failed.", 500);
        doThrow(failure).when(clusterService).getClusterForAccount(ACCOUNT, cluster.getClusterArn());

        assertEquals(failure, assertThrows(AwsException.class, () -> service.reconcileScramSecrets()));
        assertTrue(associations.getForAccount(ACCOUNT, cluster.getClusterArn()).isPresent());
    }

    @Test
    void readScramCredentialsRejectsNullSecretStringWithInternalServerError() {
        // A missing secret string maps to an internal error rather than invalid credentials.
        AwsException error = assertThrows(AwsException.class, () -> service.readScramCredentials(null));

        assertEquals("InternalServerError", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"alias/msk-scram",
            "arn:aws:kms:us-east-1:000000000000:alias/msk-scram"})
    void rejectsKmsAliasReferencesBeforeKeyLookup(String keyReference) {
        Secret metadata = new Secret();
        metadata.setArn(SECRET);
        metadata.setKmsKeyId(keyReference);
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);

        List<Map<String, String>> failures = service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals("BadRequestException", failures.getFirst().get("errorCode"));
        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());
        verifyNoInteractions(kms);
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678-1234-1234-1234-123456789012",
            "arn:aws:kms:us-east-1:000000000000:key/12345678-1234-1234-1234-123456789012"})
    void acceptsKmsKeyIdsAndKeyArns(String keyReference) {
        Secret metadata = new Secret();
        metadata.setArn(SECRET);
        metadata.setKmsKeyId(keyReference);
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);
        when(kms.describeKey(keyReference, "us-east-1")).thenReturn(new KmsKey());
        setSecretValue(SECRET, version("alice", "password"));

        assertTrue(service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());

        verify(kms).describeKey(keyReference, "us-east-1");
        assertEquals(List.of(SECRET), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
    }

    @Test
    void rejectsPartialSecretArnForAssociation() {
        String fullArn = SECRET + "-Ab12Cd";
        Secret metadata = new Secret();
        metadata.setArn(fullArn);
        metadata.setKmsKeyId("customer-key");
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);

        List<Map<String, String>> failures = service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals(List.of(Map.of("secretArn", SECRET, "errorCode", "InvalidSecretArn",
                "errorMessage", "Amazon MSK wasn't able to access the provided secret.")), failures);
        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());
        verifyNoInteractions(kms);
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
        associate(fullArn, version("alice", "password"));
        assertEquals(List.of(fullArn), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
    }

    @Test
    void rejectsPartialSecretArnForDisassociation() {
        String fullArn = SECRET + "-Ab12Cd";
        associate(fullArn, version("alice", "password"));
        clearInvocations(secrets);

        List<Map<String, String>> failures = service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals(List.of(Map.of("secretArn", SECRET, "errorCode", "InvalidSecretArn",
                "errorMessage", "The provided secret ARN is invalid.")), failures);
        assertEquals(List.of(fullArn), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(fullArn)).isEmpty());
        verifyNoInteractions(secrets);
    }

    @Test
    void disassociatesDeletedSecretWithoutLookingItUp() {
        associate(SECRET, version("alice", "password"));
        when(secrets.describeSecret(SECRET, "us-east-1"))
                .thenThrow(new AwsException("ResourceNotFoundException", "Secret not found.", 400));
        clearInvocations(secrets);

        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());

        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());
        assertTrue(associations.getForAccount(ACCOUNT, cluster.getClusterArn()).isEmpty());
        verifyNoInteractions(secrets);
    }

    @Test
    void rejectsReturnedSecretArnForDifferentSecret() {
        when(secrets.batchGetSecretValue(List.of(SECRET), "us-east-1"))
            .thenReturn(
                new BatchGetSecretValueResult(List.of(new BatchSecretValue(
                        SECRET + "Other", "AmazonMSK_fixture", "{\"username\":\"alice\",\"password\":\"password\"}",
                        null, "version", List.of("AWSCURRENT"), null)), List.of())
            );

        List<Map<String, String>> failures = service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals("InternalServerError", failures.getFirst().get("errorCode"));
        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());
    }

    @Test
    void readScramCredentialsRejectsJsonNullWithInternalServerError() {
        // JSON null has the same internal-error contract as a missing secret string.
        AwsException error =
            assertThrows(AwsException.class, () -> service.readScramCredentials("null"));

        assertEquals("InternalServerError", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("invalidCredentials")
    void readScramCredentialsWithInvalidValues(String content) {
        // Malformed content and unsupported credential values produce InvalidSecretArn.
        AwsException error =
            assertThrows(AwsException.class, () -> service.readScramCredentials(content));

        assertEquals("InvalidSecretArn", error.getErrorCode());
    }

    @Test
    void associationRejectsDefaultAndIncompatibleKmsKeysWithoutChangingCredentials() {
        // Only compatible customer-managed keys are accepted, without disturbing working credentials.
        SecretVersion original = version("alice", "password");
        associate(SECRET, original);
        clearInvocations(redpanda);
        Secret metadata = new Secret();
        String invalidArn = SECRET + "-invalid-key";
        metadata.setArn(invalidArn);
        when(secrets.describeSecret(invalidArn, "us-east-1")).thenReturn(metadata);
        KmsKey key = new KmsKey();
        when(kms.describeKey("incompatible-key", "us-east-1")).thenReturn(key);

        for (String keyKind : List.of("default", "aws-managed", "asymmetric", "hmac")) {
            metadata.setKmsKeyId("default".equals(keyKind) ? null : "incompatible-key");
            key.setKeyManager("aws-managed".equals(keyKind) ? "AWS" : "CUSTOMER");
            key.setKeySpec(
                "asymmetric".equals(keyKind)
                    ? KmsKeySpec.RSA_2048
                    : "hmac".equals(keyKind)
                    ? KmsKeySpec.HMAC_256
                    : KmsKeySpec.SYMMETRIC_DEFAULT);
            key.setKeyUsage(
                "hmac".equals(keyKind)
                    ? KmsKeyUsage.GENERATE_VERIFY_MAC
                    : KmsKeyUsage.ENCRYPT_DECRYPT);

            List<Map<String, String>> failures =
                service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(invalidArn));

            assertEquals(1, failures.size(), keyKind);
            assertEquals("BadRequestException", failures.getFirst().get("errorCode"), keyKind);
            assertNull(association(invalidArn));
            assertEquals("alice", association(SECRET).getUsername(), keyKind);
        }
        verifyNoInteractions(redpanda);
        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());
    }

    @Test
    void associationRejectsKmsAliasBeforeLookupWithoutReadingCredentials() {
        // KMS aliases fail validation before any key lookup, credential read, or broker change.
        Secret metadata = new Secret();
        metadata.setArn(SECRET);
        metadata.setKmsKeyId("alias/msk-scram");
        when(secrets.describeSecret(SECRET, "us-east-1")).thenReturn(metadata);

        List<Map<String, String>> failures = service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals(1, failures.size());
        assertEquals(SECRET, failures.getFirst().get("secretArn"));
        assertEquals("BadRequestException", failures.getFirst().get("errorCode"));
        assertNull(association(SECRET));
        verifyNoInteractions(kms, redpanda);
        verify(secrets, never()).batchGetSecretValue(any(), anyString());
    }

    @Test
    void associationReportsKmsLookupFailureWithoutCreatingAssociation() {
        // Key lookup errors remain item failures and must not create association state.
        doThrow(new AwsException("NotFoundException", "Key not found.", 400))
            .when(kms)
            .describeKey("customer-key", "us-east-1");

        List<Map<String, String>> failures =
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals("NotFoundException", failures.getFirst().get("errorCode"));
        assertNull(association(SECRET));
        verifyNoInteractions(redpanda);
    }

    @Test
    void duplicateAssociationBatchIsRejectedBeforeAnySecretIsProcessed() {
        // Duplicate ARNs invalidate the whole batch before any item can cause side effects.
        String fresh = SECRET + "-fresh";

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(fresh, SECRET, SECRET))
            );

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(state().getAssociations().isEmpty());
        verifyNoInteractions(redpanda, secrets, kms);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
    }

    @Test
    void duplicateAssociationBatchPreservesExistingAssociation() {
        // Rejecting a duplicate batch must leave existing credentials and membership untouched.
        SecretVersion original = version("alice", "original");
        associate(SECRET, original);
        clearInvocations(redpanda, secrets, kms, associations);
        String fresh = SECRET + "-fresh";

        AwsException error = assertThrows(
                AwsException.class,
                () -> service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(fresh, SECRET, SECRET))
            );

        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(
            List.of(SECRET),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        assertEquals("alice", association(SECRET).getUsername());
        verifyNoInteractions(redpanda, secrets, kms);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
    }

    @Test
    void associationRejectsSecretsWithoutAmazonMskPrefix() {
        String nonPrefixSecret =
            "arn:aws:secretsmanager:us-east-1:000000000000:secret:NotAmazonMSK_user";
        List<Map<String, String>> associateFailures =
            service.batchAssociateScramSecret(
                cluster.getClusterArn(), List.of(nonPrefixSecret));
        assertEquals(1, associateFailures.size());
        assertEquals("BadRequestException", associateFailures.getFirst().get("errorCode"));
    }

    @Test
    void listingAndDisassociationRejectServerlessClusters() {
        // Serverless clusters do not support these SCRAM management operations.
        cluster.setClusterType("SERVERLESS");
        assertThrows(AwsException.class, () -> service.listScramSecrets(cluster.getClusterArn(), null, null));
        assertThrows(AwsException.class, () -> service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)));
    }

    @Test
    void associationReadsCurrentVersionThroughExactArnBatchGetSecretValue() {
        // Association applies the AWSCURRENT secret value and records its version only after success.
        SecretVersion version = version("alice", "password");
        setSecretValue(SECRET, version);
        
        assertTrue(service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());

        verify(secrets).batchGetSecretValue(List.of(SECRET), "us-east-1");
        verify(secrets, never()).getSecretValue(anyString(), any(), any(), anyString());
        verify(secrets, never()).batchGetSecretValueByFilters(any(), anyString());
        verify(redpanda).createUser(cluster, "alice", "password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ResourceNotFoundException", "InvalidRequestException", "AccessDeniedException"})
    void batchReadErrorsAreReportedPerSecretWithoutBlockingOtherAssociations(String errorCode) {
        setSecretReadError(errorCode, "Secret cannot be read.");
        String validArn = SECRET + "-valid";
        setSecretValue(validArn, version("bob", "password"));

        List<Map<String, String>> failures = service.batchAssociateScramSecret(
            cluster.getClusterArn(), List.of(SECRET, validArn));

        assertEquals(List.of(Map.of("secretArn", SECRET, "errorCode", errorCode,
            "errorMessage", "Secret cannot be read.")), failures);
        assertNull(association(SECRET));
        assertNotNull(association(validArn));
        verify(redpanda).createUser(cluster, "bob", "password");
    }

    @Test
    void associationIsStoredAfterBrokerSideEffect() {
        doAnswer(
            invocation -> {
                verify(associations, never()).putForAccount(anyString(), anyString(), any());
                return null;
            })
            .when(redpanda)
            .createUser(cluster, "alice", "password");

        associate(SECRET, version("alice", "password"));
        assertEquals("alice", association(SECRET).getUsername());
        verify(associations).putForAccount(eq(ACCOUNT), eq(cluster.getClusterArn()), any());
    }

    @Test
    void failedAssociationCanBeRetried() {
        SecretVersion version = version("alice", "password");
        setSecretValue(SECRET, version);
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .createUser(cluster, "alice", "password");
        assertEquals(
            1,
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).size());
        assertTrue(service.listScramSecrets(cluster.getClusterArn(), null, null).items().isEmpty());

        doNothing().when(redpanda).createUser(cluster, "alice", "password");
        associate(SECRET, version);

        assertEquals("alice", association(SECRET).getUsername());
        assertEquals(List.of(SECRET), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
    }

    @Test
    void successfulAssociationCannotBeRepeated() {
        SecretVersion version = version("alice", "password");
        associate(SECRET, version);
        clearInvocations(redpanda, associations);
        setSecretValue(SECRET, version("bob", "changed"));
        List<Map<String, String>> failures =
            service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));
        assertEquals("InvalidSecretArn", failures.getFirst().get("errorCode"));
        assertEquals("alice", association(SECRET).getUsername());
        verifyNoInteractions(redpanda);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
        assertEquals(List.of(SECRET), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
    }

    @Test
    void repeatedAssociationIsRejectedBeforeReadingUnavailableSecret() {
        // Existing membership determines duplicate rejection even when the secret cannot be read.
        associate(SECRET, version("alice", "password"));
        clearInvocations(redpanda);
        for (String errorCode : List.of("ResourceNotFoundException", "InvalidRequestException")) {
            setSecretReadError(errorCode, "Secret cannot be read.");

            List<Map<String, String>> failures =
                service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

            assertEquals("InvalidSecretArn", failures.getFirst().get("errorCode"));
            assertEquals("alice", association(SECRET).getUsername());
        }
        verifyNoInteractions(redpanda);
    }

    @Test
    void disassociationRejectsNullEmptyAndOversizedBatches() {
        // Disassociation requires a nonempty batch containing at most ten secret ARNs.
        String clusterArn = cluster.getClusterArn();
        assertThrows(AwsException.class, () -> service.batchDisassociateScramSecret(clusterArn, null));
        assertThrows(AwsException.class, () -> service.batchDisassociateScramSecret(clusterArn, List.of()));
        assertThrows(AwsException.class, () -> service.batchDisassociateScramSecret(clusterArn, Collections.nCopies(11, SECRET)));
    }

    @Test
    void disassociationReportsMalformedSecretArnAsAnItemFailure() {
        // A malformed secret ARN is reported per item rather than failing the whole request.
        assertEquals(1, service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of("malformed")).size());
    }

    @Test
    void unassociatedDisassociationReturnsOneFailurePerOccurrenceWithoutBrokerCalls() {
        // Each unassociated ARN occurrence fails independently without storage or broker changes.
        List<Map<String, String>> failures =
            service.batchDisassociateScramSecret(
                cluster.getClusterArn(), List.of(SECRET, SECRET));

        assertEquals(2, failures.size());
        for (Map<String, String> failure : failures) {
            assertEquals(SECRET, failure.get("secretArn"));
            assertEquals("InvalidSecretArn", failure.get("errorCode"));
        }
        assertTrue(state().getAssociations().isEmpty());
        verifyNoInteractions(redpanda);
        verify(associations, never()).putForAccount(anyString(), anyString(), any());
    }

    @Test
    void repeatedDisassociationPreservesOtherAssociations() {
        // Removing an already absent secret must not affect other cluster associations.
        String other = SECRET + "-other";
        associate(SECRET, version("alice", "pw"));
        associate(other, version("bob", "pw"));
        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());
        clearInvocations(redpanda);

        List<Map<String, String>> failures = service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET));

        assertEquals("InvalidSecretArn", failures.getFirst().get("errorCode"));
        assertEquals(List.of(other), service.listScramSecrets(cluster.getClusterArn(), null, null).items());
        verifyNoInteractions(redpanda);
    }

    @Test
    void failedDisassociationRetriesOriginalUsernameWithoutApplyingChangedCredentials() {
        // Pending cleanup retains the original username even if the secret rotates before retry.
        associate(SECRET, version("alice", "password"));
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .deleteUser(cluster, "alice");

        assertEquals(1, service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).size());
        assertEquals("alice", association(SECRET).getUsername());
        clearInvocations(redpanda);
        setSecretValue(SECRET, version("charlie", "changed-during-cleanup"));
        doNothing().when(redpanda).deleteUser(cluster, "alice");
        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());
        assertTrue(state().getAssociations().isEmpty());
        verify(redpanda).deleteUser(cluster, "alice");
        verify(redpanda, never()).createUser(any(), anyString(), anyString());
    }

    @Test
    void deletionRetryRemovesAssociationWithoutRewritingIt() {
        associate(SECRET, version("alice", "password"));
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .deleteUser(cluster, "alice");
        service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET));
        clearInvocations(associations);
        doNothing().when(redpanda).deleteUser(cluster, "alice");

        assertTrue(service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET)).isEmpty());

        verify(associations, never()).putForAccount(anyString(), anyString(), any());
        verify(associations, times(1)).deleteForAccount(ACCOUNT, cluster.getClusterArn());
        assertTrue(state().getAssociations().isEmpty());
    }

    @Test
    void listScramSecretsReturnsEachPageAndOnlyProvidesTokenWhenMoreResultsExist() {
        // Pagination exposes each association once and omits the token on the final page.
        String second = SECRET + "-second";
        associate(SECRET, version("alice", "password"));
        associate(second, version("bob", "password"));
        PaginatedResult<String> page = service.listScramSecrets(cluster.getClusterArn(), 1, null);
        assertEquals(List.of(SECRET), page.items());
        assertNotNull(page.nextToken());
        PaginatedResult<String> last =
            service.listScramSecrets(cluster.getClusterArn(), 1, page.nextToken());
        assertEquals(List.of(second), last.items());
        assertNull(last.nextToken());
    }

    @Test
    void listScramSecretsRejectsPageSizesOutsideOneToOneHundred() {
        // List page sizes must stay within the AWS range of one through one hundred.
        assertThrows(AwsException.class, () -> service.listScramSecrets(cluster.getClusterArn(), 0, null));
        assertThrows(AwsException.class, () -> service.listScramSecrets(cluster.getClusterArn(), 101, null));
    }

    @Test
    void listScramSecretsRejectsMalformedNextToken() {
        // Invalid pagination tokens must fail instead of silently restarting the listing.
        assertThrows(AwsException.class, () -> service.listScramSecrets(cluster.getClusterArn(), 1, "!"));
    }

    @Test
    void listScramSecretsRejectsMissingCluster() {
        // Listing an unknown cluster must fail rather than appear to return an empty membership.
        assertThrows(AwsException.class, () -> service.listScramSecrets("missing", null, null));
    }

    private static Stream<Arguments> sharedUsernameAssociations() {
        return Stream.of(
            Arguments.of(Named.of("same password, sequential", false), false, "password"),
            Arguments.of(Named.of("same password, reversed sequential", false), true, "password"),
            Arguments.of(Named.of("same password, batch", true), false, "password"),
            Arguments.of(Named.of("same password, reversed batch", true), true, "password"),
            Arguments.of(Named.of("different passwords, sequential", false), false, "another-password"),
            Arguments.of(Named.of("different passwords, reversed sequential", false), true, "another-password"),
            Arguments.of(Named.of("different passwords, batch", true), false, "another-password"),
            Arguments.of(Named.of("different passwords, reversed batch", true), true, "another-password")
        );
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("sharedUsernameAssociations")
    void associationAcceptsSharedUsernameRegardlessOfPasswordAndOrder(
        boolean batch, boolean reversed, String secondPassword) {
        // Distinct secrets may share a broker username regardless of passwords, ordering, or batching.
        String second = SECRET + "-second";
        SecretVersion firstVersion = version("alice", "password");
        SecretVersion secondVersion = version("alice", secondPassword);
        setSecretValue(SECRET, firstVersion);
        setSecretValue(second, secondVersion);
        List<String> ordered = reversed ? List.of(second, SECRET) : List.of(SECRET, second);

        if (batch) {
            assertTrue(
                service.batchAssociateScramSecret(cluster.getClusterArn(), ordered).isEmpty());
        } else {
            for (String arn : ordered) {
                assertTrue(
                    service.batchAssociateScramSecret(cluster.getClusterArn(), List.of(arn))
                        .isEmpty());
            }
        }

        assertEquals(
            ordered.stream().sorted().toList(),
            service.listScramSecrets(cluster.getClusterArn(), null, null).items().stream()
                .sorted()
                .toList());
        assertEquals("alice", association(SECRET).getUsername());
        assertEquals("alice", association(second).getUsername());
        verify(redpanda, times("password".equals(secondPassword) ? 2 : 1))
            .createUser(cluster, "alice", "password");
        verify(redpanda, times("password".equals(secondPassword) ? 2 : 1))
            .createUser(cluster, "alice", secondPassword);
    }

    @Test
    void failedUserDeletionDoesNotInvalidateSharedAssociations() {
        // Failed broker deletion leaves both associations available for a caller to retry.
        String second = SECRET + "-second";
        SecretVersion surviving = version("alice", "surviving-password");
        associate(SECRET, version("alice", "password"));
        associate(second, surviving);
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .deleteUser(cluster, "alice");

        assertEquals(
            1,
            service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET))
                .size());

        assertEquals("alice", association(second).getUsername());
        assertEquals("alice", association(SECRET).getUsername());
    }

    @Test
    void associationsPersistSeparatelyWithoutPasswords() throws Exception {
        // Separate association storage survives restart without persisting passwords or reapplying credentials.
        associate(SECRET, version("alice", "sensitive-password"));
        String json = mapper.writeValueAsString(state());
        assertFalse(json.contains("sensitive-password"));
        assertFalse(mapper.readTree(mapper.writeValueAsString(cluster)).has("scramSecrets"));
        MskScramAssociations restored = mapper.readValue(json, MskScramAssociations.class);
        associations.putForAccount(ACCOUNT, cluster.getClusterArn(), restored);
        MskScramSecretsService restarted =
            new MskScramSecretsService(
                storageFactory, config, clusterService, redpanda, secrets, kms, mapper);

        assertEquals(
            List.of(SECRET),
            restarted.listScramSecrets(cluster.getClusterArn(), null, null).items());
        assertEquals("alice", restored.getAssociations().get(SECRET).getUsername());
        clearInvocations(redpanda);
        verifyNoInteractions(redpanda);
    }

    @Test
    void mockModeTracksAssociationsWithoutBrokerCalls() {
        // Mock mode maintains association lifecycle state while suppressing all broker operations.
        when(config.services().msk().mock()).thenReturn(true);
        associate(SECRET, version("alice", "password"));
        assertEquals("alice", association(SECRET).getUsername());
        service.batchDisassociateScramSecret(cluster.getClusterArn(), List.of(SECRET));
        verifyNoInteractions(redpanda);
    }

    @Test
    void failedClusterDeletionRetainsScramAssociationState() {
        // Failed cluster deletion retains both the cluster and its recoverable SCRAM association state.
        associate(SECRET, version("alice", "password"));
        doThrow(new AwsException("ServiceUnavailableException", "Broker unavailable.", 503))
            .when(redpanda)
            .stopContainer(cluster);

        assertThrows(
            AwsException.class, () -> clusterService.deleteCluster(cluster.getClusterArn()));

        assertNotNull(association(SECRET));
        assertTrue(clusters.get(cluster.getClusterArn()).isPresent());
    }
}
