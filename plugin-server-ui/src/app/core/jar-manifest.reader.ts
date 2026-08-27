import { Injectable } from '@angular/core';
import {
  ManifestReadOutcome,
  PluginManifest,
  parseManifest,
} from './models/manifest.model';

/**
 * Reads one named entry out of a plugin archive, in the browser.
 *
 * <h2>What this does and does not do</h2>
 *
 * A `.jar` is a ZIP. This locates `META-INF/workflow-plugin.json` through the archive's central directory,
 * inflates that single entry, and parses it as JSON. It does not load a class, define a class loader, execute
 * anything, or retain the archive: the bytes are read from the {@link File} the operator chose and dropped as
 * soon as the manifest is parsed.
 *
 * <h2>Why not a library</h2>
 *
 * The whole job is one central-directory walk and one call to the platform's own `DecompressionStream`. A ZIP
 * library would add a dependency, and this console's brief is explicitly not to introduce one for a problem the
 * browser already solves.
 *
 * <h2>Every failure is recoverable</h2>
 *
 * Nothing here can block an upload. The manifest is a *preview* of what the archive claims; the registry parses
 * the same file server-side and its answer decides. So an archive this reader cannot open, or a browser without
 * `DecompressionStream`, degrades to "no preview" and the wizard says so, rather than refusing a file that may
 * be perfectly good.
 */
@Injectable({ providedIn: 'root' })
export class JarManifestReader {
  /** The entry the SDK writes and the registry looks for. */
  static readonly MANIFEST_ENTRY = 'META-INF/workflow-plugin.json';

  private static readonly END_OF_CENTRAL_DIRECTORY = 0x06054b50;
  private static readonly CENTRAL_FILE_HEADER = 0x02014b50;
  private static readonly LOCAL_FILE_HEADER = 0x04034b50;
  private static readonly STORED = 0;
  private static readonly DEFLATED = 8;
  /** A field of all ones means the real value lives in a Zip64 extra field. */
  private static readonly ZIP64_SENTINEL = 0xffffffff;

  /**
   * @param file the archive the operator selected
   * @returns what could be read from it
   */
  async read(file: File): Promise<ManifestReadOutcome> {
    if (typeof DecompressionStream === 'undefined') {
      return {
        kind: 'UNSUPPORTED',
        reason:
          'This browser cannot decompress archives, so the plugin details cannot be previewed here. ' +
          'The registry will still validate the archive when it is uploaded.',
      };
    }

    let buffer: ArrayBuffer;
    try {
      buffer = await file.arrayBuffer();
    } catch (error) {
      return { kind: 'NOT_AN_ARCHIVE', reason: 'The file could not be read from disk.' };
    }

    try {
      const view = new DataView(buffer);
      const directoryOffset = this.findCentralDirectory(view);
      if (directoryOffset === null) {
        return {
          kind: 'NOT_AN_ARCHIVE',
          reason: 'This file is not a readable archive. A plugin must be a Java .jar.',
        };
      }

      const entry = this.findEntry(view, directoryOffset, JarManifestReader.MANIFEST_ENTRY);
      if (!entry) {
        return { kind: 'NO_MANIFEST' };
      }

      const bytes = await this.inflate(view, buffer, entry);
      if (!bytes) {
        return {
          kind: 'UNSUPPORTED',
          reason: 'The manifest is stored in a compression format this console cannot read.',
        };
      }

      const manifest = parseManifest(JSON.parse(new TextDecoder().decode(bytes)));
      return manifest
        ? { kind: 'READ', manifest }
        : { kind: 'NO_MANIFEST' };
    } catch (error) {
      return {
        kind: 'NOT_AN_ARCHIVE',
        reason:
          error instanceof SyntaxError
            ? 'The archive declares a manifest that is not valid JSON.'
            : 'The archive could not be read. It may be corrupt or truncated.',
      };
    }
  }

  /**
   * Locates the central directory by scanning backwards for the end-of-central-directory record.
   *
   * Backwards because the record is last and its position depends on the length of a trailing comment, which
   * is why a ZIP cannot simply be read front to back.
   */
  private findCentralDirectory(view: DataView): number | null {
    const minimum = 22;
    if (view.byteLength < minimum) {
      return null;
    }
    // A comment may be up to 64KB, so that plus the record itself bounds the search.
    const limit = Math.min(view.byteLength, minimum + 0xffff);
    for (let offset = view.byteLength - minimum; offset >= view.byteLength - limit; offset--) {
      if (view.getUint32(offset, true) === JarManifestReader.END_OF_CENTRAL_DIRECTORY) {
        const directoryOffset = view.getUint32(offset + 16, true);
        return directoryOffset === JarManifestReader.ZIP64_SENTINEL ? null : directoryOffset;
      }
    }
    return null;
  }

  /** Walks the central directory for one entry by name. */
  private findEntry(view: DataView, start: number, name: string): CentralEntry | null {
    let offset = start;
    while (offset + 46 <= view.byteLength) {
      if (view.getUint32(offset, true) !== JarManifestReader.CENTRAL_FILE_HEADER) {
        return null;
      }
      const method = view.getUint16(offset + 10, true);
      const compressedSize = view.getUint32(offset + 20, true);
      const nameLength = view.getUint16(offset + 28, true);
      const extraLength = view.getUint16(offset + 30, true);
      const commentLength = view.getUint16(offset + 32, true);
      const localOffset = view.getUint32(offset + 42, true);

      const entryName = new TextDecoder().decode(
        new Uint8Array(view.buffer, offset + 46, nameLength),
      );
      if (entryName === name) {
        if (
          compressedSize === JarManifestReader.ZIP64_SENTINEL ||
          localOffset === JarManifestReader.ZIP64_SENTINEL
        ) {
          return null;
        }
        return { method, compressedSize, localOffset };
      }
      offset += 46 + nameLength + extraLength + commentLength;
    }
    return null;
  }

  /**
   * Reads the entry's bytes, inflating them when they are deflated.
   *
   * The local header repeats the name and extra-field lengths, and they may differ from the central
   * directory's, so the data offset has to be computed from the local header rather than assumed.
   */
  private async inflate(
    view: DataView,
    buffer: ArrayBuffer,
    entry: CentralEntry,
  ): Promise<Uint8Array | null> {
    if (view.getUint32(entry.localOffset, true) !== JarManifestReader.LOCAL_FILE_HEADER) {
      return null;
    }
    const nameLength = view.getUint16(entry.localOffset + 26, true);
    const extraLength = view.getUint16(entry.localOffset + 28, true);
    const dataStart = entry.localOffset + 30 + nameLength + extraLength;
    const compressed = new Uint8Array(buffer, dataStart, entry.compressedSize);

    if (entry.method === JarManifestReader.STORED) {
      return compressed;
    }
    if (entry.method !== JarManifestReader.DEFLATED) {
      return null;
    }

    // 'deflate-raw': a ZIP entry carries a bare deflate stream with no zlib header around it.
    const stream = new Blob([compressed as BlobPart])
      .stream()
      .pipeThrough(new DecompressionStream('deflate-raw'));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }
}

/** Where one entry's bytes live, and how they are packed. */
interface CentralEntry {
  method: number;
  compressedSize: number;
  localOffset: number;
}

/** Re-exported so callers need one import for the reader and what it produces. */
export type { PluginManifest, ManifestReadOutcome };
