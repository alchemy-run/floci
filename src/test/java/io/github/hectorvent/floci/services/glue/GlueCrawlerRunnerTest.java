package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.GlueCrawlerRunner.CrawledTable;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Crawler;
import io.github.hectorvent.floci.services.glue.model.CrawlerTargets;
import io.github.hectorvent.floci.services.glue.model.JdbcTarget;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.S3Target;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlueCrawlerRunnerTest {

    private static final String ACCOUNT = "000000000000";

    @Test
    void singleFolderBelowIncludePathBecomesItsOwnTable() {
        GlueCrawlerRunner runner = runner("bucket", "data/", Map.of(
                "data/events/part-0.csv", "id,amount\n1,10.5\n2,20.0\n"));

        List<CrawledTable> tables = runner.crawl(crawler("s3://bucket/data/"), ACCOUNT, () -> false);

        assertEquals(1, tables.size());
        CrawledTable events = tables.getFirst();
        assertEquals("events", events.table().getName());
        assertEquals("s3://bucket/data/events/", events.table().getStorageDescriptor().getLocation());
        assertEquals("csv", events.table().getParameters().get("classification"));
        assertEquals("crawler", events.table().getParameters().get("UPDATED_BY_CRAWLER"));
        assertEquals("1", events.table().getParameters().get("objectCount"));
        assertEquals(List.of("id", "amount"), names(events.table().getStorageDescriptor().getColumns()));
        assertTrue(events.table().getPartitionKeys().isEmpty());
        assertTrue(events.partitions().isEmpty());
    }

    @Test
    void compatibleHivePartitionFoldersBecomePartitionsOfOneTable() {
        GlueCrawlerRunner runner = runner("bucket", "sales/", Map.of(
                "sales/year=2024/a.csv", "id,total\n1,5\n",
                "sales/year=2025/b.csv", "id,total\n2,7.5\n",
                "sales/_SUCCESS", "done"));

        List<CrawledTable> tables = runner.crawl(crawler("s3://bucket/sales/"), ACCOUNT, () -> false);

        assertEquals(1, tables.size());
        CrawledTable sales = tables.getFirst();
        assertEquals("sales", sales.table().getName());
        assertEquals(List.of("year"), names(sales.table().getPartitionKeys()));
        assertEquals(List.of("bigint", "double"), sales.table().getStorageDescriptor().getColumns().stream()
                .map(Column::getType).toList());
        List<Partition> partitions = new ArrayList<>(sales.partitions());
        partitions.sort(Comparator.comparing(partition -> partition.getValues().getFirst()));
        assertEquals(List.of("2024"), partitions.get(0).getValues());
        assertEquals("s3://bucket/sales/year=2024/", partitions.get(0).getStorageDescriptor().getLocation());
        assertEquals(List.of("2025"), partitions.get(1).getValues());
    }

    @Test
    void incompatibleSiblingFoldersBecomeSeparateTables() {
        GlueCrawlerRunner runner = runner("bucket", "raw/", Map.of(
                "raw/users/u.csv", "id,name\n1,ann\n",
                "raw/orders/o.json", "{\"order_id\":1,\"sku\":\"x\"}\n"));

        List<CrawledTable> tables = runner.crawl(crawler("s3://bucket/raw/"), ACCOUNT, () -> false);

        Map<String, String> classifications = new LinkedHashMap<>();
        tables.forEach(table -> classifications.put(table.table().getName(),
                table.table().getParameters().get("classification")));
        assertEquals(Map.of("users", "csv", "orders", "json"), classifications);
    }

    @Test
    void nonS3TargetsAreRejectedExplicitly() {
        Crawler crawler = crawler("s3://bucket/data/");
        crawler.getTargets().setJdbcTargets(List.of(new JdbcTarget()));

        AwsException error = assertThrows(AwsException.class,
                () -> runner("bucket", "data/", Map.of()).crawl(crawler, ACCOUNT, () -> false));
        assertTrue(error.getMessage().contains("S3 targets only"));
    }

    private static GlueCrawlerRunner runner(String bucket, String prefix, Map<String, String> objects) {
        S3Service s3 = mock(S3Service.class);
        List<S3Object> listed = new ArrayList<>();
        for (Map.Entry<String, String> entry : objects.entrySet()) {
            byte[] data = entry.getValue().getBytes(StandardCharsets.UTF_8);
            S3Object object = new S3Object();
            object.setBucketName(bucket);
            object.setKey(entry.getKey());
            object.setSize(data.length);
            listed.add(object);
            when(s3.openObjectStream(eq(bucket), eq(entry.getKey()), isNull()))
                    .thenAnswer(invocation -> new ByteArrayInputStream(data));
        }
        when(s3.listObjects(eq(bucket), eq(prefix), isNull(), eq(Integer.MAX_VALUE))).thenReturn(listed);
        return new GlueCrawlerRunner(s3, null, null, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static Crawler crawler(String path) {
        S3Target target = new S3Target();
        target.setPath(path);
        CrawlerTargets targets = new CrawlerTargets();
        targets.setS3Targets(List.of(target));
        Crawler crawler = new Crawler();
        crawler.setName("crawler");
        crawler.setDatabaseName("db");
        crawler.setTargets(targets);
        return crawler;
    }

    private static List<String> names(List<Column> columns) {
        return columns.stream().map(Column::getName).toList();
    }
}
