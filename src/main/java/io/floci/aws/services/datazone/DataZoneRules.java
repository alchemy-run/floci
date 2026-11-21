package io.floci.aws.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashSet;
import java.util.Set;

import static io.floci.aws.services.datazone.DataZoneStore.invalid;

/**
 * DataZone metadata rules, as {@link DataZoneCatalog} and {@link DataZoneSubscriptions} enforce them: a rule on a
 * project's domain unit (or, with {@code includeChildDomainUnits}, on an ancestor unit) can require forms or
 * glossary terms before an asset or data product is published, and forms before a listing is subscribed to.
 */
@ApplicationScoped
public class DataZoneRules {

    private final DataZoneStore store;

    @Inject
    public DataZoneRules(DataZoneStore store) {
        this.store = store;
    }

    static Set<String> values(JsonNode list) {
        Set<String> result = new LinkedHashSet<>();
        list.forEach(value -> result.add(value.asText()));
        return result;
    }

    boolean isAncestor(String region, String domainId, String ancestor, String unitId) {
        String current = unitId;
        for (int depth = 0; depth < 32 && current != null; depth++) {
            if (ancestor.equals(current)) {
                return true;
            }
            current = store.get(region, domainId, DataZoneService.UNITS, current)
                    .map(unit -> unit.path("parentDomainUnitId").asText(null)).orElse(null);
        }
        return false;
    }

    /**
     * Fails when a metadata rule on the project's domain unit requires forms or glossary terms the entity lacks:
     * DataZone enforces rules when an asset is published and when a subscription is requested.
     */
    void enforce(String region, String domainId, String action, String projectId, String assetType,
                 boolean dataProduct, JsonNode forms, JsonNode glossaryTerms) {
        String unit = store.get(region, domainId, DataZoneService.PROJECTS, projectId)
                .map(project -> project.path("domainUnitId").asText(null)).orElse(null);
        if (unit == null) {
            unit = store.domain(region, domainId).map(domain -> domain.path("rootDomainUnitId").asText(null))
                    .orElse(null);
        }
        for (ObjectNode rule : store.list(region, domainId, DataZoneGovernance.RULES)) {
            if (!action.equals(rule.path("action").asText())) {
                continue;
            }
            JsonNode target = rule.path("target").path("domainUnitTarget");
            String targetUnit = target.path("domainUnitId").asText();
            if (!(targetUnit.equals(unit) || (target.path("includeChildDomainUnits").asBoolean(false)
                    && isAncestor(region, domainId, targetUnit, unit)))) {
                continue;
            }
            JsonNode scope = rule.path("scope");
            if (dataProduct && !scope.path("dataProduct").asBoolean(false)) {
                continue;
            }
            if (!dataProduct && "SPECIFIC".equals(scope.path("assetType").path("selectionMode").asText())
                    && !values(scope.path("assetType").path("specificAssetTypes")).contains(assetType)) {
                continue;
            }
            if ("SPECIFIC".equals(scope.path("project").path("selectionMode").asText())
                    && !values(scope.path("project").path("specificProjects")).contains(projectId)) {
                continue;
            }
            for (JsonNode required : rule.path("detail").path("metadataFormEnforcementDetail")
                    .path("requiredMetadataForms")) {
                boolean present = false;
                for (JsonNode form : forms) {
                    present |= required.path("typeIdentifier").asText().equals(form.path("typeName").asText());
                }
                if (!present) {
                    throw invalid("Rule " + rule.path("name").asText() + " requires a "
                            + required.path("typeIdentifier").asText() + " form.");
                }
            }
            for (JsonNode term : rule.path("detail").path("glossaryTermEnforcementDetail")
                    .path("requiredGlossaryTermIds")) {
                if (!values(glossaryTerms).contains(term.asText())) {
                    throw invalid("Rule " + rule.path("name").asText() + " requires glossary term " + term.asText()
                            + ".");
                }
            }
        }
    }
}
