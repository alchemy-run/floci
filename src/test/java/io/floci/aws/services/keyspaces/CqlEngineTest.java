package io.floci.aws.services.keyspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CQL parser, types, Murmur3 tokens and value codec, without the endpoint around them. */
class CqlEngineTest {

    @Test
    void tokensMatchCassandrasMurmur3() {
        // The DataStax driver's murmur3(b"ana"), the token Cassandra gives the partition key 'ana'.
        assertEquals(-4939082130219364716L, CqlMurmur3.token(new byte[][] {"ana".getBytes(StandardCharsets.UTF_8)}));
    }

    @Test
    void parsesStatements() {
        CqlParser.Parsed select = CqlParser.parse("SELECT a, writetime(b) AS w, count(*) FROM ks.\"T\" WHERE k = ? "
                + "AND c >= :from ORDER BY c DESC LIMIT 10 ALLOW FILTERING;");
        CqlParser.Select s = assertInstanceOf(CqlParser.Select.class, select.statement());
        assertEquals("T", s.table());
        assertEquals(3, s.selectors().size());
        assertEquals(2, select.binds().size());
        assertEquals("from", select.binds().get(1));
        assertTrue(s.allowFiltering());

        CqlParser.Update u = assertInstanceOf(CqlParser.Update.class, CqlParser.parse("UPDATE t USING TTL 60 SET "
                + "l = ['x'] + l, m['k'] = 1, n = n - 2 WHERE id = 1 IF n > 3").statement());
        assertTrue(u.assignments().get(0).prepend());
        assertEquals("-", u.assignments().get(2).op());
        assertEquals(1, u.conditions().size());

        CqlParser.CreateTable t = assertInstanceOf(CqlParser.CreateTable.class, CqlParser.parse("CREATE TABLE IF "
                + "NOT EXISTS t (a int, b timestamp, c map<text, frozen<list<int>>>, s text STATIC, PRIMARY KEY ((a), b))"
                + " WITH CLUSTERING ORDER BY (b DESC) AND custom_properties = {'ttl': {'status': 'enabled'}}")
                .statement());
        assertEquals(List.of("a"), t.partitionKeys());
        assertEquals("DESC", t.clusteringOrder().get("b"));
        assertEquals("map<text,frozen<list<int>>>", t.columns().get(2).type().replace(" ", ""));
        assertTrue(t.columns().get(3).isStatic());
        assertThrows(CqlParser.SyntaxError.class, () -> CqlParser.parse("SELEC * FROM t"));
    }

    @Test
    void typesRequireFrozenNesting() {
        assertEquals("map<text, frozen<list<int>>>", CqlType.parse("map<text, frozen<list<int>>>", u -> false)
                .toString());
        assertThrows(RuntimeException.class, () -> CqlType.parse("list<list<int>>", u -> false));
        assertThrows(RuntimeException.class, () -> CqlType.parse("address", u -> false));
        assertEquals("frozen<address>", CqlType.parse("frozen<address>", u -> true).toString());
    }

    @Test
    void codecRoundTrips() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String[] c : new String[][] {{"decimal", "\"-12.345\""}, {"varint", "\"123456789012345678901\""},
                {"date", "\"2026-11-11\""}, {"duration", "\"1y2mo3d4h5m\""}, {"inet", "\"10.0.0.1\""},
                {"map<text, int>", "[[\"a\",1],[\"b\",2]]"}, {"tuple<int, text>", "[1,\"x\"]"},
                {"timestamp", "1767225600000"}, {"uuid", "\"5d3a5b8e-7b6a-4c38-9d5e-111111111111\""}}) {
            CqlType type = CqlType.parse(c[0], u -> true);
            JsonNode value = mapper.readTree(c[1]);
            byte[] bytes = CqlCodec.encode(type, value, n -> List.of());
            assertEquals(value, CqlCodec.decode(type, bytes, n -> List.of()), c[0]);
        }
    }
}
