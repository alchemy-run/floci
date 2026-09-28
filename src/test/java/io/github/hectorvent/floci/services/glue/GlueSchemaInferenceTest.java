package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.services.glue.GlueSchemaInference.FileSchema;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueSchemaInferenceTest {

    @Test
    void csvWithTypedDataDetectsHeaderAndColumnTypes() {
        FileSchema schema = GlueSchemaInference.inferCsv(bytes("id,amount,active\n1,10.5,true\n2,20,false\n"), false);

        assertEquals("csv", schema.classification());
        assertEquals(List.of("id", "amount", "active"), names(schema.columns()));
        assertEquals(List.of("bigint", "double", "boolean"), types(schema.columns()));
        assertEquals("1", schema.tableParameters().get("skip.header.line.count"));
        assertEquals(",", schema.serdeParameters().get("field.delim"));
        assertEquals(2, schema.records());
    }

    @Test
    void csvOfOnlyStringsHasNoDetectableHeader() {
        FileSchema schema = GlueSchemaInference.inferCsv(bytes("name|city\nann|oslo\nbob|rome\n"), false);

        assertEquals(List.of("col0", "col1"), names(schema.columns()));
        assertEquals("|", schema.serdeParameters().get("field.delim"));
        assertFalse(schema.tableParameters().containsKey("skip.header.line.count"));
        assertEquals(3, schema.records());
    }

    @Test
    void singleColumnTextIsNotClassifiedAsCsv() {
        assertNull(GlueSchemaInference.inferCsv(bytes("just\nsome\nwords\n"), false));
    }

    @Test
    void truncatedSampleDropsThePartialLastLine() {
        FileSchema schema = GlueSchemaInference.inferCsv(bytes("id,name\n1,ann\n2,b"), true);

        assertEquals(List.of("bigint", "string"), types(schema.columns()));
        assertEquals(1, schema.records());
    }

    @Test
    void jsonLinesMergeFieldsAndWidenNumbers() {
        FileSchema schema = GlueSchemaInference.inferJson(bytes("""
                {"id":1,"price":2,"tags":["a"],"meta":{"ok":true}}
                {"id":2,"price":2.5,"note":"x"}
                """), false);

        assertEquals("json", schema.classification());
        assertEquals(List.of("id", "price", "tags", "meta", "note"), names(schema.columns()));
        assertEquals(List.of("int", "double", "array<string>", "struct<ok:boolean>", "string"),
                types(schema.columns()));
        assertEquals(2, schema.records());
    }

    @Test
    void jsonArrayDocumentIsReadAsRecords() {
        FileSchema schema = GlueSchemaInference.inferJson(bytes("[\n  {\"a\": 1},\n  {\"a\": 2}\n]"), false);

        assertEquals(List.of("a"), names(schema.columns()));
        assertEquals(2, schema.records());
    }

    @Test
    void binaryContentIsNotClassified() {
        assertNull(GlueSchemaInference.classificationFor("blob.bin", null, new byte[]{1, 0, 2}));
        assertEquals("parquet", GlueSchemaInference.classificationFor("x", null, bytes("PAR1....")));
        assertEquals("json", GlueSchemaInference.classificationFor("events", null, bytes("{\"a\":1}")));
        assertEquals("csv", GlueSchemaInference.classificationFor("events.csv", "text/csv", bytes("a,b\n1,2")));
    }

    @Test
    void schemasAreSimilarAtSeventyPercentColumnOverlap() {
        FileSchema base = schema("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        assertTrue(GlueSchemaInference.similar(base, schema("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")));
        assertFalse(GlueSchemaInference.similar(base, schema("a", "b", "x", "y")));
    }

    @Test
    void parquetTypesMapToHiveTypesAndFormats() {
        FileSchema schema = GlueSchemaInference.parquetSchema(List.of(
                Map.entry("Id", "BIGINT"), Map.entry("price", "DECIMAL(10,2)"),
                Map.entry("tags", "VARCHAR[]"), Map.entry("at", "TIMESTAMP WITH TIME ZONE")), 100);

        assertEquals(List.of("id", "price", "tags", "at"), names(schema.columns()));
        assertEquals(List.of("bigint", "decimal(10,2)", "array<string>", "timestamp"), types(schema.columns()));
        StorageDescriptor descriptor = new StorageDescriptor();
        GlueSchemaInference.applyFormat(descriptor, "parquet", schema.serdeParameters());
        assertEquals("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe",
                descriptor.getSerdeInfo().getSerializationLibrary());
    }

    @Test
    void tableNamesAreLowercasedAndSanitized() {
        assertEquals("raw_my_events_2024", GlueSchemaInference.tableName("raw_", "My-Events.2024"));
    }

    private static FileSchema schema(String... columns) {
        List<Column> list = Arrays.stream(columns).map(name -> new Column(name, "string")).toList();
        return new FileSchema("csv", list, Map.of(), Map.of(), 1, 1);
    }

    private static List<String> names(List<Column> columns) {
        return columns.stream().map(Column::getName).toList();
    }

    private static List<String> types(List<Column> columns) {
        return columns.stream().map(Column::getType).toList();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
