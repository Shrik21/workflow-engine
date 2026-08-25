package com.orchpilot.workflow.scheduler;

/**
 * A friendly scheduling frequency, chosen from a dropdown instead of a cron expression.
 *
 * <p>Each value maps deterministically to a Spring six-field cron expression by
 * {@link SchedulerExpressionBuilder}. {@link #WEEKLY} and {@link #SELECTED_DAYS} are two presentations of the
 * same thing — a set of weekdays and a time — kept as distinct values only so the UI dropdown can label them as
 * the specification does; the builder treats them identically. Likewise {@link #MONTHLY} and
 * {@link #SPECIFIC_DAY_OF_MONTH} both mean "a day of the month at a time". {@link #CUSTOM} is the escape hatch:
 * the operator supplies the raw cron directly, and nothing here interprets it.
 */
public enum ScheduleFrequency {

    /** Every minute. */
    EVERY_MINUTE,

    /** Every N minutes. */
    EVERY_N_MINUTES,

    /** Once an hour, at a chosen minute past the hour. */
    HOURLY,

    /** Every N hours, from a start time. */
    EVERY_N_HOURS,

    /** Once a day, at a chosen time. */
    DAILY,

    /** On chosen weekdays, at a chosen time. */
    WEEKLY,

    /** The same as {@link #WEEKLY}, presented as a day picker. */
    SELECTED_DAYS,

    /** On a chosen day of the month (or the last day), at a chosen time. */
    MONTHLY,

    /** The same as {@link #MONTHLY}, presented as a specific-day picker. */
    SPECIFIC_DAY_OF_MONTH,

    /** A raw cron expression supplied by an advanced user. */
    CUSTOM
}
