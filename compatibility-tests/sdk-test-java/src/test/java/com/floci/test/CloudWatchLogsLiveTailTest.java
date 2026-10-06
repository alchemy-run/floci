package com.floci.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.eventstream.Message;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import java.io.DataInputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CloudWatchLogsLiveTailTest {
    private CloudWatchLogsClient logs;
    private CloudWatchLogsAsyncClient async;
    private final String group = TestFixtures.uniqueName("live-tail");
    private final String other = group + "-other";
    private final List<Tail> tails = new ArrayList<>();
    private String arn;
    private String otherArn;

    @BeforeEach
    void setup() {
        logs = TestFixtures.cloudWatchLogsClient();
        async = TestFixtures.cloudWatchLogsAsyncClient();
        for (String name : List.of(group, other)) {
            logs.createLogGroup(r -> r.logGroupName(name));
            for (String stream : List.of("prod/app", "prod/api", "dev/app")) {
                logs.createLogStream(r -> r.logGroupName(name).logStreamName(stream));
            }
        }
        var groups = logs.describeLogGroups(r -> r.logGroupNamePrefix(group)).logGroups();
        arn = groups.stream().filter(g -> g.logGroupName().equals(group)).findFirst().orElseThrow().logGroupArn();
        otherArn = groups.stream().filter(g -> g.logGroupName().equals(other)).findFirst().orElseThrow().logGroupArn();
    }

    @AfterEach
    void cleanup() {
        tails.forEach(Tail::close);
        if (async != null) {
            async.close();
        }
        if (logs != null) {
            logs.deleteLogGroup(r -> r.logGroupName(group));
            logs.deleteLogGroup(r -> r.logGroupName(other));
            logs.close();
        }
    }

    @Test
    void streamsNewIngestionAndIdleHeartbeatsWithoutReplayingHistory() throws Exception {
        put(group, "prod/app", "old event");
        Tail tail = start(r -> r.logGroupIdentifiers(arn));
        var start = tail.started();
        assertThat(start.logGroupIdentifiers()).containsExactly(arn);
        assertThat(start.requestId()).isNotBlank();
        assertThat(start.sessionId()).isNotBlank();
        var idle = tail.update();
        assertThat(idle.sessionResults()).isEmpty();
        assertThat(idle.sessionMetadata().sampled()).isFalse();

        long timestamp = System.currentTimeMillis() - 60_000;
        logs.putLogEvents(r -> r.logGroupName(group).logStreamName("prod/app").logEvents(
                InputLogEvent.builder().message("new ingestion, older timestamp ☃").timestamp(timestamp).build(),
                InputLogEvent.builder().message("rejected future event")
                        .timestamp(System.currentTimeMillis() + Duration.ofHours(3).toMillis()).build()));
        var update = tail.nonempty();
        assertThat(update.sessionResults()).singleElement().satisfies(event -> {
            assertThat(event.message()).isEqualTo("new ingestion, older timestamp ☃");
            assertThat(event.timestamp()).isEqualTo(timestamp);
            assertThat(event.ingestionTime()).isGreaterThan(timestamp);
            assertThat(event.logGroupIdentifier()).isEqualTo(arn);
            assertThat(event.logStreamName()).isEqualTo("prod/app");
        });
        assertThat(tail.update().sessionResults()).isEmpty();
    }

    @Test
    void sendsInitialResponseBeforeSessionStart() throws Exception {
        HttpURLConnection connection = openWireStream();
        try (var input = new DataInputStream(connection.getInputStream())) {
            Message initial = readFrame(input);
            assertThat(initial.getHeaders().get(":message-type").getString()).isEqualTo("event");
            assertThat(initial.getHeaders().get(":event-type").getString()).isEqualTo("initial-response");
            assertThat(initial.getPayload()).isEqualTo(new byte[]{'{', '}'});
            Message start = readFrame(input);
            assertThat(start.getHeaders().get(":event-type").getString()).isEqualTo("sessionStart");
            assertThat(new ObjectMapper().readTree(start.getPayload()).get("sessionId").asText()).isNotBlank();
        } finally {
            connection.disconnect();
        }
    }

    @Test
    @Timeout(90)
    void slowReaderDropsOldUpdatesAndStillReceivesRecentEvents() throws Exception {
        HttpURLConnection connection = openWireStream();
        try (var input = new DataInputStream(connection.getInputStream())) {
            assertThat(readFrame(input).getHeaders().get(":event-type").getString()).isEqualTo("initial-response");
            assertThat(readFrame(input).getHeaders().get(":event-type").getString()).isEqualTo("sessionStart");
            Tail observer = start(r -> r.logGroupIdentifiers(arn));
            observer.started();

            // Stop reading this connection. Large frames fill TCP buffers; the SDK
            // observer confirms ingestion keeps moving while this writer is blocked.
            String padding = "x".repeat(900_000);
            for (int i = 0; i < 30; i++) {
                String message = i + ":" + padding;
                put(group, "prod/app", message);
                assertThat(observer.nonempty().sessionResults()).singleElement()
                        .satisfies(event -> assertThat(event.message()).isEqualTo(message));
            }
            assertThat(observer.update().sessionResults()).isEmpty();

            List<Integer> received = new ArrayList<>();
            int nonemptyUpdatesAfterGap = 0;
            boolean dropped = false;
            ObjectMapper mapper = new ObjectMapper();
            while (!received.contains(29)) {
                Message frame = readFrame(input);
                assertThat(frame.getHeaders().get(":event-type").getString()).isEqualTo("sessionUpdate");
                var results = mapper.readTree(frame.getPayload()).get("sessionResults");
                for (var event : results) {
                    String message = event.get("message").asText();
                    int index = Integer.parseInt(message.substring(0, message.indexOf(':')));
                    if (!received.isEmpty() && index > received.get(received.size() - 1) + 1) {
                        dropped = true;
                    }
                    received.add(index);
                }
                if (dropped && !results.isEmpty()) {
                    nonemptyUpdatesAfterGap++;
                }
            }
            assertThat(dropped).as("old updates discarded under real transport backpressure").isTrue();
            assertThat(nonemptyUpdatesAfterGap).as("bounded recent-update backlog").isBetween(1, 10);
            assertThat(received).contains(27, 28, 29).doesNotHaveDuplicates();
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void filtersPrefixesNamesAndMessagesWithoutLeakingOtherGroups() throws Exception {
        Tail prefix = start(r -> r.logGroupIdentifiers(arn).logStreamNamePrefixes("prod/")
                .logEventFilterPattern("\"ERROR\""));
        Tail name = start(r -> r.logGroupIdentifiers(arn).logStreamNames("prod/api"));
        assertThat(prefix.started().logStreamNamePrefixes()).containsExactly("prod/");
        assertThat(name.started().logStreamNames()).containsExactly("prod/api");
        put(group, "prod/app", "ERROR app");
        put(group, "prod/api", "INFO api");
        put(group, "dev/app", "ERROR dev");
        put(other, "prod/app", "ERROR other group");
        assertThat(prefix.nonempty().sessionResults()).extracting(LiveTailSessionLogEvent::message)
                .containsExactly("ERROR app");
        assertThat(name.nonempty().sessionResults()).extracting(LiveTailSessionLogEvent::message)
                .containsExactly("INFO api");
        assertThat(prefix.update().sessionResults()).isEmpty();
        assertThat(name.update().sessionResults()).isEmpty();
    }

    @Test
    void combinesMultipleGroups() throws Exception {
        Tail tail = start(r -> r.logGroupIdentifiers(arn, otherArn));
        assertThat(tail.started().logGroupIdentifiers()).containsExactlyInAnyOrder(arn, otherArn);
        put(group, "prod/app", "first group");
        put(other, "dev/app", "second group");
        List<LiveTailSessionLogEvent> results = new ArrayList<>(tail.nonempty().sessionResults());
        if (results.size() < 2) {
            results.addAll(tail.nonempty().sessionResults());
        }
        assertThat(results).extracting(LiveTailSessionLogEvent::message)
                .containsExactlyInAnyOrder("first group", "second group");
        assertThat(results).extracting(LiveTailSessionLogEvent::logGroupIdentifier)
                .containsExactlyInAnyOrder(arn, otherArn);
    }

    @Test
    void doesNotMixRegionsOrPublishingAccounts() throws Exception {
        Tail tail = start(r -> r.logGroupIdentifiers(arn));
        tail.started();
        try (var otherAccount = CloudWatchLogsClient.builder().endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("111111111111", "test"))).build();
             var otherRegion = CloudWatchLogsClient.builder().endpointOverride(TestFixtures.endpoint())
                .region(Region.US_WEST_2).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test"))).build()) {
            otherAccount.createLogGroup(r -> r.logGroupName(group));
            otherRegion.createLogGroup(r -> r.logGroupName(group));
            try {
                otherAccount.createLogStream(r -> r.logGroupName(group).logStreamName("prod/app"));
                otherRegion.createLogStream(r -> r.logGroupName(group).logStreamName("prod/app"));
                for (var client : List.of(otherAccount, otherRegion)) {
                    client.putLogEvents(r -> r.logGroupName(group).logStreamName("prod/app").logEvents(
                            InputLogEvent.builder().timestamp(System.currentTimeMillis()).message("not this session").build()));
                }
                put(group, "prod/app", "same account and region");
                assertThat(tail.nonempty().sessionResults()).extracting(LiveTailSessionLogEvent::message)
                        .containsExactly("same account and region");
                assertThat(tail.update().sessionResults()).isEmpty();
            } finally {
                otherAccount.deleteLogGroup(r -> r.logGroupName(group));
                otherRegion.deleteLogGroup(r -> r.logGroupName(group));
            }
        }
    }

    @Test
    void streamsRealLambdaContainerLogs() throws Exception {
        String function = TestFixtures.uniqueName("live-tail-lambda");
        String lambdaGroup = "/aws/lambda/" + function;
        try (var lambda = TestFixtures.lambdaClient()) {
            lambda.createFunction(r -> r.functionName(function)
                    .runtime(software.amazon.awssdk.services.lambda.model.Runtime.NODEJS20_X)
                    .role("arn:aws:iam::000000000000:role/lambda-role").handler("index.handler").timeout(30)
                    .code(c -> c.zipFile(software.amazon.awssdk.core.SdkBytes.fromByteArray(LambdaUtils.minimalZip()))));
            try {
                logs.createLogGroup(r -> r.logGroupName(lambdaGroup));
                String lambdaArn = logs.describeLogGroups(r -> r.logGroupNamePrefix(lambdaGroup))
                        .logGroups().get(0).logGroupArn();
                Tail tail = start(r -> r.logGroupIdentifiers(lambdaArn));
                tail.started();
                var response = lambda.invoke(r -> r.functionName(function)
                        .payload(software.amazon.awssdk.core.SdkBytes.fromUtf8String("{\"marker\":\"live-tail-container\"}")));
                assertThat(response.functionError()).isNull();
                List<String> messages = new ArrayList<>();
                for (int i = 0; i < 15 && messages.stream().noneMatch(m -> m.contains("live-tail-container")); i++) {
                    tail.update().sessionResults().forEach(event -> messages.add(event.message()));
                }
                assertThat(messages).anyMatch(m -> m.contains("live-tail-container"));
            } finally {
                lambda.deleteFunction(r -> r.functionName(function));
                logs.deleteLogGroup(r -> r.logGroupName(lambdaGroup));
            }
        }
    }

    @Test
    void samplesBatchesAboveFiveHundredAndResetsSampledOnIdleUpdates() throws Exception {
        Tail tail = start(r -> r.logGroupIdentifiers(arn));
        tail.started();
        long timestamp = System.currentTimeMillis();
        var events = IntStream.range(0, 600).mapToObj(i -> InputLogEvent.builder()
                .message("event-" + i).timestamp(timestamp + i).build()).toList();
        logs.putLogEvents(r -> r.logGroupName(group).logStreamName("prod/app").logEvents(events.subList(0, 500)));
        var atLimit = tail.nonempty();
        assertThat(atLimit.sessionResults()).hasSize(500);
        assertThat(atLimit.sessionMetadata().sampled()).isFalse();
        logs.putLogEvents(r -> r.logGroupName(group).logStreamName("prod/app").logEvents(events));
        var update = tail.nonempty();
        assertThat(update.sessionResults()).hasSize(500);
        assertThat(update.sessionResults()).extracting(LiveTailSessionLogEvent::message).doesNotHaveDuplicates();
        assertThat(update.sessionMetadata().sampled()).isTrue();
        assertThat(tail.update().sessionMetadata().sampled()).isFalse();
    }

    @Test
    void rejectsInvalidSelectorsAndMissingGroupsThroughTheSdk() throws Exception {
        error(r -> r.logGroupIdentifiers(arn + ":*"), InvalidParameterException.class);
        error(r -> r.logGroupIdentifiers(group), InvalidParameterException.class);
        error(r -> r.logGroupIdentifiers(List.of()), InvalidParameterException.class);
        error(r -> r.logGroupIdentifiers(java.util.Collections.nCopies(11, arn)), InvalidParameterException.class);
        error(r -> r.logGroupIdentifiers(arn).logStreamNames(java.util.Collections.nCopies(101, "prod/app")),
                InvalidParameterException.class);
        error(r -> r.logGroupIdentifiers(arn).logStreamNames("prod/app").logStreamNamePrefixes("prod/"),
                InvalidParameterException.class);
        error(r -> r.logGroupIdentifiers(arn, otherArn).logStreamNames("prod/app"), InvalidParameterException.class);
        error(r -> r.logGroupIdentifiers(arn + "-missing"), ResourceNotFoundException.class);
    }

    @Test
    void cancellationReleasesTheSessionQuota() throws Exception {
        String account = String.format("%012d", java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
        String quotaArn = "arn:aws:logs:us-east-1:" + account + ":log-group:" + group;
        Consumer<StartLiveTailRequest.Builder> request = r -> r.logGroupIdentifiers(quotaArn)
                .overrideConfiguration(o -> o.credentialsProvider(credentials));
        logs.createLogGroup(r -> r.logGroupName(group).overrideConfiguration(o -> o.credentialsProvider(credentials)));
        try {
            logs.createLogStream(r -> r.logGroupName(group).logStreamName("prod/app")
                    .overrideConfiguration(o -> o.credentialsProvider(credentials)));
            for (int i = 0; i < 15; i++) {
                start(request).started();
            }
            error(request, LimitExceededException.class);
            tails.get(0).close();
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (true) {
                Tail replacement = start(request);
                Object event = replacement.next();
                if (event instanceof LiveTailSessionStart) {
                    logs.putLogEvents(r -> r.logGroupName(group).logStreamName("prod/app")
                            .overrideConfiguration(o -> o.credentialsProvider(credentials))
                            .logEvents(InputLogEvent.builder().timestamp(System.currentTimeMillis())
                                    .message("after cancellation").build()));
                    assertThat(replacement.nonempty().sessionResults()).extracting(LiveTailSessionLogEvent::message)
                            .containsExactly("after cancellation");
                    break;
                }
                assertThat(event).isInstanceOf(LimitExceededException.class);
                assertThat(System.nanoTime()).as("session quota released after disconnect").isLessThan(deadline);
                replacement.close();
                Thread.sleep(100);
            }
        } finally {
            tails.forEach(Tail::close);
            logs.deleteLogGroup(r -> r.logGroupName(group).overrideConfiguration(o -> o.credentialsProvider(credentials)));
        }
    }

    private HttpURLConnection openWireStream() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) TestFixtures.endpoint().toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Content-Type", "application/x-amz-json-1.1");
        connection.setRequestProperty("X-Amz-Target", "Logs_20140328.StartLiveTail");
        connection.setDoOutput(true);
        try (var output = connection.getOutputStream()) {
            output.write(new ObjectMapper().writeValueAsBytes(Map.of("logGroupIdentifiers", List.of(arn))));
        }
        assertThat(connection.getResponseCode()).isEqualTo(200);
        assertThat(connection.getContentType()).isEqualTo("application/vnd.amazon.eventstream");
        return connection;
    }

    private static Message readFrame(DataInputStream input) throws Exception {
        int length = input.readInt();
        assertThat(length).isBetween(16, 2_000_000);
        byte[] frame = new byte[length];
        ByteBuffer.wrap(frame).putInt(length);
        input.readFully(frame, 4, length - 4);
        return Message.decode(ByteBuffer.wrap(frame));
    }

    private void put(String groupName, String stream, String message) {
        logs.putLogEvents(r -> r.logGroupName(groupName).logStreamName(stream).logEvents(
                InputLogEvent.builder().timestamp(System.currentTimeMillis()).message(message).build()));
    }

    private Tail start(Consumer<StartLiveTailRequest.Builder> request) {
        Tail tail = new Tail();
        tails.add(tail);
        tail.future = async.startLiveTail(request, StartLiveTailResponseHandler.builder()
                .onError(tail.received::offer).subscriber(() -> tail).build());
        return tail;
    }

    private void error(Consumer<StartLiveTailRequest.Builder> request, Class<? extends Throwable> type) throws Exception {
        assertThat(start(request).next()).isInstanceOf(type);
    }

    private static final class Tail implements Subscriber<StartLiveTailResponseStream>, AutoCloseable {
        private final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        private volatile Subscription subscription;
        private CompletableFuture<Void> future;

        @Override
        public void onSubscribe(Subscription value) {
            subscription = value;
            value.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(StartLiveTailResponseStream event) {
            received.offer(event);
        }

        @Override
        public void onError(Throwable error) {
            received.offer(error);
        }

        @Override
        public void onComplete() {
            received.offer("unexpected end of live tail stream");
        }

        Object next() throws InterruptedException {
            Object event = received.poll(10, TimeUnit.SECONDS);
            assertThat(event).as("live tail event or modeled error").isNotNull();
            while (event instanceof java.util.concurrent.CompletionException error && error.getCause() != null) {
                event = error.getCause();
            }
            return event;
        }

        LiveTailSessionStart started() throws InterruptedException {
            Object event = next();
            assertThat(event).isInstanceOf(LiveTailSessionStart.class);
            return (LiveTailSessionStart) event;
        }

        LiveTailSessionUpdate update() throws InterruptedException {
            Object event = next();
            assertThat(event).isInstanceOf(LiveTailSessionUpdate.class);
            return (LiveTailSessionUpdate) event;
        }

        LiveTailSessionUpdate nonempty() throws InterruptedException {
            for (int i = 0; i < 5; i++) {
                var update = update();
                if (!update.sessionResults().isEmpty()) {
                    return update;
                }
            }
            throw new AssertionError("No log events received in five updates");
        }

        @Override
        public void close() {
            if (subscription != null) {
                subscription.cancel();
            }
            if (future != null) {
                future.cancel(true);
            }
        }
    }
}
