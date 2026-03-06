export interface Config {
  port: number;
  whatsappVerifyToken: string;
  whatsappAccessToken: string;
  whatsappPhoneNumberId: string;
  telegramBotToken: string;
  pluginApiToken: string;
  databasePath: string;
  llmApiKey: string;
  llmEndpoint: string;
  agentModel: string;
  agentRequestTimeout: number;
  sessionMessageLimit: number;
  eventRetentionDays: number;
  httpAllowlist: string[];
}

export function loadConfig(): Config {
  return {
    port: parseInt(process.env.PORT || "3000", 10),
    whatsappVerifyToken: process.env.WHATSAPP_VERIFY_TOKEN || "",
    whatsappAccessToken: process.env.WHATSAPP_ACCESS_TOKEN || "",
    whatsappPhoneNumberId: process.env.WHATSAPP_PHONE_NUMBER_ID || "",
    telegramBotToken: process.env.TELEGRAM_BOT_TOKEN || "",
    pluginApiToken: process.env.PLUGIN_API_TOKEN || "",
    databasePath: process.env.DATABASE_PATH || "./data/hub.sqlite",
    llmApiKey: process.env.LLM_API_KEY || process.env.OPENROUTER_API_KEY || "",
    llmEndpoint: process.env.LLM_ENDPOINT || "https://openrouter.ai/api/v1",
    agentModel: process.env.AGENT_MODEL || "anthropic/claude-sonnet-4",
    agentRequestTimeout: parseInt(process.env.AGENT_REQUEST_TIMEOUT || "30000", 10),
    sessionMessageLimit: parseInt(process.env.SESSION_MESSAGE_LIMIT || "50", 10),
    eventRetentionDays: parseInt(process.env.EVENT_RETENTION_DAYS || "30", 10),
    httpAllowlist: (() => {
      try {
        const raw = process.env.HTTP_ALLOWLIST || "[]";
        return JSON.parse(raw) as string[];
      } catch {
        return [] as string[];
      }
    })(),
  };
}

export function validateConfig(config: Config): string[] {
  const errors: string[] = [];
  if (!config.pluginApiToken) {
    errors.push("PLUGIN_API_TOKEN is required");
  }
  return errors;
}

export function validateAgentConfig(config: Config): string[] {
  const warnings: string[] = [];
  if (!config.llmApiKey) {
    warnings.push("LLM_API_KEY not set - agent chat endpoints will return 503");
  }
  return warnings;
}
