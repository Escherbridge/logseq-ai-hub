import { sseManager } from "./sse";
import type { SSEEvent } from "../types";

export type JobEventType = "job_created" | "job_started" | "job_completed" | "job_failed" | "job_cancelled" | "skill_created";

export interface JobEventData {
  jobId?: string;
  skillId?: string;
  name: string;
  status?: string;
  timestamp: string;
  error?: string;
}

export function broadcastJobEvent(type: JobEventType, data: JobEventData): void {
  const event: SSEEvent = {
    type,
    data: data as unknown as Record<string, unknown>,
  };
  sseManager.broadcast(event);
}
