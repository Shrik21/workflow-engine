import { Pipe, PipeTransform } from '@angular/core';

/** Renders a millisecond duration in the largest sensible unit. */
@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(millis: number | null | undefined): string {
    if (millis == null || Number.isNaN(millis)) {
      return '';
    }
    if (millis < 1000) {
      return `${Math.round(millis)} ms`;
    }
    const seconds = millis / 1000;
    if (seconds < 60) {
      return `${seconds.toFixed(seconds < 10 ? 1 : 0)} s`;
    }
    const minutes = Math.floor(seconds / 60);
    const remainder = Math.round(seconds % 60);
    if (minutes < 60) {
      return remainder > 0 ? `${minutes}m ${remainder}s` : `${minutes}m`;
    }
    const hours = Math.floor(minutes / 60);
    return `${hours}h ${minutes % 60}m`;
  }
}

/**
 * Renders a timestamp as a distance from now, in either direction.
 *
 * Operational screens are read to answer "is this stuck", which is a question about elapsed time. The absolute
 * timestamp is kept in the element's `title` so the exact value is still one hover away.
 *
 * Future timestamps read "in 4h". They used to read "just now", which was harmless while everything shown was an
 * event that had already happened, and became actively misleading the moment tasks introduced deadlines: a form
 * due tomorrow was displayed as though it were due at this instant.
 */
@Pipe({ name: 'ago' })
export class AgoPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) {
      return '';
    }
    const then = Date.parse(value);
    if (Number.isNaN(then)) {
      return value;
    }
    const seconds = Math.round((Date.now() - then) / 1000);
    const ahead = seconds < 0;
    const magnitude = Math.abs(seconds);

    if (magnitude < 45) {
      return 'just now';
    }
    const minutes = Math.round(magnitude / 60);
    if (minutes < 60) {
      return ahead ? `in ${minutes}m` : `${minutes}m ago`;
    }
    const hours = Math.round(minutes / 60);
    if (hours < 24) {
      return ahead ? `in ${hours}h` : `${hours}h ago`;
    }
    const days = Math.round(hours / 24);
    if (days < 30) {
      return ahead ? `in ${days}d` : `${days}d ago`;
    }
    return new Date(then).toLocaleDateString();
  }
}

/** Pretty-prints a value as JSON, for variable and payload inspectors. */
@Pipe({ name: 'prettyJson' })
export class PrettyJsonPipe implements PipeTransform {
  transform(value: unknown): string {
    if (value == null) {
      return '';
    }
    if (typeof value === 'string') {
      return value;
    }
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  }
}

/** Renders a byte count compactly, for plugin JAR sizes. */
@Pipe({ name: 'bytes' })
export class BytesPipe implements PipeTransform {
  transform(bytes: number | null | undefined): string {
    if (bytes == null || Number.isNaN(bytes)) {
      return '';
    }
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    const kb = bytes / 1024;
    if (kb < 1024) {
      return `${kb.toFixed(kb < 10 ? 1 : 0)} KB`;
    }
    return `${(kb / 1024).toFixed(1)} MB`;
  }
}

/** Shortens a long identifier or hash for display without losing its ends. */
@Pipe({ name: 'shortId' })
export class ShortIdPipe implements PipeTransform {
  transform(value: string | null | undefined, keep = 8): string {
    if (!value) {
      return '';
    }
    return value.length <= keep * 2 + 1 ? value : `${value.slice(0, keep)}…${value.slice(-4)}`;
  }
}
