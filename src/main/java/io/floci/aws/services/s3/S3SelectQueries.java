package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * S3 Select's SQL over CSV, for the services that run the same queries outside a SelectObjectContent request
 * (Glacier's select jobs): the statements, WHERE clauses and LIMITs {@link S3SelectEvaluator} reads.
 */
public final class S3SelectQueries {

    private static final Pattern ARCHIVE = Pattern.compile("\\bFROM\\s+ARCHIVE\\b", Pattern.CASE_INSENSITIVE);

    private S3SelectQueries() {
    }

    /** A Glacier Select statement reads {@code FROM archive}, as S3 Select reads {@code FROM S3Object}. */
    private static String normalize(String expression) {
        return ARCHIVE.matcher(expression).replaceAll("FROM S3Object");
    }

    /** Fails with the error S3 Select gives for a statement it cannot run. */
    public static void check(String expression) {
        if (S3SelectEvaluator.parse(normalize(expression)) == null) {
            throw new AwsException("InvalidParameterValueException", "The SQL expression is not one Glacier "
                    + "Select runs: " + expression + ". Use SELECT <columns> FROM archive [WHERE ...] [LIMIT n].",
                    400);
        }
    }

    /** The CSV records a query selects from CSV text, each without its line break. */
    public static List<String> selectCsv(String csv, String expression, String fileHeaderInfo) {
        S3SelectEvaluator.Query query = S3SelectEvaluator.parse(normalize(expression));
        if (query == null) {
            check(expression);
        }
        List<String> records = new ArrayList<>();
        try {
            S3SelectEvaluator.evaluateCsv(new BufferedReader(new StringReader(csv)), query, fileHeaderInfo, "CSV",
                    records::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return records;
    }
}
