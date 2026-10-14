package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.ParameterGroup;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.Subnet;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbMetadata.SubnetGroup;
import io.github.hectorvent.floci.services.memorydb.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forward-compatibility guard: a {@code memorydb-clusters.json} snapshot written by an
 * older build still carried the now-removed {@code authToken}/{@code authMode} fields.
 * {@link PersistentStorage} uses a plain ObjectMapper (which fails on unknown properties
 * by default), so without {@code @JsonIgnoreProperties(ignoreUnknown = true)} on
 * {@link Cluster} the load would throw and the entire cluster store would start empty.
 */
class MemoryDbPersistenceTest {

    @Test
    void groupMetadataAndUserTagsSurviveStorageReload(@TempDir Path dir) {
        Path parametersPath = dir.resolve("memorydb-parameter-groups.json");
        TypeReference<Map<String, ParameterGroup>> parameterType = new TypeReference<>() {};
        PersistentStorage<String, ParameterGroup> parameters = new PersistentStorage<>(parametersPath, parameterType);
        ParameterGroup group = new ParameterGroup("params", "memorydb_valkey7", "persistent",
                "arn:aws:memorydb:us-east-1:000000000000:parametergroup/params",
                Map.of("maxmemory-policy", "allkeys-lru"), Map.of("owner", "test"));
        parameters.put("us-east-1:params", group);
        parameters.flush();
        PersistentStorage<String, ParameterGroup> reloadedParameters = new PersistentStorage<>(parametersPath, parameterType);
        reloadedParameters.load();
        assertEquals(group, reloadedParameters.get("us-east-1:params").orElseThrow());

        Path subnetsPath = dir.resolve("memorydb-subnet-groups.json");
        TypeReference<Map<String, SubnetGroup>> subnetType = new TypeReference<>() {};
        PersistentStorage<String, SubnetGroup> subnets = new PersistentStorage<>(subnetsPath, subnetType);
        SubnetGroup subnetGroup = new SubnetGroup("subnets", "persistent", "vpc-one",
                "arn:aws:memorydb:us-east-1:000000000000:subnetgroup/subnets",
                List.of(new Subnet("subnet-one", "us-east-1a")), Map.of("owner", "test"));
        subnets.put("us-east-1:subnets", subnetGroup);
        subnets.flush();
        PersistentStorage<String, SubnetGroup> reloadedSubnets = new PersistentStorage<>(subnetsPath, subnetType);
        reloadedSubnets.load();
        assertEquals(subnetGroup, reloadedSubnets.get("us-east-1:subnets").orElseThrow());

        Path usersPath = dir.resolve("memorydb-users.json");
        TypeReference<Map<String, User>> userType = new TypeReference<>() {};
        PersistentStorage<String, User> users = new PersistentStorage<>(usersPath, userType);
        User user = new User();
        user.setName("tagged");
        user.setTags(Map.of("owner", "persistent"));
        users.put("us-east-1:tagged", user);
        users.flush();
        PersistentStorage<String, User> reloadedUsers = new PersistentStorage<>(usersPath, userType);
        reloadedUsers.load();
        assertEquals(user.getTags(), reloadedUsers.get("us-east-1:tagged").orElseThrow().getTags());
    }

    @Test
    void loadsLegacySnapshotContainingRemovedAuthFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("memorydb-clusters.json");
        Files.writeString(file, """
                {
                  "legacy-cluster": {
                    "name": "legacy-cluster",
                    "status": "AVAILABLE",
                    "nodeType": "db.t4g.small",
                    "aclName": "open-access",
                    "authMode": "PASSWORD",
                    "authToken": "old-plaintext-token",
                    "arn": "arn:aws:memorydb:us-east-1:000000000000:cluster/legacy-cluster"
                  }
                }
                """);

        PersistentStorage<String, Cluster> storage =
                new PersistentStorage<>(file, new TypeReference<Map<String, Cluster>>() {});
        storage.load();

        Optional<Cluster> loaded = storage.get("legacy-cluster");
        assertTrue(loaded.isPresent(), "legacy cluster must survive the schema change");
        assertEquals("legacy-cluster", loaded.get().getName());
        assertEquals("open-access", loaded.get().getAclName());
    }
}
