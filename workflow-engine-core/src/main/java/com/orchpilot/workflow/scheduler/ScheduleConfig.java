package com.orchpilot.workflow.scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * The friendly, user-facing shape of a schedule — the dropdown, time and day choices — stored alongside the
 * cron it generates so the UI can reconstruct exactly what the operator picked.
 *
 * <h2>Why store this and the cron</h2>
 *
 * The cron expression is what the scheduler runs on, but it is lossy: {@code 0 30 9 * * MON,FRI} does not say
 * whether the operator chose "Weekly" or "Selected days", and a cron a person hand-wrote may not map back to
 * any friendly shape at all. Persisting the configuration means an edit re-opens the same dropdowns the operator
 * saw, rather than confronting them with cron — which is the whole point of the feature. The cron stays the
 * source of truth for <em>execution</em>; this is the source of truth for <em>editing</em>.
 *
 * <p>Only the fields relevant to {@link #frequency} are meaningful; the rest are null. Timezone is not here —
 * it lives on the trigger, which already had it, and the builder takes it separately.
 */
public class ScheduleConfig {

    private ScheduleFrequency frequency;

    /** Time of day as {@code HH:mm} (24-hour), for daily, weekly, monthly and the start of every-N-hours. */
    private String time;

    /** The N in every-N-minutes / every-N-hours. Must be greater than zero. */
    private Integer interval;

    /** Minute past the hour, for hourly. */
    private Integer minute;

    /** Weekdays for weekly / selected-days, as {@code MON}…{@code SUN}. */
    private List<String> daysOfWeek = new ArrayList<>();

    /** Day of the month (1–31) for monthly / specific-day. Ignored when {@link #lastDayOfMonth} is set. */
    private Integer dayOfMonth;

    /** Whether a monthly schedule runs on the last day of the month rather than a fixed date. */
    private boolean lastDayOfMonth;

    /** The raw cron, for {@link ScheduleFrequency#CUSTOM} only. */
    private String cron;

    public ScheduleFrequency getFrequency() {
        return frequency;
    }

    public void setFrequency(ScheduleFrequency frequency) {
        this.frequency = frequency;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public Integer getInterval() {
        return interval;
    }

    public void setInterval(Integer interval) {
        this.interval = interval;
    }

    public Integer getMinute() {
        return minute;
    }

    public void setMinute(Integer minute) {
        this.minute = minute;
    }

    public List<String> getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(List<String> daysOfWeek) {
        this.daysOfWeek = daysOfWeek == null ? new ArrayList<>() : daysOfWeek;
    }

    public Integer getDayOfMonth() {
        return dayOfMonth;
    }

    public void setDayOfMonth(Integer dayOfMonth) {
        this.dayOfMonth = dayOfMonth;
    }

    public boolean isLastDayOfMonth() {
        return lastDayOfMonth;
    }

    public void setLastDayOfMonth(boolean lastDayOfMonth) {
        this.lastDayOfMonth = lastDayOfMonth;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }
}
