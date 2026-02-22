export interface AgentRequest {
  requestId: string;
  operation: string;
  params: Record<string, unknown>;
  traceId?: string;
}

export interface AgentCallback {
  requestId: string;
  success: boolean;
  data?: unknown;
  error?: string;
  traceId?: string;
}

export interface PendingRequest {
  requestId: string;
  operation: string;
  resolve: (value: unknown) => void;
  reject: (reason: unknown) => void;
  timer: ReturnType<typeof setTimeout>;
  createdAt: number;
  traceId?: string;
}

export interface JobCreateRequest {
  name: string;
  type: "autonomous" | "manual" | "scheduled" | "event-driven";
  priority?: number;
  schedule?: string;
  skill?: string;
  input?: Record<string, unknown>;
  dependsOn?: string[];
}

export interface JobSummary {
  jobId: string;
  name: string;
  status: string;
  type: string;
  priority: number;
  createdAt: string;
}

export interface JobDetail extends JobSummary {
  skill?: string;
  input?: Record<string, unknown>;
  schedule?: string;
  dependsOn?: string[];
  startedAt?: string;
  completedAt?: string;
  result?: unknown;
  error?: string;
}

export interface SkillCreateRequest {
  name: string;
  type: "llm-chain" | "tool-chain" | "composite" | "mcp-tool";
  description: string;
  inputs: string[];
  outputs: string[];
  tags?: string[];
  steps: SkillStepDef[];
}

export interface SkillStepDef {
  order: number;
  action: string;
  config?: Record<string, unknown>;
  promptTemplate?: string;
  model?: string;
  mcpServer?: string;
  mcpTool?: string;
}

export interface SkillSummary {
  skillId: string;
  name: string;
  type: string;
  description: string;
  inputs: string[];
  outputs: string[];
  tags?: string[];
}

export interface SkillDetail extends SkillSummary {
  steps: SkillStepDef[];
  version?: number;
}

export interface MCPServerSummary {
  id: string;
  url: string;
  status: string;
}

export interface MCPToolSummary {
  name: string;
  description: string;
  inputSchema?: Record<string, unknown>;
}

export interface MCPResourceSummary {
  uri: string;
  name: string;
  description?: string;
  mimeType?: string;
}

export interface AgentChatRequest {
  message: string;
  conversationId?: string;
}

export interface AgentChatResponse {
  conversationId: string;
  response: string;
  actions?: AgentAction[];
}

export interface AgentAction {
  operation: string;
  params: Record<string, unknown>;
  result: unknown;
  success: boolean;
}

// Secrets Management Types

export interface SecretSetRequest {
  key: string;
  value: string;
}

export interface SecretKeyList {
  keys: string[];
}

export interface SecretCheckResult {
  exists: boolean;
  key: string;
}

export interface SecretOperationResult {
  success: boolean;
  key: string;
}
