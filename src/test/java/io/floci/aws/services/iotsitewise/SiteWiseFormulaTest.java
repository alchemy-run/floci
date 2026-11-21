package io.floci.aws.services.iotsitewise;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SiteWise's formula expressions, as transforms and metrics evaluate them. */
class SiteWiseFormulaTest {

    /** Variables with fixed values, and for metrics, fixed points over the window (0, 60]. */
    static SiteWiseFormula.Context context(Map<String, Object> values, Map<String, List<SiteWiseFormula.Point>> series) {
        return new SiteWiseFormula.Context() {
            @Override
            public Object value(String variable) {
                return values.get(variable);
            }

            @Override
            public double timestamp(String variable) {
                return 1_700_000_000;
            }

            @Override
            public SiteWiseFormula.Point pretrigger(String variable) {
                return new SiteWiseFormula.Point(1_699_999_990, 5.0);
            }

            @Override
            public double now() {
                return 1_700_000_000;
            }

            @Override
            public List<SiteWiseFormula.Point> window(String variable, double start, double end) {
                return series.getOrDefault(variable, List.of()).stream()
                        .filter(p -> p.time() > start && p.time() <= end).toList();
            }

            @Override
            public SiteWiseFormula.Point before(String variable, double time) {
                SiteWiseFormula.Point found = null;
                for (SiteWiseFormula.Point point : series.getOrDefault(variable, List.of())) {
                    if (point.time() <= time) {
                        found = point;
                    }
                }
                return found;
            }

            @Override
            public double[] bounds() {
                return series.isEmpty() ? null : new double[] {0, 60};
            }
        };
    }

    static Object eval(String expression, Map<String, Object> values) {
        return SiteWiseFormula.parse(expression).eval(context(values, Map.of()));
    }

    static Object metric(String expression, Map<String, List<SiteWiseFormula.Point>> series) {
        return SiteWiseFormula.parse(expression).eval(context(Map.of(), series));
    }

    @Test
    void arithmeticFollowsPrecedenceAndRemainderTakesTheLeftSign() {
        assertEquals(212.0, eval("c * 9 / 5 + 32", Map.of("c", 100.0)));
        assertEquals(512.0, eval("2 ^ 3 ^ 2", Map.of()));
        assertEquals(-1.0, eval("-7 % 3", Map.of()));
        assertEquals(1.0, eval("7 % -3", Map.of()));
        assertEquals(-4.0, eval("-(2 + 2)", Map.of()));
        assertEquals(Math.PI * 2, (Double) eval("2 * pi", Map.of()), 1e-12);
    }

    @Test
    void comparisonsAndLogicReturnOneOrZero() {
        assertEquals(1.0, eval("x > 3 and x < 10", Map.of("x", 5.0)));
        assertEquals(0.0, eval("not (x >= 5)", Map.of("x", 5.0)));
        assertEquals(1.0, eval("eq(0.1 + 0.2, 0.3)", Map.of()));
        assertEquals(1.0, eval("x == 1 or y", Map.of("x", 0.0, "y", 2.0)));
        assertEquals(1.0, eval("isnan(x)", Map.of("x", Double.NaN)));
    }

    @Test
    void conditionalsChainWithElif() {
        String band = "if(t < 10, 'cold').elif(t < 25, 'mild', 'hot')";
        assertEquals("cold", eval(band, Map.of("t", 3.0)));
        assertEquals("mild", eval(band, Map.of("t", 12.0)));
        assertEquals("hot", eval(band, Map.of("t", 40.0)));
        assertSame(SiteWiseFormula.NONE, eval("if(x > 0, x, none)", Map.of("x", -1.0)));
    }

