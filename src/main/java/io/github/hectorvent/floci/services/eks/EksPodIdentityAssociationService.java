package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociationSummary;
import io.github.hectorvent.floci.services.eks.model.UpdatePodIdentityAssociationRequest;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shares association state and replay history with the management and tagging APIs. */
@ApplicationScoped
public class EksPodIdentityAssociationService {

    private final EksPodIdentityService associations;

    @Inject
    public EksPodIdentityAssociationService(EksPodIdentityService associations) {
        this.associations = associations;
    }

    @RegisterForReflection
    public record Page(List<PodIdentityAssociationSummary> associations, String nextToken) {}

    public PodIdentityAssociation create(Cluster cluster, CreatePodIdentityAssociationRequest request) {
        requireActiveCluster(cluster);
        return toModel(associations.create(cluster, request == null ? null : new EksPodIdentityService.CreateRequest(
                request.namespace(), request.serviceAccount(), request.roleArn(), request.disableSessionTags(),
                request.targetRoleArn(), request.policy(), request.tags(), request.clientRequestToken())));
    }

    public PodIdentityAssociation describe(Cluster cluster, String associationId) {
        requireActiveCluster(cluster);
        return toModel(associations.describe(cluster, associationId));
    }

    public PodIdentityAssociation update(Cluster cluster, String associationId,
                                         UpdatePodIdentityAssociationRequest request) {
        requireActiveCluster(cluster);
        return toModel(associations.update(cluster, associationId, request == null ? null
                : new EksPodIdentityService.UpdateRequest(request.roleArn(), request.disableSessionTags(),
                        request.targetRoleArn(), request.policy(), request.clientRequestToken())));
    }

    public PodIdentityAssociation delete(Cluster cluster, String associationId) {
        requireActiveCluster(cluster);
        return toModel(associations.delete(cluster, associationId));
    }

    public Page list(Cluster cluster, String namespace, String serviceAccount, Integer maxResults, String nextToken) {
        requireActiveCluster(cluster);
        EksPodIdentityService.Page page = associations.list(cluster, namespace, serviceAccount, maxResults, nextToken);
        return new Page(page.associations().stream().map(value -> new PodIdentityAssociationSummary(
                value.clusterName(), value.namespace(), value.serviceAccount(), value.associationArn(),
                value.associationId(), null)).toList(), page.nextToken());
    }

    public void tag(Cluster cluster, String associationId, Map<String, String> tags, List<String> removed) {
        associations.tag(cluster, associationId, tags, removed);
    }

    public void deleteClusterAssociations(Cluster cluster) {
        associations.deleteClusterAssociations(cluster);
    }

    public Optional<PodIdentityAssociation> findAssociation(Cluster cluster, String namespace, String serviceAccount) {
        if (cluster == null || cluster.getStatus() != ClusterStatus.ACTIVE) {
            return Optional.empty();
        }
        return associations.findAssociation(cluster, namespace, serviceAccount).map(EksPodIdentityAssociationService::toModel);
    }

    private static PodIdentityAssociation toModel(EksPodIdentityService.Association association) {
        return new PodIdentityAssociation(association.clusterName(), association.namespace(), association.serviceAccount(),
                association.roleArn(), association.associationArn(), association.associationId(), association.tags(),
                association.createdAt(), association.modifiedAt(), null, association.targetRoleArn(),
                association.disableSessionTags(), association.externalId(), association.policy());
    }

    private static void requireActiveCluster(Cluster cluster) {
        if (cluster == null) {
            throw new AwsException("ResourceNotFoundException", "Cluster not found", 404);
        }
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400);
        }
    }
}
