export type ApprovalStatus = "pending" | "approved" | "timeout" | "cancelled";

/**
 * Public representation of an approval request.
 * Items returned by getAll()/getPending() always have status "pending"
 * since resolved approvals are removed from the store.
 */
export interface ApprovalRequest {
  id: string;
  contactId: string;
  question: string;
  options?: string[];
  timeout: number; // seconds
  createdAt: string; // ISO timestamp
  status: "pending";
  response: null;
}

export interface ApprovalResult {
  status: "approved" | "timeout" | "cancelled";
  response: string | null;
  resolvedBy?: "webhook" | "manual";
}

export interface CreateApprovalParams {
  contactId: string;
  question: string;
  options?: string[];
  timeout?: number; // seconds, default 300
}

// Internal type - not exported beyond the store
export interface PendingApproval extends ApprovalRequest {
  resolve: (result: ApprovalResult) => void;
  reject: (reason: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}
