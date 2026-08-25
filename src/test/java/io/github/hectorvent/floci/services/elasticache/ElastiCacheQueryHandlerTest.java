package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the empty-list read responses for the subnet/parameter group describes, so
 * SDK clients get a valid 200 instead of failing with UnsupportedOperation (400).
 */
class ElastiCacheQueryHandlerTest {

    private ElastiCacheQueryHandler handler;
    private ElastiCacheService service;

    @BeforeEach
    void setUp() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        service = mock(ElastiCacheService.class);
        ElastiCacheMemcachedService memcachedService = mock(ElastiCacheMemcachedService.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        handler = new ElastiCacheQueryHandler(sigV4Validator, service, memcachedService, regionResolver);
    }

    @Test
    void describeCacheSubnetGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheSubnetGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheSubnetGroupsResult><CacheSubnetGroups></CacheSubnetGroups></DescribeCacheSubnetGroupsResult>"),
                "Expected empty CacheSubnetGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void describeCacheParameterGroups_returnsEmptyWrapperWithoutMarker() {
        Response response = handler.handle("DescribeCacheParameterGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeCacheParameterGroupsResult><CacheParameterGroups></CacheParameterGroups></DescribeCacheParameterGroupsResult>"),
                "Expected empty CacheParameterGroups wrapper inside the Result element");
        assertFalse(body.contains("<Marker>"), "Empty list must omit Marker");
    }

    @Test
    void unsupportedOperationStillReturnsQueryError() {
        Response response = handler.handle("NoSuchAction", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("UnsupportedOperation"));
    }

    @Test
    void describeServerlessCaches_returnsEmptyWrapper() {
        when(service.listServerlessCaches(null)).thenReturn(List.of());

        Response response = handler.handle("DescribeServerlessCaches", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeServerlessCachesResult><ServerlessCaches></ServerlessCaches></DescribeServerlessCachesResult>"),
                body);
        assertFalse(body.contains("<Marker>"));
    }

    @Test
    void describeServerlessCaches_namedMissing_returnsNotFoundFault() {
        when(service.listServerlessCaches("alchemy-nonexistent-cache-probe"))
                .thenThrow(new AwsException("ServerlessCacheNotFoundFault",
                        "Serverless cache alchemy-nonexistent-cache-probe not found.", 404));

        MultivaluedMap<String, String> params = params();
        params.putSingle("ServerlessCacheName", "alchemy-nonexistent-cache-probe");
        Response response = handler.handle("DescribeServerlessCaches", params);

        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ServerlessCacheNotFoundFault"));
    }

    @Test
    void describeServerlessCacheSnapshots_returnsEmptyWrapper() {
        when(service.listServerlessCacheSnapshots(null, null, null)).thenReturn(List.of());

        Response response = handler.handle("DescribeServerlessCacheSnapshots", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeServerlessCacheSnapshotsResult><ServerlessCacheSnapshots></ServerlessCacheSnapshots></DescribeServerlessCacheSnapshotsResult>"),
                body);
    }

    @Test
    void describeEvents_returnsEmptyWrapper() {
        when(service.listEvents(null, null)).thenReturn(List.of());
        when(service.listEvents(any(), any())).thenReturn(List.of());

        Response response = handler.handle("DescribeEvents", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeEventsResult><Events></Events></DescribeEventsResult>"), body);
    }

    @Test
    void deleteServerlessCacheSnapshot_missing_returnsNotFoundFault() {
        when(service.deleteServerlessCacheSnapshot("alchemy-elasticache-nonexistent-probe"))
                .thenThrow(new AwsException("ServerlessCacheSnapshotNotFoundFault",
                        "Serverless cache snapshot alchemy-elasticache-nonexistent-probe not found.", 404));

        MultivaluedMap<String, String> params = params();
        params.putSingle("ServerlessCacheSnapshotName", "alchemy-elasticache-nonexistent-probe");
        Response response = handler.handle("DeleteServerlessCacheSnapshot", params);

        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ServerlessCacheSnapshotNotFoundFault"));
    }

    @Test
    void copyServerlessCacheSnapshot_missingSource_returnsNotFoundFault() {
        when(service.copyServerlessCacheSnapshot(anyString(), anyString(), any(), any()))
                .thenThrow(new AwsException("ServerlessCacheSnapshotNotFoundFault",
                        "Serverless cache snapshot missing not found.", 404));

        MultivaluedMap<String, String> params = params();
        params.putSingle("SourceServerlessCacheSnapshotName", "missing");
        params.putSingle("TargetServerlessCacheSnapshotName", "missing-copy");
        Response response = handler.handle("CopyServerlessCacheSnapshot", params);

        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ServerlessCacheSnapshotNotFoundFault"));
    }

    @Test
    void exportServerlessCacheSnapshot_missing_returnsNotFoundFault() {
        when(service.exportServerlessCacheSnapshot(anyString(), anyString()))
                .thenThrow(new AwsException("ServerlessCacheSnapshotNotFoundFault",
                        "Serverless cache snapshot missing not found.", 404));

        MultivaluedMap<String, String> params = params();
        params.putSingle("ServerlessCacheSnapshotName", "missing");
        params.putSingle("S3BucketName", "alchemy-elasticache-export-probe");
        Response response = handler.handle("ExportServerlessCacheSnapshot", params);

        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("ServerlessCacheSnapshotNotFoundFault"));
    }

    private static MultivaluedMap<String, String> params() {
        return new MultivaluedHashMap<>();
    }
}
