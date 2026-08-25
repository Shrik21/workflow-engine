package com.orchpilot.workflow.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The builder that turns friendly schedule choices into Spring cron and back. Every generated expression must
 * be valid Spring cron, describe as the specification's plain English, and survive a round-trip through the
 * reverse parser.
 */
class SchedulerExpressionBuilderTest {

    private final SchedulerExpressionBuilder builder = new SchedulerExpressionBuilder();

    private static ScheduleConfig config(ScheduleFrequency frequency) {
        ScheduleConfig config = new ScheduleConfig();
        config.setFrequency(frequency);
        return config;
    }

    @Nested
    @DisplayName("buildCron")
    class Build {

        @Test
        @DisplayName("daily at 10:30 → Spring cron, valid and correct")
        void daily() {
            ScheduleConfig c = config(ScheduleFrequency.DAILY);
            c.setTime("10:30");
            String cron = builder.buildCron(c);
            assertThat(cron).isEqualTo("0 30 10 * * *");
            assertThat(CronExpression.isValidExpression(cron)).isTrue();
        }

        @Test
        @DisplayName("every minute")
        void everyMinute() {
            assertThat(builder.buildCron(config(ScheduleFrequency.EVERY_MINUTE))).isEqualTo("0 * * * * *");
        }

        @Test
        @DisplayName("every 15 minutes")
        void everyNMinutes() {
            ScheduleConfig c = config(ScheduleFrequency.EVERY_N_MINUTES);
            c.setInterval(15);
            assertThat(builder.buildCron(c)).isEqualTo("0 */15 * * * *");
        }

        @Test
        @DisplayName("hourly at 30 past")
        void hourly() {
            ScheduleConfig c = config(ScheduleFrequency.HOURLY);
            c.setMinute(30);
            assertThat(builder.buildCron(c)).isEqualTo("0 30 * * * *");
        }

        @Test
        @DisplayName("every 2 hours from 09:00")
        void everyNHours() {
            ScheduleConfig c = config(ScheduleFrequency.EVERY_N_HOURS);
            c.setInterval(2);
            c.setTime("09:00");
            assertThat(builder.buildCron(c)).isEqualTo("0 0 9/2 * * *");
        }

        @Test
        @DisplayName("weekly on Wed and Fri at 09:30")
        void weekly() {
            ScheduleConfig c = config(ScheduleFrequency.WEEKLY);
            c.setDaysOfWeek(List.of("WED", "FRI"));
            c.setTime("09:30");
            assertThat(builder.buildCron(c)).isEqualTo("0 30 9 * * WED,FRI");
            assertThat(CronExpression.isValidExpression(builder.buildCron(c))).isTrue();
        }

        @Test
        @DisplayName("monthly on the 1st at 09:00")
        void monthlySpecificDay() {
            ScheduleConfig c = config(ScheduleFrequency.MONTHLY);
            c.setDayOfMonth(1);
            c.setTime("09:00");
            assertThat(builder.buildCron(c)).isEqualTo("0 0 9 1 * *");
        }

        @Test
        @DisplayName("monthly on the last day at 23:00 uses Spring's L")
        void monthlyLastDay() {
            ScheduleConfig c = config(ScheduleFrequency.MONTHLY);
            c.setLastDayOfMonth(true);
            c.setTime("23:00");
            String cron = builder.buildCron(c);
            assertThat(cron).isEqualTo("0 0 23 L * *");
            assertThat(CronExpression.isValidExpression(cron)).isTrue();
        }

        @Test
        @DisplayName("custom passes the cron through after validating it")
        void custom() {
            ScheduleConfig c = config(ScheduleFrequency.CUSTOM);
            c.setCron("0 0 12 * * MON-FRI");
            assertThat(builder.buildCron(c)).isEqualTo("0 0 12 * * MON-FRI");
        }
    }

    @Nested
    @DisplayName("describe")
    class Describe {

        @Test
        @DisplayName("daily reads as a sentence with the 12-hour time and timezone")
        void daily() {
            ScheduleConfig c = config(ScheduleFrequency.DAILY);
            c.setTime("10:30");
            assertThat(builder.describe(c, "Asia/Kolkata"))
                    .isEqualTo("Runs every day at 10:30 AM Kolkata.");
        }

