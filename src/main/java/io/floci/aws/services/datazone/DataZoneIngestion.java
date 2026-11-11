package io.floci.aws.services.datazone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static io.floci.aws.services.datazone.DataZoneStore.conflict;
import static io.floci.aws.services.datazone.DataZoneStore.invalid;
import static io.floci.aws.services.datazone.DataZoneStore.notFound;
import static io.floci.aws.services.datazone.DataZoneStore.now;
import static io.floci.aws.services.datazone.DataZoneStore.oneOf;
import static io.floci.aws.services.datazone.DataZoneStore.optional;
import static io.floci.aws.services.datazone.DataZoneStore.present;
import static io.floci.aws.services.datazone.DataZoneStore.text;

/**
 * How metadata enters the DataZone catalog: data sources and their runs, metadata generation runs, and lineage.
 * <p>
 * A Glue data source run reads the tables of each configured Glue database (kept or dropped by its
 * {@code INCLUDE}/{@code EXCLUDE} filter expressions, {@code *} matching any characters), creates an
 * {@code amazon.datazone.GlueTableAssetType} asset for a new table, a new revision for a table whose schema or
 * description changed, and leaves an unchanged table alone, publishing each when the data source publishes on
 * import. Redshift and SageMaker data sources run, and fail as their source is not reachable from floci.
 * A metadata generation run predicts business names and descriptions that the asset's owners then accept or reject.
 * Lineage events are OpenLineage run events: each records a job, its run and the datasets it read and wrote as
 * lineage nodes, with the run downstream of its inputs and upstream of its outputs.
 */
@ApplicationScoped
public class DataZoneIngestion {

    static final String DATA_SOURCES = "data-sources";
    static final String RUNS = "data-source-runs";
    static final String METADATA_RUNS = "metadata-generation-runs";
    static final String LINEAGE_EVENTS = "lineage-events";
    static final String LINEAGE_NODES = "lineage-nodes";
    static final String GLUE_TABLE_TYPE = "amazon.datazone.GlueTableAssetType";

    private final DataZoneStore store;
    private final DataZoneService core;
    private final DataZoneCatalog catalog;
    private final DataZoneEnvironments environments;
    private final Instance<GlueService> glue;
    private final long transitionMillis;

    @Inject
    public DataZoneIngestion(DataZoneStore store, DataZoneService core, DataZoneCatalog catalog,
                             DataZoneEnvironments environments, Instance<GlueService> glue, EmulatorConfig config) {
        this.store = store;
        this.core = core;
        this.catalog = catalog;
        this.environments = environments;
        this.glue = glue;
        this.transitionMillis = config.services().datazone().transitionMillis();
    }

    // ---------------------------------------------------------------- data sources

    public synchronized ObjectNode createDataSource(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CreateDataSource").path("id")
                .asText();
        String projectId = text(input, "projectIdentifier", 64);
        core.requireMember(region, domainId, projectId, "CreateDataSource");
        String name = text(input, "name", 256);
        String type = text(input, "type", 256).toUpperCase();
        String environmentId = optional(input, "environmentIdentifier");
        String connectionId = optional(input, "connectionIdentifier");
        if (environmentId == null && connectionId == null) {
            throw invalid("Specify environmentIdentifier or connectionIdentifier.");
        }
        if (environmentId != null) {
            ObjectNode environment = environments.environment(region, domainId, environmentId);
            if (!projectId.equals(environment.path("projectId").asText())) {
                throw invalid("Environment " + environmentId + " does not belong to project " + projectId + ".");
            }
        }
        for (ObjectNode existing : store.list(region, domainId, DATA_SOURCES)) {
            if (name.equals(existing.path("name").asText()) && projectId.equals(existing.path("projectId")
                    .asText())) {
                throw conflict("A data source named " + name + " already exists in project " + projectId + ".");
            }
        }
        double now = now();
        ObjectNode source = store.object();
        source.put("id", store.id());
        source.put("status", transitionMillis <= 0 ? "READY" : "CREATING");
        source.put("type", type);
        source.put("name", name);
        if (present(input, "description")) {
            source.put("description", input.get("description").asText());
        }
        source.put("domainId", domainId);
        source.put("projectId", projectId);
        if (environmentId != null) {
            source.put("environmentId", environmentId);
        }
        if (connectionId != null) {
            source.put("connectionId", connectionId);
        }
        source.set("configuration", configuration(type, input.path("configuration"), region));
        source.set("recommendation", input.path("recommendation").isObject() ? input.get("recommendation")
                .deepCopy() : store.object().put("enableBusinessNameGeneration", false));
        source.put("enableSetting", oneOf(input, "enableSetting", Set.of("ENABLED", "DISABLED"), "ENABLED"));
        source.put("publishOnImport", input.path("publishOnImport").asBoolean(false));
        source.set("assetFormsOutput", assetForms(input.path("assetFormsInput")));
        if (input.path("schedule").isObject()) {
            source.set("schedule", input.get("schedule").deepCopy());
        }
        source.put("createdAt", now);
        source.put("updatedAt", now);
        source.putObject("_").put("readyAt", System.currentTimeMillis() + transitionMillis);
        store.put(region, domainId, DATA_SOURCES, source.path("id").asText(), source);
        return store.view(source);
    }

