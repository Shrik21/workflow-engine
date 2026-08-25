import { PluginNode } from './plugin.model';

/**
 * What a plugin archive declares about itself, read from `META-INF/workflow-plugin.json`.
 *
 * <h2>Why the browser reads this at all</h2>
 *
 * The upload wizard shows what is about to be published before it is published: name, version, nodes,
 * compatibility. Without it the operator is asked to confirm an upload of a file they can only identify by its
 * filename, which is the one thing about a JAR that guarantees nothing.
 *
 * The registry has no endpoint that inspects an archive without storing it, so the manifest is read here. A
 * `.jar` is a ZIP, and one named entry is extracted with the platform's own decompression — no library, and
 * emphatically no execution: nothing is loaded, no class is defined, and the bytes are discarded once the
 * manifest is parsed.
 *
 * <h2>It is a claim, not a fact</h2>
 *
 * This is the archive describing itself to a browser. Nothing here is trusted for a decision that matters: the
 * registry parses the same manifest server-side when the upload arrives and its answer is the authoritative
 * one. What is shown here is a preview, and it is labelled as such.
 */
export interface PluginManifest {
  pluginId: string;
  name: string | null;
  version: string;
  description: string | null;
  vendor: string | null;
  mainClass: string | null;
  pluginType: string | null;
  sdkVersion: string | null;
  javaVersion: string | null;
  engineCompatibility: string | null;
  nodes: PluginNode[];
  requestedPermissions: Record<string, unknown>;
}

/** How reading a chosen file went. */
export type ManifestReadOutcome =
  /** The manifest was found and parsed. */
  | { kind: 'READ'; manifest: PluginManifest }
  /** A valid archive that declares no manifest, which the registry will refuse. */
  | { kind: 'NO_MANIFEST' }
  /** The file is not a readable ZIP at all. */
  | { kind: 'NOT_AN_ARCHIVE'; reason: string }
  /** This browser cannot decompress, so the preview is skipped and the upload still allowed. */
  | { kind: 'UNSUPPORTED'; reason: string };

/**
 * Parses a manifest document into the shape this console uses.
 *
 * Tolerant by design: every field except the two the registry keys on is optional, and an absent list becomes
 * an empty one. A manifest from a newer SDK carrying fields this console has never seen is read for the parts
 * it understands rather than rejected.
 *
 * @param raw the parsed JSON document
 * @returns the manifest, or null when it does not identify a plugin
 */
export function parseManifest(raw: unknown): PluginManifest | null {
  if (!raw || typeof raw !== 'object') {
    return null;
  }
  const source = raw as Record<string, unknown>;
  const pluginId = text(source['pluginId']) ?? text(source['id']);
  const version = text(source['version']);
  if (!pluginId || !version) {
    // Without these two the registry cannot key the archive, so there is nothing worth previewing.
    return null;
  }
  return {
    pluginId,
    version,
    name: text(source['name']),
    description: text(source['description']),
    vendor: text(source['vendor']),
    mainClass: text(source['mainClass']),
    pluginType: text(source['pluginType']) ?? text(source['type']),
    sdkVersion: text(source['sdkVersion']),
    javaVersion: text(source['javaVersion']),
    engineCompatibility: text(source['engineCompatibility']),
    nodes: nodesFrom(source['nodes']),
    requestedPermissions:
      (source['requestedPermissions'] as Record<string, unknown> | undefined) ?? {},
  };
}

function nodesFrom(raw: unknown): PluginNode[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw
    .filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object')
    .map((entry) => ({
      nodeType: text(entry['nodeType']) ?? '',
      displayName: text(entry['displayName']),
      description: text(entry['description']),
      category: text(entry['category']),
      icon: text(entry['icon']),
      configurationSchema:
        (entry['configurationSchema'] as Record<string, unknown> | undefined) ?? {},
      inputPorts: stringsFrom(entry['inputPorts']),
      outputPorts: stringsFrom(entry['outputPorts']),
    }))
    .filter((node) => node.nodeType.length > 0);
}

function stringsFrom(raw: unknown): string[] {
  return Array.isArray(raw) ? raw.map(String).filter((value) => value.length > 0) : [];
}

function text(raw: unknown): string | null {
  return typeof raw === 'string' && raw.trim().length > 0 ? raw.trim() : null;
}
