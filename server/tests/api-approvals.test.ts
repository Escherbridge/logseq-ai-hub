import { describe, test, expect } from "bun:test";
import { ApprovalStore } from "../src/services/approval-store";
import {
  handleListApprovals,
  handleResolveApproval,
  handleCancelApproval,
  handleAskApproval,
} from "../src/routes/api/approvals";
import type { Config } from "../src/config";

const testConfig: Config = {
  port: 3000,
  whatsappVerifyToken: "",
  whatsappAccessToken: "",
  whatsappPhoneNumberId: "",
  telegramBotToken: "",
  pluginApiToken: "test-token",
  databasePath: ":memory:",
  llmApiKey: "",
  llmEndpoint: "",
  agentModel: "",
  agentRequestTimeout: 30000,
};

function authReq(method = "GET", body?: any): Request {
  const init: RequestInit = {
    method,
    headers: { Authorization: "Bearer test-token", "Content-Type": "application/json" },
  };
  if (body) init.body = JSON.stringify(body);
  return new Request("http://localhost/api/approvals", init);
}

function noAuthReq(method = "GET"): Request {
  return new Request("http://localhost/api/approvals", { method });
}

// Minimal stub for Database - handleAskApproval takes db but doesn't use it for basic contact validation
const stubDb = {} as any;

describe("handleListApprovals", () => {
  test("returns empty list when no approvals", async () => {
    const store = new ApprovalStore();
    const res = await handleListApprovals(authReq(), testConfig, store);
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.success).toBe(true);
    expect(json.data.approvals).toEqual([]);
  });

  test("returns list with pending approvals", async () => {
    const store = new ApprovalStore();
    const { id } = store.create({ contactId: "whatsapp:123", question: "Approve this?" });
    const res = await handleListApprovals(authReq(), testConfig, store);
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.success).toBe(true);
    expect(json.data.approvals).toHaveLength(1);
    expect(json.data.approvals[0].id).toBe(id);
    expect(json.data.approvals[0].question).toBe("Approve this?");
    // Clean up timer
    store.cancel(id);
  });

  test("returns 401 without auth", async () => {
    const store = new ApprovalStore();
    const res = await handleListApprovals(noAuthReq(), testConfig, store);
    expect(res.status).toBe(401);
  });
});

describe("handleResolveApproval", () => {
  test("resolves existing approval → 200", async () => {
    const store = new ApprovalStore();
    const { id } = store.create({
      contactId: "whatsapp:456",
      question: "Should I proceed?",
      timeout: 60,
    });

    const req = authReq("POST", { response: "yes" });
    const res = await handleResolveApproval(req, testConfig, store, { id });
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.success).toBe(true);
    expect(json.data.resolved).toBe(true);
  });

  test("the waiting Promise resolves with resolvedBy: manual", async () => {
    const store = new ApprovalStore();
    const { id, promise } = store.create({
      contactId: "whatsapp:789",
      question: "Confirm?",
      timeout: 60,
    });

    const req = authReq("POST", { response: "confirm" });
    await handleResolveApproval(req, testConfig, store, { id });

    const result = await promise;
    expect(result.status).toBe("approved");
    expect(result.response).toBe("confirm");
    expect(result.resolvedBy).toBe("manual");
  });

  test("unknown ID → 404", async () => {
    const store = new ApprovalStore();
    const req = authReq("POST", { response: "yes" });
    const res = await handleResolveApproval(req, testConfig, store, { id: "nonexistent-id" });
    expect(res.status).toBe(404);
  });

  test("missing response body → 400", async () => {
    const store = new ApprovalStore();
    const req = authReq("POST", {});
    const res = await handleResolveApproval(req, testConfig, store, { id: "some-id" });
    expect(res.status).toBe(400);
    const json = await res.json();
    expect(json.success).toBe(false);
  });

  test("returns 401 without auth", async () => {
    const store = new ApprovalStore();
    const res = await handleResolveApproval(noAuthReq("POST"), testConfig, store, { id: "some-id" });
    expect(res.status).toBe(401);
  });
});

describe("handleCancelApproval", () => {
  test("cancels existing approval → 200", async () => {
    const store = new ApprovalStore();
    const { id } = store.create({
      contactId: "telegram:111",
      question: "Continue?",
      timeout: 60,
    });

    const req = authReq("DELETE");
    const res = await handleCancelApproval(req, testConfig, store, { id });
    expect(res.status).toBe(200);
    const json = await res.json();
    expect(json.success).toBe(true);
    expect(json.data.cancelled).toBe(true);
  });

  test("the waiting Promise resolves with status: cancelled", async () => {
    const store = new ApprovalStore();
    const { id, promise } = store.create({
      contactId: "telegram:222",
      question: "Cancel me?",
      timeout: 60,
    });

    const req = authReq("DELETE");
    await handleCancelApproval(req, testConfig, store, { id });

    const result = await promise;
    expect(result.status).toBe("cancelled");
    expect(result.response).toBeNull();
  });

  test("unknown ID → 404", async () => {
    const store = new ApprovalStore();
    const req = authReq("DELETE");
    const res = await handleCancelApproval(req, testConfig, store, { id: "nonexistent-id" });
    expect(res.status).toBe(404);
  });

  test("returns 401 without auth", async () => {
    const store = new ApprovalStore();
    const res = await handleCancelApproval(noAuthReq("DELETE"), testConfig, store, { id: "some-id" });
    expect(res.status).toBe(401);
  });
});

describe("handleAskApproval", () => {
  test("returns 400 if contactId is missing", async () => {
    const store = new ApprovalStore();
    const req = authReq("POST", { question: "Approve?" });
    const res = await handleAskApproval(req, testConfig, store, stubDb);
    expect(res.status).toBe(400);
    const json = await res.json();
    expect(json.success).toBe(false);
    expect(json.error).toContain("contactId");
  });

  test("returns 400 if question is missing", async () => {
    const store = new ApprovalStore();
    const req = authReq("POST", { contactId: "whatsapp:123" });
    const res = await handleAskApproval(req, testConfig, store, stubDb);
    expect(res.status).toBe(400);
    const json = await res.json();
    expect(json.success).toBe(false);
    expect(json.error).toContain("question");
  });

  test("returns 400 if both fields are missing", async () => {
    const store = new ApprovalStore();
    const req = authReq("POST", {});
    const res = await handleAskApproval(req, testConfig, store, stubDb);
    expect(res.status).toBe(400);
    const json = await res.json();
    expect(json.success).toBe(false);
  });

  test("returns 401 without auth", async () => {
    const store = new ApprovalStore();
    const res = await handleAskApproval(noAuthReq("POST"), testConfig, store, stubDb);
    expect(res.status).toBe(401);
  });
});
