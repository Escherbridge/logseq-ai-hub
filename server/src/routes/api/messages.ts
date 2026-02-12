import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { getMessages } from "../../db/messages";

function authenticateRequest(req: Request, config: Config): boolean {
  const auth = req.headers.get("Authorization");
  return auth === `Bearer ${config.pluginApiToken}`;
}

export function handleGetMessages(
  req: Request,
  config: Config,
  db: Database
): Response {
  if (!authenticateRequest(req, config)) {
    return Response.json(
      { success: false, error: "Unauthorized" },
      { status: 401 }
    );
  }

  const url = new URL(req.url);
  const contactId = url.searchParams.get("contact_id") || undefined;
  const limit = parseInt(url.searchParams.get("limit") || "50", 10);
  const offset = parseInt(url.searchParams.get("offset") || "0", 10);
  const since = url.searchParams.get("since") || undefined;

  const result = getMessages(db, { contactId, limit, offset, since });

  return Response.json({
    success: true,
    data: result,
  });
}
