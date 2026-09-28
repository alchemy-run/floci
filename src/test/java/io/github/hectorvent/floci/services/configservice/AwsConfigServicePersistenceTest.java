package io.github.hectorvent.floci.services.configservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.configservice.model.ConfigEvaluation;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleSource;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import io.github.hectorvent.floci.services.configservice.model.RecordingGroup;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies AWS Config durable resources survive a restart. Two service instances share the same
 * {@link StorageFactory} backends; the second simulates a process restart reloading from disk.
 */
class AwsConfigServicePersistenceTest {

    private static final String REGION = "us-east-1";

    @Test
    void durableResourcesAndTagsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        ConfigRule rule = first.putConfigRule(REGION,
                rule("s3-public-read", "AWS", "S3_BUCKET_PUBLIC_READ_PROHIBITED"));
        first.putConformancePack(REGION, "ops-pack", "s3://bucket/template.yaml", null);
        first.putConfigurationRecorder(REGION, new ConfigurationRecorder("default",
                "arn:aws:iam::000000000000:role/config", new RecordingGroup(true, false, null)));
        first.putDeliveryChannel(REGION,
                new DeliveryChannel("default", "config-bucket", null, null, null, null));
        first.tagResource(rule.configRuleArn(), List.of(Map.of("Key", "env", "Value", "prod")));

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("s3-public-read"),
                reloaded.describeConfigRules(REGION, null).stream().map(ConfigRule::configRuleName).toList());
        assertEquals(List.of("ops-pack"),
                reloaded.describeConformancePacks(REGION, null).stream()
                        .map(p -> p.conformancePackName()).toList());
        assertEquals("default",
                reloaded.describeConfigurationRecorders(REGION, null).getFirst().name());
        assertEquals("config-bucket",
                reloaded.describeDeliveryChannels(REGION, null).getFirst().s3BucketName());
        assertEquals("prod", reloaded.listTagsForResource(rule.configRuleArn()).getFirst().get("Value"));
    }

    @Test
    void deleteAndUntagArePersistedAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        ConfigRule rule = first.putConfigRule(REGION, rule("keep", "AWS", "REQUIRED_TAGS"));
        first.putConfigRule(REGION, rule("drop", "AWS", "REQUIRED_TAGS"));
        first.deleteConfigRule(REGION, "drop");
        first.tagResource(rule.configRuleArn(),
                List.of(Map.of("Key", "env", "Value", "prod"), Map.of("Key", "team", "Value", "sec")));
        first.untagResource(rule.configRuleArn(), List.of("team"));

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("keep"),
                reloaded.describeConfigRules(REGION, null).stream().map(ConfigRule::configRuleName).toList());
        Map<String, String> tags = reloaded.listTagsForResource(rule.configRuleArn()).stream()
                .collect(java.util.stream.Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("prod", tags.get("env"));
        assertTrue(!tags.containsKey("team"), "untagged key must not reappear after restart");
    }

    @Test
    void evaluationsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        first.putConfigRule(REGION, rule("bucket-policy", "CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check"));
        first.putEvaluations(REGION, "bucket-policy",
                List.of(evaluation("AWS::S3::Bucket", "bucket-1", "NON_COMPLIANT")), false);

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals("NON_COMPLIANT", reloaded.complianceForRule(REGION, "bucket-policy").complianceType());
        assertEquals(1, reloaded.getComplianceDetailsByConfigRule(REGION, "bucket-policy",
                null, null, null).items().size());
    }

    @Test
    void deleteConfigRuleCascadesEvaluationsAcrossRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        first.putConfigRule(REGION, rule("kept-rule", "CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check"));
        first.putConfigRule(REGION, rule("dropped-rule", "CUSTOM_LAMBDA",
                "arn:aws:lambda:us-east-1:000000000000:function:check"));
        first.putEvaluations(REGION, "kept-rule",
                List.of(evaluation("AWS::S3::Bucket", "bucket-1", "COMPLIANT")), false);
        first.putEvaluations(REGION, "dropped-rule",
                List.of(evaluation("AWS::S3::Bucket", "bucket-1", "NON_COMPLIANT")), false);
        first.deleteConfigRule(REGION, "dropped-rule");

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals("COMPLIANT", reloaded.complianceForRule(REGION, "kept-rule").complianceType());
        assertEquals("INSUFFICIENT_DATA", reloaded.complianceForRule(REGION, "dropped-rule").complianceType());
    }

    @Test
    void retentionConfigurationSurvivesRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        AwsConfigService first = serviceWithStorage(storage);
        first.putRetentionConfiguration(REGION, 90);

        AwsConfigService reloaded = serviceWithStorage(storage);

        assertEquals(90, reloaded.describeRetentionConfigurations(REGION, null)
                .getFirst().retentionPeriodInDays());
    }

    @Test
    void legacyRecorderReceivesOneDurableArnOnRead() {
        SharedStorageFactory storage = new SharedStorageFactory();
        storage.create("config", "config-recorders.json", new TypeReference<Map<String, ConfigurationRecorder>>() {})
                .put(REGION, new ConfigurationRecorder("legacy", "arn:aws:iam::000000000000:role/config",
                        new RecordingGroup(true, false, null)));
        AwsConfigService first = serviceWithStorage(storage);
        String arn = first.describeConfigurationRecorders(REGION, null).getFirst().arn();
        assertTrue(arn.contains(":configuration-recorder/legacy/"));
        assertEquals(arn, first.describeConfigurationRecorders(REGION, null).getFirst().arn());
        assertEquals(arn, serviceWithStorage(storage).describeConfigurationRecorders(REGION, null).getFirst().arn());
    }

    @Test
    void recordedHistoryAndProactiveEvaluationsSurviveRestart() throws Exception {
        SharedStorageFactory storage = new SharedStorageFactory();
        ObjectMapper mapper = new ObjectMapper();
        AwsConfigService first = serviceWithStorage(storage);
        ConfigResourceService resources = resourcesWithStorage(first, storage, mapper);
        first.putConfigurationRecorder(REGION, new ConfigurationRecorder("default",
                "arn:aws:iam::000000000000:role/config", new RecordingGroup(true, false, null),
                Map.of("recordingFrequency", "DAILY"), null));
        String arn = first.describeConfigurationRecorders(REGION, null).getFirst().arn();
        first.putDeliveryChannel(REGION, new DeliveryChannel("default", "bucket", null, null, null, null));
        first.startConfigurationRecorder(REGION, "default");
        ObjectNode put = mapper.createObjectNode().put("ResourceType", "Example::Config::Widget")
                .put("ResourceId", "widget").put("SchemaVersionId", "1").put("Configuration", "{\"v\":1}");
        resources.putResourceConfig(REGION, put);
        resources.putResourceConfig(REGION, put.deepCopy().put("Configuration", "{\"v\":2}"));
        first.putConfigRule(REGION, mapper.readValue("""
                {"ConfigRuleName":"versioning","Source":{"Owner":"AWS","SourceIdentifier":"S3_BUCKET_VERSIONING_ENABLED"},
                 "EvaluationModes":[{"Mode":"PROACTIVE"}]}
                """, ConfigRule.class));
        ObjectNode start = (ObjectNode) mapper.readTree("""
                {"EvaluationMode":"PROACTIVE","ClientToken":"persistent-evaluation",
                 "ResourceDetails":{"ResourceId":"bucket","ResourceType":"AWS::S3::Bucket","ResourceConfiguration":"{}"}}
                """);
        String evaluationId = resources.startResourceEvaluation(REGION, start).path("ResourceEvaluationId").asText();

        AwsConfigService reloaded = serviceWithStorage(storage);
        ConfigResourceService reloadedResources = resourcesWithStorage(reloaded, storage, mapper);
        assertEquals(arn, reloaded.describeConfigurationRecorders(REGION, null).getFirst().arn());
        assertEquals("DAILY", reloaded.describeConfigurationRecorders(REGION, null).getFirst()
                .recordingMode().get("recordingFrequency"));
        ObjectNode history = mapper.createObjectNode().put("resourceType", "Example::Config::Widget").put("resourceId", "widget");
        assertEquals(2, reloadedResources.getResourceConfigHistory(REGION, history).path("configurationItems").size());
        assertEquals(1, reloadedResources.getDiscoveredResourceCounts(REGION, mapper.createObjectNode())
                .path("totalDiscoveredResources").asInt());
        assertEquals("NON_COMPLIANT", reloadedResources.getResourceEvaluationSummary(REGION,
                mapper.createObjectNode().put("ResourceEvaluationId", evaluationId)).path("Compliance").asText());
        assertEquals(evaluationId, reloadedResources.startResourceEvaluation(REGION, start)
                .path("ResourceEvaluationId").asText());
        assertEquals(1, reloadedResources.listResourceEvaluations(REGION, mapper.createObjectNode())
                .path("ResourceEvaluations").size());
        AwsException stopped = assertThrows(AwsException.class, () -> reloadedResources.deleteResourceConfig(REGION, put));
        assertEquals("NoRunningConfigurationRecorderException", stopped.getErrorCode());
        reloaded.startConfigurationRecorder(REGION, "default");
        reloadedResources.deleteResourceConfig(REGION, put);

        AwsConfigService third = serviceWithStorage(storage);
        ConfigResourceService thirdResources = resourcesWithStorage(third, storage, mapper);
        assertEquals(0, thirdResources.getDiscoveredResourceCounts(REGION, mapper.createObjectNode())
                .path("totalDiscoveredResources").asInt());
        assertEquals("ResourceDeleted", thirdResources.getResourceConfigHistory(REGION, history)
                .path("configurationItems").path(0).path("configurationItemStatus").asText());
    }

    private static ConfigResourceService resourcesWithStorage(AwsConfigService config, StorageFactory storage,
            ObjectMapper mapper) {
        ConfigResourceService service = new ConfigResourceService(config,
                new RegionResolver(REGION, "000000000000"), storage, mapper);
        service.initializeStorage();
        return service;
    }

    private static ConfigRule rule(String name, String owner, String sourceIdentifier) {
        return new ConfigRule(name, null, null, null, null,
                new ConfigRuleSource(owner, sourceIdentifier, null, null), null, null, null, null, null);
    }

    private static ConfigEvaluation evaluation(String resourceType, String resourceId, String complianceType) {
        return new ConfigEvaluation(resourceType, resourceId, complianceType, null, 1700000000.0, null, null);
    }

    private static AwsConfigService serviceWithStorage(StorageFactory storage) {
        AwsConfigService service = new AwsConfigService(new RegionResolver(REGION, "000000000000"), storage);
        service.initializeStorage();
        return service;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
