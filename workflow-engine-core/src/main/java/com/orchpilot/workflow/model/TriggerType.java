package com.orchpilot.workflow.model;

/**
 * How a workflow may be started.
 */
public enum TriggerType {

    /** Started by a person through the API. */
    MANUAL,

    /** Started by another system through the API. */
    API,

    /** Started by the cron scheduler. */
    SCHEDULE,

    /** Started when a named event is emitted. */
    EVENT
}
