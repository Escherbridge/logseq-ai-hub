import type { Config } from "../config";
import type { ApprovalStore } from "./approval-store";

export interface CorrelationResult {
  resolved: boolean;
  approvalId?: string;
  optionsMismatch?: boolean;
}

/**
 * Create a sendFollowUp callback that sends a message via the internal /api/send endpoint.
 * Used by webhook handlers for approval options-mismatch follow-ups.
 */
export function createSendFollowUp(config: Config): (contactId: string, message: string) => Promise<void> {
  return async (contactId: string, message: string) => {
    const parts = contactId.split(":");
    const platform = parts[0];
    const recipient = parts.slice(1).join(":"); // Handle edge case of colons in recipient
    try {
      await fetch(`http://localhost:${config.port}/api/send`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${config.pluginApiToken}`,
        },
        body: JSON.stringify({ platform, recipient, content: message }),
      });
    } catch (err) {
      console.error(`[approval-correlation] Failed to send follow-up to ${contactId}:`, err);
    }
  };
}

/**
 * Check if a contact has pending approvals and attempt to resolve the oldest.
 * Called from webhook handlers after message storage.
 *
 * @param approvalStore - The approval store instance
 * @param contactId - The contact's ID (e.g., "whatsapp:15551234567")
 * @param messageContent - The incoming message text
 * @param sendFollowUp - Callback to send a follow-up message if options don't match
 * @returns CorrelationResult indicating what happened
 */
export async function checkAndResolveApproval(
  approvalStore: ApprovalStore,
  contactId: string,
  messageContent: string,
  sendFollowUp?: (contactId: string, message: string) => Promise<void>,
): Promise<CorrelationResult> {
  // 1. Check if contact has any pending approvals
  const pending = approvalStore.getPending(contactId);
  if (pending.length === 0) {
    return { resolved: false };
  }

  // 2. Attempt to resolve the oldest (FIFO)
  const result = approvalStore.resolve(contactId, messageContent, "webhook");

  if (result.resolved) {
    return { resolved: true, approvalId: result.approvalId };
  }

  // 3. If options mismatch, send follow-up
  if ("matched" in result && result.matched === false) {
    const oldest = pending[0];
    if (oldest.options && sendFollowUp) {
      const followUpMsg = `Please reply with one of: ${oldest.options.join(", ")}`;
      await sendFollowUp(contactId, followUpMsg);
    }
    return { resolved: false, optionsMismatch: true };
  }

  return { resolved: false };
}