    private ObjectNode configuration(String type, JsonNode configuration, String region) {
        ObjectNode output = store.object();
        if ("GLUE".equals(type)) {
            JsonNode glueConfiguration = configuration.path("glueRunConfiguration");
            if (!glueConfiguration.path("relationalFilterConfigurations").isArray()
                    || glueConfiguration.get("relationalFilterConfigurations").isEmpty()) {
                throw invalid("glueRunConfiguration.relationalFilterConfigurations is required for a GLUE data "
                        + "source.");
            }
            for (JsonNode filter : glueConfiguration.get("relationalFilterConfigurations")) {
                text(filter, "databaseName", 128);
                for (JsonNode expression : filter.path("filterExpressions")) {
                    oneOf(expression, "type", Set.of("INCLUDE", "EXCLUDE"), null);
                    text(expression, "expression", 2048);
                }
            }
            ObjectNode glueOutput = glueConfiguration.deepCopy();
            glueOutput.put("accountId", store.account());
            glueOutput.put("region", region);
            output.set("glueRunConfiguration", glueOutput);
        } else if ("REDSHIFT".equals(type)) {
            output.set("redshiftRunConfiguration", configuration.path("redshiftRunConfiguration").deepCopy());
        } else if ("SAGEMAKER".equals(type)) {
            output.set("sageMakerRunConfiguration", configuration.path("sageMakerRunConfiguration").deepCopy());
        } else {
            throw invalid("Unsupported data source type " + type + ".");
        }
        return output;
    }

    private ArrayNode assetForms(JsonNode forms) {
        ArrayNode output = store.array();
        if (forms.isArray()) {
            for (JsonNode form : forms) {
                output.addObject().put("formName", text(form, "formName", 128))
                        .put("typeName", form.path("typeIdentifier").asText(""))
                        .put("typeRevision", form.path("typeRevision").asText("1"))
                        .put("content", form.path("content").asText("{}"));
            }
        }
        return output;
    }

    private ObjectNode dataSource(String region, String domainId, String id) {
        ObjectNode source = store.require(region, domainId, DATA_SOURCES, id, "Data source");
        if (System.currentTimeMillis() >= source.path("_").path("readyAt").asLong()) {
            String status = source.path("status").asText();
            if ("CREATING".equals(status) || "UPDATING".equals(status)) {
                source.put("status", "READY");
                store.put(region, domainId, DATA_SOURCES, id, source);
            } else if ("DELETING".equals(status)) {
                store.delete(region, domainId, DATA_SOURCES, id);
                throw notFound("Data source " + id + " was not found.");
            }
        }
        return source;
    }

    public synchronized ObjectNode getDataSource(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetDataSource").path("id")
                .asText();
        ObjectNode view = store.view(dataSource(region, domainId, text(input, "identifier", 64)));
        view.put("lastRunAssetCount", lastRunAssetCount(region, domainId, view.path("id").asText()));
        return view;
    }

    private int lastRunAssetCount(String region, String domainId, String sourceId) {
        ObjectNode last = null;
        for (ObjectNode run : store.list(region, domainId, RUNS)) {
            if (sourceId.equals(run.path("dataSourceId").asText())) {
                last = run;
            }
        }
        if (last == null) {
            return 0;
        }
        JsonNode stats = last.path("runStatisticsForAssets");
        return stats.path("added").asInt() + stats.path("updated").asInt() + stats.path("unchanged").asInt();
    }

