import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { getMessages } from "../../db/messages";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";

export function handleGetMessages(
  req: Request,
  config: Config,
  db: Database
): Response {
  if (!authenticate(req, config)) {
    return unauthorizedResponse();
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
