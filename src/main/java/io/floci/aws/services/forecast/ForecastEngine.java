package io.floci.aws.services.forecast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How floci trains a Forecast predictor and forecasts with it. A series is the target summed per time step of the
 * forecast frequency; its forecast for a step is the mean of the same point in its last three seasons (or of its
 * last season's values, when it is shorter than two seasons), and a quantile of it adds the normal quantile of the
 * in-sample residuals, widening with the horizon. Accuracy is measured by backtesting: the last horizon of each
 * series is held out, forecast from what precedes it and compared with what happened.
 */
final class ForecastEngine {

    private static final Pattern MINUTES = Pattern.compile("(\\d+)min");

    private ForecastEngine() {
    }

    /** A value of a series at a time step. */
    record Point(Instant time, double value) {
    }

    /** A forecast of one series: the time steps, and each forecast type's values at them. */
    record Prediction(List<Instant> times, Map<String, List<Double>> values) {
    }

    // ---------------------------------------------------------------- time

    /** Parses a timestamp in a dataset import's timestamp format (Java date patterns, as Forecast's are). */
    static Instant parse(String value, String format) {
        String pattern = format == null || format.isBlank() ? "yyyy-MM-dd HH:mm:ss" : format;
        String trimmed = value.trim();
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
            if (pattern.contains("H") || pattern.contains("m")) {
                return LocalDateTime.parse(trimmed, formatter).toInstant(ZoneOffset.UTC);
            }
            return LocalDate.parse(trimmed, formatter).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            try {
                return LocalDate.parse(trimmed.substring(0, Math.min(10, trimmed.length()))).atStartOfDay()
                        .toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException inner) {
                throw new IllegalArgumentException("The timestamp " + value + " does not match " + pattern + ".");
            }
        }
    }

    /** Whether a frequency is one Forecast takes: Y, M, W, D, H, or 1, 5, 10, 15 or 30 minutes. */
    static boolean validFrequency(String frequency) {
        return switch (frequency) {
            case "Y", "M", "W", "D", "H", "1min", "5min", "10min", "15min", "30min" -> true;
            default -> false;
        };
    }

    /** The start of the time step a moment falls in. */
    static Instant bucket(Instant time, String frequency) {
        LocalDateTime local = LocalDateTime.ofInstant(time, ZoneOffset.UTC);
        LocalDateTime start = switch (frequency) {
            case "Y" -> local.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS);
            case "M" -> local.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            case "W" -> local.truncatedTo(ChronoUnit.DAYS).minusDays(local.getDayOfWeek().getValue() - 1L);
            case "D" -> local.truncatedTo(ChronoUnit.DAYS);
            case "H" -> local.truncatedTo(ChronoUnit.HOURS);
            default -> {
                int minutes = minutes(frequency);
                LocalDateTime hour = local.truncatedTo(ChronoUnit.HOURS);
                yield hour.plusMinutes((long) (local.getMinute() / minutes) * minutes);
            }
        };
        return start.toInstant(ZoneOffset.UTC);
    }

    static Instant step(Instant time, String frequency, int steps) {
        LocalDateTime local = LocalDateTime.ofInstant(time, ZoneOffset.UTC);
        LocalDateTime next = switch (frequency) {
            case "Y" -> local.plusYears(steps);
            case "M" -> local.plusMonths(steps);
            case "W" -> local.plusWeeks(steps);
            case "D" -> local.plusDays(steps);
            case "H" -> local.plusHours(steps);
            default -> local.plusMinutes((long) minutes(frequency) * steps);
        };
        return next.toInstant(ZoneOffset.UTC);
    }

    private static int minutes(String frequency) {
        Matcher matcher = MINUTES.matcher(frequency);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 1;
    }

    /** The length of a season, in time steps, at a frequency. */
    static int season(String frequency) {
        return switch (frequency) {
            case "Y" -> 1;
            case "M" -> 12;
            case "W" -> 52;
            case "D" -> 7;
            case "H" -> 24;
            default -> Math.max(1, 1440 / minutes(frequency));
        };
    }

    /** Sums values into the time steps of a frequency, oldest first. */
    static List<Point> aggregate(List<Point> points, String frequency) {
        TreeMap<Instant, Double> sums = new TreeMap<>();
        for (Point point : points) {
            sums.merge(bucket(point.time(), frequency), point.value(), Double::sum);
        }
        List<Point> series = new ArrayList<>();
        sums.forEach((time, value) -> series.add(new Point(time, value)));
        return series;
    }

    // ---------------------------------------------------------------- forecasting

    /** Forecasts a series, at least one point long, for a horizon of steps. */
    static Prediction forecast(List<Point> series, String frequency, int horizon, List<String> types) {
        int season = season(frequency);
        int n = series.size();
        double[] values = series.stream().mapToDouble(Point::value).toArray();
        boolean nonNegative = series.stream().allMatch(point -> point.value() >= 0);
        double sigma = residualDeviation(values, season);
        List<Instant> times = new ArrayList<>();
        Map<String, List<Double>> out = new TreeMap<>();
        types.forEach(type -> out.put(type, new ArrayList<>()));
        Instant last = series.get(n - 1).time();
        for (int h = 1; h <= horizon; h++) {
            times.add(step(last, frequency, h));
            double mean = seasonalMean(values, season, h);
            double spread = sigma * Math.sqrt(1 + (double) (h - 1) / season);
            for (String type : types) {
                double value = "mean".equals(type) ? mean : mean + inverseNormal(Double.parseDouble(type)) * spread;
                out.get(type).add(nonNegative ? Math.max(0, value) : value);
            }
        }
        return new Prediction(times, out);
    }

    private static double seasonalMean(double[] values, int season, int h) {
        int n = values.length;
        if (season > 1 && n >= 2 * season) {
            double sum = 0;
            int count = 0;
            for (int k = 1; k <= 3; k++) {
                int index = n - k * season + ((h - 1) % season);
                if (index >= 0 && index < n) {
                    sum += values[index];
                    count++;
                }
            }
            if (count > 0) {
                return sum / count;
            }
        }
        int window = Math.min(n, Math.max(1, season));
        double sum = 0;
        for (int i = n - window; i < n; i++) {
            sum += values[i];
        }
        return sum / window;
    }

    private static double residualDeviation(double[] values, int season) {
        int lag = values.length > season ? season : 1;
        if (values.length <= lag) {
            return values.length == 0 ? 0 : Math.abs(values[0]) * 0.1;
        }
        double sum = 0;
        int count = 0;
        for (int i = lag; i < values.length; i++) {
            double residual = values[i] - values[i - lag];
            sum += residual * residual;
            count++;
        }
        return Math.sqrt(sum / count);
    }

    /** The standard normal quantile of a probability (Acklam's approximation). */
    static double inverseNormal(double p) {
        double[] a = {-39.6968302866538, 220.946098424521, -275.928510446969, 138.357751867269, -30.6647980661472,
            2.50662827745924};
        double[] b = {-54.4760987982241, 161.585836858041, -155.698979859887, 66.8013118877197, -13.2806815528857};
        double[] c = {-0.00778489400243029, -0.322396458041136, -2.40075827716184, -2.54973253934373,
            4.37466414146497, 2.93816398269878};
        double[] d = {0.00778469570904146, 0.32246712907004, 2.445134137143, 3.75440866190742};
        double low = 0.02425;
        if (p <= 0 || p >= 1) {
            return p <= 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (p < low) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - low) {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    // ---------------------------------------------------------------- accuracy

    /** A backtest's errors, summed over its series. */
    static final class Accuracy {
        double squared;
        double absolute;
        double actual;
        double percentage;
        double scaled;
        int points;
        int items;
        final Map<String, double[]> quantileLoss = new TreeMap<>();
        Instant start;
        Instant end;

        /** Adds one series' holdout: what happened and what was forecast for it. */
        void add(List<Point> history, List<Point> holdout, Prediction prediction, String frequency) {
            items++;
            double naive = 0;
            int lag = Math.min(season(frequency), Math.max(1, history.size() - 1));
            int naiveCount = 0;
            for (int i = lag; i < history.size(); i++) {
                naive += Math.abs(history.get(i).value() - history.get(i - lag).value());
                naiveCount++;
            }
            double scale = naiveCount == 0 ? 0 : naive / naiveCount;
            List<Double> median = prediction.values().containsKey("0.5") ? prediction.values().get("0.5")
                    : prediction.values().getOrDefault("mean", prediction.values().values().iterator().next());
            for (int i = 0; i < holdout.size(); i++) {
                double y = holdout.get(i).value();
                double f = median.get(i);
                squared += (y - f) * (y - f);
                absolute += Math.abs(y - f);
                actual += Math.abs(y);
                percentage += y == 0 ? 0 : Math.abs((y - f) / y);
                scaled += scale == 0 ? 0 : Math.abs(y - f) / scale;
                points++;
                for (Map.Entry<String, List<Double>> entry : prediction.values().entrySet()) {
                    if ("mean".equals(entry.getKey())) {
                        continue;
                    }
                    double tau = Double.parseDouble(entry.getKey());
                    double q = entry.getValue().get(i);
                    double loss = y > q ? tau * (y - q) : (1 - tau) * (q - y);
                    quantileLoss.computeIfAbsent(entry.getKey(), key -> new double[1])[0] += 2 * loss;
                }
            }
            Instant first = holdout.get(0).time();
            Instant last = holdout.get(holdout.size() - 1).time();
            start = start == null || first.isBefore(start) ? first : start;
            end = end == null || last.isAfter(end) ? last : end;
        }

        double rmse() {
            return points == 0 ? 0 : Math.sqrt(squared / points);
        }

        double wape() {
            return actual == 0 ? 0 : absolute / actual;
        }

        double mape() {
            return points == 0 ? 0 : percentage / points;
        }

        double mase() {
            return points == 0 ? 0 : scaled / points;
        }

        double weightedQuantileLoss(String quantile) {
            return actual == 0 ? 0 : quantileLoss.getOrDefault(quantile, new double[1])[0] / actual;
        }
    }
}
