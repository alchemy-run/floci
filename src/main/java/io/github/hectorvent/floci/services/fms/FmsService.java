package io.github.hectorvent.floci.services.fms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.fms.model.FmsAdminAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AWS Firewall Manager JSON 1.1 ({@code AWSFMS_20180101.*}).
 *
 * <p>Admin-account APIs are organization-level and served only from
 * {@code us-east-1}. Association settles immediately to {@code READY} so
 * local reconcilers do not wait on the live FMS onboarding window.
 */
@ApplicationScoped
public class FmsService implements Resettable {

    static final String SERVICE = "fms";
    static final String ADMIN_REGION = "us-east-1";
    private static final String ADMIN_KEY = "admin";
    private static final Pattern ACCOUNT_ID = Pattern.compile("\\d{12}");
    static final Set<String> THIRD_PARTY_FIREWALLS = Set.of(
            "PALO_ALTO_NETWORKS_CLOUD_NGFW",
            "FORTIGATE_CLOUD_NATIVE_FIREWALL");

    private final StorageBackend<String, FmsAdminAccount> store;
    private final RegionResolver regionResolver;

    @Inject
    public FmsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create(SERVICE, "fms-admin-account.json",
                new TypeReference<Map<String, FmsAdminAccount>>() {
                }), regionResolver);
    }

    FmsService(StorageBackend<String, FmsAdminAccount> store) {
        this(store, null);
    }

    FmsService(StorageBackend<String, FmsAdminAccount> store, RegionResolver regionResolver) {
        this.store = store;
        this.regionResolver = regionResolver;
    }

    public Optional<FmsAdminAccount> findAdmin() {
        return store.get(ADMIN_KEY);
    }

    /**
     * Admin-scoped policy/list/resource-set APIs. Live FMS rejects these with
     * {@code AccessDeniedException} when the organization has no default admin.
     */
    public FmsAdminAccount requireAdmin() {
        return findAdmin().orElseThrow(() -> new AwsException(
                "AccessDeniedException",
                "No default admin could be found for account " + accountId() + ".",
                400));
    }

    /**
     * {@code ListAdminAccountsForOrganization} is management-account-only.
     */
    public FmsAdminAccount requireOrganizationAdmin() {
        return findAdmin().orElseThrow(() -> new AwsException(
                "InvalidOperationException",
                "No default admin could be found for organization " + accountId() + ".",
                400));
    }

    /**
     * {@code ListAdminsManagingAccount} surfaces a missing designation as
     * {@code ResourceNotFoundException}.
     */
    public FmsAdminAccount requireManagingAdmin() {
        return findAdmin().orElseThrow(() -> new AwsException(
                "ResourceNotFoundException",
                "The referenced item does not exist.",
                400));
    }

    public void requireThirdPartyFirewall(JsonNode request) {
        if (request == null || !request.hasNonNull("ThirdPartyFirewall")) {
            throw new AwsException("InvalidInputException", "ThirdPartyFirewall is a required parameter.", 400);
        }
        String vendor = request.get("ThirdPartyFirewall").asText();
        if (vendor == null || vendor.isBlank() || !THIRD_PARTY_FIREWALLS.contains(vendor)) {
            throw new AwsException("InvalidInputException", "ThirdPartyFirewall is invalid.", 400);
        }
    }

    public FmsAdminAccount getAdminAccount(String region) {
        requireAdminRegion(region);
        return store.get(ADMIN_KEY).orElseThrow(FmsService::notFound);
    }

    public synchronized void associateAdminAccount(String region, JsonNode request) {
        requireAdminRegion(region);
        String accountId = requireAccountId(request);
        Optional<FmsAdminAccount> existing = store.get(ADMIN_KEY);
        if (existing.isPresent()) {
            if (accountId.equals(existing.get().getAdminAccount())) {
                existing.get().setRoleStatus("READY");
                store.put(ADMIN_KEY, existing.get());
                return;
            }
            throw new AwsException(
                    "InvalidOperationException",
                    "An administrator account is already associated with this organization.",
                    400);
        }
        FmsAdminAccount admin = new FmsAdminAccount();
        admin.setAdminAccount(accountId);
        admin.setRoleStatus("READY");
        store.put(ADMIN_KEY, admin);
    }

    public synchronized void disassociateAdminAccount(String region) {
        requireAdminRegion(region);
        if (store.get(ADMIN_KEY).isEmpty()) {
            throw notFound();
        }
        store.delete(ADMIN_KEY);
    }

    @Override
    public void clear() {
        store.clear();
    }

    private static void requireAdminRegion(String region) {
        if (region != null && !region.isBlank() && !ADMIN_REGION.equals(region)) {
            throw new AwsException(
                    "InvalidOperationException",
                    "This operation is not supported in the '" + region + "' region.",
                    400);
        }
    }

    private static String requireAccountId(JsonNode request) {
        if (request == null || !request.hasNonNull("AdminAccount")) {
            throw new AwsException("InvalidInputException", "AdminAccount is a required parameter.", 400);
        }
        String accountId = request.get("AdminAccount").asText();
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new AwsException("InvalidInputException", "AdminAccount is invalid.", 400);
        }
        return accountId;
    }

    private static AwsException notFound() {
        return new AwsException(
                "ResourceNotFoundException",
                "Unable to retrieve resource. Please retry.",
                400);
    }

    private String accountId() {
        return regionResolver != null ? regionResolver.getAccountId() : "000000000000";
    }
}
