/**
 * Where this console sends its requests.
 *
 * <h2>One backend</h2>
 *
 * The plugin registry, and nothing else. It authenticates its own users, issues its own tokens and enforces
 * its own permissions, so this console has no dependency on the workflow platform at all — not even to sign
 * in. Empty means same-origin, which is what the dev server proxy and the production reverse proxy both
 * arrange; see .
 */
export const environment = {
  production: false,

  /** The registry: authentication, accounts, roles, plugins, archives and the audit trail. */
  registryBaseUrl: '',

  /**
   * The largest archive this console will attempt to upload.
   *
   * Matched to the registry's own `plugin-server.registry.max-jar-size`, which defaults to 64MB. Checking here
   * as well means an oversized file is refused before it is read, rather than after several minutes of upload
   * ending in a 413.
   */
  maxJarSizeBytes: 64 * 1024 * 1024,
};
