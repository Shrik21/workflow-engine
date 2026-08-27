/**
 * Shapes shared by every endpoint.
 *
 * These interfaces mirror the engine's DTOs field for field. They are hand-written rather than
 * generated because the set is small and stable, and because a hand-written model is where the
 * front end documents which fields it actually relies on.
 */

/** Spring Data page envelope, as returned by the list endpoints. */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

/**
 * The single error shape every endpoint returns.
 *
 * `details` carries the full list when several things are wrong at once, which is the normal case
 * for workflow and plugin validation. The UI always renders the list when it is present, because
 * showing one problem at a time turns fixing a graph into a guessing game.
 */
export interface ApiError {
  code: string;
  message: string;
  details: string[];
  path: string;
  at: string;
}

/** An empty page, for initialising state before the first load. */
export function emptyPage<T>(size = 20): Page<T> {
  return {
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size,
    first: true,
    last: true,
  };
}
