package com.orchpilot.workflow.auth.model;

/**
 * A single capability a principal may hold.
 *
 * <p>Authorization in this platform is expressed on permissions, never on roles. Every endpoint
 * asserts a permission, and roles are bundles of permissions defined in {@link Role}. The
 * indirection is the whole point: introducing {@code WORKFLOW_EDITOR} later becomes one constant in
 * {@link Role} rather than an audit of every {@code @PreAuthorize} in the code base.
 *
 * <p>Each constant is granted as a Spring Security authority under its own name, so an annotation
 * reads {@code hasAuthority('PLUGIN_UPLOAD')}. There is no {@code ROLE_} prefix here; that prefix is
 * reserved for the coarse role authorities {@link Role} also grants.
 */
public enum Permission {

    // ------------------------------------------------------------------ workflows
    WORKFLOW_VIEW,
    WORKFLOW_CREATE,
    WORKFLOW_EDIT,
    WORKFLOW_DELETE,
    WORKFLOW_EXECUTE,
    WORKFLOW_PUBLISH,

    // -------------------------------------------------------------------- plugins
    PLUGIN_VIEW,
    PLUGIN_UPLOAD,
    PLUGIN_ACTIVATE,
    PLUGIN_DEACTIVATE,
    PLUGIN_DELETE,

    // ---------------------------------------------------------------------- users
    USER_VIEW,
    USER_CREATE,
    USER_EDIT,
    USER_DELETE,

    // ----------------------------------------------------------------- executions
    EXECUTION_VIEW,
    EXECUTION_CANCEL,

    // -------------------------------------------------------- workflow instances
    // Runtime lifecycle control over a single running instance, distinct from editing the workflow design.
    /** Pause a running workflow instance, holding its active tasks. */
    WORKFLOW_INSTANCE_PAUSE,
    /** Resume a paused workflow instance. */
    WORKFLOW_INSTANCE_RESUME,
    /** Terminate a workflow instance permanently, ending its active tasks. */
    WORKFLOW_INSTANCE_TERMINATE,

    // ------------------------------------------------------------- external forms
    /** Generate a secure external (public) form link for a task. */
    EXTERNAL_FORM_CREATE_LINK,
    /** Revoke or regenerate an external form link. */
    EXTERNAL_FORM_REVOKE_LINK,
    /**
     * Submit an external form. Held by the platform's own external channel rather than by an internal user:
     * external customers are not OrchPilot accounts and never receive a JWT, so the public submit endpoint is
     * authorised by the form token, not by this permission. It exists so the capability is named and auditable.
     */
    EXTERNAL_FORM_SUBMIT,

    // ---------------------------------------------------------------- human tasks
    /**
     * Use the task inbox at all.
     *
     * <p>Holding it does not make any particular task visible: which tasks a person sees is decided per task by
     * their assignment and candidate groups. This is the gate on the feature, not on the data.
     */
    TASK_VIEW,

    /** See every task in the platform, whoever it belongs to. An oversight permission. */
    TASK_VIEW_ALL,

    /** Take an unclaimed task that was offered to you. */
    TASK_CLAIM,

    /** Submit a task you are the assignee of. */
    TASK_COMPLETE,

    /** Withdraw a task, which fails the step that raised it. */
    TASK_CANCEL,

    /** Move a task to somebody else. */
    TASK_REASSIGN,

    /** Full authority over tasks, including ones addressed to nobody. */
    TASK_ADMIN,

    // ------------------------------------------------------------------ schedules
    SCHEDULE_VIEW,
    SCHEDULE_CREATE,
    SCHEDULE_EDIT,
    SCHEDULE_DELETE,

    // -------------------------------------------------------------------- secrets
    // Viewing a secret means viewing its name and metadata. No permission grants reading a value,
    // because no endpoint returns one.
    SECRET_VIEW,
    SECRET_MANAGE,

    // --------------------------------------------------------------------- events
    EVENT_EMIT,

    // ---------------------------------------------------------------- AI providers
    /** List AI providers, their models and the configured connections, to configure an AI Agent node. */
    AI_PROVIDER_VIEW,
    /** Create, edit, test and delete AI provider connections, which hold credentials. */
    AI_PROVIDER_MANAGE,

    // --------------------------------------------------------------- file storage
    // Where uploaded workflow files physically live. Split from view so an auditor can confirm the location
    // without being able to redirect it; a single combined permission could not express that.
    /** See the configured storage location, its status and its free space. */
    WORKFLOW_STORAGE_SETTINGS_VIEW,
    /**
     * Change the storage location.
     *
     * <p>Deliberately held by no role but {@code ADMIN}. Repointing storage decides where every future upload
     * is written and can make every existing file unreadable, so it is not something a workflow author does.
     * Note that attaching files to a workflow needs no permission from this group at all — that follows the
     * workflow's own access control, so the two cannot drift apart.
     */
    WORKFLOW_STORAGE_SETTINGS_EDIT,

    // ------------------------------------------------------------------- AI CLI tools
    // Configuring an AI CLI names an executable that the *engine host* will run. That is a far stronger
    // capability than configuring an HTTP provider, where the worst case is calling the wrong endpoint — so it
    // gets its own permissions rather than riding on AI_PROVIDER_MANAGE, and execute is split from configure.
    /** See the configured AI CLI tools, their status and detected version. Never the executable's output. */
    AI_CLI_VIEW,
    /** Add an AI CLI configuration, naming an executable the engine host will run. */
    AI_CLI_CREATE,
    /** Change an AI CLI configuration, including which executable it points at. */
    AI_CLI_UPDATE,
    /** Remove an AI CLI configuration. */
    AI_CLI_DELETE,
    /**
     * Run the configured CLI — test the connection, detect the version, send a prompt.
     *
     * <p>Separate from create/update on purpose: an operator may legitimately be trusted to point the engine at
     * an already-vetted binary without also being able to drive it, and an auditor should be able to confirm
     * what is configured while running nothing at all.
     */
    AI_CLI_EXECUTE,
    /** Ask the configured AI to analyse a failed node. Reads execution records, so it is not open to everyone. */
    AI_ERROR_ANALYSIS;

    /**
     * @return the authority name Spring Security matches, which is the constant name itself
     */
    public String authority() {
        return name();
    }
}