    public synchronized ObjectNode updateDataSource(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "UpdateDataSource").path("id")
                .asText();
        ObjectNode source = dataSource(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, source.path("projectId").asText(), "UpdateDataSource");
        if ("RUNNING".equals(source.path("status").asText())) {
            throw conflict("Data source " + source.path("id").asText() + " is running.");
        }
        for (String field : List.of("name", "description")) {
            if (present(input, field)) {
                source.put(field, input.get(field).asText());
            }
        }
        if (present(input, "enableSetting")) {
            source.put("enableSetting", oneOf(input, "enableSetting", Set.of("ENABLED", "DISABLED"), null));
        }
        if (input.has("publishOnImport")) {
            source.put("publishOnImport", input.get("publishOnImport").asBoolean());
        }
        if (input.has("assetFormsInput")) {
            source.set("assetFormsOutput", assetForms(input.get("assetFormsInput")));
        }
        if (input.has("schedule")) {
            source.set("schedule", input.get("schedule").deepCopy());
        }
        if (input.has("recommendation")) {
            source.set("recommendation", input.get("recommendation").deepCopy());
        }
        if (input.has("configuration")) {
            source.set("configuration", configuration(source.path("type").asText(), input.get("configuration"),
                    region));
        }
        if (input.has("retainPermissionsOnRevokeFailure")) {
            source.put("retainPermissionsOnRevokeFailure", input.get("retainPermissionsOnRevokeFailure")
                    .asBoolean());
        }
        source.put("updatedAt", now());
        store.put(region, domainId, DATA_SOURCES, source.path("id").asText(), source);
        return store.view(source);
    }

    public synchronized ObjectNode deleteDataSource(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "DeleteDataSource").path("id")
                .asText();
        ObjectNode source = dataSource(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, source.path("projectId").asText(), "DeleteDataSource");
        if ("RUNNING".equals(source.path("status").asText())) {
            throw conflict("Data source " + source.path("id").asText() + " is running.");
        }
        if (input.has("retainPermissionsOnRevokeFailure")) {
            source.put("retainPermissionsOnRevokeFailure", input.get("retainPermissionsOnRevokeFailure")
                    .asBoolean());
        }
        ObjectNode view = store.view(source);
        view.put("status", "DELETING");
        if (transitionMillis <= 0) {
            store.delete(region, domainId, DATA_SOURCES, source.path("id").asText());
        } else {
            source.put("status", "DELETING");
            ((ObjectNode) source.get("_")).put("readyAt", System.currentTimeMillis() + transitionMillis);
            store.put(region, domainId, DATA_SOURCES, source.path("id").asText(), source);
        }
        for (ObjectNode run : store.list(region, domainId, RUNS)) {
            if (source.path("id").asText().equals(run.path("dataSourceId").asText())) {
                store.delete(region, domainId, RUNS, run.path("id").asText());
            }
        }
        return view;
    }

    public synchronized ObjectNode listDataSources(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListDataSources").path("id")
                .asText();
        String projectId = text(input, "projectIdentifier", 64);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.list(region, domainId, DATA_SOURCES)) {
            ObjectNode source;
            try {
                source = dataSource(region, domainId, stored.path("id").asText());
            } catch (AwsException e) {
                continue;
            }
            if (!projectId.equals(source.path("projectId").asText())
                    || !matches(input, "environmentIdentifier", source.path("environmentId").asText())
                    || !matches(input, "connectionIdentifier", source.path("connectionId").asText())
                    || !matches(input, "type", source.path("type").asText())
                    || !matches(input, "status", source.path("status").asText())
                    || !matches(input, "name", source.path("name").asText())) {
                continue;
            }
            ObjectNode summary = store.view(source, "domainId", "environmentId", "connectionId", "name", "type",
                    "status", "enableSetting", "schedule", "lastRunStatus", "lastRunAt", "lastRunErrorMessage",
                    "createdAt", "updatedAt", "description");
            summary.put("dataSourceId", source.path("id").asText());
            summary.put("lastRunAssetCount", lastRunAssetCount(region, domainId, source.path("id").asText()));
            items.add(summary);
        }
        return store.page(items, input, "items", item -> item.path("dataSourceId").asText());
    }

    private static boolean matches(JsonNode input, String field, String value) {
        return !present(input, field) || input.get(field).asText().equals(value);
    }

    // ---------------------------------------------------------------- data source runs

    public synchronized ObjectNode startDataSourceRun(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "StartDataSourceRun")
                .path("id").asText();
        ObjectNode source = dataSource(region, domainId, text(input, "dataSourceIdentifier", 64));
        String editor = core.requireMember(region, domainId, source.path("projectId").asText(),
                "StartDataSourceRun");
        if (!"READY".equals(source.path("status").asText()) && !"FAILED_UPDATE".equals(source.path("status")
                .asText())) {
            throw conflict("Data source " + source.path("id").asText() + " is " + source.path("status").asText()
                    + ".");
        }
        if ("DISABLED".equals(source.path("enableSetting").asText())) {
            throw conflict("Data source " + source.path("id").asText() + " is disabled.");
        }
        double now = now();
        ObjectNode run = store.object();
        String runId = store.id();
        run.put("domainId", domainId);
        run.put("dataSourceId", source.path("id").asText());
        run.put("id", runId);
        run.put("projectId", source.path("projectId").asText());
        run.put("type", "PRIORITIZED");
        run.put("dataSourceConfigurationSnapshot", source.path("configuration").toString());
        run.put("createdAt", now);
        run.put("updatedAt", now);
        run.put("startedAt", now);
        ArrayNode activities = store.array();
        int[] stats = new int[5];
        String error = null;
        String errorType = null;
        if ("GLUE".equals(source.path("type").asText())) {
            try {
                importGlue(region, domainId, source, runId, editor, activities, stats);
            } catch (AwsException e) {
                error = e.getMessage();
                errorType = "ResourceNotFoundException".equals(e.getErrorCode()) ? "RESOURCE_NOT_FOUND_EXCEPTION"
                        : "VALIDATION_EXCEPTION";
            }
        } else {
            error = "The " + source.path("type").asText() + " source of data source " + source.path("id").asText()
                    + " is not reachable.";
            errorType = "RESOURCE_NOT_FOUND_EXCEPTION";
        }
        String status = error != null ? "FAILED" : stats[4] > 0 ? "PARTIALLY_SUCCEEDED" : "SUCCESS";
        run.putObject("runStatisticsForAssets").put("added", stats[0]).put("updated", stats[1])
                .put("unchanged", stats[2]).put("skipped", stats[3]).put("failed", stats[4]);
        if (error != null) {
            run.putObject("errorMessage").put("errorType", errorType).put("errorDetail", error);
        }
        run.putObject("lineageSummary").put("importStatus", "SUCCESS");
        run.put("status", transitionMillis <= 0 ? status : "RUNNING");
        if (transitionMillis <= 0) {
            run.put("stoppedAt", now);
        }
        run.putObject("_").put("finalStatus", status).put("readyAt", System.currentTimeMillis() + transitionMillis)
                .set("activities", activities);
        store.put(region, domainId, RUNS, runId, run);
        source.put("lastRunStatus", run.path("status").asText());
        source.put("lastRunAt", now);
        if (error != null) {
            source.set("lastRunErrorMessage", run.get("errorMessage").deepCopy());
        } else {
            source.remove("lastRunErrorMessage");
        }
        store.put(region, domainId, DATA_SOURCES, source.path("id").asText(), source);
        ObjectNode view = store.view(run);
        view.remove("lineageSummary");
        return view;
    }

    private ObjectNode settleRun(String region, String domainId, ObjectNode run) {
        if ("RUNNING".equals(run.path("status").asText())
                && System.currentTimeMillis() >= run.path("_").path("readyAt").asLong()) {
            run.put("status", run.path("_").path("finalStatus").asText());
            run.put("stoppedAt", now());
            run.put("updatedAt", now());
            store.put(region, domainId, RUNS, run.path("id").asText(), run);
            store.get(region, domainId, DATA_SOURCES, run.path("dataSourceId").asText()).ifPresent(source -> {
                source.put("lastRunStatus", run.path("status").asText());
                store.put(region, domainId, DATA_SOURCES, source.path("id").asText(), source);
            });
        }
        return run;
    }

    private void importGlue(String region, String domainId, ObjectNode source, String runId, String editor,
                            ArrayNode activities, int[] stats) {
        if (!glue.isResolvable()) {
            throw notFound("AWS Glue is not enabled.");
        }
        JsonNode configuration = source.path("configuration").path("glueRunConfiguration");
        String projectId = source.path("projectId").asText();
        for (JsonNode filter : configuration.path("relationalFilterConfigurations")) {
            String database = filter.path("databaseName").asText();
            glue.get().getDatabase(database);
            for (Table table : glue.get().getTables(database)) {
                if (!included(table.getName(), filter.path("filterExpressions"))) {
                    continue;
                }
                double now = now();
                ObjectNode activity = activities.addObject();
                activity.put("database", database);
                activity.put("dataSourceRunId", runId);
                activity.put("technicalName", table.getName());
                activity.put("projectId", projectId);
                if (table.getDescription() != null) {
                    activity.put("technicalDescription", table.getDescription());
                }
                activity.put("createdAt", now);
                activity.put("updatedAt", now);
                try {
                    String outcome = upsertTable(region, domainId, source, runId, editor, database, table, activity);
                    activity.put("dataAssetStatus", outcome);
                    switch (outcome) {
                        case "SUCCEEDED_CREATED" -> stats[0]++;
                        case "SUCCEEDED_UPDATED" -> stats[1]++;
                        case "UNCHANGED" -> stats[2]++;
                        default -> stats[3]++;
                    }
                } catch (AwsException e) {
                    activity.put("dataAssetStatus", "FAILED");
                    activity.putObject("errorMessage").put("errorType", "VALIDATION_EXCEPTION")
                            .put("errorDetail", e.getMessage());
                    stats[4]++;
                }
            }
        }
    }

    /** Keeps a table when any INCLUDE expression matches (or there is none) and no EXCLUDE expression does. */
    private static boolean included(String table, JsonNode expressions) {
        boolean anyInclude = false;
        boolean include = false;
        for (JsonNode expression : expressions) {
            Pattern pattern = Pattern.compile(glob(expression.path("expression").asText()), Pattern.CASE_INSENSITIVE);
            boolean matched = pattern.matcher(table).matches();
            if ("EXCLUDE".equals(expression.path("type").asText()) && matched) {
                return false;
            }
            if ("INCLUDE".equals(expression.path("type").asText())) {
                anyInclude = true;
                include |= matched;
            }
        }
        return !anyInclude || include;
    }

    private static String glob(String expression) {
        StringBuilder regex = new StringBuilder();
        for (char character : expression.toCharArray()) {
            regex.append(character == '*' ? ".*" : Pattern.quote(String.valueOf(character)));
        }
        return regex.toString();
    }

    private String upsertTable(String region, String domainId, ObjectNode source, String runId, String editor,
                               String database, Table table, ObjectNode activity) {
        String tableArn = store.regions().buildArn("glue", region, store.account(), "table/" + database + "/"
                + table.getName());
        ObjectNode content = store.object();
        content.put("databaseName", database);
        content.put("tableName", table.getName());
        content.put("tableArn", tableArn);
        content.put("catalogId", store.account());
        content.put("region", region);
        content.put("sourceClientType", "GLUE");
        if (table.getDescription() != null) {
            content.put("tableDescription", table.getDescription());
        }
        ArrayNode columns = content.putArray("columns");
        if (table.getStorageDescriptor() != null && table.getStorageDescriptor().getColumns() != null) {
            for (Column column : table.getStorageDescriptor().getColumns()) {
                ObjectNode entry = columns.addObject().put("columnName", column.getName())
                        .put("dataType", Objects.toString(column.getType(), "string"));
                if (column.getComment() != null) {
                    entry.put("description", column.getComment());
                }
            }
        }
        ArrayNode partitions = content.putArray("partitionKeys");
        if (table.getPartitionKeys() != null) {
            for (Column column : table.getPartitionKeys()) {
                partitions.addObject().put("columnName", column.getName())
                        .put("dataType", Objects.toString(column.getType(), "string"));
            }
        }
        ObjectNode reference = store.object().put("dataSourceIdentifier", source.path("id").asText())
                .put("filterableDataSourceId", source.path("id").asText()).put("dataSourceRunId", runId);
        ObjectNode existing = null;
        for (ObjectNode asset : store.list(region, domainId, DataZoneCatalog.ASSETS)) {
            if (tableArn.equals(asset.path("externalIdentifier").asText())) {
                existing = asset;
            }
        }
        String outcome;
        ObjectNode asset;
        if (existing == null) {
            ObjectNode create = store.object();
            create.put("domainIdentifier", domainId);
            create.put("name", table.getName());
            create.put("typeIdentifier", GLUE_TABLE_TYPE);
            create.put("externalIdentifier", tableArn);
            create.put("owningProjectIdentifier", source.path("projectId").asText());
            if (table.getDescription() != null) {
                create.put("description", table.getDescription());
            }
            ArrayNode forms = create.putArray("formsInput");
            forms.addObject().put("formName", "GlueTableForm").put("typeIdentifier",
                    "amazon.datazone.GlueTableFormType").put("content", content.toString());
            forms.addObject().put("formName", "DataSourceReferenceForm").put("typeIdentifier",
                    "amazon.datazone.DataSourceReferenceFormType").put("content", reference.toString());
            for (JsonNode form : source.path("assetFormsOutput")) {
                forms.addObject().put("formName", form.path("formName").asText())
                        .put("typeIdentifier", form.path("typeName").asText())
                        .put("content", form.path("content").asText("{}"));
            }
            String id = catalog.createAsset(create, region).path("id").asText();
            asset = catalog.asset(region, domainId, id);
            outcome = "SUCCEEDED_CREATED";
        } else {
            asset = existing;
            String previous = null;
            for (JsonNode form : asset.path("formsOutput")) {
                if ("GlueTableForm".equals(form.path("formName").asText())) {
                    previous = form.path("content").asText();
                }
            }
            if (Objects.equals(previous, content.toString())) {
                outcome = "UNCHANGED";
            } else {
                catalog.upsertForm(asset, "GlueTableForm", "amazon.datazone.GlueTableFormType", content.toString());
                catalog.upsertForm(asset, "DataSourceReferenceForm", "amazon.datazone.DataSourceReferenceFormType",
                        reference.toString());
                if (table.getDescription() != null) {
                    asset.put("description", table.getDescription());
                }
                catalog.bumpRevision(asset, editor);
                store.put(region, domainId, DataZoneCatalog.ASSETS, asset.path("id").asText(), asset);
                outcome = "SUCCEEDED_UPDATED";
            }
        }
        activity.put("dataAssetId", asset.path("id").asText());
        if (source.path("publishOnImport").asBoolean(false) && !"UNCHANGED".equals(outcome)) {
            catalog.publish(region, domainId, asset, null, "PUBLISH", editor);
        }
        return outcome;
    }

    public synchronized ObjectNode getDataSourceRun(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetDataSourceRun").path("id")
                .asText();
        ObjectNode run = store.require(region, domainId, RUNS, text(input, "identifier", 64), "Data source run");
        return store.view(settleRun(region, domainId, run));
    }

    public synchronized ObjectNode listDataSourceRuns(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListDataSourceRuns").path("id")
                .asText();
        String sourceId = dataSource(region, domainId, text(input, "dataSourceIdentifier", 64)).path("id").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode run : store.list(region, domainId, RUNS)) {
            if (sourceId.equals(run.path("dataSourceId").asText())) {
                settleRun(region, domainId, run);
                if (matches(input, "status", run.path("status").asText())) {
                    ObjectNode summary = store.view(run);
                    summary.remove("dataSourceConfigurationSnapshot");
                    summary.remove("domainId");
                    items.add(summary);
                }
            }
        }
        items.sort(Comparator.comparing((ObjectNode item) -> item.path("createdAt").asDouble()).reversed());
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    public synchronized ObjectNode listDataSourceRunActivities(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListDataSourceRunActivities")
                .path("id").asText();
        ObjectNode run = settleRun(region, domainId, store.require(region, domainId, RUNS, text(input,
                "identifier", 64), "Data source run"));
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode activity : run.path("_").path("activities")) {
            if (matches(input, "status", activity.path("dataAssetStatus").asText())) {
                items.add(((ObjectNode) activity).deepCopy());
            }
        }
        return store.page(items, input, "items", item -> item.path("database").asText() + "/"
                + item.path("technicalName").asText());
    }

    // ---------------------------------------------------------------- metadata generation runs

    public synchronized ObjectNode startMetadataGenerationRun(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "StartMetadataGenerationRun")
                .path("id").asText();
        String projectId = text(input, "owningProjectIdentifier", 64);
        String creator = core.requireMember(region, domainId, projectId, "StartMetadataGenerationRun");
        JsonNode target = input.path("target");
        oneOf(target, "type", Set.of("ASSET"), null);
        ObjectNode asset = catalog.asset(region, domainId, text(target, "identifier", 64));
        if (!projectId.equals(asset.path("owningProjectId").asText())) {
            throw invalid("Asset " + asset.path("id").asText() + " is not owned by project " + projectId + ".");
        }
        Set<String> allowed = Set.of("BUSINESS_DESCRIPTIONS", "BUSINESS_NAMES", "BUSINESS_GLOSSARY_ASSOCIATIONS");
        Set<String> types = new LinkedHashSet<>();
        if (present(input, "type")) {
            types.add(oneOf(input, "type", allowed, null));
        }
        for (JsonNode type : input.path("types")) {
            if (!allowed.contains(type.asText())) {
                throw invalid("types must hold " + String.join(", ", allowed) + ".");
            }
            types.add(type.asText());
        }
        if (types.isEmpty()) {
            throw invalid("Specify type or types.");
        }
        for (ObjectNode existing : store.list(region, domainId, METADATA_RUNS)) {
            settleMetadataRun(region, domainId, existing);
            if (asset.path("id").asText().equals(existing.path("target").path("identifier").asText())
                    && Set.of("SUBMITTED", "IN_PROGRESS").contains(existing.path("status").asText())) {
                throw conflict("Asset " + asset.path("id").asText() + " already has metadata generation run "
                        + existing.path("id").asText() + " in progress.");
            }
        }
        ObjectNode run = store.object();
        run.put("domainId", domainId);
        run.put("id", store.id());
        ObjectNode runTarget = run.putObject("target");
        runTarget.put("type", "ASSET");
        runTarget.put("identifier", asset.path("id").asText());
        runTarget.put("revision", present(target, "revision") ? target.get("revision").asText()
                : asset.path("revision").asText());
        run.put("status", "SUBMITTED");
        run.put("type", types.iterator().next());
        ArrayNode typeList = run.putArray("types");
        types.forEach(typeList::add);
        run.put("createdAt", now());
        run.put("createdBy", creator);
        run.put("owningProjectId", projectId);
        run.putObject("_").put("readyAt", System.currentTimeMillis() + transitionMillis);
        store.put(region, domainId, METADATA_RUNS, run.path("id").asText(), run);
        settleMetadataRun(region, domainId, run);
        ObjectNode view = store.view(run, "domainId", "id", "status", "type", "types", "createdAt", "createdBy",
                "owningProjectId");
        view.put("status", "SUBMITTED");
        return view;
    }

    private ObjectNode settleMetadataRun(String region, String domainId, ObjectNode run) {
        if ("SUBMITTED".equals(run.path("status").asText())
                && System.currentTimeMillis() >= run.path("_").path("readyAt").asLong()) {
            Set<String> types = new HashSet<>();
            run.path("types").forEach(type -> types.add(type.asText()));
            ArrayNode stats = run.putArray("typeStats");
            String assetId = run.path("target").path("identifier").asText();
            ObjectNode asset = store.get(region, domainId, DataZoneCatalog.ASSETS, assetId).orElse(null);
            if (asset == null) {
                run.put("status", "FAILED");
                types.forEach(type -> stats.addObject().put("type", type).put("status", "FAILED")
                        .put("errorMessage", "Asset " + assetId + " was deleted."));
            } else {
                catalog.predict(region, domainId, asset, types);
                run.put("status", "SUCCEEDED");
                types.forEach(type -> stats.addObject().put("type", type).put("status", "SUCCEEDED"));
            }
            store.put(region, domainId, METADATA_RUNS, run.path("id").asText(), run);
        }
        return run;
    }

    private ObjectNode metadataRun(String region, String domainId, String id) {
        return settleMetadataRun(region, domainId, store.require(region, domainId, METADATA_RUNS, id,
                "Metadata generation run"));
    }

    public synchronized ObjectNode getMetadataGenerationRun(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetMetadataGenerationRun")
                .path("id").asText();
        return store.view(metadataRun(region, domainId, text(input, "identifier", 64)));
    }

    public synchronized ObjectNode cancelMetadataGenerationRun(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "CancelMetadataGenerationRun")
                .path("id").asText();
        ObjectNode run = metadataRun(region, domainId, text(input, "identifier", 64));
        core.requireMember(region, domainId, run.path("owningProjectId").asText(), "CancelMetadataGenerationRun");
        if (!Set.of("SUBMITTED", "IN_PROGRESS").contains(run.path("status").asText())) {
            throw conflict("Metadata generation run " + run.path("id").asText() + " is "
                    + run.path("status").asText() + ".");
        }
        run.put("status", "CANCELED");
        store.put(region, domainId, METADATA_RUNS, run.path("id").asText(), run);
        return store.object();
    }

    public synchronized ObjectNode listMetadataGenerationRuns(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListMetadataGenerationRuns")
                .path("id").asText();
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode stored : store.list(region, domainId, METADATA_RUNS)) {
            ObjectNode run = settleMetadataRun(region, domainId, stored);
            boolean typed = !present(input, "type");
            for (JsonNode type : run.path("types")) {
                typed |= type.asText().equals(input.path("type").asText());
            }
            if (typed && matches(input, "status", run.path("status").asText())
                    && matches(input, "targetIdentifier", run.path("target").path("identifier").asText())) {
                ObjectNode view = store.view(run);
                view.remove("typeStats");
                items.add(view);
            }
        }
        return store.page(items, input, "items", item -> item.path("id").asText());
    }

    // ---------------------------------------------------------------- lineage

    public synchronized ObjectNode postLineageEvent(ObjectNode input, String region) {
        String domainId = core.available(region, text(input, "domainIdentifier", 64), "PostLineageEvent").path("id")
                .asText();
        String creator = core.callerProfileId(region, domainId);
        String encoded = text(input, "event", 300000 * 2);
        JsonNode event;
        try {
            event = store.mapper().readTree(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw invalid("The lineage event is not valid JSON.");
        }
        String eventTime = text(event, "eventTime", 64);
        double at;
        try {
            at = OffsetDateTime.parse(eventTime).toInstant().toEpochMilli() / 1000.0;
        } catch (DateTimeParseException e) {
            throw invalid("eventTime must be an ISO 8601 timestamp.");
        }
        String runId = text(event.path("run"), "runId", 256);
        String jobNamespace = text(event.path("job"), "namespace", 1024);
        String jobName = text(event.path("job"), "name", 1024);
        text(event, "producer", 2048);
        String eventType = event.path("eventType").asText("OTHER").toUpperCase();
        String clientToken = optional(input, "clientToken");
        if (clientToken != null) {
            for (ObjectNode existing : store.list(region, domainId, LINEAGE_EVENTS)) {
                if (clientToken.equals(existing.path("_").path("clientToken").asText())) {
                    return store.object().put("id", existing.path("id").asText()).put("domainId", domainId);
                }
            }
        }
        String id = store.id();
        double now = now();
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        ObjectNode summary = store.object();
        ObjectNode openLineage = summary.putObject("openLineageRunEventSummary");
        openLineage.put("eventType", eventType);
        openLineage.put("runId", runId);
        openLineage.putObject("job").put("namespace", jobNamespace).put("name", jobName);
        ArrayNode summaryInputs = openLineage.putArray("inputs");
        ArrayNode summaryOutputs = openLineage.putArray("outputs");
        for (JsonNode dataset : event.path("inputs")) {
            String sourceId = text(dataset, "namespace", 1024) + "/" + text(dataset, "name", 1024);
            inputs.add(node(region, domainId, "amazon.datazone.DatasetType", sourceId, dataset.path("name").asText(),
                    at, creator));
            summaryInputs.addObject().put("namespace", dataset.path("namespace").asText())
                    .put("name", dataset.path("name").asText());
        }
        for (JsonNode dataset : event.path("outputs")) {
            String sourceId = text(dataset, "namespace", 1024) + "/" + text(dataset, "name", 1024);
            outputs.add(node(region, domainId, "amazon.datazone.DatasetType", sourceId, dataset.path("name").asText(),
                    at, creator));
            summaryOutputs.addObject().put("namespace", dataset.path("namespace").asText())
                    .put("name", dataset.path("name").asText());
        }
        String job = node(region, domainId, "amazon.datazone.JobType", jobNamespace + "/" + jobName, jobName, at,
                creator);
        String jobRun = node(region, domainId, "amazon.datazone.JobRunType", jobNamespace + "/" + jobName + "/"
                + runId, jobName + " " + runId, at, creator);
        link(region, domainId, job, jobRun, at);
        for (String upstream : inputs) {
            link(region, domainId, upstream, jobRun, at);
        }
        for (String downstream : outputs) {
            link(region, domainId, jobRun, downstream, at);
        }
        ObjectNode stored = store.object();
        stored.put("id", id);
        stored.put("domainId", domainId);
        stored.put("event", encoded);
        stored.put("createdBy", creator);
        stored.put("processingStatus", "SUCCESS");
        stored.put("eventTime", at);
        stored.put("createdAt", now);
        stored.set("eventSummary", summary);
        stored.putObject("_").put("clientToken", clientToken);
        store.put(region, domainId, LINEAGE_EVENTS, id, stored);
        return store.object().put("id", id).put("domainId", domainId);
    }

    /** The lineage node with a source identifier, created or brought up to the event's time. */
    private String node(String region, String domainId, String type, String sourceIdentifier, String name, double at,
                        String creator) {
        for (ObjectNode existing : store.list(region, domainId, LINEAGE_NODES)) {
            if (sourceIdentifier.equals(existing.path("sourceIdentifier").asText())
                    && type.equals(existing.path("typeName").asText())) {
                if (at > existing.path("eventTimestamp").asDouble()) {
                    existing.put("eventTimestamp", at);
                    existing.put("updatedAt", now());
                    existing.put("updatedBy", creator);
                }
                ((ArrayNode) existing.path("_").path("history")).add(at);
                store.put(region, domainId, LINEAGE_NODES, existing.path("id").asText(), existing);
                return existing.path("id").asText();
            }
        }
        double now = now();
        ObjectNode node = store.object();
        node.put("domainId", domainId);
        node.put("id", store.id());
        node.put("name", name);
        node.put("createdAt", now);
        node.put("createdBy", creator);
        node.put("updatedAt", now);
        node.put("updatedBy", creator);
        node.put("typeName", type);
        node.put("typeRevision", "1");
        node.put("sourceIdentifier", sourceIdentifier);
        node.put("eventTimestamp", at);
        node.putArray("formsOutput");
        node.putArray("upstreamNodes");
        node.putArray("downstreamNodes");
        node.putObject("_").putArray("history").add(at);
        store.put(region, domainId, LINEAGE_NODES, node.path("id").asText(), node);
        return node.path("id").asText();
    }

    private void link(String region, String domainId, String upstreamId, String downstreamId, double at) {
        ObjectNode upstream = store.require(region, domainId, LINEAGE_NODES, upstreamId, "Lineage node");
        ObjectNode downstream = store.require(region, domainId, LINEAGE_NODES, downstreamId, "Lineage node");
        reference((ArrayNode) upstream.get("downstreamNodes"), downstreamId, at);
        reference((ArrayNode) downstream.get("upstreamNodes"), upstreamId, at);
        store.put(region, domainId, LINEAGE_NODES, upstreamId, upstream);
        store.put(region, domainId, LINEAGE_NODES, downstreamId, downstream);
    }

    private static void reference(ArrayNode references, String id, double at) {
        for (JsonNode reference : references) {
            if (id.equals(reference.path("id").asText())) {
                ((ObjectNode) reference).put("eventTimestamp", Math.max(at, reference.path("eventTimestamp")
                        .asDouble()));
                return;
            }
        }
        references.addObject().put("id", id).put("eventTimestamp", at);
    }

    /**
     * A lineage node by its id or its source identifier. A source identifier in a request path may arrive with its
     * {@code //} collapsed to {@code /}, so the two are compared with runs of slashes collapsed.
     */
    ObjectNode lineageNode(String region, String domainId, String identifier) {
        return store.get(region, domainId, LINEAGE_NODES, identifier).orElseGet(() -> {
            String wanted = identifier.replaceAll("/+", "/");
            for (ObjectNode node : store.list(region, domainId, LINEAGE_NODES)) {
                if (wanted.equals(node.path("sourceIdentifier").asText().replaceAll("/+", "/"))) {
                    return node;
                }
            }
            throw notFound("Lineage node " + identifier + " was not found.");
        });
    }

    public synchronized ObjectNode getLineageNode(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetLineageNode").path("id")
                .asText();
        ObjectNode node = lineageNode(region, domainId, text(input, "identifier", 2048));
        ObjectNode view = store.view(node);
        if (input.hasNonNull("eventTimestamp")) {
            double at = input.get("eventTimestamp").asDouble();
            double version = Double.NEGATIVE_INFINITY;
            for (JsonNode time : node.path("_").path("history")) {
                if (time.asDouble() <= at) {
                    version = Math.max(version, time.asDouble());
                }
            }
            if (version == Double.NEGATIVE_INFINITY) {
                throw notFound("Lineage node " + node.path("id").asText() + " did not exist at " + at + ".");
            }
            view.put("eventTimestamp", version);
            for (String edge : List.of("upstreamNodes", "downstreamNodes")) {
                ArrayNode filtered = store.array();
                for (JsonNode reference : node.path(edge)) {
                    if (reference.path("eventTimestamp").asDouble() <= at) {
                        filtered.add(reference.deepCopy());
                    }
                }
                view.set(edge, filtered);
            }
        }
        return view;
    }

    public synchronized ObjectNode listLineageNodeHistory(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListLineageNodeHistory")
                .path("id").asText();
        ObjectNode node = lineageNode(region, domainId, text(input, "identifier", 2048));
        double from = input.path("eventTimestampGTE").asDouble(Double.NEGATIVE_INFINITY);
        double to = input.path("eventTimestampLTE").asDouble(Double.POSITIVE_INFINITY);
        Set<Double> times = new LinkedHashSet<>();
        node.path("_").path("history").forEach(time -> times.add(time.asDouble()));
        List<ObjectNode> items = new ArrayList<>();
        for (double time : times) {
            if (time >= from && time <= to) {
                ObjectNode summary = store.view(node, "domainId", "name", "description", "createdAt", "createdBy",
                        "updatedAt", "updatedBy", "id", "typeName", "typeRevision", "sourceIdentifier");
                summary.put("eventTimestamp", time);
                items.add(summary);
            }
        }
        Comparator<ObjectNode> order = Comparator.comparing(item -> item.path("eventTimestamp").asDouble());
        items.sort("ASCENDING".equals(input.path("sortOrder").asText()) ? order : order.reversed());
        ObjectNode response = store.page(items, input, "nodes", item -> item.path("eventTimestamp").asText());
        return response;
    }

    public synchronized ObjectNode getLineageEvent(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "GetLineageEvent").path("id")
                .asText();
        ObjectNode event = store.require(region, domainId, LINEAGE_EVENTS, text(input, "identifier", 64),
                "Lineage event");
        ObjectNode view = store.view(event);
        view.remove("eventSummary");
        return view;
    }

    public synchronized ObjectNode listLineageEvents(ObjectNode input, String region) {
        String domainId = core.domain(region, text(input, "domainIdentifier", 64), "ListLineageEvents").path("id")
                .asText();
        double after = input.path("timestampAfter").asDouble(Double.NEGATIVE_INFINITY);
        double before = input.path("timestampBefore").asDouble(Double.POSITIVE_INFINITY);
        List<ObjectNode> items = new ArrayList<>();
        for (ObjectNode event : store.list(region, domainId, LINEAGE_EVENTS)) {
            double at = event.path("eventTime").asDouble();
            if (at > after && at < before && matches(input, "processingStatus", event.path("processingStatus")
                    .asText())) {
                ObjectNode summary = store.view(event);
                summary.remove("event");
                items.add(summary);
            }
        }
        Comparator<ObjectNode> order = Comparator.comparing(item -> item.path("eventTime").asDouble());
        items.sort("ASCENDING".equals(input.path("sortOrder").asText()) ? order : order.reversed());
        return store.page(items, input, "items", item -> item.path("id").asText());
    }
}
