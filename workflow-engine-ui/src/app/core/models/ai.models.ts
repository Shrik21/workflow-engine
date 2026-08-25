/** AI providers, connections and models — mirrors the server's `/api/ai` shapes. */

/** A provider type and what it can do. */
export interface AiProvider {
  type: string;
  supportsTools: boolean;
  supportsStreaming: boolean;
  supportsVision: boolean;
  supportsStructuredOutput: boolean;
}

/** A stored connection, never including its key — `hasKey` says only whether one is set. */
export interface AiConnection {
  id: string;
  name: string;
  providerType: string;
  endpoint: string | null;
  enabled: boolean;
  hasKey: boolean;
  settings: Record<string, unknown>;
  createdAt: string | null;
  updatedAt: string | null;
}

/** Create/update body; `apiKey` is write-only and, on update, blank keeps the existing key. */
export interface AiConnectionRequest {
  name: string;
  providerType: string;
  endpoint?: string | null;
  apiKey?: string | null;
  settings?: Record<string, unknown>;
  enabled?: boolean;
}

/** A plugin-backed tool an AI Agent can be configured with. */
export interface AiTool {
  pluginId: string;
  pluginVersion: string;
  nodeType: string;
  toolName: string;
  displayName: string;
  description: string;
  category: string;
  supportsAI: boolean;
  destructive: boolean;
}

/** An agent's reference to a selected tool. */
export interface AiToolSelection {
  pluginId: string;
  nodeType: string;
}

/** Usage grouped under one provider or model. */
export interface AiUsageBreakdown {
  name: string;
  executions: number;
  totalTokens: number;
}

/** Totals-and-breakdowns summary of AI Agent token usage. */
export interface AiUsageSummary {
  executions: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  toolCalls: number;
  byProvider: AiUsageBreakdown[];
  byModel: AiUsageBreakdown[];
}

/** One AI Agent run, as history — metadata only, never a prompt or a response. */
export interface AiAgentExecution {
  id: string;
  workflowExecutionId: string;
  nodeId: string;
  provider: string;
  model: string;
  status: string;
  startedAt: string | null;
  completedAt: string | null;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  toolCalls: number;
  blockedToolCalls: number;
  iterations: number;
  repairAttempts: number;
  stopReason: string | null;
  error: string | null;
}

/** A model a provider offers. */
export interface AiModel {
  id: string;
  displayName: string;
  supportsTools: boolean;
  supportsStructured: boolean;
  supportsVision: boolean;
}

/** The provider types the designer offers, grouped for the dropdown. */
export const AI_PROVIDER_GROUPS: { group: string; types: { value: string; label: string }[] }[] = [
  {
    group: 'Cloud',
    types: [
      { value: 'OPENAI', label: 'OpenAI' },
      { value: 'ANTHROPIC', label: 'Anthropic Claude' },
      { value: 'GEMINI', label: 'Google Gemini' },
      { value: 'AZURE_OPENAI', label: 'Azure OpenAI' },
      { value: 'AWS_BEDROCK', label: 'AWS Bedrock' },
      { value: 'VERTEX_AI', label: 'Google Vertex AI' },
    ],
  },
  {
    group: 'Self-hosted',
    types: [
      { value: 'OLLAMA', label: 'Ollama' },
      { value: 'NVIDIA_NIM', label: 'NVIDIA NIM' },
      { value: 'VLLM', label: 'vLLM' },
    ],
  },
  { group: 'Testing', types: [{ value: 'MOCK', label: 'Mock (offline)' }] },
];
