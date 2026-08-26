package io.github.hectorvent.floci.services.resourceexplorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.resourceexplorer.model.ExplorerIndex;
import io.github.hectorvent.floci.services.resourceexplorer.model.ExplorerView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceExplorerServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private ResourceExplorerService service;

    @BeforeEach
    void setUp() {
        service = new ResourceExplorerService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void getIndexWhenMissingThrowsResourceNotFound() {
        AwsException error = assertThrows(AwsException.class, () -> service.getIndex(REGION));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void createViewWithoutIndexThrowsUnauthorized() {
        ObjectNode request = mapper.createObjectNode().put("ViewName", "no-index");
        AwsException error = assertThrows(AwsException.class, () -> service.createView(REGION, request));
        assertEquals("UnauthorizedException", error.getErrorCode());
        assertEquals(401, error.getHttpStatus());
    }

    @Test
    void indexViewSearchListResourcesTagAndDeleteLifecycle() {
        ObjectNode createIndex = mapper.createObjectNode();
        createIndex.putObject("Tags").put("purpose", "alchemy-re2-test");
        ExplorerIndex created = service.createIndex(REGION, createIndex);
        assertNotNull(created.getArn());
        assertTrue(created.getArn().contains(":index/"));
        assertEquals("LOCAL", created.getType());
        assertEquals("ACTIVE", created.getState());

        ExplorerIndex live = service.getIndex(REGION);
        assertEquals(created.getArn(), live.getArn());
        assertEquals("alchemy-re2-test", live.getTags().get("purpose"));

        service.tagResource(REGION, created.getArn(), Map.of("team", "platform"));
        service.untagResource(REGION, created.getArn(), List.of("purpose"));
        ExplorerIndex tagged = service.getIndex(REGION);
        assertEquals("platform", tagged.getTags().get("team"));
        assertNull(tagged.getTags().get("purpose"));

        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createIndex(REGION, mapper.createObjectNode()));
        assertEquals("ConflictException", conflict.getErrorCode());
        assertEquals(409, conflict.getHttpStatus());

        ObjectNode createView = mapper.createObjectNode();
        createView.put("ViewName", "alchemy-re2-renamed-view");
        createView.putObject("Filters").put("FilterString", "service:s3");
        createView.putArray("IncludedProperties").addObject().put("Name", "tags");
        createView.putObject("Tags").put("alchemy::id", "TestView");
        ExplorerView view = service.createView(REGION, createView);
        assertTrue(view.getViewArn().contains(":view/alchemy-re2-renamed-view/"));
        assertEquals("service:s3", view.getFilterString());
        assertEquals(List.of("tags"), view.getIncludedProperties());

        ObjectNode getView = mapper.createObjectNode().put("ViewArn", view.getViewArn());
        ExplorerView observed = service.getView(REGION, getView);
        assertEquals("TestView", observed.getTags().get("alchemy::id"));

        ObjectNode updateView = mapper.createObjectNode();
        updateView.put("ViewArn", view.getViewArn());
        updateView.putObject("Filters").put("FilterString", "service:sqs");
        ExplorerView updated = service.updateView(REGION, updateView);
        assertEquals("service:sqs", updated.getFilterString());
        assertTrue(updated.getIncludedProperties().isEmpty());

        assertTrue(service.listViews(REGION, mapper.createObjectNode()).items().contains(view.getViewArn()));

        ObjectNode search = mapper.createObjectNode();
        search.put("QueryString", "service:s3");
        search.put("ViewArn", view.getViewArn());
        ResourceExplorerService.SearchResult searchResult = service.search(REGION, search);
        assertEquals(view.getViewArn(), searchResult.viewArn());
        assertTrue(searchResult.complete());
        assertEquals(0, searchResult.totalResources());

        ObjectNode listResources = mapper.createObjectNode();
        listResources.putObject("Filters").put("FilterString", "service:s3");
        listResources.put("ViewArn", view.getViewArn());
        assertEquals(view.getViewArn(), service.listResources(REGION, listResources).viewArn());

        assertFalse(service.listSupportedResourceTypes(mapper.createObjectNode()).items().isEmpty());

        ObjectNode promote = mapper.createObjectNode();
        promote.put("Arn", created.getArn());
        promote.put("Type", "AGGREGATOR");
        ExplorerIndex promoted = service.updateIndexType(REGION, promote);
        assertEquals("AGGREGATOR", promoted.getType());
        assertEquals("ACTIVE", promoted.getState());

        service.deleteView(REGION, getView);
        AwsException goneView = assertThrows(AwsException.class, () -> service.getView(REGION, getView));
        assertEquals("UnauthorizedException", goneView.getErrorCode());

        ObjectNode deleteIndex = mapper.createObjectNode().put("Arn", created.getArn());
        ExplorerIndex deleted = service.deleteIndex(REGION, deleteIndex);
        assertEquals("DELETED", deleted.getState());
        AwsException goneIndex = assertThrows(AwsException.class, () -> service.getIndex(REGION));
        assertEquals("ResourceNotFoundException", goneIndex.getErrorCode());
    }

    @Test
    void rewritePathPrefixesOperationsAndLeavesTagsAlone() {
        assertEquals("/resource-explorer-2/CreateIndex",
                ResourceExplorerRoutingFilter.rewritePath("/CreateIndex"));
        assertEquals("/resource-explorer-2/GetIndex",
                ResourceExplorerRoutingFilter.rewritePath("/GetIndex"));
        assertEquals("/resource-explorer-2/ListResources",
                ResourceExplorerRoutingFilter.rewritePath("/ListResources"));
        assertEquals("/tags/arn:aws:resource-explorer-2:us-east-1:000000000000:index/abc",
                ResourceExplorerRoutingFilter.rewritePath(
                        "/tags/arn:aws:resource-explorer-2:us-east-1:000000000000:index/abc"));
        assertTrue(ResourceExplorerRoutingFilter.isResourceExplorer(
                "AWS4-HMAC-SHA256 Credential=000000000000/20260205/us-east-1/resource-explorer-2/aws4_request"));
        assertFalse(ResourceExplorerRoutingFilter.isResourceExplorer(
                "AWS4-HMAC-SHA256 Credential=000000000000/20260205/us-east-1/s3/aws4_request"));
    }
}
