package com.orchpilot.workflow.storage.model;

/**
 * What the console shows for the configured storage location.
 *
 * <p>Three states rather than a boolean, because "never set up" and "set up but broken" call for different
 * messages and different actions: the first is an administrator's outstanding task, the second is an incident.
 */
public enum StorageStatus {

    /** No settings document exists. Uploads are refused with {@code FILE_STORAGE_NOT_CONFIGURED}. */
    NOT_CONFIGURED,

    /** Settings exist and the path passed its read/write probe. */
    CONNECTED,

    /**
     * Settings exist but the path no longer works — deleted, unmounted, or permissions changed under it.
     *
     * <p>Reached by re-probing on demand, not by trusting what was true at save time. A volume that failed to
     * mount after a restart is exactly the case this state exists for.
     */
    INVALID
}
