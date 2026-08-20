package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Crawler {
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Role")
    private String role;
    @JsonProperty("DatabaseName")
    private String databaseName;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("Targets")
    private Map<String, Object> targets;
    @JsonProperty("Schedule")
    @JsonDeserialize(using = CrawlerSchedule.Deserializer.class)
    private CrawlerSchedule schedule;
    @JsonProperty("Classifiers")
    private List<String> classifiers;
    @JsonProperty("TablePrefix")
    private String tablePrefix;
    @JsonProperty("SchemaChangePolicy")
    private Map<String, Object> schemaChangePolicy;
    @JsonProperty("RecrawlPolicy")
    private Map<String, Object> recrawlPolicy;
    @JsonProperty("Configuration")
    private String configuration;
    @JsonProperty("State")
    private String state;
    @JsonProperty("CrawlElapsedTime")
    private Long crawlElapsedTime;
    @JsonProperty("CreationTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant creationTime;
    @JsonProperty("LastUpdated")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastUpdated;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getTargets() { return targets; }
    public void setTargets(Map<String, Object> targets) { this.targets = targets; }
    public CrawlerSchedule getSchedule() { return schedule; }
    public void setSchedule(CrawlerSchedule schedule) { this.schedule = schedule; }
    public List<String> getClassifiers() { return classifiers; }
    public void setClassifiers(List<String> classifiers) { this.classifiers = classifiers; }
    public String getTablePrefix() { return tablePrefix; }
    public void setTablePrefix(String tablePrefix) { this.tablePrefix = tablePrefix; }
    public Map<String, Object> getSchemaChangePolicy() { return schemaChangePolicy; }
    public void setSchemaChangePolicy(Map<String, Object> schemaChangePolicy) {
        this.schemaChangePolicy = schemaChangePolicy;
    }
    public Map<String, Object> getRecrawlPolicy() { return recrawlPolicy; }
    public void setRecrawlPolicy(Map<String, Object> recrawlPolicy) { this.recrawlPolicy = recrawlPolicy; }
    public String getConfiguration() { return configuration; }
    public void setConfiguration(String configuration) { this.configuration = configuration; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Long getCrawlElapsedTime() { return crawlElapsedTime; }
    public void setCrawlElapsedTime(Long crawlElapsedTime) { this.crawlElapsedTime = crawlElapsedTime; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }

    /**
     * CreateCrawler/UpdateCrawler accept {@code Schedule} as a cron string. GetCrawler
     * returns AWS's {@code Schedule} object ({@code ScheduleExpression} + {@code State}).
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CrawlerSchedule {
        @JsonProperty("ScheduleExpression")
        private String scheduleExpression;
        @JsonProperty("State")
        private String state;

        public CrawlerSchedule() {}

        public CrawlerSchedule(String scheduleExpression) {
            this.scheduleExpression = scheduleExpression;
            this.state = "SCHEDULED";
        }

        public String getScheduleExpression() { return scheduleExpression; }
        public void setScheduleExpression(String scheduleExpression) { this.scheduleExpression = scheduleExpression; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        public static class Deserializer extends JsonDeserializer<CrawlerSchedule> {
            @Override
            public CrawlerSchedule deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                JsonNode node = parser.getCodec().readTree(parser);
                if (node == null || node.isNull()) {
                    return null;
                }
                if (node.isTextual()) {
                    String expression = node.asText();
                    if (expression == null || expression.isBlank()) {
                        return null;
                    }
                    return new CrawlerSchedule(expression);
                }
                if (node.isObject()) {
                    String expression = node.path("ScheduleExpression").asText(null);
                    if (expression == null || expression.isBlank()) {
                        return null;
                    }
                    CrawlerSchedule schedule = new CrawlerSchedule(expression);
                    String state = node.path("State").asText(null);
                    if (state != null && !state.isBlank()) {
                        schedule.setState(state);
                    }
                    return schedule;
                }
                return null;
            }
        }
    }
}