        @Test
        @DisplayName("weekly names the days and joins them naturally")
        void weekly() {
            ScheduleConfig c = config(ScheduleFrequency.WEEKLY);
            c.setDaysOfWeek(List.of("WED", "FRI"));
            c.setTime("09:30");
            assertThat(builder.describe(c, null))
                    .isEqualTo("Runs every Wednesday and Friday at 9:30 AM.");
        }

        @Test
        @DisplayName("monthly uses an ordinal, or the last day")
        void monthly() {
            ScheduleConfig first = config(ScheduleFrequency.MONTHLY);
            first.setDayOfMonth(1);
            first.setTime("09:00");
            assertThat(builder.describe(first, null))
                    .isEqualTo("Runs on the 1st day of every month at 9:00 AM.");

            ScheduleConfig last = config(ScheduleFrequency.MONTHLY);
            last.setLastDayOfMonth(true);
            last.setTime("23:00");
            assertThat(builder.describe(last, null))
                    .isEqualTo("Runs on the last day of every month at 11:00 PM.");
        }
    }

    @Nested
    @DisplayName("fromCron round-trip")
    class Reverse {

        @Test
        @DisplayName("built crons reverse back to the same friendly frequency")
        void roundTrips() {
            assertThat(builder.fromCron("0 30 10 * * *").getFrequency()).isEqualTo(ScheduleFrequency.DAILY);
            assertThat(builder.fromCron("0 * * * * *").getFrequency()).isEqualTo(ScheduleFrequency.EVERY_MINUTE);
            assertThat(builder.fromCron("0 */15 * * * *").getFrequency())
                    .isEqualTo(ScheduleFrequency.EVERY_N_MINUTES);
            assertThat(builder.fromCron("0 30 * * * *").getFrequency()).isEqualTo(ScheduleFrequency.HOURLY);
            assertThat(builder.fromCron("0 0 9/2 * * *").getFrequency())
                    .isEqualTo(ScheduleFrequency.EVERY_N_HOURS);
            assertThat(builder.fromCron("0 0 23 L * *").getFrequency()).isEqualTo(ScheduleFrequency.MONTHLY);

            ScheduleConfig weekly = builder.fromCron("0 30 9 * * WED,FRI");
            assertThat(weekly.getFrequency()).isEqualTo(ScheduleFrequency.WEEKLY);
            assertThat(weekly.getDaysOfWeek()).containsExactly("WED", "FRI");
            assertThat(weekly.getTime()).isEqualTo("09:30");
        }

        @Test
        @DisplayName("an unrecognised hand-written cron degrades to CUSTOM, keeping the cron")
        void unrecognisedIsCustom() {
            ScheduleConfig c = builder.fromCron("0 0 0 1/2 * *");
            assertThat(c.getFrequency()).isEqualTo(ScheduleFrequency.CUSTOM);
            assertThat(c.getCron()).isEqualTo("0 0 0 1/2 * *");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("every-N must be positive")
        void intervalPositive() {
            ScheduleConfig c = config(ScheduleFrequency.EVERY_N_MINUTES);
            c.setInterval(0);
            assertThatThrownBy(() -> builder.buildCron(c)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("weekly needs at least one day")
        void weeklyNeedsADay() {
            ScheduleConfig c = config(ScheduleFrequency.WEEKLY);
            c.setTime("09:00");
            assertThatThrownBy(() -> builder.buildCron(c))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one day");
        }

        @Test
        @DisplayName("monthly day out of range is rejected")
        void monthlyDayRange() {
            ScheduleConfig c = config(ScheduleFrequency.MONTHLY);
            c.setDayOfMonth(40);
            c.setTime("09:00");
            assertThatThrownBy(() -> builder.buildCron(c)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a custom cron that is not valid is refused")
        void customInvalid() {
            ScheduleConfig c = config(ScheduleFrequency.CUSTOM);
            c.setCron("not a cron");
            assertThatThrownBy(() -> builder.buildCron(c)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a missing time is rejected")
        void missingTime() {
            assertThatThrownBy(() -> builder.buildCron(config(ScheduleFrequency.DAILY)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("time");
        }
    }
}
