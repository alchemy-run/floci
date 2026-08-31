package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ram.model.RamAssociation;
import io.github.hectorvent.floci.services.ram.model.RamInvitation;
import io.github.hectorvent.floci.services.ram.model.RamPermission;
import io.github.hectorvent.floci.services.ram.model.RamPermissionVersion;
import io.github.hectorvent.floci.services.ram.model.RamResourceShare;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS RAM restJson1 — resource shares (Alchemy {@code ResourceShare}) and
 * customer-managed permissions (Alchemy {@code Permission}).
 */
@ApplicationScoped
public class RamService implements Resettable {

    static final String SERVICE = "ram";
    static final String STATUS_ATTACHABLE = "ATTACHABLE";
    static final String STATUS_DELETED = "DELETED";
    static final String TYPE_CUSTOMER_MANAGED = "CUSTOMER_MANAGED";
    static final String TYPE_AWS_MANAGED = "AWS_MANAGED";
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 500;
    private static final int MAX_VERSIONS = 5;
    private static final String TOKEN_PREFIX = "ram:v1:";
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\w+=,.@-]{1,36}");
    private static final Pattern SHARE_ARN = Pattern.compile(
            "^arn:aws:ram:[a-z0-9-]+:\\d{12}:resource-share/[0-9a-fA-F-]{36}$");
    private static final Pattern INVITATION_ARN = Pattern.compile(
            "^arn:aws:ram:[a-z0-9-]+:\\d{12}:resource-share-invitation/[0-9a-fA-F-]{36}$");
    private static final Pattern ACCOUNT_ID = Pattern.compile("^\\d{12}$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<RamPermission> AWS_MANAGED = awsManagedPermissions();

    private final StorageBackend<String, RamResourceShare> shares;
    private final StorageBackend<String, RamInvitation> invitations;
    private final StorageBackend<String, RamPermission> store;
    private final RegionResolver regionResolver;

    @Inject
    public RamService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(SERVICE, "ram-shares.json",
                        new TypeReference<Map<String, RamResourceShare>>() {
                        }),
                storageFactory.create(SERVICE, "ram-invitations.json",
                        new TypeReference<Map<String, RamInvitation>>() {
                        }),
                storageFactory.create(SERVICE, "ram-permissions.json",
                        new TypeReference<Map<String, RamPermission>>() {
                        }),
                regionResolver);
    }

    RamService(StorageBackend<String, RamPermission> store, RegionResolver regionResolver) {
        this(new InMemoryStorage<>(), new InMemoryStorage<>(), store, regionResolver);
    }

    RamService(
            StorageBackend<String, RamResourceShare> shares,
            StorageBackend<String, RamInvitation> invitations,
            StorageBackend<String, RamPermission> store,
            RegionResolver regionResolver) {
        this.shares = shares;
        this.invitations = invitations;
        this.store = store;
        this.regionResolver = regionResolver;
    }

    public synchronized RamResourceShare createResourceShare(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        if (name.length() > 128) {
            throw invalidParameter("name must be 128 characters or fewer.");
        }
        if (findLiveByName(region, name).isPresent()) {
            throw invalidParameter("A resource share with the specified name already exists.");
        }
        long now = epochSeconds();
        String id = UUID.randomUUID().toString();
        String account = regionResolver.getAccountId();
        RamResourceShare share = new RamResourceShare();
        share.setArn("arn:aws:ram:" + region + ":" + account + ":resource-share/" + id);
        share.setName(name);
        share.setRegion(region);
        share.setOwningAccountId(account);
        share.setAllowExternalPrincipals(boolOr(request, "allowExternalPrincipals", true));
        share.setStatus("ACTIVE");
        share.setFeatureSet("STANDARD");
        share.setCreationTime(now);
        share.setLastUpdatedTime(now);
        share.setTags(readShareTags(request));
        share.setPermissionArns(stringList(request, "permissionArns"));
        for (String principal : stringList(request, "principals")) {
            associatePrincipal(share, principal, now);
        }
        for (String resourceArn : stringList(request, "resourceArns")) {
            associateResource(share, resourceArn, now);
        }
        for (String source : stringList(request, "sources")) {
            associateSource(share, source, now);
        }
        shares.put(share.getArn(), share);
        return share;
    }

    public Page<RamResourceShare> getResourceShares(String region, JsonNode request) {
        requireObject(request, "Request body");
        String owner = requireText(request, "resourceOwner");
        int maxResults = parseMaxResults(request);
        String nextToken = textOrNull(request, "nextToken");
        List<String> arns = stringList(request, "resourceShareArns");
        String name = textOrNull(request, "name");
        String status = textOrNull(request, "resourceShareStatus");
        List<RamResourceShare> items = new ArrayList<>();
        if ("OTHER-ACCOUNTS".equals(owner)) {
            return page(items, maxResults, nextToken);
        }
        if (!"SELF".equals(owner)) {
            throw invalidParameter("resourceOwner must be SELF or OTHER-ACCOUNTS.");
        }
        for (RamResourceShare share : shares.values()) {
            if (!region.equals(share.getRegion())) {
                continue;
            }
            if ("DELETED".equals(share.getStatus()) || "DELETING".equals(share.getStatus())) {
                continue;
            }
            if (name != null && !name.equals(share.getName())) {
                continue;
            }
            if (status != null && !status.equals(share.getStatus())) {
                continue;
            }
            if (!arns.isEmpty() && !arns.contains(share.getArn())) {
                continue;
            }
            if (!matchesTagFilters(share, request)) {
                continue;
            }
            items.add(share);
        }
        if (!arns.isEmpty() && items.isEmpty()) {
            throw unknown("ResourceShare", arns.get(0));
        }
        items.sort(Comparator.comparing(RamResourceShare::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, maxResults, nextToken);
    }

    public synchronized RamResourceShare updateResourceShare(String region, JsonNode request) {
        requireObject(request, "Request body");
        RamResourceShare share = requireShare(region, requireText(request, "resourceShareArn"));
        String name = textOrNull(request, "name");
        if (name != null) {
            Optional<RamResourceShare> existing = findLiveByName(region, name);
            if (existing.isPresent() && !existing.get().getArn().equals(share.getArn())) {
                throw invalidParameter("A resource share with the specified name already exists.");
            }
            share.setName(name);
            for (RamAssociation association : share.getAssociations()) {
                association.setResourceShareName(name);
            }
        }
        if (request.has("allowExternalPrincipals") && !request.get("allowExternalPrincipals").isNull()) {
            share.setAllowExternalPrincipals(request.get("allowExternalPrincipals").asBoolean());
        }
        share.setLastUpdatedTime(epochSeconds());
        shares.put(share.getArn(), share);
        return share;
    }

    public synchronized void deleteResourceShare(String region, String resourceShareArn) {
        if (resourceShareArn == null || resourceShareArn.isBlank()) {
            throw missing("resourceShareArn");
        }
        RamResourceShare share = requireShare(region, resourceShareArn);
        invitations.values().stream()
                .filter(invitation -> resourceShareArn.equals(invitation.getResourceShareArn()))
                .forEach(invitation -> invitations.delete(invitation.getArn()));
        shares.delete(share.getArn());
    }

    public synchronized List<RamAssociation> associateResourceShare(String region, JsonNode request) {
        requireObject(request, "Request body");
        RamResourceShare share = requireShare(region, requireText(request, "resourceShareArn"));
        long now = epochSeconds();
        List<RamAssociation> changed = new ArrayList<>();
        for (String principal : stringList(request, "principals")) {
            changed.add(associatePrincipal(share, principal, now));
        }
        for (String resourceArn : stringList(request, "resourceArns")) {
            changed.add(associateResource(share, resourceArn, now));
        }
        for (String source : stringList(request, "sources")) {
            changed.add(associateSource(share, source, now));
        }
        share.setLastUpdatedTime(now);
        shares.put(share.getArn(), share);
        return changed;
    }

    public synchronized List<RamAssociation> disassociateResourceShare(String region, JsonNode request) {
        requireObject(request, "Request body");
        RamResourceShare share = requireShare(region, requireText(request, "resourceShareArn"));
        long now = epochSeconds();
        List<String> principals = stringList(request, "principals");
        List<String> resources = stringList(request, "resourceArns");
        List<String> sources = stringList(request, "sources");
        List<RamAssociation> removed = new ArrayList<>();
        share.getAssociations().removeIf(association -> {
            boolean match = matchDisassociate(association, principals, resources, sources);
            if (match) {
                association.setStatus("DISASSOCIATED");
                association.setLastUpdatedTime(now);
                removed.add(association);
            }
            return match;
        });
        share.setLastUpdatedTime(now);
        shares.put(share.getArn(), share);
        return removed;
    }

    public Page<RamAssociation> getResourceShareAssociations(String region, JsonNode request) {
        requireObject(request, "Request body");
        String type = requireText(request, "associationType");
        if (!"PRINCIPAL".equals(type) && !"RESOURCE".equals(type) && !"SOURCE".equals(type)) {
            throw invalidParameter("associationType must be PRINCIPAL, RESOURCE, or SOURCE.");
        }
        List<String> arns = stringList(request, "resourceShareArns");
        String resourceArn = textOrNull(request, "resourceArn");
        String principal = textOrNull(request, "principal");
        String status = textOrNull(request, "associationStatus");
        List<RamAssociation> items = new ArrayList<>();
        for (RamResourceShare share : sharesInRegion(region)) {
            if (!arns.isEmpty() && !arns.contains(share.getArn())) {
                continue;
            }
            for (RamAssociation association : share.getAssociations()) {
                if (!type.equals(association.getAssociationType())) {
                    continue;
                }
                if (status != null && !status.equals(association.getStatus())) {
                    continue;
                }
                if (resourceArn != null && !resourceArn.equals(association.getAssociatedEntity())) {
                    continue;
                }
                if (principal != null && !principal.equals(association.getAssociatedEntity())) {
                    continue;
                }
                items.add(association);
            }
        }
        items.sort(Comparator.comparing(RamAssociation::getAssociatedEntity,
                Comparator.nullsLast(String::compareTo)));
        return page(items, parseMaxResults(request), textOrNull(request, "nextToken"));
    }

    public Page<RamInvitation> getResourceShareInvitations(String region, JsonNode request) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? null
                : request;
        List<String> invitationArns = stringList(body, "resourceShareInvitationArns");
        List<String> shareArns = stringList(body, "resourceShareArns");
        String account = regionResolver.getAccountId();
        List<RamInvitation> items = new ArrayList<>();
        for (RamInvitation invitation : invitations.values()) {
            if (!invitation.getArn().contains(":" + region + ":")) {
                continue;
            }
            if (!account.equals(invitation.getSenderAccountId())
                    && !account.equals(invitation.getReceiverAccountId())) {
                continue;
            }
            if (!invitationArns.isEmpty() && !invitationArns.contains(invitation.getArn())) {
                continue;
            }
            if (!shareArns.isEmpty() && !shareArns.contains(invitation.getResourceShareArn())) {
                continue;
            }
            items.add(invitation);
        }
        if (!invitationArns.isEmpty() && items.isEmpty()) {
            requireInvitationArn(invitationArns.get(0));
            throw invitationNotFound(invitationArns.get(0));
        }
        items.sort(Comparator.comparing(RamInvitation::getArn));
        return page(items, parseMaxResults(body), textOrNull(body, "nextToken"));
    }

    public synchronized RamInvitation acceptInvitation(String region, JsonNode request) {
        RamInvitation invitation = requirePendingInvitation(request);
        invitation.setStatus("ACCEPTED");
        invitations.put(invitation.getArn(), invitation);
        shares.get(invitation.getResourceShareArn()).ifPresent(share -> {
            for (RamAssociation association : share.getAssociations()) {
                if ("PRINCIPAL".equals(association.getAssociationType())
                        && invitation.getReceiverAccountId().equals(association.getAssociatedEntity())) {
                    association.setStatus("ASSOCIATED");
                    association.setLastUpdatedTime(epochSeconds());
                }
            }
            shares.put(share.getArn(), share);
        });
        return invitation;
    }

    public synchronized RamInvitation rejectInvitation(String region, JsonNode request) {
        RamInvitation invitation = requirePendingInvitation(request);
        invitation.setStatus("REJECTED");
        invitations.put(invitation.getArn(), invitation);
        return invitation;
    }

    public Page<RamAssociation> listPendingInvitationResources(String region, JsonNode request) {
        requireObject(request, "Request body");
        RamInvitation invitation = requireInvitation(requireText(request, "resourceShareInvitationArn"));
        RamResourceShare share = shares.get(invitation.getResourceShareArn()).orElse(null);
        List<RamAssociation> items = new ArrayList<>();
        if (share != null) {
            for (RamAssociation association : share.getAssociations()) {
                if ("RESOURCE".equals(association.getAssociationType())
                        && ("ASSOCIATED".equals(association.getStatus())
                                || "ASSOCIATING".equals(association.getStatus()))) {
                    items.add(association);
                }
            }
        }
        return page(items, parseMaxResults(request), textOrNull(request, "nextToken"));
    }

    public Page<RamAssociation> listResources(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "resourceOwner");
        List<String> shareArns = stringList(request, "resourceShareArns");
        List<String> resourceArns = stringList(request, "resourceArns");
        String resourceType = textOrNull(request, "resourceType");
        List<RamAssociation> items = new ArrayList<>();
        for (RamResourceShare share : sharesInRegion(region)) {
            if (!shareArns.isEmpty() && !shareArns.contains(share.getArn())) {
                continue;
            }
            for (RamAssociation association : share.getAssociations()) {
                if (!"RESOURCE".equals(association.getAssociationType())) {
                    continue;
                }
                if (!"ASSOCIATED".equals(association.getStatus())
                        && !"ASSOCIATING".equals(association.getStatus())) {
                    continue;
                }
                if (!resourceArns.isEmpty() && !resourceArns.contains(association.getAssociatedEntity())) {
                    continue;
                }
                if (resourceType != null && !resourceType.equals(association.getResourceType())) {
                    continue;
                }
                items.add(association);
            }
        }
        items.sort(Comparator.comparing(RamAssociation::getAssociatedEntity,
                Comparator.nullsLast(String::compareTo)));
        return page(items, parseMaxResults(request), textOrNull(request, "nextToken"));
    }

    public Page<RamAssociation> listPrincipals(String region, JsonNode request) {
        requireObject(request, "Request body");
        requireText(request, "resourceOwner");
        List<String> shareArns = stringList(request, "resourceShareArns");
        List<String> principals = stringList(request, "principals");
        List<RamAssociation> items = new ArrayList<>();
        for (RamResourceShare share : sharesInRegion(region)) {
            if (!shareArns.isEmpty() && !shareArns.contains(share.getArn())) {
                continue;
            }
            for (RamAssociation association : share.getAssociations()) {
                if (!"PRINCIPAL".equals(association.getAssociationType())) {
                    continue;
                }
                if (!"ASSOCIATED".equals(association.getStatus())
                        && !"ASSOCIATING".equals(association.getStatus())) {
                    continue;
                }
                if (!principals.isEmpty() && !principals.contains(association.getAssociatedEntity())) {
                    continue;
                }
                items.add(association);
            }
        }
        items.sort(Comparator.comparing(RamAssociation::getAssociatedEntity,
                Comparator.nullsLast(String::compareTo)));
        return page(items, parseMaxResults(request), textOrNull(request, "nextToken"));
    }

    public List<String> getResourcePolicies(JsonNode request) {
        requireObject(request, "Request body");
        List<String> resourceArns = stringList(request, "resourceArns");
        if (resourceArns.isEmpty()) {
            throw missing("resourceArns");
        }
        List<String> policies = new ArrayList<>();
        for (String resourceArn : resourceArns) {
            for (RamResourceShare share : shares.values()) {
                boolean attached = share.getAssociations().stream()
                        .anyMatch(association -> "RESOURCE".equals(association.getAssociationType())
                                && resourceArn.equals(association.getAssociatedEntity())
                                && ("ASSOCIATED".equals(association.getStatus())
                                        || "ASSOCIATING".equals(association.getStatus())));
                if (attached) {
                    policies.add(sharePolicy(share, resourceArn));
                    break;
                }
            }
        }
        return policies;
    }

    public synchronized RamPermission createPermission(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "name");
        validateName(name);
        String resourceType = requireText(request, "resourceType");
        String policyTemplate = requirePolicy(request);
        Map<String, String> tags = readPermissionTags(request);
        if (findByName(region, name) != null) {
            throw new AwsException(
                    "PermissionAlreadyExistsException",
                    "A permission with that name already exists.",
                    409);
        }
        long now = epochSeconds();
        String id = UUID.randomUUID().toString();
        RamPermissionVersion version = newVersion(1, policyTemplate, now);
        RamPermission permission = new RamPermission();
        permission.setId(id);
        permission.setName(name);
        permission.setResourceType(resourceType);
        permission.setPermissionType(TYPE_CUSTOMER_MANAGED);
        permission.setFeatureSet("STANDARD");
        permission.setDefaultVersion(1);
        permission.setTags(tags);
        permission.setVersions(List.of(version));
        permission.setCreationTime(now);
        permission.setLastUpdatedTime(now);
        permission.setArn(arn(region, "permission/" + name + "/" + id));
        store.put(storageKey(region, permission.getArn()), permission);
        return permission;
    }

    public RamPermission getPermission(JsonNode request) {
        return getPermission(null, request);
    }

    public RamPermission getPermission(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "permissionArn");
        for (RamPermission permission : AWS_MANAGED) {
            if (arn.equals(permission.getArn())) {
                return permission;
            }
        }
        if (region != null) {
            RamPermission permission = requirePermission(region, arn);
            Integer requested = optionalInt(request, "permissionVersion");
            if (requested != null) {
                requireVersion(permission, requested);
            }
            return permission;
        }
        return store.values().stream()
                .filter(permission -> arn.equals(permission.getArn()))
                .findFirst()
                .orElseThrow(() -> unknown(arn));
    }

    public Page<RamPermission> listPermissions(JsonNode request) {
        return listPermissions(null, request);
    }

    public Page<RamPermission> listPermissions(String region, JsonNode request) {
        JsonNode body = request == null || request.isNull() || request.isMissingNode()
                ? null
                : request;
        String permissionType = textOrNull(body, "permissionType");
        String resourceType = textOrNull(body, "resourceType");
        List<RamPermission> items = new ArrayList<>();
        if (permissionType == null || "ALL".equals(permissionType) || TYPE_AWS_MANAGED.equals(permissionType)) {
            items.addAll(AWS_MANAGED);
        }
        if (permissionType == null || "ALL".equals(permissionType) || TYPE_CUSTOMER_MANAGED.equals(permissionType)) {
            if (region == null) {
                items.addAll(store.values());
            } else {
                items.addAll(store.scan(key -> key.startsWith(region + "::")));
            }
        }
        if (resourceType != null) {
            items.removeIf(permission -> !resourceType.equals(permission.getResourceType()));
        }
        items.sort(Comparator.comparing(RamPermission::getName, Comparator.nullsLast(String::compareTo)));
        return page(items, parseMaxResults(body), textOrNull(body, "nextToken"));
    }

    public Page<RamPermissionVersion> listPermissionVersions(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "permissionArn");
        RamPermission permission = requirePermission(region, arn);
        List<RamPermissionVersion> versions = new ArrayList<>(permission.getVersions());
        versions.sort(Comparator.comparingInt(RamPermissionVersion::getVersion));
        return page(versions, parseMaxResults(request), textOrNull(request, "nextToken"));
    }

    public synchronized RamPermission createPermissionVersion(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = requireText(request, "permissionArn");
        String policyTemplate = requirePolicy(request);
        RamPermission permission = requirePermission(region, arn);
        long live = permission.getVersions().stream().filter(RamService::isLive).count();
        if (live >= MAX_VERSIONS) {
            throw new AwsException(
                    "PermissionVersionsLimitExceededException",
                    "A permission can have at most " + MAX_VERSIONS + " versions.",
                    400);
        }
        int next = permission.getVersions().stream()
                .mapToInt(RamPermissionVersion::getVersion)
                .max()
                .orElse(0) + 1;
        long now = epochSeconds();
        List<RamPermissionVersion> versions = new ArrayList<>(permission.getVersions());
        versions.add(newVersion(next, policyTemplate, now));
        permission.setVersions(versions);
        permission.setDefaultVersion(next);
        permission.setLastUpdatedTime(now);
        store.put(storageKey(region, permission.getArn()), permission);
        return permission;
    }

    public synchronized RamPermission deletePermissionVersion(
            String region, String permissionArn, Integer permissionVersion) {
        if (permissionArn == null || permissionArn.isBlank()) {
            throw missing("permissionArn");
        }
        if (permissionVersion == null) {
            throw missing("permissionVersion");
        }
        RamPermission permission = requirePermission(region, permissionArn);
        RamPermissionVersion version = requireVersion(permission, permissionVersion);
        if (version.getVersion() == permission.getDefaultVersion()) {
            throw new AwsException(
                    "OperationNotPermittedException",
                    "The default version of a permission cannot be deleted.",
                    400);
        }
        if (!isLive(version)) {
            throw unknown(permissionArn);
        }
        long now = epochSeconds();
        version.setStatus(STATUS_DELETED);
        version.setLastUpdatedTime(now);
        permission.setLastUpdatedTime(now);
        store.put(storageKey(region, permission.getArn()), permission);
        return permission;
    }

    public synchronized RamPermission deletePermission(String region, String permissionArn) {
        if (permissionArn == null || permissionArn.isBlank()) {
            throw missing("permissionArn");
        }
        RamPermission permission = requirePermission(region, permissionArn);
        store.delete(storageKey(region, permission.getArn()));
        return permission;
    }

    public synchronized RamPermission tagResource(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = resourceArn(request);
        if (isShareArn(arn)) {
            RamResourceShare share = requireShare(region, arn);
            Map<String, String> tags = new LinkedHashMap<>(share.getTags());
            tags.putAll(readShareTags(request));
            share.setTags(tags);
            share.setLastUpdatedTime(epochSeconds());
            shares.put(share.getArn(), share);
            return null;
        }
        RamPermission permission = requirePermission(region, arn);
        Map<String, String> tags = new LinkedHashMap<>(permission.getTags());
        tags.putAll(readPermissionTags(request));
        permission.setTags(tags);
        permission.setLastUpdatedTime(epochSeconds());
        store.put(storageKey(region, permission.getArn()), permission);
        return permission;
    }

    public synchronized RamPermission untagResource(String region, JsonNode request) {
        requireObject(request, "Request body");
        String arn = resourceArn(request);
        if (isShareArn(arn)) {
            RamResourceShare share = requireShare(region, arn);
            Map<String, String> tags = new LinkedHashMap<>(share.getTags());
            for (String key : stringList(request, "tagKeys")) {
                tags.remove(key);
            }
            share.setTags(tags);
            share.setLastUpdatedTime(epochSeconds());
            shares.put(share.getArn(), share);
            return null;
        }
        RamPermission permission = requirePermission(region, arn);
        Map<String, String> tags = new LinkedHashMap<>(permission.getTags());
        for (String key : readTagKeys(request)) {
            tags.remove(key);
        }
        permission.setTags(tags);
        permission.setLastUpdatedTime(epochSeconds());
        store.put(storageKey(region, permission.getArn()), permission);
        return permission;
    }

    public RamPermissionVersion defaultVersion(RamPermission permission) {
        return requireVersion(permission, permission.getDefaultVersion());
    }

    public RamPermissionVersion requireVersion(RamPermission permission, int versionNumber) {
        return permission.getVersions().stream()
                .filter(version -> version.getVersion() == versionNumber)
                .findFirst()
                .orElseThrow(() -> unknown(permission.getArn()));
    }

    @Override
    public void clear() {
        shares.clear();
        invitations.clear();
        store.clear();
    }

    private RamInvitation requirePendingInvitation(JsonNode request) {
        requireObject(request, "Request body");
        RamInvitation invitation = requireInvitation(requireText(request, "resourceShareInvitationArn"));
        if (!"PENDING".equals(invitation.getStatus())) {
            throw new AwsException("InvalidStateTransitionException",
                    "The invitation is not pending.", 400);
        }
        return invitation;
    }

    private RamInvitation requireInvitation(String arn) {
        requireInvitationArn(arn);
        return invitations.get(arn).orElseThrow(() -> invitationNotFound(arn));
    }

    private void requireInvitationArn(String arn) {
        if (arn == null || !INVITATION_ARN.matcher(arn).matches()) {
            throw new AwsException("MalformedArnException",
                    "The specified resource share invitation ARN is not valid.", 400);
        }
    }

    private RamResourceShare requireShare(String region, String arn) {
        if (arn == null || !SHARE_ARN.matcher(arn).matches()) {
            throw new AwsException("MalformedArnException",
                    "The specified resource share ARN is not valid.", 400);
        }
        RamResourceShare share = shares.get(arn).orElseThrow(() -> unknown("ResourceShare", arn));
        if (!region.equals(share.getRegion())) {
            throw unknown("ResourceShare", arn);
        }
        if ("DELETED".equals(share.getStatus())) {
            throw unknown("ResourceShare", arn);
        }
        return share;
    }

    private Optional<RamResourceShare> findLiveByName(String region, String name) {
        return shares.values().stream()
                .filter(share -> region.equals(share.getRegion()))
                .filter(share -> name.equals(share.getName()))
                .filter(share -> !"DELETED".equals(share.getStatus()) && !"DELETING".equals(share.getStatus()))
                .findFirst();
    }

    private List<RamResourceShare> sharesInRegion(String region) {
        return shares.values().stream()
                .filter(share -> region.equals(share.getRegion()))
                .filter(share -> !"DELETED".equals(share.getStatus()) && !"DELETING".equals(share.getStatus()))
                .toList();
    }

    private RamAssociation associatePrincipal(RamResourceShare share, String principal, long now) {
        Optional<RamAssociation> existing = share.getAssociations().stream()
                .filter(association -> "PRINCIPAL".equals(association.getAssociationType())
                        && principal.equals(association.getAssociatedEntity()))
                .findFirst();
        if (existing.isPresent()) {
            RamAssociation association = existing.get();
            association.setStatus("ASSOCIATED");
            association.setLastUpdatedTime(now);
            return association;
        }
        boolean owner = principal.equals(share.getOwningAccountId());
        boolean external = !owner && ACCOUNT_ID.matcher(principal).matches();
        RamAssociation association = principalAssociation(share, principal, external, now);
        if (external && share.isAllowExternalPrincipals()) {
            association.setStatus("ASSOCIATING");
            createInvitation(share, principal, now);
        }
        share.getAssociations().add(association);
        return association;
    }

    private RamAssociation associateResource(RamResourceShare share, String resourceArn, long now) {
        Optional<RamAssociation> existing = share.getAssociations().stream()
                .filter(association -> "RESOURCE".equals(association.getAssociationType())
                        && resourceArn.equals(association.getAssociatedEntity()))
                .findFirst();
        if (existing.isPresent()) {
            existing.get().setStatus("ASSOCIATED");
            existing.get().setLastUpdatedTime(now);
            return existing.get();
        }
        RamAssociation association = new RamAssociation();
        association.setResourceShareArn(share.getArn());
        association.setResourceShareName(share.getName());
        association.setAssociatedEntity(resourceArn);
        association.setAssociationType("RESOURCE");
        association.setStatus("ASSOCIATED");
        association.setCreationTime(now);
        association.setLastUpdatedTime(now);
        association.setExternal(false);
        association.setResourceType(resourceTypeFromArn(resourceArn));
        share.getAssociations().add(association);
        return association;
    }

    private RamAssociation associateSource(RamResourceShare share, String source, long now) {
        Optional<RamAssociation> existing = share.getAssociations().stream()
                .filter(association -> "SOURCE".equals(association.getAssociationType())
                        && source.equals(association.getAssociatedEntity()))
                .findFirst();
        if (existing.isPresent()) {
            existing.get().setStatus("ASSOCIATED");
            existing.get().setLastUpdatedTime(now);
            return existing.get();
        }
        RamAssociation association = new RamAssociation();
        association.setResourceShareArn(share.getArn());
        association.setResourceShareName(share.getName());
        association.setAssociatedEntity(source);
        association.setAssociationType("SOURCE");
        association.setStatus("ASSOCIATED");
        association.setCreationTime(now);
        association.setLastUpdatedTime(now);
        association.setExternal(false);
        share.getAssociations().add(association);
        return association;
    }

    private RamAssociation principalAssociation(
            RamResourceShare share, String principal, boolean external, long now) {
        RamAssociation association = new RamAssociation();
        association.setResourceShareArn(share.getArn());
        association.setResourceShareName(share.getName());
        association.setAssociatedEntity(principal);
        association.setAssociationType("PRINCIPAL");
        association.setStatus("ASSOCIATED");
        association.setCreationTime(now);
        association.setLastUpdatedTime(now);
        association.setExternal(external);
        return association;
    }

    private void createInvitation(RamResourceShare share, String principal, long now) {
        String id = UUID.randomUUID().toString();
        RamInvitation invitation = new RamInvitation();
        invitation.setArn("arn:aws:ram:" + share.getRegion() + ":" + share.getOwningAccountId()
                + ":resource-share-invitation/" + id);
        invitation.setResourceShareArn(share.getArn());
        invitation.setResourceShareName(share.getName());
        invitation.setSenderAccountId(share.getOwningAccountId());
        invitation.setReceiverAccountId(principal);
        invitation.setInvitationTimestamp(now);
        invitation.setStatus("PENDING");
        invitations.put(invitation.getArn(), invitation);
    }

    private static boolean matchDisassociate(
            RamAssociation association,
            List<String> principals,
            List<String> resources,
            List<String> sources) {
        if ("PRINCIPAL".equals(association.getAssociationType())
                && principals.contains(association.getAssociatedEntity())) {
            return true;
        }
        if ("RESOURCE".equals(association.getAssociationType())
                && resources.contains(association.getAssociatedEntity())) {
            return true;
        }
        return "SOURCE".equals(association.getAssociationType())
                && sources.contains(association.getAssociatedEntity());
    }

    private static boolean matchesTagFilters(RamResourceShare share, JsonNode request) {
        JsonNode filters = request == null ? null : request.get("tagFilters");
        if (filters == null || !filters.isArray() || filters.isEmpty()) {
            return true;
        }
        for (JsonNode filter : filters) {
            String key = textOrNull(filter, "tagKey");
            if (key == null) {
                continue;
            }
            String actual = share.getTags().get(key);
            if (actual == null) {
                return false;
            }
            List<String> values = stringList(filter, "tagValues");
            if (!values.isEmpty() && !values.contains(actual)) {
                return false;
            }
        }
        return true;
    }

    private static String resourceTypeFromArn(String arn) {
        String[] parts = arn.split(":", 6);
        if (parts.length < 6) {
            return "unknown";
        }
        String service = parts[2];
        String resource = parts[5];
        String type = resource.contains("/") ? resource.substring(0, resource.indexOf('/')) : resource;
        if (type.isEmpty()) {
            return service;
        }
        return service + ":" + Character.toUpperCase(type.charAt(0))
                + (type.length() > 1 ? type.substring(1) : "");
    }

    private static String sharePolicy(RamResourceShare share, String resourceArn) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"ram:GetResourceShares\",\"Resource\":\"" + resourceArn + "\","
                + "\"Condition\":{\"StringEquals\":{\"ram:ResourceShareArn\":\"" + share.getArn() + "\"}}}]}";
    }

    private static List<RamPermission> awsManagedPermissions() {
        long created = 1_577_836_800L;
        return List.of(
                managed("AWSRAMDefaultPermissionSubnet", "ec2:Subnet",
                        "ec2:DescribeSubnets", created),
                managed("AWSRAMDefaultPermissionPrefixList", "ec2:PrefixList",
                        "ec2:DescribePrefixLists", created),
                managed("AWSRAMDefaultPermissionTransitGateway", "ec2:TransitGateway",
                        "ec2:DescribeTransitGateways", created),
                managed("AWSRAMDefaultPermissionLicenseConfiguration",
                        "license-manager:LicenseConfiguration",
                        "license-manager:GetLicenseConfiguration", created),
                managed("AWSRAMDefaultPermissionResolverRule", "route53resolver:ResolverRule",
                        "route53resolver:GetResolverRule", created));
    }

    private static RamPermission managed(String name, String resourceType, String action, long created) {
        RamPermission permission = new RamPermission();
        permission.setArn("arn:aws:ram::aws:permission/" + name);
        permission.setName(name);
        permission.setResourceType(resourceType);
        permission.setDefaultVersion(1);
        permission.setPermissionType(TYPE_AWS_MANAGED);
        permission.setFeatureSet("STANDARD");
        permission.setCreationTime(created);
        permission.setLastUpdatedTime(created);
        permission.setVersions(List.of(newVersion(1,
                "{\"Effect\":\"Allow\",\"Action\":[\"" + action + "\"]}", created)));
        return permission;
    }

    private RamPermission requirePermission(String region, String arn) {
        validateArn(arn);
        return store.get(storageKey(region, arn)).orElseThrow(() -> unknown(arn));
    }

    private RamPermission findByName(String region, String name) {
        return store.scan(key -> key.startsWith(region + "::")).stream()
                .filter(permission -> name.equals(permission.getName()))
                .findFirst()
                .orElse(null);
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private static String storageKey(String region, String arn) {
        return region + "::" + arn;
    }

    private static RamPermissionVersion newVersion(int number, String policyTemplate, long now) {
        RamPermissionVersion version = new RamPermissionVersion();
        version.setVersion(number);
        version.setPolicyTemplate(policyTemplate);
        version.setStatus(STATUS_ATTACHABLE);
        version.setCreationTime(now);
        version.setLastUpdatedTime(now);
        return version;
    }

    private static boolean isLive(RamPermissionVersion version) {
        return version != null
                && !STATUS_DELETED.equals(version.getStatus())
                && !"DELETING".equals(version.getStatus());
    }

    private static void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw invalidParameter("name must match [\\w+=,.@-]{1,36}.");
        }
    }

    private static void validateArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!SERVICE.equals(parsed.service())
                    || parsed.resource() == null
                    || !parsed.resource().startsWith("permission/")) {
                throw malformed(arn);
            }
        } catch (IllegalArgumentException e) {
            throw malformed(arn);
        }
    }

    private static boolean isShareArn(String arn) {
        return arn != null && arn.contains(":resource-share/");
    }

    private static String resourceArn(JsonNode request) {
        String arn = textOrNull(request, "resourceArn");
        if (arn == null) {
            arn = textOrNull(request, "resourceShareArn");
        }
        if (arn == null) {
            throw missing("resourceArn");
        }
        return arn;
    }

    private static String requirePolicy(JsonNode request) {
        String policy = requireText(request, "policyTemplate");
        try {
            JsonNode parsed = JSON.readTree(policy);
            if (parsed == null || !parsed.isObject()) {
                throw new AwsException(
                        "MalformedPolicyTemplateException",
                        "policyTemplate must be a JSON object.",
                        400);
            }
        } catch (AwsException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new AwsException(
                    "MalformedPolicyTemplateException",
                    "policyTemplate is not valid JSON.",
                    400);
        }
        return policy;
    }

    private static Map<String, String> readPermissionTags(JsonNode request) {
        if (request == null || !request.has("tags") || request.get("tags").isNull()) {
            return new LinkedHashMap<>();
        }
        JsonNode tagsNode = request.get("tags");
        if (!tagsNode.isArray()) {
            throw invalidParameter("tags must be an array.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : tagsNode) {
            if (tag == null || !tag.isObject()) {
                throw invalidParameter("tags members must be objects.");
            }
            JsonNode key = tag.get("key");
            JsonNode value = tag.get("value");
            if (key == null || !key.isTextual() || key.textValue().isBlank()
                    || value == null || !value.isTextual()) {
                throw invalidParameter("tags contains an invalid key or value.");
            }
            tags.put(key.textValue(), value.textValue());
        }
        return tags;
    }

    private static Map<String, String> readShareTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (request == null || !request.has("tags") || request.get("tags").isNull()) {
            return tags;
        }
        JsonNode node = request.get("tags");
        if (node.isArray()) {
            for (JsonNode tag : node) {
                String key = textOrNull(tag, "key");
                if (key == null) {
                    key = textOrNull(tag, "Key");
                }
                String value = textOrNull(tag, "value");
                if (value == null) {
                    value = textOrNull(tag, "Value");
                }
                if (key != null && value != null) {
                    tags.put(key, value);
                }
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        }
        return tags;
    }

    private static List<String> readTagKeys(JsonNode request) {
        if (request == null || !request.has("tagKeys") || request.get("tagKeys").isNull()) {
            throw missing("tagKeys");
        }
        JsonNode array = request.get("tagKeys");
        if (!array.isArray()) {
            throw invalidParameter("tagKeys must be an array of strings.");
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode value : array) {
            if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw invalidParameter("tagKeys members must be strings.");
            }
            keys.add(value.textValue());
        }
        return keys;
    }

    private static List<String> stringList(JsonNode request, String field) {
        List<String> values = new ArrayList<>();
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return values;
        }
        JsonNode node = request.get(field);
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && !item.isNull()) {
                    String value = item.asText();
                    if (value != null && !value.isBlank()) {
                        values.add(value);
                    }
                }
            }
        }
        return values;
    }

    private static boolean boolOr(JsonNode request, String field, boolean fallback) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return fallback;
        }
        return request.get(field).asBoolean(fallback);
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw invalidParameter(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull() || (!value.isTextual() && !value.isNumber() && !value.isBoolean())) {
            throw missing(field);
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            throw missing(field);
        }
        return text;
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (!value.isTextual() && !value.isNumber() && !value.isBoolean()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static Integer optionalInt(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode value = parent.get(field);
        if (value.isNumber()) {
            return value.intValue();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.textValue());
            } catch (NumberFormatException e) {
                throw invalidParameter(field + " must be an integer.");
            }
        }
        throw invalidParameter(field + " must be an integer.");
    }

    private static int parseMaxResults(JsonNode request) {
        if (request == null || !request.has("maxResults") || request.get("maxResults").isNull()) {
            return DEFAULT_MAX_RESULTS;
        }
        JsonNode value = request.get("maxResults");
        if (!value.isNumber() && !value.isTextual()) {
            throw invalidParameter("maxResults must be an integer between 1 and 500.");
        }
        int parsed = value.isNumber() ? value.intValue() : Integer.parseInt(value.asText());
        if (parsed < 1 || parsed > MAX_RESULTS) {
            throw invalidParameter("maxResults must be between 1 and 500.");
        }
        return parsed;
    }

    private static <T> Page<T> page(List<T> items, int maxResults, String nextToken) {
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(offset + maxResults, items.size());
        String responseToken = end < items.size() ? encodeOffset(end) : null;
        return new Page<>(items.subList(offset, end), responseToken);
    }

    private static int decodeOffset(String token, int resultSize) {
        if (token == null) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(TOKEN_PREFIX)) {
                throw invalidParameter("nextToken is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(TOKEN_PREFIX.length()));
            if (offset < 1 || offset >= resultSize) {
                throw invalidParameter("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw invalidParameter("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((TOKEN_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static long epochSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException unknown(String arn) {
        return new AwsException("UnknownResourceException", "Resource " + arn + " was not found.", 400);
    }

    private static AwsException unknown(String type, String arn) {
        return new AwsException("UnknownResourceException", type + " not found: " + arn, 400);
    }

    private static AwsException malformed(String arn) {
        return new AwsException("MalformedArnException", "ARN " + arn + " is malformed.", 400);
    }

    private static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private static AwsException missing(String field) {
        return new AwsException(
                "MissingRequiredParameterException",
                "Missing required parameter " + field + ".",
                400);
    }

    private static AwsException invitationNotFound(String arn) {
        return new AwsException("ResourceShareInvitationArnNotFoundException",
                "Resource share invitation not found: " + arn, 400);
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
