package io.floci.aws.services.imagebuilder;

import io.floci.aws.services.scheduler.SchedulerExpressionParser;

import java.time.Instant;

/**
 * A pipeline schedule's {@code scheduleExpression}: an AWS {@code cron(...)} (six fields, in the schedule's
 * {@code timezone}) or a {@code rate(...)}, the same grammar EventBridge Scheduler takes.
 */
final class ImageBuilderSchedule {

    private ImageBuilderSchedule() {
    }

    static void validate(String expression) {
        try {
            SchedulerExpressionParser.Kind kind = SchedulerExpressionParser.classify(expression);
            if (kind == SchedulerExpressionParser.Kind.AT) {
                throw ImageBuilderStore.invalidParameter("A pipeline schedule is a cron or rate expression.");
            }
            if (kind == SchedulerExpressionParser.Kind.CRON) {
                SchedulerExpressionParser.nextCronFire(expression, Instant.now(), null);
            } else {
                SchedulerExpressionParser.parseRateMillis(expression);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ImageBuilderStore.invalidParameter("The schedule expression " + expression + " is not valid: "
                    + e.getMessage());
        }
    }

    /** The next time a schedule fires after {@code from}. */
    static Instant next(String expression, String timezone, Instant from) {
        if (SchedulerExpressionParser.classify(expression) == SchedulerExpressionParser.Kind.RATE) {
            return from.plusMillis(SchedulerExpressionParser.parseRateMillis(expression));
        }
        return SchedulerExpressionParser.nextCronFire(expression, from.plusSeconds(1), timezone);
    }
}
