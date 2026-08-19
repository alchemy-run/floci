package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AthenaGlueDdlTest {

    @Test
    void createsQualifiedAndUnqualifiedViews() {
        String select = "SELECT * FROM read_csv('s3://b/data/rows.csv', header = false)";
        assertEquals(
                "CREATE SCHEMA IF NOT EXISTS \"alchemy_athena_e2e\";\n",
                AthenaGlueDdl.createSchema("alchemy_athena_e2e"));
        assertEquals(
                "CREATE OR REPLACE VIEW \"alchemy_athena_e2e\".\"people\" AS " + select + ";\n",
                AthenaGlueDdl.createView("alchemy_athena_e2e", "people", select));
        assertEquals(
                "CREATE OR REPLACE VIEW \"people\" AS " + select + ";\n",
                AthenaGlueDdl.createUnqualifiedView("people", select));
    }

    @Test
    void csvHasNoHeaderByDefaultAndUsesGlueColumns() {
        Table table = csvTable(Map.of("field.delim", ","));
        assertFalse(AthenaGlueDdl.hasHeader(table));
        assertEquals(",", AthenaGlueDdl.delimiter(table));
        String expr = AthenaGlueDdl.readExpression(table, "s3://bucket/data/rows.csv");
        assertTrue(expr.contains("header = false"));
        assertTrue(expr.contains("'id': 'VARCHAR'"));
        assertTrue(expr.contains("'name': 'VARCHAR'"));
        assertTrue(expr.startsWith("read_csv("));
    }

    @Test
    void lazySimpleSerDeIsCsvNotJson() {
        Table table = csvTable(Map.of());
        assertEquals("read_csv", AthenaGlueDdl.inferReadFunction(table));
    }

    @Test
    void skipHeaderLineCountEnablesHeader() {
        Table table = csvTable(Map.of());
        table.setParameters(Map.of("skip.header.line.count", "1"));
        assertTrue(AthenaGlueDdl.hasHeader(table));
    }

    @Test
    void unionsListedCsvFiles() {
        Table table = csvTable(Map.of("field.delim", ","));
        String sql = AthenaGlueDdl.selectFromFiles(
                table,
                List.of("s3://bucket/data/a.csv", "s3://bucket/data/b.csv"),
                "s3://bucket/data/**");
        assertTrue(sql.startsWith("SELECT * FROM read_csv('s3://bucket/data/a.csv'"));
        assertTrue(sql.contains(" UNION ALL SELECT * FROM read_csv('s3://bucket/data/b.csv'"));
    }

    private static Table csvTable(Map<String, String> serdeParams) {
        Table table = new Table();
        table.setName("people");
        table.setDatabaseName("alchemy_athena_e2e");
        StorageDescriptor sd = new StorageDescriptor();
        sd.setLocation("s3://bucket/data/");
        sd.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        sd.setColumns(List.of(new Column("id", "string"), new Column("name", "string")));
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
        serde.setParameters(serdeParams);
        sd.setSerdeInfo(serde);
        table.setStorageDescriptor(sd);
        return table;
    }
}
