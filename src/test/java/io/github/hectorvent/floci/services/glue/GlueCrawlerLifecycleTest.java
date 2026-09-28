package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.glue.model.Crawler;
import io.github.hectorvent.floci.services.glue.model.CrawlerTargets;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.S3Target;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlueCrawlerLifecycleTest {

    private final ManualExecutor executor = new ManualExecutor();
    private final GlueService service = service();

    @Test
    void startRunsCrawlInBackgroundAndRecordsSucceededLastCrawl() {
        service.createDatabase(new Database("analytics"));
        service.createCrawler(crawler("crawler-a", "analytics"));

        service.startCrawler("crawler-a");
        assertEquals("RUNNING", service.getCrawler("crawler-a").getState());
        assertThrows(AwsException.class, () -> service.startCrawler("crawler-a"));
        assertThrows(AwsException.class, () -> service.deleteCrawler("crawler-a", "us-east-1"));

        executor.runAll();

        Crawler crawler = service.getCrawler("crawler-a");
        assertEquals("READY", crawler.getState());
        assertEquals("SUCCEEDED", crawler.getLastCrawl().getStatus());
        assertNotNull(crawler.getLastCrawl().getStartTime());
        Table events = service.getTable("analytics", "events");
        assertEquals("csv", events.getParameters().get("classification"));
        assertEquals("s3://bucket/data/events/", events.getStorageDescriptor().getLocation());

        service.startCrawler("crawler-a");
        executor.runAll();
        assertEquals("1", service.getTable("analytics", "events").getVersionId());
    }

    @Test
    void stopMovesCrawlerToStoppingAndTheCrawlEndsCancelled() {
        service.createDatabase(new Database("analytics"));
        service.createCrawler(crawler("crawler-b", "analytics"));

        service.startCrawler("crawler-b");
        service.stopCrawler("crawler-b");
        assertEquals("STOPPING", service.getCrawler("crawler-b").getState());
        AwsException stopping = assertThrows(AwsException.class, () -> service.stopCrawler("crawler-b"));
        assertEquals("CrawlerStoppingException", stopping.getErrorCode());

        executor.runAll();

        Crawler crawler = service.getCrawler("crawler-b");
        assertEquals("READY", crawler.getState());
        assertEquals("CANCELLED", crawler.getLastCrawl().getStatus());
        assertThrows(AwsException.class, () -> service.getTable("analytics", "events"));
    }

    @Test
    void crawlIntoMissingDatabaseFailsWithErrorMessage() {
        service.createCrawler(crawler("crawler-c", "missing"));

        service.startCrawler("crawler-c");
        executor.runAll();

        Crawler crawler = service.getCrawler("crawler-c");
        assertEquals("READY", crawler.getState());
        assertEquals("FAILED", crawler.getLastCrawl().getStatus());
        assertEquals("Database not found: missing", crawler.getLastCrawl().getErrorMessage());
    }

    private GlueService service() {
        S3Service s3 = mock(S3Service.class);
        byte[] data = "id,amount\n1,10.5\n".getBytes(StandardCharsets.UTF_8);
        S3Object object = new S3Object();
        object.setBucketName("bucket");
        object.setKey("data/events/part-0.csv");
        object.setSize(data.length);
        when(s3.listObjects(eq("bucket"), eq("data/"), isNull(), eq(Integer.MAX_VALUE))).thenReturn(List.of(object));
        when(s3.openObjectStream(eq("bucket"), eq("data/events/part-0.csv"), isNull()))
                .thenAnswer(invocation -> new ByteArrayInputStream(data));
        GlueCrawlerRunner runner = new GlueCrawlerRunner(s3, null, null, executor);
        return new GlueService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                null, new RegionResolver("us-east-1", "000000000000"), new ResourceGroupsTaggingService(null), null,
                runner);
    }

    private static Crawler crawler(String name, String database) {
        S3Target target = new S3Target();
        target.setPath("s3://bucket/data/");
        CrawlerTargets targets = new CrawlerTargets();
        targets.setS3Targets(List.of(target));
        Crawler crawler = new Crawler();
        crawler.setName(name);
        crawler.setRole("arn:aws:iam::000000000000:role/GlueCrawler");
        crawler.setDatabaseName(database);
        crawler.setTargets(targets);
        return crawler;
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        void runAll() {
            List<Runnable> batch = new ArrayList<>(pending);
            pending.clear();
            batch.forEach(Runnable::run);
        }

        @Override
        public void shutdown() {
            pending.clear();
        }

        @Override
        public List<Runnable> shutdownNow() {
            List<Runnable> remaining = new ArrayList<>(pending);
            pending.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
