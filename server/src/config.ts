export interface Config {
  port: number;
  whatsappVerifyToken: string;
  whatsappAccessToken: string;
  whatsappPhoneNumberId: string;
  telegramBotToken: string;
  pluginApiToken: string;
  databasePath: string;
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
  };
}

export function validateConfig(config: Config): string[] {
  const errors: string[] = [];
  if (!config.pluginApiToken) {
    errors.push("PLUGIN_API_TOKEN is required");
  }
  return errors;
}
