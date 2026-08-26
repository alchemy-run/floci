package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ram.model.RamAssociation;
import io.github.hectorvent.floci.services.ram.model.RamInvitation;
import io.github.hectorvent.floci.services.ram.model.RamPermission;
import io.github.hectorvent.floci.services.ram.model.RamResourceShare;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the ResourceShare observe/ensure/sync operations Alchemy
 * exercises: create with principal+tags, list, tag, disassociate, delete.
 */
class RamResourceShareTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String EXTERNAL = "123456789012";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void createAssociateTagDisassociateAndDelete() {
        RamService service = newService();

        ObjectNode create = JSON.createObjectNode();
        create.put("name", "resource-share-fixture");
        create.put("allowExternalPrincipals", true);
        create.putArray("principals").add(EXTERNAL);
        ArrayNode tags = create.putArray("tags");
        tag(tags, "team", "platform");
        tag(tags, "alchemy::id", "TestShare");

        RamResourceShare share = service.createResourceShare(REGION, create);
        assertTrue(share.getArn().startsWith("arn:aws:ram:" + REGION + ":" + ACCOUNT + ":resource-share/"));
        assertEquals("ACTIVE", share.getStatus());
        assertTrue(share.isAllowExternalPrincipals());
        assertEquals("platform", share.getTags().get("team"));
        assertTrue(share.getAssociations().stream().anyMatch(association ->
                EXTERNAL.equals(association.getAssociatedEntity())
                        && ("ASSOCIATED".equals(association.getStatus())
                                || "ASSOCIATING".equals(association.getStatus()))));

        ObjectNode get = JSON.createObjectNode();
        get.put("resourceOwner", "SELF");
        get.putArray("resourceShareArns").add(share.getArn());
        RamService.Page<RamResourceShare> found = service.getResourceShares(REGION, get);
        assertEquals(1, found.items().size());
        assertEquals(share.getArn(), found.items().get(0).getArn());

        ObjectNode associationsReq = JSON.createObjectNode();
        associationsReq.put("associationType", "PRINCIPAL");
        associationsReq.putArray("resourceShareArns").add(share.getArn());
        RamService.Page<RamAssociation> principals =
                service.getResourceShareAssociations(REGION, associationsReq);
        assertTrue(principals.items().stream().anyMatch(association ->
                EXTERNAL.equals(association.getAssociatedEntity())));

        ObjectNode tagReq = JSON.createObjectNode();
        tagReq.put("resourceShareArn", share.getArn());
        tag(tagReq.putArray("tags"), "env", "prod");
        service.tagResource(REGION, tagReq);
        assertEquals("prod", service.getResourceShares(REGION, get).items().get(0).getTags().get("env"));

        ObjectNode disassociate = JSON.createObjectNode();
        disassociate.put("resourceShareArn", share.getArn());
        disassociate.putArray("principals").add(EXTERNAL);
        service.disassociateResourceShare(REGION, disassociate);
        RamService.Page<RamAssociation> after =
                service.getResourceShareAssociations(REGION, associationsReq);
        assertFalse(after.items().stream().anyMatch(association ->
                EXTERNAL.equals(association.getAssociatedEntity())
                        && ("ASSOCIATED".equals(association.getStatus())
                                || "ASSOCIATING".equals(association.getStatus()))));

        ObjectNode list = JSON.createObjectNode();
        list.put("resourceOwner", "SELF");
        assertTrue(service.getResourceShares(REGION, list).items().stream()
                .anyMatch(item -> share.getArn().equals(item.getArn())));

        service.deleteResourceShare(REGION, share.getArn());
        try {
            service.getResourceShares(REGION, get);
            throw new AssertionError("expected UnknownResourceException");
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            assertEquals("UnknownResourceException", e.getErrorCode());
        }
    }

    private static RamService newService() {
        return new RamService(
                new InMemoryStorage<String, RamResourceShare>(),
                new InMemoryStorage<String, RamInvitation>(),
                new InMemoryStorage<String, RamPermission>(),
                new RegionResolver(REGION, ACCOUNT));
    }

    private static void tag(ArrayNode tags, String key, String value) {
        ObjectNode tag = tags.addObject();
        tag.put("key", key);
        tag.put("value", value);
    }
}
