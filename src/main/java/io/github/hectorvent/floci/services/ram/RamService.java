package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ram.model.PrincipalAssociation;
import io.github.hectorvent.floci.services.ram.model.RamPermission;
import io.github.hectorvent.floci.services.ram.model.RamPermission.PermissionVersion;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.ResourceShareInvitation;
import io.github.hectorvent.floci.services.ram.model.ShareAssociation;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Resource Access Manager (RAM) business logic.
 *
 * <p>Covers {@code EnableSharingWithAwsOrganization} plus the resource-share reads LZA's
 * TGW share flow performs: the owning account registers a share (from the
 * {@code AWS::RAM::ResourceShare} CFN provisioner), and accepting accounts page
 * GetResourceShareInvitations, find the share via GetResourceShares(OTHER-ACCOUNTS), and read
 * the shared ARNs via ListResources.
 *
 * <p>Visibility is principal-aware: an owner sees its own shares, an account principal sees a
 * share only while its invitation is pending or accepted, and organization/OU principals are
 * visible only to members resolved through the existing Organizations service. This same rule
 * is used by GetResourceShares, ListResources, and ListPrincipals so filtering one operation
 * cannot leak metadata through another.
 *
 * <p>Mutations remain owner-only, and a non-owner gets the same UnknownResourceException a
 * never-created ARN gets.
 */
@ApplicationScoped
public class RamService {

    private static final String ORGANIZATION_SHARING_KEY = "sharing-with-organization-enabled";
    /** The modeled ResourceShareStatus enum. */
    private static final List<String> SHARE_STATUSES =
            List.of("PENDING", "ACTIVE", "FAILED", "DELETING", "DELETED");
    /** An AWS account id, as opposed to an organization/OU principal ARN. */
    private static final Pattern ACCOUNT_ID_PRINCIPAL = Pattern.compile("\\d{12}");
    private static final List<String> ASSOCIATION_TYPES = List.of("PRINCIPAL", "RESOURCE", "SOURCE");
    private static final List<String> ASSOCIATION_STATUSES = List.of("ASSOCIATING", "ASSOCIATED", "FAILED",
            "DISASSOCIATING", "DISASSOCIATED", "SUSPENDED", "SUSPENDING", "RESTORING");
    private static final List<String> PERMISSION_TYPE_FILTERS = List.of("ALL", "AWS_MANAGED", "CUSTOMER_MANAGED");
    private static final List<String> REGION_SCOPE_FILTERS = List.of("ALL", "REGIONAL", "GLOBAL");
    /** CreatePermission's name constraint: 1-36 word characters, periods, or hyphens. */
    private static final Pattern PERMISSION_NAME = Pattern.compile("[\\w.-]{1,36}");
    /** A RAM resource type, e.g. {@code appsync:Apis} or {@code ec2:Subnet}. */
    private static final Pattern RESOURCE_TYPE = Pattern.compile("[a-z0-9-]+:[A-Za-z0-9]+");
    /** RAM caps a customer managed permission at five versions that are not deleted. */
    private static final int MAX_PERMISSION_VERSIONS = 5;
    private static final int MAX_RESULTS_LIMIT = 500;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final StorageFactory storageFactory;
    private final OrganizationsService organizationsService;
    private StorageBackend<String, ResourceShare> shares;
    private StorageBackend<String, Boolean> settings;
    private StorageBackend<String, ResourceShareInvitation> invitations;
    private StorageBackend<String, RamPermission> permissions;

    /** One page of a paginated read plus the token for the next page (null on the last page). */
    public record Page<T>(List<T> items, String nextToken) {}

    @Inject
    public RamService(StorageFactory storageFactory, OrganizationsService organizationsService) {
        this.storageFactory = storageFactory;
        this.organizationsService = organizationsService;
    }

    /** Constructor used by isolated service tests with an explicit Organizations test double. */
    RamService(StorageFactory storageFactory) {
        this(storageFactory, null);
    }

    @PostConstruct
    void initializeStorage() {
        shares = storageFactory.create("ram", "ram-resource-shares.json",
                new TypeReference<Map<String, ResourceShare>>() {});
        settings = storageFactory.create("ram", "ram-settings.json",
                new TypeReference<Map<String, Boolean>>() {});
        invitations = storageFactory.create("ram", "ram-resource-share-invitations.json",
                new TypeReference<Map<String, ResourceShareInvitation>>() {});
        permissions = storageFactory.create("ram", "ram-permissions.json",
                new TypeReference<Map<String, RamPermission>>() {});
    }

    public boolean enableSharingWithAwsOrganization() {
        settings.put(ORGANIZATION_SHARING_KEY, true);
        return true;
    }

    public boolean enableSharingWithAwsOrganization(String callerAccountId) {
        if (settings instanceof AccountAwareStorageBackend<Boolean> accountAware) {
            accountAware.putForAccount(callerAccountId, ORGANIZATION_SHARING_KEY, true);
        } else {
            settings.put(ORGANIZATION_SHARING_KEY, true);
        }
        return true;
    }

    public boolean isSharingWithOrganizationEnabled() {
        return settings.get(ORGANIZATION_SHARING_KEY).orElse(false);
    }

    public boolean isSharingWithOrganizationEnabled(String accountId) {
        if (settings instanceof AccountAwareStorageBackend<Boolean> accountAware) {
            return accountAware.getForAccount(accountId, ORGANIZATION_SHARING_KEY).orElse(false);
        }
        return settings.get(ORGANIZATION_SHARING_KEY).orElse(false);
    }

    public ResourceShare createResourceShare(String name, List<String> principals,
                                             List<String> resourceArns, boolean allowExternalPrincipals,
                                             String region, String owningAccountId) {
        return createResourceShare(name, principals, resourceArns, List.of(), List.of(), Map.of(),
                allowExternalPrincipals, region, owningAccountId);
    }

    /**
     * @param permissionArns managed permissions to attach; each must be an AWS managed permission
     *                       or a customer managed permission the caller owns
     * @param tags           tags applied to the share at creation
     */
    public ResourceShare createResourceShare(String name, List<String> principals, List<String> resourceArns,
                                             List<String> sources, List<String> permissionArns,
                                             Map<String, String> tags, boolean allowExternalPrincipals,
                                             String region, String owningAccountId) {
        requireValidArns(resourceArns);
        requireValidArns(permissionArns);
        for (String permissionArn : permissionArns) {
            requirePermission(permissionArn, owningAccountId);
        }
        String arn = "arn:aws:ram:" + region + ":" + owningAccountId
                + ":resource-share/" + UUID.randomUUID();
        ResourceShare share = new ResourceShare(
                arn, name, owningAccountId, principals, resourceArns, allowExternalPrincipals)
                .withSources(List.copyOf(new LinkedHashSet<>(sources)))
                .withPermissionArns(List.copyOf(new LinkedHashSet<>(permissionArns)))
                .withTags(tags);
        ResourceShare stored = putForOwner(share);
        inviteAccountPrincipals(stored, principals);
        return stored;
    }

