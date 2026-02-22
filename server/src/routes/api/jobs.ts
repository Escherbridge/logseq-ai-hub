import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse } from "../../helpers/responses";
import { bridgeGuard, bridgeRequest } from "../../helpers/bridge";
import { validateJobCreate } from "../../validation/jobs";
import { broadcastJobEvent } from "../../services/job-events";

export async function handleCreateJob(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  const validation = validateJobCreate(body);
  if (!validation.valid) {
    return errorResponse(400, validation.errors.join("; "));
  }

  try {
    const result = await bridge!.sendRequest("create_job", validation.data as unknown as Record<string, unknown>, traceId);
    broadcastJobEvent("job_created", {
      name: validation.data.name,
      jobId: `Jobs/${validation.data.name}`,
      status: "queued",
      timestamp: new Date().toISOString(),
    });
    return successResponse(result, 201);
  } catch (err: any) {
    if (err.message?.includes("timed out")) {
      return errorResponse(504, "Plugin did not respond in time");
    }
    return errorResponse(500, err.message || "Failed to create job");
  }
}

export async function handleListJobs(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  const url = new URL(req.url);
  const params: Record<string, unknown> = {
    status: url.searchParams.get("status") || undefined,
    limit: parseInt(url.searchParams.get("limit") || "50", 10),
    offset: parseInt(url.searchParams.get("offset") || "0", 10),
  };

  return bridgeRequest(bridge!, "list_jobs", params, traceId);
}

export async function handleGetJob(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  return bridgeRequest(bridge!, "get_job", { jobId: params.id }, traceId);
}

export async function handleStartJob(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  try {
    const result = await bridge!.sendRequest("start_job", { jobId: params.id }, traceId);
    broadcastJobEvent("job_started", {
      name: params.id,
      jobId: params.id,
      status: "running",
      timestamp: new Date().toISOString(),
    });
    return successResponse(result);
  } catch (err: any) {
    if (err.message?.includes("timed out")) {
      return errorResponse(504, "Plugin did not respond in time");
    }
    if (err.message?.includes("not found") || err.message?.includes("Not found")) {
      return errorResponse(404, err.message);
    }
    if (err.message?.includes("conflict") || err.message?.includes("already")) {
      return errorResponse(409, err.message);
    }
    return errorResponse(500, err.message || "Internal server error");
  }
}

export async function handleCancelJob(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  try {
    const result = await bridge!.sendRequest("cancel_job", { jobId: params.id }, traceId);
    broadcastJobEvent("job_cancelled", {
      name: params.id,
      jobId: params.id,
      status: "cancelled",
      timestamp: new Date().toISOString(),
    });
    return successResponse(result);
  } catch (err: any) {
    if (err.message?.includes("timed out")) {
      return errorResponse(504, "Plugin did not respond in time");
    }
    if (err.message?.includes("not found") || err.message?.includes("Not found")) {
      return errorResponse(404, err.message);
    }
    if (err.message?.includes("conflict") || err.message?.includes("already")) {
      return errorResponse(409, err.message);
    }
    return errorResponse(500, err.message || "Internal server error");
  }
}

export async function handlePauseJob(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  return bridgeRequest(bridge!, "pause_job", { jobId: params.id }, traceId);
}

export async function handleResumeJob(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  return bridgeRequest(bridge!, "resume_job", { jobId: params.id }, traceId);
}
