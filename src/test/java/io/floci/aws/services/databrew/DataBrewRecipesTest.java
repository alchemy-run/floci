package io.floci.aws.services.databrew;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recipe engine, the CSV and JSON readers, and ruleset evaluation, over small in-memory tables. */
class DataBrewRecipesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DataBrewTable people() {
        return DataBrewTable.readCsv("id,name,city,score\n1,alice,\"Paris, FR\",10\n2,bob,,7\n3,carol,Oslo,\n"
                + "3,carol,Oslo,\n", ',', true);
    }

    private static JsonNode steps(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void readsQuotedCsvWithMissingValues() {
        DataBrewTable table = people();
        assertEquals(List.of("id", "name", "city", "score"), table.columns);
        assertEquals(4, table.rows.size());
        assertEquals("Paris, FR", table.rows.get(0).get("city"));
        assertEquals(null, table.rows.get(1).get("city"));
        assertEquals("id,name,city,score\n1,alice,\"Paris, FR\",10\n2,bob,,7\n3,carol,Oslo,\n3,carol,Oslo,\n",
                table.toCsv(',', true));
    }

    @Test
    void readsJsonLinesAndDocuments() {
        DataBrewTable lines = DataBrewTable.readJson(MAPPER, "{\"a\":1,\"b\":{\"c\":2}}\n{\"a\":3,\"d\":null}\n",
                false);
        assertEquals(List.of("a", "b", "d"), lines.columns);
        assertEquals("{\"c\":2}", lines.rows.get(0).get("b"));
        assertEquals(null, lines.rows.get(0).get("d"));
        DataBrewTable document = DataBrewTable.readJson(MAPPER, "[{\"a\":1},{\"a\":2}]", true);
        assertEquals(2, document.rows.size());
    }

    @Test
    void appliesColumnAndTextTransforms() throws Exception {
        DataBrewTable result = DataBrewRecipes.apply(people(), steps("""
                [
                  {"Action":{"Operation":"UPPER_CASE","Parameters":{"sourceColumn":"name"}}},
                  {"Action":{"Operation":"RENAME","Parameters":{"sourceColumn":"city","targetColumn":"town"}}},
                  {"Action":{"Operation":"FILL_WITH_CUSTOM","Parameters":{"sourceColumn":"town","value":"unknown"}}},
                  {"Action":{"Operation":"ADD_PREFIX","Parameters":{"sourceColumn":"id","pattern":"P-"}}},
                  {"Action":{"Operation":"MOVE_TO_END","Parameters":{"sourceColumn":"id"}}},
                  {"Action":{"Operation":"DELETE_DUPLICATE_ROWS","Parameters":{}}}
                ]"""));
        assertEquals(List.of("name", "town", "score", "id"), result.columns);
        assertEquals(3, result.rows.size());
        assertEquals("ALICE", result.rows.get(0).get("name"));
        assertEquals("unknown", result.rows.get(1).get("town"));
        assertEquals("P-3", result.rows.get(2).get("id"));
    }

    @Test
    void conditionsSelectTheRowsAStepApplies() throws Exception {
        DataBrewTable removed = DataBrewRecipes.apply(people(), steps("""
                [{"Action":{"Operation":"REMOVE_VALUES","Parameters":{"sourceColumn":"score"}},
                  "ConditionExpressions":[{"Condition":"IS_MISSING","TargetColumn":"score"}]}]"""));
        assertEquals(2, removed.rows.size());
        DataBrewTable upper = DataBrewRecipes.apply(people(), steps("""
                [{"Action":{"Operation":"UPPER_CASE","Parameters":{"sourceColumn":"name"}},
                  "ConditionExpressions":[{"Condition":"GREATER_THAN","Value":"8","TargetColumn":"score"}]}]"""));
        assertEquals("ALICE", upper.rows.get(0).get("name"));
        assertEquals("bob", upper.rows.get(1).get("name"));
        DataBrewTable between = DataBrewRecipes.apply(people(), steps("""
                [{"Action":{"Operation":"REMOVE_VALUES","Parameters":{"sourceColumn":"id"}},
                  "ConditionExpressions":[{"Condition":"IS_BETWEEN","TargetColumn":"id",
                    "Value":"{\\"greaterThanEquals\\":\\"2\\",\\"lessThanEquals\\":\\"3\\"}"}]}]"""));
        assertEquals(1, between.rows.size());
    }

    @Test
    void computesAndSplitsColumns() throws Exception {
        DataBrewTable result = DataBrewRecipes.apply(people(), steps("""
                [
                  {"Action":{"Operation":"MULTIPLY","Parameters":{"sourceColumn1":"score","value2":"1.5",
                    "targetColumn":"weighted"}}},
                  {"Action":{"Operation":"MERGE","Parameters":{"sourceColumns":"[\\"name\\",\\"id\\"]",
                    "delimiter":"#","targetColumn":"key"}}},
                  {"Action":{"Operation":"SPLIT_COLUMN_SINGLE_DELIMITER","Parameters":{"sourceColumn":"city",
                    "pattern":", ","limit":"1"}}},
                  {"Action":{"Operation":"SORT","Parameters":{"expressions":
                    "[{\\"sourceColumn\\":\\"score\\",\\"ordering\\":\\"DESCENDING\\",\\"nullsOrdering\\":\\"NULLS_BOTTOM\\"}]"}}}
                ]"""));
        assertEquals("15", result.rows.get(0).get("weighted"));
        assertEquals("alice#1", result.rows.get(0).get("key"));
        assertEquals("Paris", result.rows.get(0).get("city_1"));
        assertEquals("FR", result.rows.get(0).get("city_2"));
        assertEquals(null, result.rows.get(3).get("score"));
    }

    @Test
    void unsupportedOperationsNameTheStep() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> DataBrewRecipes.apply(
                people(), steps("[{\"Action\":{\"Operation\":\"PIVOT\",\"Parameters\":{}}}]")));
        assertTrue(error.getMessage().contains("step 1 (PIVOT)"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DataBrewRecipes.apply(people(), steps(
                "[{\"Action\":{\"Operation\":\"UPPER_CASE\",\"Parameters\":{\"sourceColumn\":\"missing\"}}}]")));
    }

    @Test
    void profilesColumns() {
        ObjectNode profile = DataBrewProfiles.profile(MAPPER, people(), MAPPER.createObjectNode());
        assertEquals(1, profile.path("duplicateRowsCount").asInt());
        JsonNode score = profile.path("columns").get(3);
        assertEquals("int", score.path("type").asText());
        assertEquals(2, score.path("missingValuesCount").asInt());
        assertEquals("8.5", score.path("mean").asText());
        assertEquals("string", profile.path("columns").get(1).path("type").asText());
    }

    @Test
    void evaluatesRulesets() throws Exception {
        JsonNode ruleset = MAPPER.readTree("""
                {"Name":"quality","ResourceArn":"arn:aws:databrew:us-east-1:000000000000:ruleset/quality","Rules":[
                  {"Name":"ids","CheckExpression":"AGG(MISSING_VALUES_PERCENTAGE) == :val1",
                   "SubstitutionMap":{":val1":"0"},"ColumnSelectors":[{"Name":"id"}]},
                  {"Name":"dupes","CheckExpression":"AGG(DUPLICATE_ROWS_COUNT) == :val1","SubstitutionMap":{":val1":"0"}},
                  {"Name":"scores","CheckExpression":":col1 >= :val1","SubstitutionMap":{":col1":"`score`",":val1":"7"},
                   "Threshold":{"Value":50,"Type":"GREATER_THAN_OR_EQUAL","Unit":"PERCENTAGE"}},
                  {"Name":"names","CheckExpression":":col1 in (:val1, :val2) or :col1 starts_with :val3",
                   "SubstitutionMap":{":col1":"`name`",":val1":"alice",":val2":"bob",":val3":"car"}},
                  {"Name":"broken","CheckExpression":"AGG(ENTROPY) > :val1","SubstitutionMap":{":val1":"1"},
                   "ColumnSelectors":[{"Name":"id"}]}
                ]}""");
        ObjectNode result = DataBrewProfiles.validate(MAPPER, people(), ruleset);
        JsonNode rules = result.path("ruleResults");
        assertEquals("SUCCEEDED", rules.get(0).path("status").asText());
        assertEquals("FAILED", rules.get(1).path("status").asText());
        assertEquals("SUCCEEDED", rules.get(2).path("status").asText());
        assertEquals(2, rules.get(2).path("failedRowsCount").asInt());
        assertEquals("SUCCEEDED", rules.get(3).path("status").asText());
        assertEquals("ERROR", rules.get(4).path("status").asText());
        assertEquals("FAILED", result.path("status").asText());
    }
}
