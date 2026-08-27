package com.orchpilot.workflow.plugins.mongodb;

import java.util.Locale;

/**
 * What a MongoDB operation requires the caller to hold.
 *
 * <h2>Where these are actually enforced</h2>
 *
 * The engine's {@code Permission} enum is a closed Java enum compiled into the engine: a plugin cannot add
 * {@code MONGODB_DELETE} to it, and it should not be able to — a platform where installing a plugin invents
 * new authorities is one where installing a plugin can grant them. So these names are enforced in two places,
 * neither of which is this class alone:
 *
 * <ol>
 *   <li><b>The engine.</b> Running any workflow node at all takes {@code WORKFLOW_EXECUTE}, and editing the
 *       node that names the operation takes {@code WORKFLOW_EDIT}. That is the authoritative gate and it
 *       exists whatever this plugin does.</li>
 *   <li><b>This plugin.</b> An administrator maps each permission below to the roles that hold it, in the
 *       plugin's installation settings — {@code mongodb.permission.MONGODB_DELETE=ADMIN,DATA_STEWARD}. At
 *       execution the acting user's roles are checked against that map. Unmapped permissions are open,
 *       because a plugin that refused everything until it was configured would be one nobody could use, and
 *       the engine's own gate is still in front of it.</li>
 * </ol>
 *
 * <p>The SDK is explicit that "authorization decisions belong to the engine and a plugin should not be the
 * only thing enforcing one", and this is written to agree with that: the mapping is defence in depth over a
 * permission the engine already checked, not a replacement for it.
 */
enum MongoPermission {

    /** Open a connection at all. Held implicitly by anything that can run one of these nodes. */
    CONNECT,

    READ,
    INSERT,
    UPDATE,
    DELETE,
    AGGREGATE,
    INDEX_MANAGE,
    COLLECTION_MANAGE,
    TRANSACTION,

    /** Run an arbitrary database command. The widest reach this plugin has. */
    COMMAND_EXECUTE;

    /** @return the name an administrator writes in the settings, e.g. {@code MONGODB_DELETE} */
    String authority() {
        return "MONGODB_" + name();
    }

    /** @return the settings key holding the roles that carry this permission */
    String settingKey() {
        return "permission." + authority().toLowerCase(Locale.ROOT);
    }
}
