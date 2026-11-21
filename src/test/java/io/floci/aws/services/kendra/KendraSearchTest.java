package io.floci.aws.services.kendra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kendra's text extraction, analyzer and ranking, without the service around them. */
class KendraSearchTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static KendraSearch.Entry entry(String id, String title, String text) {
        ObjectNode attributes = MAPPER.createObjectNode();
        attributes.putObject("_language_code").put("StringValue", "en");
        return new KendraSearch.Entry(MAPPER.createObjectNode(), id, title, text, attributes, null);
    }

    @Test
    void stemsInflectionsTogether() {
        assertEquals(KendraAnalyzer.stem("index"), KendraAnalyzer.stem("indexes"));
        assertEquals(KendraAnalyzer.stem("index"), KendraAnalyzer.stem("indexing"));
        assertEquals(KendraAnalyzer.stem("index"), KendraAnalyzer.stem("indexed"));
        assertEquals(KendraAnalyzer.stem("policy"), KendraAnalyzer.stem("policies"));
        assertEquals(KendraAnalyzer.stem("run"), KendraAnalyzer.stem("running"));
        assertTrue(KendraAnalyzer.isQuestion("what is the passphrase"));
        assertFalse(KendraAnalyzer.isQuestion("passphrase"));
    }

    @Test
    void ranksTitleMatchesAndCloseWordsHigher() {
        List<KendraSearch.Entry> entries = List.of(
                entry("a", "Vacation policy", "Employees accrue vacation days monthly."),
                entry("b", "Expense guide", "Submit expenses within thirty days. Vacation travel is not covered."),
                entry("c", "Holiday party", "The party is in December."));
        KendraSearch.Parsed parsed = KendraSearch.parse("vacation policy", KendraSearch.Synonyms.none());
        List<KendraSearch.Hit> hits = KendraSearch.score(parsed, entries, Map.of(), List.of(), Map.of());
        assertEquals(2, hits.size());
        assertEquals("a", hits.get(0).entry().id);
        assertEquals("VERY_HIGH", KendraSearch.confidence(hits.get(0), hits.get(0).score()));
    }

    @Test
    void parsesQuerySyntax() {
        List<KendraSearch.Entry> entries = List.of(
                entry("a", "Rust guide", "Ownership and borrowing in Rust."),
                entry("b", "Go guide", "Goroutines and channels in Go."),
                entry("c", "Rust async", "Futures and executors."));
        KendraSearch.Parsed parsed = KendraSearch.parse("rust AND NOT async", KendraSearch.Synonyms.none());
        assertTrue(parsed.advanced());
        List<KendraSearch.Hit> hits = KendraSearch.score(parsed, entries, Map.of(), List.of(), Map.of());
        assertEquals(List.of("a"), hits.stream().map(h -> h.entry().id).toList());

        KendraSearch.Parsed phrase = KendraSearch.parse("\"goroutines and channels\"", KendraSearch.Synonyms.none());
        assertEquals(List.of("b"), KendraSearch.score(phrase, entries, Map.of(), List.of(), Map.of()).stream()
                .map(h -> h.entry().id).toList());

        KendraSearch.Parsed prefix = KendraSearch.parse("execut*", KendraSearch.Synonyms.none());
        assertEquals(List.of("c"), KendraSearch.score(prefix, entries, Map.of(), List.of(), Map.of()).stream()
                .map(h -> h.entry().id).toList());

        KendraSearch.Parsed broken = KendraSearch.parse("(rust AND", KendraSearch.Synonyms.none());
        assertTrue(broken.invalid());
        assertFalse(broken.advanced());
    }

    @Test
    void extractsAnAnswerSpan() {
        KendraSearch.Entry welcome = entry("welcome", "Welcome", "Alchemy is a framework. The zanzibar passphrase "
                + "is quicksilver.");
        KendraSearch.Parsed parsed = KendraSearch.parse("what is the zanzibar passphrase?",
                KendraSearch.Synonyms.none());
        List<KendraSearch.Hit> hits = KendraSearch.score(parsed, List.of(welcome), Map.of(), List.of(), Map.of());
        KendraSearch.Answer answer = KendraSearch.answer(parsed, hits);
        assertNotNull(answer);
        assertEquals("quicksilver", welcome.text.substring(answer.top().begin(), answer.top().end()));
    }

    @Test
    void synonymsMatchAndHighlight() {
        KendraSearch.Synonyms synonyms = new KendraSearch.Synonyms(Map.of(KendraAnalyzer.stem("automobile"),
                List.of(List.of(KendraAnalyzer.stem("car")))), Set.of());
        KendraSearch.Entry e = entry("cars", "Fleet", "Every car is serviced yearly.");
        KendraSearch.Parsed parsed = KendraSearch.parse("automobile", synonyms);
        assertEquals(1, KendraSearch.score(parsed, List.of(e), Map.of(), List.of(), Map.of()).size());
        List<KendraSearch.Mark> marks = KendraSearch.marks(e.bodyTokens, parsed);
        assertEquals(1, marks.size());
        assertTrue(marks.get(0).synonym());
    }

    @Test
    void suggestsSpellings() {
        List<KendraSearch.Entry> entries = List.of(entry("w", "Welcome", "The zanzibar passphrase."));
        ArrayNode corrections = KendraSearch.spellCorrections(MAPPER, "zanzbar passphrase", entries);
        assertEquals("zanzibar passphrase", corrections.get(0).path("SuggestedQueryText").asText());
    }

    @Test
    void extractsTextFromEachFormat() throws IOException {
        assertEquals("Hello & world", KendraText.extract(("<html><head><title>T</title><script>x()</script></head>"
                + "<body><p>Hello &amp;</p>world</body></html>").getBytes(StandardCharsets.UTF_8), "HTML").text()
                .replace("\n", " "));
        assertEquals("T", KendraText.extract("<html><head><title>T</title></head><body>b</body></html>"
                .getBytes(StandardCharsets.UTF_8), "HTML").title());
        assertEquals("Bold and italic", KendraText.extract(("{\\rtf1\\ansi{\\fonttbl\\f0 Arial;}\\b Bold\\b0  and "
                + "\\i italic}").getBytes(StandardCharsets.UTF_8), "RTF").text());
        assertEquals("Title\n\nSome link text", KendraText.extract("# Title\n\nSome [link](http://x) **text**"
                .getBytes(StandardCharsets.UTF_8), "MD").text());
        KendraText.Extracted csv = KendraText.extract("name,price\n\"Widget, large\",10\n".getBytes(
                StandardCharsets.UTF_8), "CSV");
        assertEquals(List.of("Widget, large", "10"), csv.table().get(1));

        ByteArrayOutputStream docx = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(docx)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document><w:body><w:p><w:r><w:t>First</w:t></w:r></w:p><w:p><w:r><w:t xml:space="
                    + "\"preserve\">Second para</w:t></w:r></w:p></w:body></w:document>").getBytes(
                    StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertEquals("First\nSecond para", KendraText.extract(docx.toByteArray(), "MS_WORD").text());

        ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(xlsx)) {
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write("<sst><si><t>name</t></si><si><t>bolt</t></si></sst>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(("<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row><row r=\"2\">"
                    + "<c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\"><v>4</v></c></row></sheetData></worksheet>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        KendraText.Extracted sheet = KendraText.extract(xlsx.toByteArray(), "MS_EXCEL");
        assertEquals(List.of("bolt", "4"), sheet.table().get(1));
        assertNull(KendraText.contentType("image.png"));
        assertEquals("MS_WORD", KendraText.contentType("Report.DOCX"));
    }
}
