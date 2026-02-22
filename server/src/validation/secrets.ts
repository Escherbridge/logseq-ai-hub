const SECRET_KEY_PATTERN = /^[A-Z0-9_]+$/;

interface ValidationResult {
  valid: boolean;
  error?: string;
}

export function validateSecretSet(body: unknown): ValidationResult {
  if (!body || typeof body !== "object") {
    return { valid: false, error: "Request body must be a JSON object" };
  }

  const { key, value } = body as Record<string, unknown>;

  if (!key || typeof key !== "string") {
    return {
      valid: false,
      error: "Missing or invalid 'key' field (must be a string)",
    };
  }

  if (!SECRET_KEY_PATTERN.test(key)) {
    return {
      valid: false,
      error: `Invalid key format: '${key}'. Must match [A-Z0-9_]+ (uppercase letters, digits, underscores only)`,
    };
  }

  if (key.length > 128) {
    return { valid: false, error: "Key too long (max 128 characters)" };
  }

  if (value === undefined || value === null || typeof value !== "string") {
    return {
      valid: false,
      error: "Missing or invalid 'value' field (must be a string)",
    };
  }

  if (value.length === 0) {
    return { valid: false, error: "Value cannot be empty" };
  }

  if (value.length > 4096) {
    return { valid: false, error: "Value too long (max 4096 characters)" };
  }

  return { valid: true };
}
