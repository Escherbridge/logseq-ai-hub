import type { AgentBridge } from "./agent-bridge";

export interface PiDevConfig {
  enabled: boolean;
  installPath: string;
  defaultModel: string;
  rpcPort: number;
  maxConcurrentSessions: number;
}

export interface PiSession {
  id: string;
  project: string;
  task: string;
  status: "starting" | "running" | "stopping" | "stopped" | "error";
  startedAt: Date;
  pid?: number;
  rpcPort?: number;
  output: string[];
  agentProfile?: string;
}

export interface SpawnOptions {
  agentProfile?: string;
  model?: string;
  workingDir?: string;
  timeout?: number;
}

export class PiDevManager {
  private bridge: AgentBridge;
  private config: PiDevConfig;
  private sessions: Map<string, PiSession> = new Map();
  private sessionCounter = 0;

  constructor(bridge: AgentBridge, config: PiDevConfig) {
    this.bridge = bridge;
    this.config = config;
  }

  /** Check if pi.dev is enabled */
  isEnabled(): boolean {
    return this.config.enabled;
  }

  /** Validate pi CLI installation */
  async validateInstall(): Promise<{ valid: boolean; version?: string; error?: string }> {
    if (!this.config.installPath) {
      return { valid: false, error: "Pi.dev install path not configured" };
    }
    try {
      // Use Bun.spawn to run `pi --version`
      const proc = Bun.spawn([this.config.installPath, "--version"], {
        stdout: "pipe",
        stderr: "pipe",
      });
      const exitCode = await proc.exited;
      if (exitCode !== 0) {
        const stderr = await new Response(proc.stderr).text();
        return { valid: false, error: `Pi CLI exited with code ${exitCode}: ${stderr}` };
      }
      const stdout = await new Response(proc.stdout).text();
      return { valid: true, version: stdout.trim() };
    } catch (err: any) {
      return { valid: false, error: `Pi CLI not found at ${this.config.installPath}: ${err.message}` };
    }
  }

  /** Spawn a new pi.dev session */
  async spawn(project: string, task: string, options?: SpawnOptions): Promise<PiSession> {
    if (!this.config.enabled) {
      throw new Error("Pi.dev integration is not enabled");
    }

    // Check concurrent session limit
    const activeSessions = [...this.sessions.values()].filter(
      (s) => s.status === "running" || s.status === "starting",
    );
    if (activeSessions.length >= this.config.maxConcurrentSessions) {
      throw new Error(
        `Maximum concurrent sessions (${this.config.maxConcurrentSessions}) reached. ` +
          `Stop an existing session first.`,
      );
    }

    const sessionId = `pi-${++this.sessionCounter}-${Date.now()}`;
    const session: PiSession = {
      id: sessionId,
      project,
      task,
      status: "starting",
      startedAt: new Date(),
      output: [],
      agentProfile: options?.agentProfile,
    };

    this.sessions.set(sessionId, session);

    // Assemble context from bridge (project page + ADRs + lessons)
    try {
      if (this.bridge.isPluginConnected()) {
        const projectData = await this.bridge
          .sendRequest("project_get", { name: project })
          .catch(() => null);
        if (projectData) {
          session.output.push(`[context] Project loaded: ${project}`);
        }
      }
    } catch {
      session.output.push("[context] Bridge not available, proceeding without context");
    }

    // NOTE: Actual process spawning is stubbed for now.
    // In production, this would:
    // 1. Generate .pi-context/AGENTS.md and SYSTEM.md files
    // 2. Spawn `pi --rpc --port <port> --model <model>` child process
    // 3. Track the PID and RPC port
    // 4. Set up output streaming

    session.status = "running";
    session.output.push(`[session] Pi.dev session ${sessionId} started for project "${project}"`);

    return session;
  }

  /** Send a message to a running session */
  async send(
    sessionId: string,
    message: string,
    steering?: string,
  ): Promise<{ received: boolean; output?: string }> {
    const session = this.sessions.get(sessionId);
    if (!session) {
      throw new Error(`Session ${sessionId} not found`);
    }
    if (session.status !== "running") {
      throw new Error(`Session ${sessionId} is not running (status: ${session.status})`);
    }

    // NOTE: In production, send via RPC to the pi process
    session.output.push(`[send] ${message}`);
    if (steering) {
      session.output.push(`[steering] ${steering}`);
    }

    return { received: true, output: `Acknowledged: ${message}` };
  }

  /** Get session status */
  status(sessionId: string): PiSession | undefined {
    return this.sessions.get(sessionId);
  }

  /** Stop a running session */
  async stop(sessionId: string): Promise<{ stopped: boolean; output: string[] }> {
    const session = this.sessions.get(sessionId);
    if (!session) {
      throw new Error(`Session ${sessionId} not found`);
    }

    if (session.status === "stopped") {
      return { stopped: true, output: session.output };
    }

    session.status = "stopping";

    // NOTE: In production, send SIGTERM to the pi process, wait, then SIGKILL
    session.status = "stopped";
    session.output.push(`[session] Pi.dev session ${sessionId} stopped`);

    return { stopped: true, output: session.output };
  }

  /** List all sessions */
  listSessions(): PiSession[] {
    return [...this.sessions.values()];
  }

  /** Clean up stopped sessions older than given duration */
  cleanupSessions(maxAgeMs: number = 3_600_000): number {
    let cleaned = 0;
    const now = Date.now();
    for (const [id, session] of this.sessions) {
      if (session.status === "stopped" && now - session.startedAt.getTime() > maxAgeMs) {
        this.sessions.delete(id);
        cleaned++;
      }
    }
    return cleaned;
  }
}