    /** The unfiltered read: every share visible to the caller under {@code resourceOwner}. */
    public List<ResourceShare> getResourceShares(String callerAccountId, String resourceOwner) {
        return getResourceShares(callerAccountId, resourceOwner, null, List.of(), null);
    }

    /**
     * @param resourceOwner {@code SELF} (shares the caller owns) or {@code OTHER-ACCOUNTS}
     *                      (shares other accounts made visible to the caller)
     * @param name exact share name to match, or null for any
     * @param resourceShareArns share ARNs to restrict the result to, or empty for any
     * @param resourceShareStatus one {@code ResourceShareStatus} value to match, or null for any
     */
    public List<ResourceShare> getResourceShares(String callerAccountId, String resourceOwner,
                                                 String name, List<String> resourceShareArns,
                                                 String resourceShareStatus) {
        requireResourceOwner(resourceOwner);
        requireResourceShareStatus(resourceShareStatus);
        List<ResourceShare> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (!isVisible(share, callerAccountId, resourceOwner)) {
                continue;
            }
            if (name != null && !name.equals(share.getName())) {
                continue;
            }
            if (!resourceShareArns.isEmpty() && !resourceShareArns.contains(share.getResourceShareArn())) {
                continue;
            }
            if (resourceShareStatus != null && !resourceShareStatus.equals(share.getStatus())) {
                continue;
            }
            result.add(share);
        }
        return result;
    }

    /**
     * @param resourceShareArns restrict to invitations for these shares, or empty for any
     * @param resourceShareInvitationArns restrict to these invitation ARNs, or empty for any
     */
    public List<ResourceShareInvitation> getResourceShareInvitations(String callerAccountId,
                                                                      List<String> resourceShareArns,
                                                                      List<String> resourceShareInvitationArns) {
        requireValidArns(resourceShareArns);
        requireValidArns(resourceShareInvitationArns);
        List<ResourceShareInvitation> result = new ArrayList<>();
        for (ResourceShareInvitation invitation : allInvitations()) {
            // "Retrieves details about invitations that you have received": receiver only,
            // not the sender (verified against the API reference; unlike GetResourceShares,
            // there is no SELF/OTHER-ACCOUNTS style toggle here).
            if (!callerAccountId.equals(invitation.receiverAccountId())) {
                continue;
            }
            if (!resourceShareArns.isEmpty() && !resourceShareArns.contains(invitation.resourceShareArn())) {
                continue;
            }
            if (!resourceShareInvitationArns.isEmpty()
                    && !resourceShareInvitationArns.contains(invitation.resourceShareInvitationArn())) {
                continue;
            }
            result.add(invitation);
        }
        return result;
    }

    /** Both GetResourceShareInvitations and Accept/RejectResourceShareInvitation model this. */
    private static void requireValidArns(List<String> arns) {
        for (String arn : arns) {
            if (!isValidArn(arn)) {
                throw new AwsException("MalformedArnException",
                        "The specified Amazon Resource Name (ARN) has a format that isn't valid: " + arn, 400);
            }
        }
    }

    private static boolean isValidArn(String arn) {
        try {
            AwsArnUtils.parse(arn);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public ResourceShareInvitation acceptResourceShareInvitation(String resourceShareInvitationArn,
                                                                  String callerAccountId) {
        return resolveInvitation(resourceShareInvitationArn, callerAccountId, "ACCEPTED");
    }

    public ResourceShareInvitation rejectResourceShareInvitation(String resourceShareInvitationArn,
                                                                  String callerAccountId) {
        return resolveInvitation(resourceShareInvitationArn, callerAccountId, "REJECTED");
    }

    private ResourceShareInvitation resolveInvitation(String resourceShareInvitationArn,
                                                       String callerAccountId, String newStatus) {
        if (!isValidArn(resourceShareInvitationArn)) {
            throw new AwsException("MalformedArnException",
                    "The specified Amazon Resource Name (ARN) has a format that isn't valid: "
                            + resourceShareInvitationArn, 400);
        }
        // Serializes the status check against the write: two concurrent Accept calls on the
        // same invitation must not both observe PENDING and both succeed.
        synchronized (this) {
            ResourceShareInvitation invitation = allInvitations().stream()
                    .filter(i -> i.resourceShareInvitationArn().equals(resourceShareInvitationArn))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("ResourceShareInvitationArnNotFoundException",
                            "ResourceShareInvitation " + resourceShareInvitationArn + " does not exist.", 400));
            // Only the invited account can act on its own invitation: the sender polls it via
            // GetResourceShareInvitations but does not get to accept or reject on the receiver's
            // behalf.
            if (!callerAccountId.equals(invitation.receiverAccountId())) {
                throw new AwsException("OperationNotPermittedException",
                        "You do not have permission to accept or reject this invitation.", 400);
            }
            if ("ACCEPTED".equals(invitation.status())) {
                throw new AwsException("ResourceShareInvitationAlreadyAcceptedException",
                        "ResourceShareInvitation " + resourceShareInvitationArn + " has already been accepted.", 400);
            }
            if ("REJECTED".equals(invitation.status())) {
                throw new AwsException("ResourceShareInvitationAlreadyRejectedException",
                        "ResourceShareInvitation " + resourceShareInvitationArn + " has already been rejected.", 400);
            }
            ResourceShareInvitation updated = invitation.withStatus(newStatus);
            putInvitation(updated);
            return updated;
        }
    }

    /**
     * An organization/OU principal (not a bare account id) never gets one: real AWS only
     * invites account principals, and auto-accepts those too once organization sharing is
     * enabled (matching {@link #enableSharingWithAwsOrganization()}). A principal already
     * PENDING or ACCEPTED on this share is skipped so re-associating it is a no-op; one that
     * was REJECTED gets invited again, same as real AWS re-sharing after a rejection.
     */
    private void inviteAccountPrincipals(ResourceShare share, List<String> principals) {
        if (isSharingWithOrganizationEnabled(share.getOwningAccountId())) {
            return;
        }
        // Serializes the live-invitation check against the insert: two concurrent
        // AssociateResourceShare calls for the same principal must not both observe "no
        // live invitation" and both create one.
        synchronized (this) {
            for (String principal : principals) {
                if (!ACCOUNT_ID_PRINCIPAL.matcher(principal).matches()) {
                    continue;
                }
                boolean live = allInvitations().stream()
                        .anyMatch(i -> i.resourceShareArn().equals(share.getResourceShareArn())
                                && i.receiverAccountId().equals(principal)
                                && !"REJECTED".equals(i.status()));
                if (live) {
                    continue;
                }
                String region = extractRegion(share.getResourceShareArn());
                String arn = "arn:aws:ram:" + region + ":" + share.getOwningAccountId()
                        + ":resource-share-invitation/" + UUID.randomUUID();
                putInvitation(new ResourceShareInvitation(arn, share.getResourceShareArn(), share.getName(),
                        share.getOwningAccountId(), principal, Instant.now(), "PENDING"));
            }
        }
    }

    /** {@code arn:aws:ram:<region>:<account>:resource-share/<id>} → {@code <region>}. */
    private static String extractRegion(String resourceShareArn) {
        String[] parts = resourceShareArn.split(":", 6);
        return parts.length < 4 ? "" : parts[3];
    }

    private void putInvitation(ResourceShareInvitation invitation) {
        if (invitations instanceof AccountAwareStorageBackend<ResourceShareInvitation> accountAware) {
            accountAware.putForAccount(invitation.receiverAccountId(),
                    invitation.resourceShareInvitationArn(), invitation);
            return;
        }
        invitations.put(invitation.resourceShareInvitationArn(), invitation);
    }

    private List<ResourceShareInvitation> allInvitations() {
        if (invitations instanceof AccountAwareStorageBackend<ResourceShareInvitation> accountAware) {
            return accountAware.scanAllAccounts();
        }
        return invitations.scan(key -> true);
    }

    public List<SharedResource> listResources(String callerAccountId, String resourceOwner,
                                              List<String> resourceShareArns) {
        requireResourceOwner(resourceOwner);
        List<SharedResource> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (!isVisible(share, callerAccountId, resourceOwner)) {
                continue;
            }
            // A deleted share stays readable via GetResourceShares (status DELETED)
            // but its contents stop being consumable.
            if ("DELETED".equals(share.getStatus())) {
                continue;
            }
            if (!resourceShareArns.isEmpty() && !resourceShareArns.contains(share.getResourceShareArn())) {
                continue;
            }
            for (String resourceArn : share.getResourceArns()) {
                result.add(new SharedResource(resourceArn, ramResourceType(resourceArn),
                        share.getResourceShareArn(), "AVAILABLE"));
            }
        }
        return result;
    }

    public ResourceShare deleteResourceShare(String resourceShareArn, String callerAccountId) {
        return putForOwner(
                requireOwnedShare(resourceShareArn, callerAccountId).withStatus("DELETED"));
    }

    public ResourceShare updateResourceShare(String resourceShareArn, String name,
                                             Boolean allowExternalPrincipals, String callerAccountId) {
        ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
        if (name != null) {
            share = share.withName(name);
        }
        if (allowExternalPrincipals != null) {
            share = share.withAllowExternalPrincipals(allowExternalPrincipals);
        }
        return putForOwner(share);
    }

    public ResourceShare associateResourceShare(String resourceShareArn, List<String> resourceArns,
                                                List<String> principals, String callerAccountId) {
        return associateResourceShare(resourceShareArn, resourceArns, principals, List.of(), callerAccountId);
    }

    public ResourceShare associateResourceShare(String resourceShareArn, List<String> resourceArns,
                                                List<String> principals, List<String> sources,
                                                String callerAccountId) {
        requireValidArns(resourceArns);
        // The read, the merged write, and the resulting invitation creation must all happen as
        // one operation: two concurrent associates for different principals on the same share
        // must not each read the pre-update share and overwrite each other's addition, which
        // would also leave an invitation on record for a principal the stored share lost.
        synchronized (this) {
            ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
            ResourceShare updated = share.withPrincipalsAndResources(
                    mergeDistinct(share.getPrincipals(), principals),
                    mergeDistinct(share.getResourceArns(), resourceArns))
                    .withSources(mergeDistinct(share.getSources(), sources));
            ResourceShare stored = putForOwner(updated);
            // Only newly-added principals: re-associating one already on the share (or one that
            // already has a live invitation) must not spawn a second invitation.
            inviteAccountPrincipals(stored, withoutAll(principals, share.getPrincipals()));
            return stored;
        }
    }

    public ResourceShare disassociateResourceShare(String resourceShareArn, List<String> resourceArns,
                                                    List<String> principals, String callerAccountId) {
        return disassociateResourceShare(resourceShareArn, resourceArns, principals, List.of(), callerAccountId);
    }

    public ResourceShare disassociateResourceShare(String resourceShareArn, List<String> resourceArns,
                                                    List<String> principals, List<String> sources,
                                                    String callerAccountId) {
        synchronized (this) {
            ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
            ResourceShare updated = share.withPrincipalsAndResources(
                    withoutAll(share.getPrincipals(), principals),
                    withoutAll(share.getResourceArns(), resourceArns))
                    .withSources(withoutAll(share.getSources(), sources));
            return putForOwner(updated);
        }
    }

    /**
     * The owner's view of what its shares are associated with. A deleted share reports its
     * former associations as DISASSOCIATED, since deleting a share disassociates everything.
     *
     * @param associationType   {@code PRINCIPAL}, {@code RESOURCE}, or {@code SOURCE} (required)
     * @param resourceShareArns restrict to these shares; an ARN the caller does not own is
     *                          UnknownResourceException
     * @param resourceArn       only valid with {@code RESOURCE}
     * @param principal         only valid with {@code PRINCIPAL}
     */
    public List<ShareAssociation> getResourceShareAssociations(String callerAccountId, String associationType,
                                                               List<String> resourceShareArns, String resourceArn,
                                                               String principal, String associationStatus) {
        if (associationType == null || !ASSOCIATION_TYPES.contains(associationType)) {
            throw new AwsException("InvalidParameterException",
                    "associationType is required and must be one of " + ASSOCIATION_TYPES + ".", 400);
        }
        if (associationStatus != null && !ASSOCIATION_STATUSES.contains(associationStatus)) {
            throw new AwsException("InvalidParameterException",
                    "associationStatus must be one of " + ASSOCIATION_STATUSES + ".", 400);
        }
        if (resourceArn != null && !"RESOURCE".equals(associationType)) {
            throw new AwsException("InvalidParameterException",
                    "resourceArn can be specified only when associationType is RESOURCE.", 400);
        }
        if (principal != null && !"PRINCIPAL".equals(associationType)) {
            throw new AwsException("InvalidParameterException",
                    "principal can be specified only when associationType is PRINCIPAL.", 400);
        }
        requireValidArns(resourceShareArns);
        if (resourceArn != null) {
            requireValidArns(List.of(resourceArn));
        }

        List<ResourceShare> owned = allShares().stream()
                .filter(share -> share.getOwningAccountId().equals(callerAccountId))
                .sorted(Comparator.comparing(ResourceShare::getCreationTime)
                        .thenComparing(ResourceShare::getResourceShareArn))
                .toList();
        for (String requested : resourceShareArns) {
            if (owned.stream().noneMatch(share -> share.getResourceShareArn().equals(requested))) {
                throw new AwsException("UnknownResourceException",
                        "ResourceShare " + requested + " could not be found.", 400);
            }
        }

        List<ShareAssociation> result = new ArrayList<>();
        for (ResourceShare share : owned) {
            if (!resourceShareArns.isEmpty() && !resourceShareArns.contains(share.getResourceShareArn())) {
                continue;
            }
            String status = "DELETED".equals(share.getStatus()) ? "DISASSOCIATED" : "ASSOCIATED";
            if (associationStatus != null && !associationStatus.equals(status)) {
                continue;
            }
            List<String> entities = switch (associationType) {
                case "PRINCIPAL" -> share.getPrincipals();
                case "RESOURCE" -> share.getResourceArns();
                default -> share.getSources();
            };
            for (String entity : entities) {
                if (resourceArn != null && !resourceArn.equals(entity)) {
                    continue;
                }
                if (principal != null && !principal.equals(entity)) {
                    continue;
                }
                boolean external = "PRINCIPAL".equals(associationType)
                        && isExternalPrincipal(share.getOwningAccountId(), entity);
                result.add(new ShareAssociation(share.getResourceShareArn(), share.getName(), entity,
                        associationType, status, share.getCreationTime(), share.getLastUpdatedTime(), external));
            }
        }
        return result;
    }

    /**
     * An account principal outside the owner's organization is external; organization and OU
     * principals can only name the owner's own organization, and IAM role/user principals
     * are judged by the account in their ARN.
     */
    boolean isExternalPrincipal(String ownerAccountId, String principal) {
        String accountId;
        if (ACCOUNT_ID_PRINCIPAL.matcher(principal).matches()) {
            accountId = principal;
        } else {
            String[] arn = principal.split(":", 6);
            if (arn.length != 6 || !"iam".equals(arn[2])) {
                return false;
            }
            accountId = arn[4];
        }
        if (accountId.equals(ownerAccountId)) {
            return false;
        }
        return !isMemberOfSameOrganization(ownerAccountId, accountId);
    }

    /**
     * The resources of a share the caller was invited to but has not accepted yet.
     *
     * @param resourceRegionScope {@code ALL} (default), {@code REGIONAL}, or {@code GLOBAL}
     */
    public List<SharedResource> listPendingInvitationResources(String callerAccountId,
                                                               String resourceShareInvitationArn,
                                                               String resourceRegionScope) {
        if (resourceShareInvitationArn == null || resourceShareInvitationArn.isEmpty()) {
            throw new AwsException("MissingRequiredParameterException",
                    "resourceShareInvitationArn is required.", 400);
        }
        if (!isValidArn(resourceShareInvitationArn)) {
            throw new AwsException("MalformedArnException",
                    "The specified Amazon Resource Name (ARN) has a format that isn't valid: "
                            + resourceShareInvitationArn, 400);
        }
        if (resourceRegionScope != null && !REGION_SCOPE_FILTERS.contains(resourceRegionScope)) {
            throw new AwsException("InvalidParameterException",
                    "resourceRegionScope must be one of " + REGION_SCOPE_FILTERS + ".", 400);
        }
        ResourceShareInvitation invitation = allInvitations().stream()
                .filter(i -> i.resourceShareInvitationArn().equals(resourceShareInvitationArn))
                .filter(i -> i.receiverAccountId().equals(callerAccountId))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceShareInvitationArnNotFoundException",
                        "ResourceShareInvitation " + resourceShareInvitationArn + " does not exist.", 400));
        if ("REJECTED".equals(invitation.status())) {
            throw new AwsException("ResourceShareInvitationAlreadyRejectedException",
                    "ResourceShareInvitation " + resourceShareInvitationArn + " has already been rejected.", 400);
        }
        // Floci models every shared resource as regional.
        if ("GLOBAL".equals(resourceRegionScope)) {
            return List.of();
        }
        List<SharedResource> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (!share.getResourceShareArn().equals(invitation.resourceShareArn())
                    || "DELETED".equals(share.getStatus())) {
                continue;
            }
            for (String resourceArn : share.getResourceArns()) {
                result.add(new SharedResource(resourceArn, ramResourceType(resourceArn),
                        share.getResourceShareArn(), "AVAILABLE"));
            }
        }
        return result;
    }

    /**
     * The resource-based policies RAM generates for the caller's shared resources: one per live
     * share (with principals) containing the resource, granting the share's principals the
     * actions of the share's managed permission for that resource type (or the AWS managed
     * default for the type when the share names none). A resource the caller has not shared has
     * no RAM policy, so it contributes nothing.
     *
     * @param principal only policies of shares associated with this principal, or null for any
     */
    public List<String> getResourcePolicies(String callerAccountId, List<String> resourceArns, String principal) {
        if (resourceArns.isEmpty()) {
            throw new AwsException("MissingRequiredParameterException", "resourceArns is required.", 400);
        }
        requireValidArns(resourceArns);
        List<ResourceShare> owned = allShares().stream()
                .filter(share -> share.getOwningAccountId().equals(callerAccountId))
                .filter(share -> !"DELETED".equals(share.getStatus()))
                .sorted(Comparator.comparing(ResourceShare::getCreationTime)
                        .thenComparing(ResourceShare::getResourceShareArn))
                .toList();
        List<String> policies = new ArrayList<>();
        for (String resourceArn : resourceArns) {
            String resourceType = ramResourceType(resourceArn);
            for (ResourceShare share : owned) {
                if (!share.getResourceArns().contains(resourceArn) || share.getPrincipals().isEmpty()) {
                    continue;
                }
                if (principal != null && !share.getPrincipals().contains(principal)) {
                    continue;
                }
                Optional<JsonNode> template = sharePolicyTemplate(share, resourceType, callerAccountId);
                if (template.isEmpty()) {
                    continue;
                }
                policies.add(resourcePolicy(template.get(), share.getPrincipals(), resourceArn));
            }
        }
        return policies;
    }

    private Optional<JsonNode> sharePolicyTemplate(ResourceShare share, String resourceType,
                                                   String callerAccountId) {
        Optional<RamPermission> permission = share.getPermissionArns().stream()
                .map(arn -> findPermission(arn, callerAccountId))
                .flatMap(Optional::stream)
                .filter(p -> p.resourceType().equalsIgnoreCase(resourceType))
                .findFirst()
                .or(() -> RamManagedPermissionCatalog.defaultFor(resourceType));
        return permission.map(p -> parseTemplate(p.defaultVersionEntry().policyTemplate()));
    }

    private static String resourcePolicy(JsonNode template, List<String> principals, String resourceArn) {
        ObjectNode statement = JSON.createObjectNode();
        statement.put("Effect", "Allow");
        ObjectNode principalNode = statement.putObject("Principal");
        ArrayNode aws = principalNode.putArray("AWS");
        principals.forEach(aws::add);
        statement.set("Action", template.path("Action"));
        statement.put("Resource", resourceArn);
        if (template.has("Condition")) {
            statement.set("Condition", template.get("Condition"));
        }
        ObjectNode policy = JSON.createObjectNode();
        policy.put("Version", "2012-10-17");
        policy.putArray("Statement").add(statement);
        return policy.toString();
    }

    // ── Managed permissions ────────────────────────────────────────────────────────────────

    /**
     * AWS managed permissions plus the caller's customer managed permissions in {@code region}.
     *
     * @param resourceType   case-insensitive resource type filter, or null for any
     * @param permissionType {@code ALL} (default), {@code AWS_MANAGED}, or {@code CUSTOMER_MANAGED}
     */
    public List<RamPermission> listPermissions(String callerAccountId, String region, String resourceType,
                                               String permissionType) {
        String type = permissionType == null ? "ALL" : permissionType;
        if (!PERMISSION_TYPE_FILTERS.contains(type)) {
            throw new AwsException("InvalidParameterException",
                    "permissionType must be one of " + PERMISSION_TYPE_FILTERS + ".", 400);
        }
        List<RamPermission> result = new ArrayList<>();
        if (!"CUSTOMER_MANAGED".equals(type)) {
            result.addAll(RamManagedPermissionCatalog.all());
        }
        if (!"AWS_MANAGED".equals(type)) {
            ownedPermissions(callerAccountId).stream()
                    .filter(p -> region.equals(AwsArnUtils.parse(p.arn()).region()))
                    .sorted(Comparator.comparing(RamPermission::name))
                    .forEach(result::add);
        }
        if (resourceType != null) {
            result.removeIf(p -> !p.resourceType().equalsIgnoreCase(resourceType));
        }
        return result;
    }

    /** @return the permission and the version requested (the default version when null) */
    public Map.Entry<RamPermission, PermissionVersion> getPermission(String callerAccountId, String permissionArn,
                                                                     Integer permissionVersion) {
        RamPermission permission = requirePermission(permissionArn, callerAccountId);
        if (permissionVersion == null) {
            return Map.entry(permission, permission.defaultVersionEntry());
        }
        PermissionVersion version = permission.findVersion(permissionVersion)
                .orElseThrow(() -> new AwsException("InvalidParameterException",
                        "Version " + permissionVersion + " of permission " + permissionArn
                                + " does not exist.", 400));
        return Map.entry(permission, version);
    }

    public List<PermissionVersion> listPermissionVersions(String callerAccountId, String permissionArn) {
        return requirePermission(permissionArn, callerAccountId).versions().stream()
                .sorted(Comparator.comparingInt(PermissionVersion::version))
                .toList();
    }

    public RamPermission createPermission(String callerAccountId, String region, String name, String resourceType,
                                          String policyTemplate, Map<String, String> tags) {
        if (name == null || !PERMISSION_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterException",
                    "name must be 1-36 characters of letters, digits, underscores, periods, or hyphens.", 400);
        }
        if (resourceType == null || !RESOURCE_TYPE.matcher(resourceType).matches()) {
            throw new AwsException("InvalidParameterException",
                    "resourceType must have the form <service>:<ResourceType>.", 400);
        }
        String template = validatePolicyTemplate(policyTemplate, resourceType);
        String arn = AwsArnUtils.Arn.of("ram", region, callerAccountId, "permission/" + name).toString();
        synchronized (this) {
            if (findOwnedPermission(arn, callerAccountId).isPresent()) {
                throw new AwsException("PermissionAlreadyExistsException",
                        "A permission named " + name + " already exists in this account and Region.", 400);
            }
            Instant now = Instant.now();
            RamPermission permission = new RamPermission(arn, name, resourceType, "CUSTOMER_MANAGED", false,
                    callerAccountId, 1, List.of(new PermissionVersion(1, template, false, now, now)),
                    now, now, tags);
            putPermission(permission);
            return permission;
        }
    }

    /** The new version becomes the default; the previous default becomes UNATTACHABLE. */
    public RamPermission createPermissionVersion(String callerAccountId, String permissionArn,
                                                 String policyTemplate) {
        synchronized (this) {
            RamPermission permission = requireCustomerManaged(permissionArn, callerAccountId,
                    "InvalidParameterException");
            String template = validatePolicyTemplate(policyTemplate, permission.resourceType());
            long live = permission.versions().stream().filter(v -> !v.deleted()).count();
            if (live >= MAX_PERMISSION_VERSIONS) {
                throw new AwsException("PermissionVersionsLimitExceededException",
                        "Permission " + permissionArn + " already has the maximum of "
                                + MAX_PERMISSION_VERSIONS + " versions.", 400);
            }
            int next = permission.versions().stream().mapToInt(PermissionVersion::version).max().orElse(0) + 1;
            Instant now = Instant.now();
            List<PermissionVersion> versions = new ArrayList<>(permission.versions());
            versions.add(new PermissionVersion(next, template, false, now, now));
            RamPermission updated = permission.withVersions(versions, next, now);
            putPermission(updated);
            return updated;
        }
    }

    public RamPermission setDefaultPermissionVersion(String callerAccountId, String permissionArn, int version) {
        synchronized (this) {
            RamPermission permission = requireCustomerManaged(permissionArn, callerAccountId,
                    "InvalidParameterException");
            PermissionVersion target = permission.findVersion(version)
                    .filter(v -> !v.deleted())
                    .orElseThrow(() -> new AwsException("InvalidParameterException",
                            "Version " + version + " of permission " + permissionArn + " does not exist.", 400));
            RamPermission updated = permission.withVersions(permission.versions(), target.version(), Instant.now());
            putPermission(updated);
            return updated;
        }
    }

    /**
     * The default version cannot be deleted, nor can a version still attached to a live share.
     *
     * @return the permission after the deletion
     */
    public RamPermission deletePermissionVersion(String callerAccountId, String permissionArn, int version) {
        synchronized (this) {
            RamPermission permission = requireCustomerManaged(permissionArn, callerAccountId,
                    "OperationNotPermittedException");
            PermissionVersion target = permission.findVersion(version)
                    .filter(v -> !v.deleted())
                    .orElseThrow(() -> new AwsException("InvalidParameterException",
                            "Version " + version + " of permission " + permissionArn + " does not exist.", 400));
            if (target.version() == permission.defaultVersion()) {
                throw new AwsException("OperationNotPermittedException",
                        "You can't delete the default version of a permission.", 400);
            }
            Instant now = Instant.now();
            List<PermissionVersion> versions = permission.versions().stream()
                    .map(v -> v.version() == version ? v.markDeleted(now) : v)
                    .toList();
            RamPermission updated = permission.withVersions(versions, permission.defaultVersion(), now);
            putPermission(updated);
            return updated;
        }
    }

    /** A permission attached to a live resource share cannot be deleted. */
    public void deletePermission(String callerAccountId, String permissionArn) {
        synchronized (this) {
            RamPermission permission = requireCustomerManaged(permissionArn, callerAccountId,
                    "OperationNotPermittedException");
            boolean attached = allShares().stream()
                    .filter(share -> !"DELETED".equals(share.getStatus()))
                    .anyMatch(share -> share.getPermissionArns().contains(permission.arn()));
            if (attached) {
                throw new AwsException("OperationNotPermittedException",
                        "Permission " + permissionArn + " is associated with a resource share.", 400);
            }
            if (permissions instanceof AccountAwareStorageBackend<RamPermission> accountAware) {
                accountAware.deleteForAccount(callerAccountId, permission.arn());
            } else {
                permissions.delete(permission.arn());
            }
        }
    }

    public void tagPermission(String permissionArn, Map<String, String> newTags, String callerAccountId) {
        synchronized (this) {
            RamPermission permission = requireTaggablePermission(permissionArn, callerAccountId);
            Map<String, String> merged = new LinkedHashMap<>(permission.tags());
            merged.putAll(newTags);
            putPermission(permission.withTags(merged));
        }
    }

    public void untagPermission(String permissionArn, List<String> tagKeys, String callerAccountId) {
        synchronized (this) {
            RamPermission permission = requireTaggablePermission(permissionArn, callerAccountId);
            Map<String, String> remaining = new LinkedHashMap<>(permission.tags());
            tagKeys.forEach(remaining::remove);
            putPermission(permission.withTags(remaining));
        }
    }

    private RamPermission requireTaggablePermission(String permissionArn, String callerAccountId) {
        requireValidArns(List.of(permissionArn));
        if (RamManagedPermissionCatalog.find(permissionArn).isPresent()) {
            throw new AwsException("InvalidParameterException",
                    "AWS managed permissions cannot be tagged.", 400);
        }
        return findOwnedPermission(permissionArn, callerAccountId)
                .orElseThrow(() -> new AwsException("ResourceArnNotFoundException",
                        "The resource " + permissionArn + " could not be found.", 400));
    }

    /**
     * Resolves a customer managed permission the caller owns. An AWS managed permission answers
     * with {@code awsManagedErrorCode}, since the caller can read but never mutate it.
     */
    private RamPermission requireCustomerManaged(String permissionArn, String callerAccountId,
                                                 String awsManagedErrorCode) {
        RamPermission permission = requirePermission(permissionArn, callerAccountId);
        if (!permission.isCustomerManaged()) {
            throw new AwsException(awsManagedErrorCode,
                    "AWS managed permission " + permissionArn + " cannot be modified.", 400);
        }
        return permission;
    }

    private RamPermission requirePermission(String permissionArn, String callerAccountId) {
        if (permissionArn == null || !isValidArn(permissionArn)) {
            throw new AwsException("MalformedArnException",
                    "The specified Amazon Resource Name (ARN) has a format that isn't valid: " + permissionArn, 400);
        }
        return findPermission(permissionArn, callerAccountId).orElseThrow(() -> unknownPermission(permissionArn));
    }

    private static AwsException unknownPermission(String permissionArn) {
        return new AwsException("UnknownResourceException",
                "Permission " + permissionArn + " could not be found.", 400);
    }

    private Optional<RamPermission> findPermission(String permissionArn, String callerAccountId) {
        return RamManagedPermissionCatalog.find(permissionArn)
                .or(() -> findOwnedPermission(permissionArn, callerAccountId));
    }

    private Optional<RamPermission> findOwnedPermission(String permissionArn, String callerAccountId) {
        return ownedPermissions(callerAccountId).stream()
                .filter(p -> p.arn().equals(permissionArn))
                .findFirst();
    }

    private List<RamPermission> ownedPermissions(String callerAccountId) {
        if (permissions instanceof AccountAwareStorageBackend<RamPermission> accountAware) {
            return accountAware.scanForAccount(callerAccountId, key -> true);
        }
        return permissions.scan(key -> true).stream()
                .filter(p -> callerAccountId.equals(p.ownerAccountId()))
                .toList();
    }

    private void putPermission(RamPermission permission) {
        if (permissions instanceof AccountAwareStorageBackend<RamPermission> accountAware) {
            accountAware.putForAccount(permission.ownerAccountId(), permission.arn(), permission);
            return;
        }
        permissions.put(permission.arn(), permission);
    }

    /**
     * A RAM policy template is a single statement body: {@code Effect} (only {@code Allow}),
     * {@code Action}, and an optional {@code Condition}. The share supplies Principal and
     * Resource, so a template naming them is malformed. Every action must belong to the
     * resource type's service.
     *
     * @return the template as submitted
     */
    private static String validatePolicyTemplate(String policyTemplate, String resourceType) {
        if (policyTemplate == null || policyTemplate.isBlank()) {
            throw new AwsException("MalformedPolicyTemplateException", "policyTemplate is required.", 400);
        }
        JsonNode template = parseTemplate(policyTemplate);
        if (!template.isObject()) {
            throw new AwsException("MalformedPolicyTemplateException",
                    "The policy template must be a JSON object.", 400);
        }
        for (Iterator<String> it = template.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!List.of("Effect", "Action", "Condition").contains(field)) {
                throw new AwsException("MalformedPolicyTemplateException",
                        "The policy template contains an unsupported element: " + field + ".", 400);
            }
        }
        if (template.has("Effect") && !"Allow".equals(template.path("Effect").asText())) {
            throw new AwsException("InvalidPolicyException",
                    "Managed permission policy templates only support Effect Allow.", 400);
        }
        JsonNode action = template.path("Action");
        List<String> actions = new ArrayList<>();
        if (action.isTextual()) {
            actions.add(action.asText());
        } else if (action.isArray()) {
            action.forEach(a -> actions.add(a.isTextual() ? a.asText() : ""));
        }
        if (actions.isEmpty()) {
            throw new AwsException("MalformedPolicyTemplateException",
                    "The policy template must specify at least one Action.", 400);
        }
        String service = resourceType.substring(0, resourceType.indexOf(':'));
        for (String a : actions) {
            if (!a.startsWith(service + ":") || a.length() == service.length() + 1) {
                throw new AwsException("InvalidPolicyException",
                        "Action " + a + " is not supported for resource type " + resourceType + ".", 400);
            }
        }
        if (template.has("Condition") && !template.get("Condition").isObject()) {
            throw new AwsException("MalformedPolicyTemplateException",
                    "The policy template Condition must be a JSON object.", 400);
        }
        return policyTemplate;
    }

    private static JsonNode parseTemplate(String policyTemplate) {
        try {
            return JSON.readTree(policyTemplate);
        } catch (JsonProcessingException e) {
            throw new AwsException("MalformedPolicyTemplateException",
                    "The policy template is not valid JSON.", 400);
        }
    }

    /**
     * Offset pagination over an already-ordered result. {@code maxResults} is 1-500 like RAM;
     * a token floci did not issue is InvalidNextTokenException.
     */
    public static <T> Page<T> paginate(List<T> all, Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_RESULTS_LIMIT)) {
            throw new AwsException("InvalidParameterException",
                    "maxResults must be between 1 and " + MAX_RESULTS_LIMIT + ".", 400);
        }
        int start = 0;
        if (nextToken != null && !nextToken.isEmpty()) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(nextToken),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw invalidNextToken();
            }
            if (start < 0 || start > all.size()) {
                throw invalidNextToken();
            }
        }
        int end = maxResults == null ? all.size() : (int) Math.min(all.size(), (long) start + maxResults);
        String next = end < all.size()
                ? Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(Integer.toString(end).getBytes(StandardCharsets.UTF_8))
                : null;
        return new Page<>(List.copyOf(all.subList(start, end)), next);
    }

    private static AwsException invalidNextToken() {
        return new AwsException("InvalidNextTokenException", "The specified value for nextToken isn't valid.", 400);
    }

    /**
     * @param resourceOwner {@code SELF} or {@code OTHER-ACCOUNTS}, same visibility rule as
     *                      {@link #getResourceShares}
     */
    public List<PrincipalAssociation> listPrincipals(String callerAccountId, String resourceOwner,
                                                      List<String> resourceShareArns) {
        requireResourceOwner(resourceOwner);
        List<PrincipalAssociation> result = new ArrayList<>();
        for (ResourceShare share : allShares()) {
            if (!isVisible(share, callerAccountId, resourceOwner)) {
                continue;
            }
            // A deleted share stays readable via GetResourceShares (status DELETED)
            // but its contents stop being consumable.
            if ("DELETED".equals(share.getStatus())) {
                continue;
            }
            if (!resourceShareArns.isEmpty() && !resourceShareArns.contains(share.getResourceShareArn())) {
                continue;
            }
            for (String principal : share.getPrincipals()) {
                result.add(new PrincipalAssociation(principal, share.getResourceShareArn(),
                        share.getCreationTime(), share.getLastUpdatedTime(),
                        isExternalPrincipal(share.getOwningAccountId(), principal)));
            }
        }
        return result;
    }

    public void tagResource(String resourceShareArn, Map<String, String> newTags, String callerAccountId) {
        ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
        Map<String, String> merged = new LinkedHashMap<>(share.getTags());
        merged.putAll(newTags);
        putForOwner(share.withTags(merged));
    }

    public void untagResource(String resourceShareArn, List<String> tagKeys, String callerAccountId) {
        ResourceShare share = requireOwnedShare(resourceShareArn, callerAccountId);
        Map<String, String> remaining = new LinkedHashMap<>(share.getTags());
        tagKeys.forEach(remaining::remove);
        putForOwner(share.withTags(remaining));
    }

    /**
     * Resolves a share the caller may mutate. A share owned by another account gets the same
     * UnknownResourceException as one that was never created: AWS resolves a share ARN within
     * the caller's own account, so a non-owner must not learn that the ARN exists, let alone
     * be able to rename, retag, or delete it.
     */
    private ResourceShare requireOwnedShare(String resourceShareArn, String callerAccountId) {
        return findOwnedShare(resourceShareArn, callerAccountId)
                .orElseThrow(() -> new AwsException("UnknownResourceException",
                        "ResourceShare " + resourceShareArn + " does not exist.", 400));
    }

    private Optional<ResourceShare> findOwnedShare(String resourceShareArn, String callerAccountId) {
        return allShares().stream()
                .filter(share -> share.getResourceShareArn().equals(resourceShareArn))
                .filter(share -> share.getOwningAccountId().equals(callerAccountId))
                // DELETED is terminal: the share stays readable via GetResourceShares for the
                // retention window, but mutations resolve it like an ARN that never existed.
                .filter(share -> !"DELETED".equals(share.getStatus()))
                .findFirst();
    }

    private static List<String> mergeDistinct(List<String> existing, List<String> additions) {
        Set<String> merged = new LinkedHashSet<>(existing);
        merged.addAll(additions);
        return List.copyOf(merged);
    }

    private static List<String> withoutAll(List<String> existing, List<String> removals) {
        List<String> remaining = new ArrayList<>(existing);
        remaining.removeAll(removals);
        return List.copyOf(remaining);
    }

    /**
     * The single write seam: every create and every mutation lands here, so stamping
     * lastUpdatedTime once at this point keeps it advancing without six call sites having to
     * remember to do it. Returns the stamped share so callers respond with what was stored.
     */
    private ResourceShare putForOwner(ResourceShare share) {
        ResourceShare stamped = share.withLastUpdatedTime(Instant.now());
        if (shares instanceof AccountAwareStorageBackend<ResourceShare> accountAware) {
            accountAware.putForAccount(
                    stamped.getOwningAccountId(), stamped.getResourceShareArn(), stamped);
            return stamped;
        }
        shares.put(stamped.getResourceShareArn(), stamped);
        return stamped;
    }

    private List<ResourceShare> allShares() {
        if (shares instanceof AccountAwareStorageBackend<ResourceShare> accountAware) {
            return accountAware.scanAllAccounts();
        }
        return shares.scan(key -> true);
    }

    /**
     * The model enumerates {@code resourceOwner} as {@code SELF} or {@code OTHER-ACCOUNTS} on every
     * read operation that takes it. The visibility fork below is a two-way branch, so an unmodelled
     * value silently means OTHER-ACCOUNTS: a caller who sent {@code "self"} would be handed every
     * share they do NOT own. AWS answers InvalidParameterException, which all three operations list.
     *
     * <p>It is also a required member, so a null one is rejected rather than defaulted: guessing
     * SELF answers a question the caller never asked, with the caller's own shares.
     */
    /** {@code ResourceShareStatus} is optional on GetResourceShares, but enumerated when sent. */
    private static void requireResourceShareStatus(String resourceShareStatus) {
        if (resourceShareStatus != null && !SHARE_STATUSES.contains(resourceShareStatus)) {
            throw new AwsException("InvalidParameterException",
                    "resourceShareStatus must be one of " + SHARE_STATUSES + ".", 400);
        }
    }

    private static void requireResourceOwner(String resourceOwner) {
        if (resourceOwner == null) {
            throw new AwsException("InvalidParameterException",
                    "resourceOwner is required and must be one of [SELF, OTHER-ACCOUNTS].", 400);
        }
        if (!"SELF".equals(resourceOwner) && !"OTHER-ACCOUNTS".equals(resourceOwner)) {
            throw new AwsException("InvalidParameterException",
                    "resourceOwner must be one of [SELF, OTHER-ACCOUNTS].", 400);
        }
    }

    private boolean isVisible(ResourceShare share, String callerAccountId, String resourceOwner) {
        boolean owned = share.getOwningAccountId().equals(callerAccountId);
        if ("SELF".equals(resourceOwner)) {
            return owned;
        }
        if (owned) {
            return false;
        }
        return share.getPrincipals().stream()
                .anyMatch(principal -> isVisibleToPrincipal(share, principal, callerAccountId));
    }

    private boolean isVisibleToPrincipal(ResourceShare share, String principal, String callerAccountId) {
        if (ACCOUNT_ID_PRINCIPAL.matcher(principal).matches()) {
            if (!principal.equals(callerAccountId)) {
                return false;
            }
            // A rejected or removed invitation is no longer an authorization to discover the
            // share. PENDING remains visible because RAM exposes the invitation's share metadata
            // before the receiver accepts it.
            boolean hasLiveInvitation = allInvitations().stream()
                    .anyMatch(candidate -> candidate.resourceShareArn().equals(share.getResourceShareArn())
                            && candidate.receiverAccountId().equals(callerAccountId)
                            && ("PENDING".equals(candidate.status())
                            || "ACCEPTED".equals(candidate.status())));
            if (hasLiveInvitation) {
                return true;
            }
            // Organization sharing auto-accepts account principals without persisting an
            // invitation. Restrict that path to the owner's organization, never any organization.
            return isSharingWithOrganizationEnabled(share.getOwningAccountId())
                    && isMemberOfSameOrganization(share.getOwningAccountId(), callerAccountId);
        }

        return isOrganizationPrincipalVisible(principal, callerAccountId);
    }

    private boolean isOrganizationPrincipalVisible(String principal, String callerAccountId) {
        if (organizationsService == null) {
            return false;
        }
        String[] arn = principal.split(":", 6);
        if (arn.length != 6 || !"aws".equals(arn[1]) || !"organizations".equals(arn[2])) {
            return false;
        }
        String[] resource = arn[5].split("/");
        if (resource.length < 2) {
            return false;
        }
        Organization organization = findOrganizationForCaller(callerAccountId);
        if (organization == null) {
            return false;
        }
        if (!organization.getId().equals(resource[1])) {
            return false;
        }
        if ("organization".equals(resource[0])) {
            return resource.length == 2;
        }
        if (!"ou".equals(resource[0]) || resource.length != 3) {
            return false;
        }
        try {
            String path = organizationsService.organizationPath(callerAccountId, callerAccountId);
            return List.of(path.split("/")).contains(resource[2]);
        } catch (AwsException e) {
            return false;
        }
    }

    private boolean isMemberOfSameOrganization(String ownerAccountId, String callerAccountId) {
        Organization ownerOrganization = findOrganizationForCaller(ownerAccountId);
        Organization callerOrganization = findOrganizationForCaller(callerAccountId);
        return ownerOrganization != null && callerOrganization != null
                && ownerOrganization.getId().equals(callerOrganization.getId());
    }

    private Organization findOrganizationForCaller(String callerAccountId) {
        if (organizationsService == null) {
            return null;
        }
        try {
            return organizationsService.describeOrganization(callerAccountId);
        } catch (AwsException e) {
            return null;
        }
    }

    /** {@code arn:aws:ec2:...:transit-gateway/tgw-1} → {@code ec2:TransitGateway}. */
    private static String ramResourceType(String resourceArn) {
        String[] parts = resourceArn.split(":", 6);
        if (parts.length < 6) {
            return "";
        }
        String service = parts[2];
        String resource = parts[5];
        int slash = resource.indexOf('/');
        String typeSegment = slash >= 0 ? resource.substring(0, slash) : resource;
        StringBuilder camel = new StringBuilder();
        for (String word : typeSegment.split("-")) {
            if (!word.isEmpty()) {
                camel.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return service + ":" + camel;
    }
}
