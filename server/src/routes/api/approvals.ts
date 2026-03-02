import type { Config } from "../../config";
import type { ApprovalStore } from "../../services/approval-store";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse, notFoundResponse } from "../../helpers/responses";
import { formatApprovalMessage } from "../../services/mcp/approval-tools";
import type { Database } from "bun:sqlite";

const DEFAULT_TIMEOUT = 300; // 5 minutes
const MAX_TIMEOUT = 3600; // 1 hour

export async function handleListApprovals(
  req: Request,
  config: Config,
  approvalStore: ApprovalStore
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  return successResponse({ approvals: approvalStore.getAll() });
}

export async function handleResolveApproval(
  req: Request,
  config: Config,
  approvalStore: ApprovalStore,
  params: Record<string, string>
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.response || typeof body.response !== "string") {
    return errorResponse(400, "Missing required field: response");
  }

  const resolved = approvalStore.resolveById(params.id, body.response);
  if (!resolved) {
    return notFoundResponse(`Approval ${params.id} not found`);
  }

  return successResponse({ resolved: true });
}

export async function handleCancelApproval(
  req: Request,
  config: Config,
  approvalStore: ApprovalStore,
  params: Record<string, string>
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const cancelled = approvalStore.cancel(params.id);
  if (!cancelled) {
    return notFoundResponse(`Approval ${params.id} not found`);
  }

  return successResponse({ cancelled: true });
}

export async function handleAskApproval(
  req: Request,
  config: Config,
  approvalStore: ApprovalStore,
  db: Database
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  const { contactId, question, options, timeout } = body as {
    contactId?: string;
    question?: string;
    options?: string[];
    timeout?: number;
  };

  if (!contactId || typeof contactId !== "string") {
    return errorResponse(400, "Missing required field: contactId");
  }
  if (!question || typeof question !== "string") {
    return errorResponse(400, "Missing required field: question");
  }

  const clampedTimeout = Math.min(
    typeof timeout === "number" && timeout > 0 ? timeout : DEFAULT_TIMEOUT,
    MAX_TIMEOUT
  );

  const message = formatApprovalMessage(question, options, clampedTimeout);

  // Determine platform and recipient from contactId (format: "platform:userId")
  const colonIdx = contactId.indexOf(":");
  const platform = colonIdx > 0 ? contactId.substring(0, colonIdx) : "whatsapp";
  const recipient = colonIdx > 0 ? contactId.substring(colonIdx + 1) : contactId;

  // Send message via internal fetch
  let sendOk = false;
  try {
    const sendRes = await fetch(`http://localhost:${config.port}/api/send`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${config.pluginApiToken}`,
      },
      body: JSON.stringify({
        platform,
        recipient,
        content: message,
      }),
    });
    sendOk = sendRes.ok;
    if (!sendOk) {
      const errText = await sendRes.text().catch(() => "unknown error");
      return errorResponse(502, `Failed to send approval message: ${errText}`);
    }
  } catch (err: any) {
    return errorResponse(502, `Failed to send approval message: ${err.message || "network error"}`);
  }

  // Create approval and await its resolution (long-polling)
  try {
    const { id: approvalId, promise } = approvalStore.create({
      contactId,
      question,
      options,
      timeout: clampedTimeout,
    });

    const result = await promise;
    return successResponse({ approvalId, ...result });
  } catch (err: any) {
    return errorResponse(500, err.message || "Failed to create approval");
  }
}
