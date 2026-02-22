import type { Config } from "../config";

export function authenticate(req: Request, config: Config): boolean {
  const auth = req.headers.get("Authorization");
  return auth === `Bearer ${config.pluginApiToken}`;
}

export function unauthorizedResponse(): Response {
  return Response.json(
    { success: false, error: "Unauthorized" },
    { status: 401 }
  );
}
