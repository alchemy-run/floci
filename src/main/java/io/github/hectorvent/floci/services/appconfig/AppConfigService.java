package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

@ApplicationScoped
public class AppConfigService {
    private static final Logger LOG = Logger.getLogger(AppConfigService.class);

    private final StorageBackend<String, Application> applicationStore;
    private final StorageBackend<String, Environment> environmentStore;
    private final StorageBackend<String, ConfigurationProfile> profileStore;
    private final StorageBackend<String, DeploymentStrategy> strategyStore;
    private final StorageBackend<String, HostedConfigurationVersion> versionStore;
    private final StorageBackend<String, Deployment> deploymentStore;
    private final StorageBackend<String, String> activeConfigStore; // envId::profileId -> versionNumber
    private final StorageBackend<String, Extension> extensionStore;
    private final StorageBackend<String, ExtensionAssociation> associationStore;
    private final RegionResolver regionResolver;

    @Inject
    public AppConfigService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.applicationStore = storageFactory.create("appconfig", "appconfig-applications.json", new TypeReference<>() {});
        this.environmentStore = storageFactory.create("appconfig", "appconfig-environments.json", new TypeReference<>() {});
        this.profileStore = storageFactory.create("appconfig", "appconfig-profiles.json", new TypeReference<>() {});
        this.strategyStore = storageFactory.create("appconfig", "appconfig-strategies.json", new TypeReference<>() {});
        this.versionStore = storageFactory.create("appconfig", "appconfig-versions.json", new TypeReference<>() {});
        this.deploymentStore = storageFactory.create("appconfig", "appconfig-deployments.json", new TypeReference<>() {});
        this.activeConfigStore = storageFactory.create("appconfig", "appconfig-active-configs.json", new TypeReference<>() {});
        this.extensionStore = storageFactory.create("appconfig", "appconfig-extensions.json", new TypeReference<>() {});
        this.associationStore = storageFactory.create("appconfig", "appconfig-extension-associations.json", new TypeReference<>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Application ────────────────────────────

    public Application createApplication(Map<String, Object> request) {
        Application app = new Application();
        app.setId(shortId(7));
        app.setName((String) request.get("Name"));
        app.setDescription((String) request.get("Description"));
        applyTags(app.getTags(), request.get("Tags"));
        applicationStore.put(app.getId(), app);
        return app;
    }

    public Application updateApplication(String id, Map<String, Object> request) {
        Application app = getApplication(id);
        if (request.containsKey("Name")) {
            app.setName((String) request.get("Name"));
        }
        if (request.containsKey("Description")) {
            app.setDescription((String) request.get("Description"));
        }
        applicationStore.put(id, app);
        return app;
    }

    public Application getApplication(String id) {
        return applicationStore.get(id).orElseThrow(() -> new AwsException("ResourceNotFoundException", "Application not found", 404));
    }

    public List<Application> listApplications() {
        return applicationStore.scan(k -> true);
    }

    public void deleteApplication(String id) {
        getApplication(id);
        if (!listEnvironments(id).isEmpty() || !listConfigurationProfiles(id).isEmpty()) {
            throw new AwsException("BadRequestException",
                    "Cannot delete application " + id + ", because there are still environments existing under it", 400);
        }
        applicationStore.delete(id);
    }

    // ──────────────────────────── Environment ────────────────────────────

    public Environment createEnvironment(String appId, Map<String, Object> request) {
        getApplication(appId);
        Environment env = new Environment();
        env.setId(shortId(7));
        env.setApplicationId(appId);
        env.setName((String) request.get("Name"));
        env.setDescription((String) request.get("Description"));
        env.setState("READY");
        env.setMonitors(monitorsFrom(request.get("Monitors")));
        applyTags(env.getTags(), request.get("Tags"));
        environmentStore.put(env.getId(), env);
        return env;
    }

    public Environment updateEnvironment(String appId, String envId, Map<String, Object> request) {
        Environment env = getEnvironment(appId, envId);
        if (request.containsKey("Name")) {
            env.setName((String) request.get("Name"));
        }
        if (request.containsKey("Description")) {
            env.setDescription((String) request.get("Description"));
        }
        if (request.containsKey("Monitors")) {
            env.setMonitors(monitorsFrom(request.get("Monitors")));
        }
        environmentStore.put(envId, env);
        return env;
    }

    public void deleteEnvironment(String appId, String envId) {
        getEnvironment(appId, envId);
        environmentStore.delete(envId);
    }

    public Environment getEnvironment(String appId, String envId) {
        Environment env = environmentStore.get(envId).orElseThrow(() -> new AwsException("ResourceNotFoundException", "Environment not found", 404));
        if (!env.getApplicationId().equals(appId)) throw new AwsException("ResourceNotFoundException", "Environment not found in this application", 404);
        return env;
    }

    public List<Environment> listEnvironments(String appId) {
        return environmentStore.scan(k -> true).stream()
                .filter(e -> e.getApplicationId().equals(appId))
                .toList();
    }

    // ──────────────────────────── Configuration Profile ────────────────────────────

    public ConfigurationProfile createConfigurationProfile(String appId, Map<String, Object> request) {
        getApplication(appId);
        ConfigurationProfile profile = new ConfigurationProfile();
        profile.setId(shortId(7));
        profile.setApplicationId(appId);
        profile.setName((String) request.get("Name"));
        profile.setDescription((String) request.get("Description"));
        profile.setLocationUri((String) request.get("LocationUri"));
        profile.setType((String) request.get("Type"));
        profile.setRetrievalRoleArn((String) request.get("RetrievalRoleArn"));
        profile.setKmsKeyIdentifier((String) request.get("KmsKeyIdentifier"));
        profile.setValidators(monitorsFrom(request.get("Validators")));
        applyTags(profile.getTags(), request.get("Tags"));
        profileStore.put(profile.getId(), profile);
        return profile;
    }

    public ConfigurationProfile updateConfigurationProfile(String appId, String profileId, Map<String, Object> request) {
        ConfigurationProfile profile = getConfigurationProfile(appId, profileId);
        if (request.containsKey("Name")) {
            profile.setName((String) request.get("Name"));
        }
        if (request.containsKey("Description")) {
            profile.setDescription((String) request.get("Description"));
        }
        if (request.containsKey("RetrievalRoleArn")) {
            profile.setRetrievalRoleArn((String) request.get("RetrievalRoleArn"));
        }
        if (request.containsKey("KmsKeyIdentifier")) {
            profile.setKmsKeyIdentifier((String) request.get("KmsKeyIdentifier"));
        }
        if (request.containsKey("Validators")) {
            profile.setValidators(monitorsFrom(request.get("Validators")));
        }
        profileStore.put(profileId, profile);
        return profile;
    }

    public void deleteConfigurationProfile(String appId, String profileId) {
        getConfigurationProfile(appId, profileId);
        String prefix = appId + "::" + profileId + "::";
        if (!versionStore.scan(k -> k.startsWith(prefix)).isEmpty()) {
            throw new AwsException("BadRequestException",
                    "Cannot delete configuration profile " + profileId + " because hosted configuration versions still exist", 400);
        }
        profileStore.delete(profileId);
    }

    public ConfigurationProfile getConfigurationProfile(String appId, String profileId) {
        ConfigurationProfile profile = profileStore.get(profileId).orElseThrow(() -> new AwsException("ResourceNotFoundException", "Configuration profile not found", 404));
        if (!profile.getApplicationId().equals(appId)) throw new AwsException("ResourceNotFoundException", "Profile not found in this application", 404);
        return profile;
    }

    public List<ConfigurationProfile> listConfigurationProfiles(String appId) {
        return profileStore.scan(k -> true).stream()
                .filter(p -> p.getApplicationId().equals(appId))
                .toList();
    }

    // ──────────────────────────── Hosted Configuration Version ────────────────────────────

    public HostedConfigurationVersion createHostedConfigurationVersion(String appId, String profileId, byte[] content, String contentType, String description, String versionLabel) {
        getConfigurationProfile(appId, profileId);
        String prefix = appId + "::" + profileId + "::";
        int nextVersion = versionStore.scan(k -> k.startsWith(prefix))
                .stream().mapToInt(HostedConfigurationVersion::getVersionNumber).max().orElse(0) + 1;

        HostedConfigurationVersion version = new HostedConfigurationVersion();
        version.setApplicationId(appId);
        version.setConfigurationProfileId(profileId);
        version.setVersionNumber(nextVersion);
        version.setContent(content);
        version.setContentType(contentType);
        version.setDescription(description);
        version.setVersionLabel(versionLabel);

        versionStore.put(prefix + nextVersion, version);
        return version;
    }

    public void deleteHostedConfigurationVersion(String appId, String profileId, int versionNumber) {
        getHostedConfigurationVersion(appId, profileId, versionNumber);
        versionStore.delete(appId + "::" + profileId + "::" + versionNumber);
    }

    public HostedConfigurationVersion getHostedConfigurationVersion(String appId, String profileId, int versionNumber) {
        return versionStore.get(appId + "::" + profileId + "::" + versionNumber)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Hosted configuration version not found", 404));
    }

    public List<HostedConfigurationVersionSummary> listHostedConfigurationVersions(String appId, String profileId) {
        String prefix = appId + "::" + profileId + "::";
        return versionStore.scan(k -> k.startsWith(prefix))
                .stream()
                .sorted(Comparator.comparingInt(HostedConfigurationVersion::getVersionNumber))
                .map(v -> {
                    HostedConfigurationVersionSummary s = new HostedConfigurationVersionSummary();
                    s.setApplicationId(v.getApplicationId());
                    s.setConfigurationProfileId(v.getConfigurationProfileId());
                    s.setVersionNumber(v.getVersionNumber());
                    s.setDescription(v.getDescription());
                    s.setContentType(v.getContentType());
                    return s;
                })
                .toList();
    }

    // ──────────────────────────── Deployment Strategy ────────────────────────────

    public DeploymentStrategy createDeploymentStrategy(Map<String, Object> request) {
        DeploymentStrategy strategy = new DeploymentStrategy();
        strategy.setId(shortId(7));
        strategy.setName((String) request.get("Name"));
        strategy.setDescription((String) request.get("Description"));
        strategy.setDeploymentDurationInMinutes((Integer) request.getOrDefault("DeploymentDurationInMinutes", 0));
        strategy.setGrowthFactor(((Number) request.getOrDefault("GrowthFactor", 100.0f)).floatValue());
        strategy.setFinalBakeTimeInMinutes((Integer) request.getOrDefault("FinalBakeTimeInMinutes", 0));
        strategy.setGrowthType((String) request.getOrDefault("GrowthType", "LINEAR"));
        strategy.setReplicateTo((String) request.getOrDefault("ReplicateTo", "NONE"));
        applyTags(strategy.getTags(), request.get("Tags"));
        strategyStore.put(strategy.getId(), strategy);
        return strategy;
    }

    public List<DeploymentStrategy> listDeploymentStrategies() {
        List<DeploymentStrategy> strategies = new ArrayList<>(List.of(
                builtinStrategy("AppConfig.AllAtOnce"),
                builtinStrategy("AppConfig.Linear50PercentEvery30Seconds"),
                builtinStrategy("AppConfig.Canary10Percent20Minutes")
        ));
        strategies.addAll(strategyStore.scan(k -> true));
        return strategies;
    }

    public DeploymentStrategy updateDeploymentStrategy(String id, Map<String, Object> request) {
        DeploymentStrategy strategy = getDeploymentStrategy(id);
        if (id.startsWith("AppConfig.")) {
            throw new AwsException("BadRequestException", "Cannot update predefined Deployment Strategy", 400);
        }
        if (request.containsKey("Description")) {
            strategy.setDescription((String) request.get("Description"));
        }
        if (request.get("DeploymentDurationInMinutes") instanceof Number duration) {
            strategy.setDeploymentDurationInMinutes(duration.intValue());
        }
        if (request.get("FinalBakeTimeInMinutes") instanceof Number bake) {
            strategy.setFinalBakeTimeInMinutes(bake.intValue());
        }
        if (request.get("GrowthFactor") instanceof Number growth) {
            strategy.setGrowthFactor(growth.floatValue());
        }
        if (request.containsKey("GrowthType")) {
            strategy.setGrowthType((String) request.get("GrowthType"));
        }
        strategyStore.put(id, strategy);
        return strategy;
    }

    public void deleteDeploymentStrategy(String id) {
        if (id.startsWith("AppConfig.")) {
            throw new AwsException("BadRequestException", "Cannot delete predefined Deployment Strategy", 400);
        }
        getDeploymentStrategy(id);
        strategyStore.delete(id);
    }

    public DeploymentStrategy getDeploymentStrategy(String id) {
        // AWS predefined built-in strategies
        DeploymentStrategy builtin = builtinStrategy(id);
        if (builtin != null) return builtin;
        return strategyStore.get(id).orElseThrow(() -> new AwsException("ResourceNotFoundException", "Deployment strategy not found", 404));
    }

    private static DeploymentStrategy builtinStrategy(String id) {
        return switch (id) {
            case "AppConfig.AllAtOnce" -> {
                DeploymentStrategy s = new DeploymentStrategy();
                s.setId(id); s.setName(id);
                s.setDescription("Quick");
                s.setDeploymentDurationInMinutes(0); s.setGrowthFactor(100f);
                s.setFinalBakeTimeInMinutes(10); s.setGrowthType("LINEAR");
                s.setReplicateTo("NONE");
                yield s;
            }
            case "AppConfig.Linear50PercentEvery30Seconds" -> {
                DeploymentStrategy s = new DeploymentStrategy();
                s.setId(id); s.setName(id);
                s.setDescription("Test/Demo");
                s.setDeploymentDurationInMinutes(1); s.setGrowthFactor(50f);
                s.setFinalBakeTimeInMinutes(1); s.setGrowthType("LINEAR");
                s.setReplicateTo("NONE");
                yield s;
            }
            case "AppConfig.Canary10Percent20Minutes" -> {
                DeploymentStrategy s = new DeploymentStrategy();
                s.setId(id); s.setName(id);
                s.setDescription("AWS Recommended");
                s.setDeploymentDurationInMinutes(20); s.setGrowthFactor(10f);
                s.setFinalBakeTimeInMinutes(10); s.setGrowthType("EXPONENTIAL");
                s.setReplicateTo("NONE");
                yield s;
            }
            default -> null;
        };
    }

    // ──────────────────────────── Deployment ────────────────────────────

    public Deployment startDeployment(String appId, String envId, Map<String, Object> request) {
        getEnvironment(appId, envId);
        String profileId = (String) request.get("ConfigurationProfileId");
        String version = (String) request.get("ConfigurationVersion");
        String strategyId = (String) request.get("DeploymentStrategyId");

        getConfigurationProfile(appId, profileId);
        getDeploymentStrategy(strategyId);

        Deployment deployment = new Deployment();
        deployment.setApplicationId(appId);
        deployment.setEnvironmentId(envId);
        deployment.setConfigurationProfileId(profileId);
        deployment.setConfigurationVersion(version);
        deployment.setDeploymentStrategyId(strategyId);
        deployment.setDeploymentNumber(deploymentStore.keys().size() + 1);
        deployment.setState("COMPLETE"); // Synchronous immediate deployment
        deployment.setDescription((String) request.get("Description"));

        deploymentStore.put(appId + "::" + envId + "::" + deployment.getDeploymentNumber(), deployment);

        // Update active configuration
        activeConfigStore.put(envId + "::" + profileId, version);

        LOG.infov("Started deployment for app {0}, env {1}, profile {2}, version {3}. State: COMPLETE", appId, envId, profileId, version);
        return deployment;
    }

    public Deployment getDeployment(String appId, String envId, int deploymentNumber) {
        return deploymentStore.get(appId + "::" + envId + "::" + deploymentNumber)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Deployment not found", 404));
    }

    public String getActiveVersion(String envId, String profileId) {
        return activeConfigStore.get(envId + "::" + profileId).orElse(null);
    }

    public Deployment stopDeployment(String appId, String envId, int deploymentNumber) {
        Deployment deployment = getDeployment(appId, envId, deploymentNumber);
        if ("COMPLETE".equals(deployment.getState()) || "ROLLED_BACK".equals(deployment.getState())) {
            throw new AwsException("BadRequestException",
                    "Deployment is already in a terminal state and cannot be stopped", 400);
        }
        deployment.setState("ROLLED_BACK");
        deploymentStore.put(appId + "::" + envId + "::" + deploymentNumber, deployment);
        return deployment;
    }

    // ──────────────────────────── Extensions ────────────────────────────

    public Extension createExtension(Map<String, Object> request) {
        Extension extension = new Extension();
        extension.setId(shortId(7));
        extension.setName((String) request.get("Name"));
        extension.setDescription((String) request.get("Description"));
        extension.setActions(request.get("Actions"));
        extension.setParameters(request.get("Parameters"));
        extension.setVersionNumber(1);
        extension.setArn(buildExtensionArn(extension.getId(), 1));
        applyTags(extension.getTags(), request.get("Tags"));
        extensionStore.put(extension.getId(), extension);
        return extension;
    }

    public Extension getExtension(String identifier) {
        return findExtension(identifier)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Extension not found", 404));
    }

    public List<Extension> listExtensions(String name) {
        return extensionStore.scan(k -> true).stream()
                .filter(e -> name == null || name.equals(e.getName()))
                .toList();
    }

    public Extension updateExtension(String identifier, Map<String, Object> request) {
        Extension extension = getExtension(identifier);
        if (request.containsKey("Description")) {
            extension.setDescription((String) request.get("Description"));
        }
        if (request.containsKey("Actions")) {
            extension.setActions(request.get("Actions"));
        }
        if (request.containsKey("Parameters")) {
            extension.setParameters(request.get("Parameters"));
        }
        extension.setVersionNumber(extension.getVersionNumber() + 1);
        extension.setArn(buildExtensionArn(extension.getId(), extension.getVersionNumber()));
        extensionStore.put(extension.getId(), extension);
        return extension;
    }

    public void deleteExtension(String identifier) {
        Extension extension = getExtension(identifier);
        boolean inUse = associationStore.scan(k -> true).stream()
                .anyMatch(a -> extension.getId().equals(a.getExtensionId()));
        if (inUse) {
            throw new AwsException("BadRequestException",
                    "Cannot delete extension " + extension.getId() + " because associations still exist", 400);
        }
        extensionStore.delete(extension.getId());
    }

    public ExtensionAssociation createExtensionAssociation(Map<String, Object> request) {
        Extension extension = getExtension((String) request.get("ExtensionIdentifier"));
        ExtensionAssociation association = new ExtensionAssociation();
        association.setId(shortId(7));
        association.setExtensionId(extension.getId());
        association.setExtensionArn(extension.getArn());
        association.setResourceArn((String) request.get("ResourceIdentifier"));
        association.setParameters(request.get("Parameters"));
        Integer version = request.get("ExtensionVersionNumber") instanceof Number n
                ? n.intValue()
                : extension.getVersionNumber();
        association.setExtensionVersionNumber(version);
        association.setArn(buildAssociationArn(association.getId()));
        applyTags(association.getTags(), request.get("Tags"));
        associationStore.put(association.getId(), association);
        return association;
    }

    public ExtensionAssociation getExtensionAssociation(String id) {
        return associationStore.get(id).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "Extension association not found", 404));
    }

    public List<ExtensionAssociation> listExtensionAssociations(String resourceIdentifier, String extensionIdentifier) {
        String extensionId = null;
        if (extensionIdentifier != null) {
            extensionId = findExtension(extensionIdentifier).map(Extension::getId).orElse(null);
        }
        String extId = extensionId;
        return associationStore.scan(k -> true).stream()
                .filter(a -> resourceIdentifier == null || resourceIdentifier.equals(a.getResourceArn()))
                .filter(a -> extId == null || extId.equals(a.getExtensionId()))
                .toList();
    }

    public ExtensionAssociation updateExtensionAssociation(String id, Map<String, Object> request) {
        ExtensionAssociation association = getExtensionAssociation(id);
        if (request.containsKey("Parameters")) {
            association.setParameters(request.get("Parameters"));
        }
        associationStore.put(id, association);
        return association;
    }

    public void deleteExtensionAssociation(String id) {
        getExtensionAssociation(id);
        associationStore.delete(id);
    }

    public void validateConfiguration(String appId, String profileId, String configurationVersion) {
        getConfigurationProfile(appId, profileId);
        if (configurationVersion != null) {
            try {
                getHostedConfigurationVersion(appId, profileId, Integer.parseInt(configurationVersion));
            } catch (NumberFormatException ignored) {
                // Non-hosted versions are accepted as-is for local validation.
            }
        }
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> getApplicationTags(String appId) {
        return getApplication(appId).getTags();
    }

    public void tagApplication(String appId, Map<String, String> tags) {
        Application app = getApplication(appId);
        app.getTags().putAll(tags);
        applicationStore.put(appId, app);
    }

    public void untagApplication(String appId, List<String> tagKeys) {
        Application app = getApplication(appId);
        tagKeys.forEach(app.getTags()::remove);
        applicationStore.put(appId, app);
    }

    public Map<String, String> getEnvironmentTags(String envId) {
        return environmentStore.get(envId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Environment not found", 404))
                .getTags();
    }

    public void tagEnvironment(String envId, Map<String, String> tags) {
        Environment env = environmentStore.get(envId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Environment not found", 404));
        env.getTags().putAll(tags);
        environmentStore.put(envId, env);
    }

    public void untagEnvironment(String envId, List<String> tagKeys) {
        Environment env = environmentStore.get(envId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Environment not found", 404));
        tagKeys.forEach(env.getTags()::remove);
        environmentStore.put(envId, env);
    }

    public Map<String, String> getProfileTags(String profileId) {
        return profileStore.get(profileId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Configuration profile not found", 404))
                .getTags();
    }

    public void tagProfile(String profileId, Map<String, String> tags) {
        ConfigurationProfile profile = profileStore.get(profileId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Configuration profile not found", 404));
        profile.getTags().putAll(tags);
        profileStore.put(profileId, profile);
    }

    public void untagProfile(String profileId, List<String> tagKeys) {
        ConfigurationProfile profile = profileStore.get(profileId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Configuration profile not found", 404));
        tagKeys.forEach(profile.getTags()::remove);
        profileStore.put(profileId, profile);
    }

    public Map<String, String> getStrategyTags(String strategyId) {
        DeploymentStrategy strategy = getDeploymentStrategy(strategyId);
        return strategy.getTags() != null ? strategy.getTags() : Map.of();
    }

    public void tagStrategy(String strategyId, Map<String, String> tags) {
        if (strategyId.startsWith("AppConfig.")) {
            return;
        }
        DeploymentStrategy strategy = getDeploymentStrategy(strategyId);
        strategy.getTags().putAll(tags);
        strategyStore.put(strategyId, strategy);
    }

    public void untagStrategy(String strategyId, List<String> tagKeys) {
        if (strategyId.startsWith("AppConfig.")) {
            return;
        }
        DeploymentStrategy strategy = getDeploymentStrategy(strategyId);
        tagKeys.forEach(strategy.getTags()::remove);
        strategyStore.put(strategyId, strategy);
    }

    public Map<String, String> getExtensionTags(String extensionId) {
        return getExtension(extensionId).getTags();
    }

    public void tagExtension(String extensionId, Map<String, String> tags) {
        Extension extension = getExtension(extensionId);
        extension.getTags().putAll(tags);
        extensionStore.put(extension.getId(), extension);
    }

    public void untagExtension(String extensionId, List<String> tagKeys) {
        Extension extension = getExtension(extensionId);
        tagKeys.forEach(extension.getTags()::remove);
        extensionStore.put(extension.getId(), extension);
    }

    public Map<String, String> getAssociationTags(String associationId) {
        return getExtensionAssociation(associationId).getTags();
    }

    public void tagAssociation(String associationId, Map<String, String> tags) {
        ExtensionAssociation association = getExtensionAssociation(associationId);
        association.getTags().putAll(tags);
        associationStore.put(associationId, association);
    }

    public void untagAssociation(String associationId, List<String> tagKeys) {
        ExtensionAssociation association = getExtensionAssociation(associationId);
        tagKeys.forEach(association.getTags()::remove);
        associationStore.put(associationId, association);
    }

    private Optional<Extension> findExtension(String identifier) {
        Optional<Extension> byId = extensionStore.get(identifier);
        if (byId.isPresent()) {
            return byId;
        }
        return extensionStore.scan(k -> true).stream()
                .filter(e -> identifier.equals(e.getName()))
                .findFirst();
    }

    private String buildExtensionArn(String id, int version) {
        return AwsArnUtils.Arn.of("appconfig", regionResolver.getRegion(), regionResolver.getAccountId(),
                "extension/" + id + "/" + version).toString();
    }

    private String buildAssociationArn(String id) {
        return AwsArnUtils.Arn.of("appconfig", regionResolver.getRegion(), regionResolver.getAccountId(),
                "extensionassociation/" + id).toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> monitorsFrom(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static void applyTags(Map<String, String> target, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                target.put(String.valueOf(k), String.valueOf(v));
            }
        });
    }

    private static String shortId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
