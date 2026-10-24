package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.services.lakeformation.model.DataLakePrincipal;
import io.github.hectorvent.floci.services.lakeformation.model.DataLakeSettings;
import io.github.hectorvent.floci.services.lakeformation.model.FilterCondition;
import io.github.hectorvent.floci.services.lakeformation.model.LFTag;
import io.github.hectorvent.floci.services.lakeformation.model.LFTagPair;
import io.github.hectorvent.floci.services.lakeformation.model.PrincipalResourcePermissions;
import io.github.hectorvent.floci.services.lakeformation.model.Resource;
import io.github.hectorvent.floci.services.lakeformation.model.ResourceInfo;

import java.util.List;
import java.util.Optional;

public interface LakeFormationStorage {

    void putDataLakeSettings(String region, String catalogId, DataLakeSettings settings);
    Optional<DataLakeSettings> getDataLakeSettings(String region, String catalogId);

    void registerResource(String region, String resourceArn, String roleArn, boolean useServiceLinkedRole, Boolean withFederation);
    void registerResource(String region, ResourceInfo info);
    void updateResource(String region, String resourceArn, String roleArn, String expectedResourceOwnerAccount,
                        Boolean hybridAccessEnabled, Boolean withFederation);
    void deregisterResource(String region, String resourceArn);
    List<ResourceInfo> listResources(String region, List<FilterCondition> filterConditions, Integer maxResults, String nextToken);
    Optional<ResourceInfo> describeResource(String region, String resourceArn);

    void grantPermissions(String region, String catalogId, PrincipalResourcePermissions permissions);
    void revokePermissions(String region, String catalogId, PrincipalResourcePermissions permissions);
    List<PrincipalResourcePermissions> listPermissions(String region, String catalogId, DataLakePrincipal principal,
                                                       Resource resource, String resourceType,
                                                       boolean includeRelated, Integer maxResults, String nextToken);

    void createLFTag(String region, String catalogId, String tagKey, List<String> tagValues);
    Optional<LFTag> getLFTag(String region, String catalogId, String tagKey);
    void updateLFTag(String region, String catalogId, String tagKey, List<String> tagValuesToAdd, List<String> tagValuesToDelete);
    void deleteLFTag(String region, String catalogId, String tagKey);
    List<LFTagPair> listLFTags(String region, String catalogId, String resourceShareType, Integer maxResults, String nextToken);

    List<LFTagPair> getResourceLFTags(String region, String catalogId, Resource resource);
    void addLFTagsToResource(String region, String catalogId, Resource resource, List<LFTagPair> lfTags);
    void removeLFTagsFromResource(String region, String catalogId, Resource resource, List<LFTagPair> lfTags);
}
