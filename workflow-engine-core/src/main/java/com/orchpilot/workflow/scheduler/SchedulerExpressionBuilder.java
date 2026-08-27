package com.orchpilot.workflow.scheduler;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one place a friendly {@link ScheduleConfig} becomes a Spring cron expression, and back.
 *
 * <h2>Why the cron lives here and nowhere in the UI</h2>
 *
 * The specification is explicit that the Angular layer must not invent cron. So this component owns three
 * responsibilities and the browser owns none of them: {@link #buildCron} turns the dropdown choices into the
 * six-field Spring expression the scheduler runs; {@link #describe} turns them into a sentence a person reads;
 * and {@link #fromCron} turns an existing expression back into the dropdown choices so an edit re-opens the same
 * controls rather than a cron box. The reverse is best-effort — a hand-written cron that matches none of the
 * shapes this builds degrades to {@link ScheduleFrequency#CUSTOM}, which is honest rather than lossy.
 *
 * <p>All expressions are Spring's six-field form ({@code second minute hour day-of-month month day-of-week}),
 * validated with {@link CronExpression} before they are returned, so an invalid configuration fails here with a
 * readable message instead of at scheduler-registration time.
 */
@Component
public class SchedulerExpressionBuilder {

    private static final DateTimeFormatter TWELVE_HOUR =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    /** MON…SUN in the order a week is usually read, for stable day lists. */
    private static final List<String> WEEK = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final List<String> WEEK_FULL = List.of("Monday", "Tuesday", "Wednesday", "Thursday",
            "Friday", "Saturday", "Sunday");

    /**
     * Builds the cron for a configuration.
     *
     * @param config the friendly configuration
     * @return a valid Spring six-field cron expression
     * @throws IllegalArgumentException when the configuration is incomplete or invalid
     */
    public String buildCron(ScheduleConfig config) {
        validate(config);
        String cron = switch (config.getFrequency()) {
            case EVERY_MINUTE -> "0 * * * * *";
            case EVERY_N_MINUTES -> "0 */" + config.getInterval() + " * * * *";
            case HOURLY -> "0 " + config.getMinute() + " * * * *";
            case EVERY_N_HOURS -> {
                LocalTime t = time(config);
                yield "0 " + t.getMinute() + " " + t.getHour() + "/" + config.getInterval() + " * * *";
            }
            case DAILY -> {
                LocalTime t = time(config);
                yield "0 " + t.getMinute() + " " + t.getHour() + " * * *";
            }
            case WEEKLY, SELECTED_DAYS -> {
                LocalTime t = time(config);
                yield "0 " + t.getMinute() + " " + t.getHour() + " * * " + String.join(",", orderedDays(config));
            }
            case MONTHLY, SPECIFIC_DAY_OF_MONTH -> {
                LocalTime t = time(config);
                String dom = config.isLastDayOfMonth() ? "L" : String.valueOf(config.getDayOfMonth());
                yield "0 " + t.getMinute() + " " + t.getHour() + " " + dom + " * *";
            }
            case CUSTOM -> config.getCron().trim();
        };
        if (!CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("The schedule produced an invalid cron expression.");
        }
        return cron;
    }

    /**
     * Describes a configuration in one plain sentence.
     *
     * @param config   the configuration
     * @param timezone the timezone label to append, or null to omit it
     * @return a human-readable description, e.g. "Runs every Monday and Friday at 9:30 AM IST"
     */
    public String describe(ScheduleConfig config, String timezone) {
        if (config == null || config.getFrequency() == null) {
            return "No schedule configured.";
        }
        String body = switch (config.getFrequency()) {
            case EVERY_MINUTE -> "Runs every minute";
            case EVERY_N_MINUTES -> "Runs every " + config.getInterval() + " minute"
                    + plural(config.getInterval());
            case HOURLY -> "Runs every hour at " + config.getMinute() + " minute"
                    + plural(config.getMinute()) + " past the hour";
            case EVERY_N_HOURS -> "Runs every " + config.getInterval() + " hour"
                    + plural(config.getInterval()) + " starting at " + display(time(config));
            case DAILY -> "Runs every day at " + display(time(config));
            case WEEKLY, SELECTED_DAYS -> "Runs every " + joinDays(orderedDays(config)) + " at "
                    + display(time(config));
            case MONTHLY, SPECIFIC_DAY_OF_MONTH -> "Runs on the "
                    + (config.isLastDayOfMonth() ? "last day" : ordinal(config.getDayOfMonth()) + " day")
                    + " of every month at " + display(time(config));
            case CUSTOM -> describeCron(config.getCron());
        };
        return body + (timezone == null || timezone.isBlank() ? "." : " " + shortZone(timezone) + ".");
    }

    /**
     * Recovers a friendly configuration from an existing cron, so an edit shows dropdowns rather than cron.
     *
     * @param cron a Spring six-field cron expression
     * @return the configuration; {@link ScheduleFrequency#CUSTOM} carrying the cron when no friendly shape fits
     */
    public ScheduleConfig fromCron(String cron) {
        ScheduleConfig config = new ScheduleConfig();
        if (cron == null || cron.isBlank()) {
            config.setFrequency(ScheduleFrequency.CUSTOM);
            config.setCron(cron);
            return config;
        }
        String[] f = cron.trim().split("\\s+");
        if (f.length == 6 && f[0].equals("0")) {
            String min = f[1];
            String hour = f[2];
            String dom = f[3];
            String month = f[4];
            String dow = f[5];
            boolean everyDom = dom.equals("*") || dom.equals("?");
            boolean everyDow = dow.equals("*") || dow.equals("?");

            if (min.equals("*") && hour.equals("*") && everyDom && month.equals("*") && everyDow) {
                return freq(config, ScheduleFrequency.EVERY_MINUTE);
            }
            if (min.startsWith("*/") && hour.equals("*") && everyDom && everyDow && isInt(min.substring(2))) {
                config.setInterval(Integer.parseInt(min.substring(2)));
                return freq(config, ScheduleFrequency.EVERY_N_MINUTES);
            }
            if (isInt(min) && hour.equals("*") && everyDom && everyDow) {
                config.setMinute(Integer.parseInt(min));
                return freq(config, ScheduleFrequency.HOURLY);
            }
            if (isInt(min) && hour.contains("/") && everyDom && everyDow) {
                String[] hs = hour.split("/");
                if (isInt(hs[0]) && isInt(hs[1])) {
                    config.setInterval(Integer.parseInt(hs[1]));
                    config.setTime(hhmm(Integer.parseInt(hs[0]), Integer.parseInt(min)));
                    return freq(config, ScheduleFrequency.EVERY_N_HOURS);
                }
            }
            if (isInt(min) && isInt(hour) && everyDom && month.equals("*") && everyDow) {
                config.setTime(hhmm(Integer.parseInt(hour), Integer.parseInt(min)));
                return freq(config, ScheduleFrequency.DAILY);
            }
            if (isInt(min) && isInt(hour) && everyDom && month.equals("*") && !everyDow && isDayList(dow)) {
                config.setTime(hhmm(Integer.parseInt(hour), Integer.parseInt(min)));
                config.setDaysOfWeek(parseDays(dow));
                return freq(config, ScheduleFrequency.WEEKLY);
            }
            if (isInt(min) && isInt(hour) && month.equals("*") && everyDow
                    && (dom.equalsIgnoreCase("L") || isInt(dom))) {
                config.setTime(hhmm(Integer.parseInt(hour), Integer.parseInt(min)));
                if (dom.equalsIgnoreCase("L")) {
                    config.setLastDayOfMonth(true);
                } else {
                    config.setDayOfMonth(Integer.parseInt(dom));
                }
                return freq(config, ScheduleFrequency.MONTHLY);
            }
        }
        config.setFrequency(ScheduleFrequency.CUSTOM);
        config.setCron(cron);
        return config;
    }

    /**
     * Validates a configuration, before any cron is built from it.
     *
     * @param config the configuration
     * @throws IllegalArgumentException with a readable message on the first problem found
     */
    public void validate(ScheduleConfig config) {
        if (config == null || config.getFrequency() == null) {
            throw new IllegalArgumentException("A schedule frequency is required.");
        }
        switch (config.getFrequency()) {
            case EVERY_N_MINUTES -> {
                requirePositive(config.getInterval(), "minutes");
                if (config.getInterval() > 59) {
                    throw new IllegalArgumentException("Every-N-minutes must be between 1 and 59.");
                }
            }
            case EVERY_N_HOURS -> {
                requirePositive(config.getInterval(), "hours");
                if (config.getInterval() > 23) {
                    throw new IllegalArgumentException("Every-N-hours must be between 1 and 23.");
                }
                time(config);
            }
            case HOURLY -> {
                if (config.getMinute() == null || config.getMinute() < 0 || config.getMinute() > 59) {
                    throw new IllegalArgumentException("The minute past the hour must be between 0 and 59.");
                }
            }
            case DAILY -> time(config);
            case WEEKLY, SELECTED_DAYS -> {
                if (orderedDays(config).isEmpty()) {
                    throw new IllegalArgumentException("Select at least one day of the week.");
                }
                time(config);
            }
            case MONTHLY, SPECIFIC_DAY_OF_MONTH -> {
                if (!config.isLastDayOfMonth()) {
                    Integer d = config.getDayOfMonth();
                    if (d == null || d < 1 || d > 31) {
                        throw new IllegalArgumentException("The day of the month must be between 1 and 31.");
                    }
                }
                time(config);
            }
            case CUSTOM -> {
                if (config.getCron() == null || config.getCron().isBlank()) {
                    throw new IllegalArgumentException("A custom cron expression is required.");
                }
                if (!CronExpression.isValidExpression(config.getCron().trim())) {
                    throw new IllegalArgumentException("The custom cron expression is not valid.");
                }
            }
            case EVERY_MINUTE -> { /* nothing to configure */ }
        }
    }

    // ------------------------------------------------------------------- internals

    private static LocalTime time(ScheduleConfig config) {
        String value = config.getTime();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A time is required for this schedule.");
        }
        try {
            String[] parts = value.trim().split(":");
            return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("The time must be in HH:mm 24-hour form, e.g. 09:30.");
        }
    }

    /** Days in canonical week order, uppercased three-letter, de-duplicated. */
    private static List<String> orderedDays(ScheduleConfig config) {
        List<String> out = new ArrayList<>();
        for (String day : WEEK) {
            if (config.getDaysOfWeek().stream().anyMatch(d -> normaliseDay(d).equals(day))) {
                out.add(day);
            }
        }
        return out;
    }

    private static String normaliseDay(String day) {
        if (day == null) {
            return "";
        }
        String d = day.trim().toUpperCase(Locale.ENGLISH);
        return d.length() >= 3 ? d.substring(0, 3) : d;
    }

    private static ScheduleConfig freq(ScheduleConfig config, ScheduleFrequency frequency) {
        config.setFrequency(frequency);
        return config;
    }

    private static void requirePositive(Integer value, String unit) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("The number of " + unit + " must be greater than zero.");
        }
    }

    private static String display(LocalTime time) {
        return time.format(TWELVE_HOUR);
    }

    private static String hhmm(int hour, int minute) {
        return String.format("%02d:%02d", hour, minute);
    }

    private static String joinDays(List<String> days) {
        List<String> names = new ArrayList<>();
        for (String day : days) {
            names.add(WEEK_FULL.get(WEEK.indexOf(day)));
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        if (names.size() == 2) {
            return names.get(0) + " and " + names.get(1);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static String ordinal(int n) {
        if (n >= 11 && n <= 13) {
            return n + "th";
        }
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }

    private static String plural(Integer n) {
        return n != null && n == 1 ? "" : "s";
    }

    /** A short, human timezone label: the last path segment, e.g. Asia/Kolkata → Kolkata. */
    private static String shortZone(String timezone) {
        int slash = timezone.lastIndexOf('/');
        return slash >= 0 && slash < timezone.length() - 1
                ? timezone.substring(slash + 1).replace('_', ' ') : timezone;
    }

    /** A best-effort description of a custom cron, via the reverse parser, falling back to the raw expression. */
    private String describeCron(String cron) {
        ScheduleConfig parsed = fromCron(cron);
        if (parsed.getFrequency() != ScheduleFrequency.CUSTOM) {
            return describe(parsed, null).replaceFirst("\\.$", "");
        }
        return "Runs on a custom schedule (" + cron.trim() + ")";
    }

    private static boolean isInt(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDayList(String dow) {
        for (String part : dow.split(",")) {
            String d = normaliseDay(part);
            if (!WEEK.contains(d)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> parseDays(String dow) {
        List<String> days = new ArrayList<>();
        for (String part : dow.split(",")) {
            String d = normaliseDay(part);
            if (WEEK.contains(d) && !days.contains(d)) {
                days.add(d);
            }
        }
        return days;
    }
}
