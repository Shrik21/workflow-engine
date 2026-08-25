/**
 * Where the engine's REST API lives.
 *
 * Two ways to point the console at a backend, in this order of precedence:
 *
 * 1. **At runtime**, by setting `window.WORKFLOW_API_BASE_URL` before the app bootstraps. There is a
 *    commented-out script tag in `index.html` for exactly this. Runtime configuration matters for an
 *    on-premise console: the same built artifact can be deployed against a different engine without a
 *    rebuild, which is not true of a value baked in at compile time.
 * 2. **At build time**, by editing the fallback below.
 *
 * An empty value means same-origin: the browser calls `/api/...` on whatever host served the page. That
 * is the right default for both supported deployments:
 *
 * - `npm start`, where the Angular dev server proxies `/api` to the engine (see `proxy.conf.json`);
 * - the Docker image, where nginx serves the app and proxies `/api` to the engine.
 *
 * Set it to an absolute URL such as `http://localhost:8080` only when the console must call the engine
 * cross-origin. That path also requires the engine to allow this origin, through
 * `workflow.engine.security.allowed-origins`, or the browser will block every request before the engine
 * sees it.
 */
export const environment = {
  apiBaseUrl: readApiBaseUrl(),
  production: true,
};

/** Reads the runtime override, tolerating a trailing slash so both forms work. */
function readApiBaseUrl(): string {
  const runtime = (globalThis as Record<string, unknown>)['WORKFLOW_API_BASE_URL'];
  if (typeof runtime === 'string' && runtime.trim().length > 0) {
    return runtime.trim().replace(/\/+$/, '');
  }
  return '';
}
