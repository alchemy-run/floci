package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KinesisJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final String STREAM_ARN = "arn:aws:kinesis:us-east-1:123456789012:stream/test-stream";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KinesisJsonHandler handler;

    @BeforeEach
    void setUp() {
        KinesisService service = new KinesisService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT)
        );
        handler = new KinesisJsonHandler(service, MAPPER);
    }

    private void createStream(String name) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", name);
        req.put("ShardCount", 1);
        assertThat(handler.handle("CreateStream", req, REGION).getStatus(), is(200));
    }

    private ObjectNode responseEntity(Response response) {
        return (ObjectNode) response.getEntity();
    }

    @Test
    void describeStreamByName() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("test-stream", desc.get("StreamName").asText());
    }

    @Test
    void describeStreamByArn() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", STREAM_ARN);
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("test-stream", desc.get("StreamName").asText());
    }

    @Test
    void arnFallbackWhenNameIsEmpty() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "");
        req.put("StreamARN", STREAM_ARN);
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("test-stream",
                responseEntity(resp).get("StreamDescription").get("StreamName").asText());
    }

    @Test
    void arnFallbackWhenNameIsWhitespace() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "   ");
        req.put("StreamARN", STREAM_ARN);
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("test-stream",
                responseEntity(resp).get("StreamDescription").get("StreamName").asText());
    }

    @Test
    void neitherFieldThrowsInvalidArgument() {
        ObjectNode req = MAPPER.createObjectNode();
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void whitespaceOnlyNameWithoutArnThrows() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "   ");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void malformedArnWithoutStreamSegmentThrows() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:table/not-a-stream");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void arnEndingInSlashThrows() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:stream/");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeStream", req, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void consumerArnExtractsStreamNameNotConsumerName() {
        createStream("my-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:stream/my-stream/consumer/my-consumer");
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("my-stream",
                responseEntity(resp).get("StreamDescription").get("StreamName").asText());
    }

    @Test
    void putRecordByArn() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamARN", STREAM_ARN);
        req.put("Data", "dGVzdA==");
        req.put("PartitionKey", "pk1");
        Response resp = handler.handle("PutRecord", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertThat(responseEntity(resp).has("SequenceNumber"), is(true));
    }

    @Test
    void enableEnhancedMonitoringReturnsMetrics() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.putArray("ShardLevelMetrics").add("IncomingBytes").add("OutgoingBytes");
        Response resp = handler.handle("EnableEnhancedMonitoring", req, REGION);
        assertThat(resp.getStatus(), is(200));

        ObjectNode body = responseEntity(resp);
        assertEquals("test-stream", body.get("StreamName").asText());
        assertEquals(0, body.get("CurrentShardLevelMetrics").size());
        assertEquals(2, body.get("DesiredShardLevelMetrics").size());
    }

    @Test
    void disableEnhancedMonitoringReturnsMetrics() {
        createStream("test-stream");

        ObjectNode enableReq = MAPPER.createObjectNode();
        enableReq.put("StreamName", "test-stream");
        enableReq.putArray("ShardLevelMetrics").add("IncomingBytes").add("OutgoingBytes");
        handler.handle("EnableEnhancedMonitoring", enableReq, REGION);

        ObjectNode disableReq = MAPPER.createObjectNode();
        disableReq.put("StreamName", "test-stream");
        disableReq.putArray("ShardLevelMetrics").add("IncomingBytes");
        Response resp = handler.handle("DisableEnhancedMonitoring", disableReq, REGION);
        assertThat(resp.getStatus(), is(200));

        ObjectNode body = responseEntity(resp);
        assertEquals(2, body.get("CurrentShardLevelMetrics").size());
        assertEquals(1, body.get("DesiredShardLevelMetrics").size());
    }

    @Test
    void describeStreamIncludesEnhancedMonitoring() {
        createStream("test-stream");

        ObjectNode enableReq = MAPPER.createObjectNode();
        enableReq.put("StreamName", "test-stream");
        enableReq.putArray("ShardLevelMetrics").add("IncomingBytes");
        handler.handle("EnableEnhancedMonitoring", enableReq, REGION);

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", descReq, REGION);
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals(1, desc.get("EnhancedMonitoring").size());
        assertEquals(1, desc.get("EnhancedMonitoring").get(0).get("ShardLevelMetrics").size());
        assertEquals("IncomingBytes", desc.get("EnhancedMonitoring").get(0).get("ShardLevelMetrics").get(0).asText());
    }

    @Test
    void describeStreamSummaryIncludesEnhancedMonitoring() {
        createStream("test-stream");

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStreamSummary", descReq, REGION);
        ObjectNode summary = (ObjectNode) responseEntity(resp).get("StreamDescriptionSummary");
        assertEquals(1, summary.get("EnhancedMonitoring").size());
        assertEquals(0, summary.get("EnhancedMonitoring").get(0).get("ShardLevelMetrics").size());
    }

    @Test
    void streamNameTakesPrecedenceOverArn() {
        createStream("by-name");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "by-name");
        req.put("StreamARN", "arn:aws:kinesis:us-east-1:123456789012:stream/nonexistent");
        Response resp = handler.handle("DescribeStream", req, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("by-name",
                responseEntity(resp).get("StreamDescription").get("StreamName").asText());
    }

    @Test
    void describeStreamReturnsDefaultStreamMode() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", req, REGION);
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("PROVISIONED", desc.get("StreamModeDetails").get("StreamMode").asText());
    }

    @Test
    void describeStreamSummaryReturnsDefaultStreamMode() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStreamSummary", req, REGION);
        ObjectNode summary = (ObjectNode) responseEntity(resp).get("StreamDescriptionSummary");
        assertEquals("PROVISIONED", summary.get("StreamModeDetails").get("StreamMode").asText());
    }

    @Test
    void createStreamHonorsOnDemandStreamMode() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("StreamName", "test-stream");
        req.put("ShardCount", 1);
        req.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        assertThat(handler.handle("CreateStream", req, REGION).getStatus(), is(200));

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", descReq, REGION);
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("ON_DEMAND", desc.get("StreamModeDetails").get("StreamMode").asText());
    }

    @Test
    void updateStreamModeSwitchesProvisionedToOnDemand() {
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        updateReq.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        assertThat(handler.handle("UpdateStreamMode", updateReq, REGION).getStatus(), is(200));

        ObjectNode descReq = MAPPER.createObjectNode();
        descReq.put("StreamName", "test-stream");
        Response resp = handler.handle("DescribeStream", descReq, REGION);
        ObjectNode desc = (ObjectNode) responseEntity(resp).get("StreamDescription");
        assertEquals("ON_DEMAND", desc.get("StreamModeDetails").get("StreamMode").asText());
    }

    @Test
    void updateStreamModeSameModeIsNoOp() {
        // Terraform refresh calls UpdateStreamMode unconditionally; same-mode must succeed.
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        updateReq.putObject("StreamModeDetails").put("StreamMode", "PROVISIONED");
        assertThat(handler.handle("UpdateStreamMode", updateReq, REGION).getStatus(), is(200));
    }

    @Test
    void updateStreamModeRejectsInvalidMode() {
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        updateReq.putObject("StreamModeDetails").put("StreamMode", "BOGUS");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateStreamMode", updateReq, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void updateStreamModeRequiresStreamArn() {
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamName", "test-stream");
        updateReq.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateStreamMode", updateReq, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void updateStreamModeRequiresStreamModeDetails() {
        createStream("test-stream");

        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateStreamMode", updateReq, REGION));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void updateStreamModeRejectsUnknownStream() {
        ObjectNode updateReq = MAPPER.createObjectNode();
        updateReq.put("StreamARN", STREAM_ARN);
        updateReq.putObject("StreamModeDetails").put("StreamMode", "ON_DEMAND");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("UpdateStreamMode", updateReq, REGION));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void listTagsForResourceUsesStreamArn() {
        createStream("test-stream");

        ObjectNode add = MAPPER.createObjectNode();
        add.put("StreamName", "test-stream");
        add.putObject("Tags").put("Environment", "test");
        assertThat(handler.handle("AddTagsToStream", add, REGION).getStatus(), is(200));

        ObjectNode list = MAPPER.createObjectNode();
        list.put("ResourceARN", STREAM_ARN);
        Response resp = handler.handle("ListTagsForResource", list, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("Environment", responseEntity(resp).get("Tags").get(0).get("Key").asText());
        assertEquals("test", responseEntity(resp).get("Tags").get(0).get("Value").asText());
    }

    @Test
    void tagResourceAndUntagResourceOnStreamArn() {
        createStream("test-stream");

        ObjectNode tag = MAPPER.createObjectNode();
        tag.put("ResourceARN", STREAM_ARN);
        tag.putArray("Tags").addObject().put("Key", "Owner").put("Value", "platform");
        assertThat(handler.handle("TagResource", tag, REGION).getStatus(), is(200));

        ObjectNode list = MAPPER.createObjectNode();
        list.put("ResourceARN", STREAM_ARN);
        assertEquals("Owner", responseEntity(handler.handle("ListTagsForResource", list, REGION))
                .get("Tags").get(0).get("Key").asText());

        ObjectNode untag = MAPPER.createObjectNode();
        untag.put("ResourceARN", STREAM_ARN);
        untag.putArray("TagKeys").add("Owner");
        assertThat(handler.handle("UntagResource", untag, REGION).getStatus(), is(200));
        assertEquals(0, responseEntity(handler.handle("ListTagsForResource", list, REGION)).get("Tags").size());
    }

    @Test
    void getResourcePolicyOnStreamWithoutPolicyIsNotFound() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("ResourceARN", STREAM_ARN);
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("GetResourcePolicy", req, REGION));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void putGetDeleteResourcePolicyOnStreamArn() {
        createStream("test-stream");

        ObjectNode put = MAPPER.createObjectNode();
        put.put("ResourceARN", STREAM_ARN);
        put.put("Policy", "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        assertThat(handler.handle("PutResourcePolicy", put, REGION).getStatus(), is(200));

        ObjectNode get = MAPPER.createObjectNode();
        get.put("ResourceARN", STREAM_ARN);
        Response got = handler.handle("GetResourcePolicy", get, REGION);
        assertThat(got.getStatus(), is(200));
        assertTrue(responseEntity(got).get("Policy").asText().contains("2012-10-17"));

        ObjectNode delete = MAPPER.createObjectNode();
        delete.put("ResourceARN", STREAM_ARN);
        assertThat(handler.handle("DeleteResourcePolicy", delete, REGION).getStatus(), is(200));
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("GetResourcePolicy", get, REGION));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void listStreamsIncludesStreamSummaries() {
        createStream("test-stream");

        ObjectNode req = MAPPER.createObjectNode();
        Response resp = handler.handle("ListStreams", req, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode body = responseEntity(resp);
        assertEquals("test-stream", body.get("StreamNames").get(0).asText());
        assertEquals("test-stream", body.get("StreamSummaries").get(0).get("StreamName").asText());
        assertTrue(body.get("StreamSummaries").get(0).get("StreamARN").asText().contains("test-stream"));
        assertEquals("ACTIVE", body.get("StreamSummaries").get(0).get("StreamStatus").asText());
    }

    @Test
    void updateShardCountScalesProvisionedStream() {
        createStream("test-stream");

        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamName", "test-stream");
        update.put("TargetShardCount", 2);
        update.put("ScalingType", "UNIFORM_SCALING");
        Response resp = handler.handle("UpdateShardCount", update, REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode body = responseEntity(resp);
        assertEquals(1, body.get("CurrentShardCount").asInt());
        assertEquals(2, body.get("TargetShardCount").asInt());

        ObjectNode desc = MAPPER.createObjectNode();
        desc.put("StreamName", "test-stream");
        ObjectNode summary = (ObjectNode) responseEntity(
                handler.handle("DescribeStreamSummary", desc, REGION)).get("StreamDescriptionSummary");
        assertEquals(2, summary.get("OpenShardCount").asInt());
    }

    @Test
    void updateMaxRecordSizeRoundTripsOnDescribe() {
        createStream("test-stream");

        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamARN", STREAM_ARN);
        update.put("MaxRecordSizeInKiB", 2048);
        assertThat(handler.handle("UpdateMaxRecordSize", update, REGION).getStatus(), is(200));

        ObjectNode desc = MAPPER.createObjectNode();
        desc.put("StreamName", "test-stream");
        ObjectNode summary = (ObjectNode) responseEntity(
                handler.handle("DescribeStreamSummary", desc, REGION)).get("StreamDescriptionSummary");
        assertEquals(2048, summary.get("MaxRecordSizeInKiB").asInt());
    }

    @Test
    void updateStreamWarmThroughputRoundTripsOnDescribe() {
        createStream("test-stream");

        ObjectNode update = MAPPER.createObjectNode();
        update.put("StreamARN", STREAM_ARN);
        update.put("WarmThroughputMiBps", 10);
        Response resp = handler.handle("UpdateStreamWarmThroughput", update, REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals(10, responseEntity(resp).get("WarmThroughput").get("TargetMiBps").asInt());

        ObjectNode desc = MAPPER.createObjectNode();
        desc.put("StreamName", "test-stream");
        ObjectNode summary = (ObjectNode) responseEntity(
                handler.handle("DescribeStreamSummary", desc, REGION)).get("StreamDescriptionSummary");
        assertEquals(10, summary.get("WarmThroughput").get("TargetMiBps").asInt());
        assertEquals(10, summary.get("WarmThroughput").get("CurrentMiBps").asInt());
    }

    @Test
    void describeAccountSettingsReturnsEnabledCommitment() {
        Response resp = handler.handle("DescribeAccountSettings", MAPPER.createObjectNode(), REGION);
        assertThat(resp.getStatus(), is(200));
        assertEquals("ENABLED", responseEntity(resp)
                .get("MinimumThroughputBillingCommitment").get("Status").asText());
    }

    @Test
    void describeLimitsReturnsPositiveShardLimit() {
        createStream("test-stream");
        Response resp = handler.handle("DescribeLimits", MAPPER.createObjectNode(), REGION);
        assertThat(resp.getStatus(), is(200));
        ObjectNode body = responseEntity(resp);
        assertTrue(body.get("ShardLimit").asInt() > 0);
        assertEquals(1, body.get("OpenShardCount").asInt());
    }

    @Test
    void registerAndTagConsumerKeepsTagsOffTheStream() {
        createStream("test-stream");

        ObjectNode register = MAPPER.createObjectNode();
        register.put("StreamARN", STREAM_ARN);
        register.put("ConsumerName", "analytics");
        register.putObject("Tags").put("fixture", "consumer-test");
        Response created = handler.handle("RegisterStreamConsumer", register, REGION);
        String consumerArn = responseEntity(created).get("Consumer").get("ConsumerARN").asText();

        ObjectNode listConsumer = MAPPER.createObjectNode();
        listConsumer.put("ResourceARN", consumerArn);
        Response consumerTags = handler.handle("ListTagsForResource", listConsumer, REGION);
        assertEquals("fixture", responseEntity(consumerTags).get("Tags").get(0).get("Key").asText());
        assertEquals("consumer-test", responseEntity(consumerTags).get("Tags").get(0).get("Value").asText());

        ObjectNode listStream = MAPPER.createObjectNode();
        listStream.put("ResourceARN", STREAM_ARN);
        assertEquals(0, responseEntity(handler.handle("ListTagsForResource", listStream, REGION))
                .get("Tags").size());
    }

    @Test
    void splitThenMergeRestoresSingleOpenShardWithLineage() {
        createStream("test-stream");

        ObjectNode desc = MAPPER.createObjectNode();
        desc.put("StreamName", "test-stream");
        ObjectNode shards = (ObjectNode) responseEntity(handler.handle("ListShards", desc, REGION));
        String parentId = shards.get("Shards").get(0).get("ShardId").asText();

        ObjectNode split = MAPPER.createObjectNode();
        split.put("StreamName", "test-stream");
        split.put("ShardToSplit", parentId);
        split.put("NewStartingHashKey", "170141183460469231731687303715884105728");
        assertThat(handler.handle("SplitShard", split, REGION).getStatus(), is(200));

        ObjectNode afterSplit = (ObjectNode) responseEntity(handler.handle("ListShards", desc, REGION));
        java.util.List<com.fasterxml.jackson.databind.JsonNode> open = new java.util.ArrayList<>();
        afterSplit.get("Shards").forEach(shard -> {
            if (!shard.path("SequenceNumberRange").has("EndingSequenceNumber")) {
                open.add(shard);
            }
        });
        assertEquals(2, open.size());
        assertTrue(!open.get(0).get("ShardId").asText().equals(open.get(1).get("ShardId").asText()));
        open.sort((a, b) -> new java.math.BigInteger(a.get("HashKeyRange").get("StartingHashKey").asText())
                .compareTo(new java.math.BigInteger(b.get("HashKeyRange").get("StartingHashKey").asText())));

        ObjectNode merge = MAPPER.createObjectNode();
        merge.put("StreamName", "test-stream");
        merge.put("ShardToMerge", open.get(0).get("ShardId").asText());
        merge.put("AdjacentShardToMerge", open.get(1).get("ShardId").asText());
        assertThat(handler.handle("MergeShards", merge, REGION).getStatus(), is(200));

        ObjectNode afterMerge = (ObjectNode) responseEntity(handler.handle("ListShards", desc, REGION));
        java.util.List<com.fasterxml.jackson.databind.JsonNode> merged = new java.util.ArrayList<>();
        afterMerge.get("Shards").forEach(shard -> {
            if (!shard.path("SequenceNumberRange").has("EndingSequenceNumber")) {
                merged.add(shard);
            }
        });
        assertEquals(1, merged.size());
        assertEquals(open.get(0).get("ShardId").asText(), merged.get(0).get("ParentShardId").asText());
    }
}