    @Test
    void stringsIndexSliceAndFormat() {
        assertEquals("ELBOW", eval("upper(s)", Map.of("s", "elbow")));
        assertEquals("e", eval("s[0]", Map.of("s", "elbow")));
        assertEquals("w", eval("s[-1]", Map.of("s", "elbow")));
        assertEquals("lbo", eval("s[1:4]", Map.of("s", "elbow")));
        assertEquals("woble", eval("s[::-1]", Map.of("s", "elbow")));
        assertEquals(3.0, eval("len('abc')", Map.of()));
        assertEquals(1.0, eval("contains(s, 'bo')", Map.of("s", "elbow")));
        assertEquals(2.0, eval("find(s, 'b')", Map.of("s", "elbow")));
        assertEquals("a-b-c", eval("join('-', 'a', 'b', 'c')", Map.of()));
        assertEquals("temp 21.50", eval("f'temp {t:\".2f\"}'", Map.of("t", 21.5)));
        assertEquals("21.5 C", eval("t + ' C'", Map.of("t", 21.5)));
    }

    @Test
    void jsonPathReadsNumbersAndStrings() {
        String doc = "{\"a\":{\"b\":[10,{\"c\":\"x\"}]}}";
        assertEquals(10.0, eval("jp(d, '$.a.b[0]')", Map.of("d", doc)));
        assertEquals("x", eval("jp(d, '$.a.b[1].c')", Map.of("d", doc)));
        assertSame(SiteWiseFormula.NONE, eval("jp(d, '$.missing')", Map.of("d", doc)));
    }

    @Test
    void functionsAreCaseInsensitiveAndTakeUniformCallSyntax() {
        assertEquals(2.0, eval("x.log10()", Map.of("x", 100.0)));
        assertEquals(3.0, eval("ABS(x)", Map.of("x", -3.0)));
        assertEquals(8.0, eval("x.pow(3)", Map.of("x", 2.0)));
    }

    @Test
    void dateAndTimeFunctions() {
        assertEquals(1_700_000_000.0, eval("timestamp(x)", Map.of("x", 1.0)));
        assertEquals(2023.0, eval("year(1700000000)", Map.of()));
        assertEquals(1_609_459_200.0, eval("mktime(2021, 1, 1)", Map.of()));
        assertEquals(1_609_426_800.0, eval("mktime(2021, 1, 1, 'Asia/Tokyo')", Map.of()));
        assertEquals(5.0, eval("pretrigger(x)", Map.of("x", 7.0)));
    }

    @Test
    void aggregationsReadTheWindow() {
        Map<String, List<SiteWiseFormula.Point>> series = Map.of("x", List.of(new SiteWiseFormula.Point(0, 100.0),
                new SiteWiseFormula.Point(10, 1.0), new SiteWiseFormula.Point(20, 2.0),
                new SiteWiseFormula.Point(30, 3.0), new SiteWiseFormula.Point(60, 6.0)));
        assertEquals(3.0, metric("avg(x)", series));
        assertEquals(12.0, metric("sum(x)", series));
        assertEquals(4.0, metric("count(x)", series));
        assertEquals(6.0, metric("max(x * 1)", series));
        assertEquals(1.0, metric("first(x)", series));
        assertEquals(6.0, metric("last(x)", series));
        assertEquals(100.0, metric("earliest(x)", series));
        assertEquals(Math.sqrt(14.0 / 3), (Double) metric("stdev(x)", series), 1e-9);
        // 100 for 10s, 1 for 10s, 2 for 10s, 3 for 30s.
        assertEquals((1000 + 10 + 20 + 90) / 60.0, (Double) metric("TimeWeightedAvg(x)", series), 1e-9);
        assertEquals(60.0, metric("statetime(x > 0)", series));
    }

    @Test
    void aggregationsAreForMetricsOnly() {
        assertTrue(SiteWiseFormula.usesAggregation("avg(x) + 1"));
        assertThrows(IllegalArgumentException.class, () -> eval("avg(x)", Map.of("x", 1.0)));
    }

    @Test
    void syntaxErrorsAreReported() {
        assertThrows(IllegalArgumentException.class, () -> SiteWiseFormula.parse("1 +"));
        assertThrows(IllegalArgumentException.class, () -> SiteWiseFormula.parse("(1"));
        assertThrows(IllegalArgumentException.class, () -> SiteWiseFormula.parse(""));
        assertEquals(List.of("a", "b"), List.copyOf(SiteWiseFormula.variables(SiteWiseFormula.parse("a + b * a"))));
    }
}
