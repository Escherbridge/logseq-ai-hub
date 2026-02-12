import { describe, it, expect, beforeEach, afterEach } from "bun:test";
import { sseManager } from "../src/services/sse";

describe("SSE Manager", () => {
  beforeEach(() => {
    sseManager.stop();
  });

  afterEach(() => {
    sseManager.stop();
  });

  it("should track client count", () => {
    expect(sseManager.clientCount).toBe(0);

    const mockController = {
      enqueue: () => {},
      close: () => {},
    } as any;

    sseManager.addClient("client1", mockController);
    expect(sseManager.clientCount).toBe(1);

    sseManager.addClient("client2", mockController);
    expect(sseManager.clientCount).toBe(2);

    sseManager.removeClient("client1");
    expect(sseManager.clientCount).toBe(1);
  });

  it("should broadcast to all clients", () => {
    const received: string[] = [];
    const mockController = {
      enqueue: (data: Uint8Array) => {
        received.push(new TextDecoder().decode(data));
      },
      close: () => {},
    } as any;

    sseManager.addClient("client1", mockController);

    sseManager.broadcast({
      type: "new_message",
      data: { content: "test" },
    });

    expect(received.length).toBe(1);
    expect(received[0]).toContain("event: new_message");
    expect(received[0]).toContain('"content":"test"');
  });

  it("should remove clients that fail on enqueue", () => {
    const failController = {
      enqueue: () => {
        throw new Error("closed");
      },
      close: () => {},
    } as any;

    sseManager.addClient("bad-client", failController);
    expect(sseManager.clientCount).toBe(1);

    sseManager.broadcast({ type: "heartbeat", data: {} });
    expect(sseManager.clientCount).toBe(0);
  });
});
