package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognito.model.ManagedLoginBranding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Managed login branding styles (CreateManagedLoginBranding / DescribeManagedLoginBranding /
 * DescribeManagedLoginBrandingByClient / UpdateManagedLoginBranding / DeleteManagedLoginBranding).
 *
 * <p>Styles are stored on their app client. The legacy standalone store is migrated on access.</p>
 */
@ApplicationScoped
public class CognitoManagedLoginBrandingService {

    private final StorageBackend<String, ManagedLoginBranding> store;
    private final CognitoService cognitoService;

    @Inject
    public CognitoManagedLoginBrandingService(StorageFactory storageFactory, CognitoService cognitoService) {
        this(storageFactory.create("cognito", "cognito-managed-login-branding.json",
                new TypeReference<Map<String, ManagedLoginBranding>>() {}), cognitoService);
    }

    CognitoManagedLoginBrandingService(StorageBackend<String, ManagedLoginBranding> store,
                                       CognitoService cognitoService) {
        this.store = store;
        this.cognitoService = cognitoService;
    }

    public ManagedLoginBranding create(String userPoolId, String clientId, Boolean useCognitoProvidedValues,
                                       Map<String, Object> settings, List<Map<String, Object>> assets) {
        migratePool(userPoolId);
        return cognitoService.createManagedLoginBranding(userPoolId, clientId, useCognitoProvidedValues,
                settings, assets);
    }

    public ManagedLoginBranding describe(String userPoolId, String managedLoginBrandingId) {
        migratePool(userPoolId);
        return cognitoService.describeManagedLoginBranding(userPoolId, managedLoginBrandingId);
    }

    public ManagedLoginBranding describeByClient(String userPoolId, String clientId) {
        migratePool(userPoolId);
        return cognitoService.describeManagedLoginBrandingByClient(userPoolId, clientId);
    }

    public ManagedLoginBranding update(String userPoolId, String managedLoginBrandingId,
                                       Boolean useCognitoProvidedValues,
                                       Map<String, Object> settings, List<Map<String, Object>> assets) {
        migratePool(userPoolId);
        return cognitoService.updateManagedLoginBranding(userPoolId, managedLoginBrandingId,
                useCognitoProvidedValues, settings, assets);
    }

    public void delete(String userPoolId, String managedLoginBrandingId) {
        migratePool(userPoolId);
        cognitoService.deleteManagedLoginBranding(userPoolId, managedLoginBrandingId);
    }

    private void migratePool(String userPoolId) {
        if (userPoolId == null || userPoolId.isBlank()) {
            return;
        }
        for (ManagedLoginBranding branding : store.scan(k -> k.startsWith(userPoolId + "::"))) {
            cognitoService.restoreManagedLoginBranding(branding);
            store.delete(key(userPoolId, branding.getManagedLoginBrandingId()));
        }
    }

    /** Cascade for DeleteUserPool: styles cannot outlive their pool. */
    public void deleteAllForUserPool(String userPoolId) {
        String prefix = userPoolId + "::";
        store.scan(k -> k.startsWith(prefix))
                .forEach(b -> store.delete(key(userPoolId, b.getManagedLoginBrandingId())));
    }

    /** Cascade for DeleteUserPoolClient: a style is bound to exactly one client. */
    public void deleteAllForClient(String userPoolId, String clientId) {
        ManagedLoginBranding branding = findByClient(userPoolId, clientId);
        if (branding != null) {
            store.delete(key(userPoolId, branding.getManagedLoginBrandingId()));
        }
    }

    /**
     * The settings document a describe with {@code ReturnMergedResources=true} reports: the
     * caller's own document when one is stored, otherwise Cognito's provided default style.
     */
    public Map<String, Object> mergedSettings(ManagedLoginBranding branding) {
        if (branding.getSettings() != null && !branding.getSettings().isEmpty()) {
            return branding.getSettings();
        }
        return defaultSettings();
    }

    private ManagedLoginBranding findByClient(String userPoolId, String clientId) {
        String prefix = userPoolId + "::";
        return store.scan(k -> k.startsWith(prefix)).stream()
                .filter(b -> clientId.equals(b.getClientId()))
                .findFirst()
                .orElse(null);
    }

    private static String key(String userPoolId, String managedLoginBrandingId) {
        return userPoolId + "::" + managedLoginBrandingId;
    }

    /** Cognito's provided default managed-login style, in the designer's document shape. */
    static Map<String, Object> defaultSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("componentClasses", Map.of(
                "buttons", Map.of("borderRadius", 8.0),
                "divider", Map.of(
                        "darkMode", Map.of("borderColor", "232b37ff"),
                        "lightMode", Map.of("borderColor", "ebebf0ff")),
                "input", Map.of(
                        "borderRadius", 8.0,
                        "darkMode", Map.of("defaults", Map.of("backgroundColor", "0f1b2aff", "borderColor", "424650ff")),
                        "lightMode", Map.of("defaults", Map.of("backgroundColor", "ffffffff", "borderColor", "8b8b8bff"))),
                "link", Map.of(
                        "darkMode", Map.of("defaults", Map.of("textColor", "539fe5ff"), "hover", Map.of("textColor", "89bdeeff")),
                        "lightMode", Map.of("defaults", Map.of("textColor", "0972d3ff"), "hover", Map.of("textColor", "033160ff")))));
        settings.put("components", Map.of(
                "pageBackground", Map.of(
                        "darkMode", Map.of("color", "0f1b2aff"),
                        "lightMode", Map.of("color", "ffffffff")),
                "pageHeader", Map.of(
                        "darkMode", Map.of("backgroundColor", "0f1b2aff", "borderColor", "424650ff"),
                        "lightMode", Map.of("backgroundColor", "ffffffff", "borderColor", "ebebf0ff")),
                "primaryButton", Map.of(
                        "darkMode", Map.of(
                                "defaults", Map.of("backgroundColor", "539fe5ff", "textColor", "000716ff"),
                                "hover", Map.of("backgroundColor", "89bdeeff", "textColor", "000716ff")),
                        "lightMode", Map.of(
                                "defaults", Map.of("backgroundColor", "0972d3ff", "textColor", "ffffffff"),
                                "hover", Map.of("backgroundColor", "033160ff", "textColor", "ffffffff"))),
                "form", Map.of(
                        "borderRadius", 8.0,
                        "darkMode", Map.of("backgroundColor", "0f1b2aff", "borderColor", "424650ff"),
                        "lightMode", Map.of("backgroundColor", "ffffffff", "borderColor", "c6c6cdff"))));
        settings.put("categories", Map.of(
                "form", Map.of("displayGraphics", true, "instructions", Map.of("enabled", false)),
                "global", Map.of("colorSchemeMode", "LIGHT", "pageHeader", Map.of("enabled", false),
                        "pageFooter", Map.of("enabled", false)),
                "signUp", Map.of("acceptanceElements", List.of())));
        return settings;
    }
}
