import { FormField } from './form.models';

/** The customer-facing state a public form link is in. Mirrors the server's `ExternalFormState`. */
export type ExternalFormState =
  | 'OPEN'
  | 'WORKFLOW_PAUSED'
  | 'WORKFLOW_TERMINATED'
  | 'ALREADY_SUBMITTED'
  | 'EXPIRED'
  | 'REVOKED'
  | 'CANCELLED'
  | 'INVALID';

/** The response of `GET /api/public/forms/{token}` — everything the public page needs, and nothing internal. */
export interface PublicFormView {
  state: ExternalFormState;
  message: string | null;
  formTitle: string | null;
  formDescription: string | null;
  fields: FormField[];
  allowSubmit: boolean;
  allowDraft: boolean;
  expiresAt: string | null;
  draftData: Record<string, unknown>;
}

/** The confirmation an external submission returns. */
export interface ExternalSubmitResult {
  referenceNumber: string;
}

/** A generated external link — the one response that carries the URL. */
export interface ExternalLinkResponse {
  url: string;
  tokenId: string;
  status: string;
  expiresAt: string | null;
  maxSubmissions: number;
}

/** A link's status, without anything that could reconstruct its URL. */
export interface ExternalLinkSummary {
  tokenId: string;
  status: string;
  expiresAt: string | null;
  createdAt: string | null;
  createdBy: string | null;
  submissionCount: number;
  maxSubmissions: number;
}

/** Options an operator may set when generating a link. */
export interface GenerateLinkRequest {
  expirationHours?: number | null;
  maxSubmissions?: number | null;
  allowSubmit?: boolean | null;
  allowDraft?: boolean | null;
  customerName?: string | null;
  customerEmail?: string | null;
  customerReference?: string | null;
}
