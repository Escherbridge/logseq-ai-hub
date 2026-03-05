interface ClaimEntry {
  sessionId: string;
  description: string;
  claimedAt: Date;
}

export class WorkClaimStore {
  private claims: Map<string, ClaimEntry>;

  constructor() {
    this.claims = new Map();
  }

  /**
   * Check if two paths overlap using simple glob matching.
   * - "src/api/**" conflicts with "src/api/routes.ts" (wildcard prefix match)
   * - "src/api/routes.ts" conflicts with "src/api/**" (reverse: file under wildcard)
   * - Otherwise exact match
   */
  private pathsOverlap(existingPath: string, checkPath: string): boolean {
    if (existingPath === checkPath) return true;

    const existingIsWildcard = existingPath.endsWith("/**");
    const checkIsWildcard = checkPath.endsWith("/**");

    if (existingIsWildcard) {
      const prefix = existingPath.slice(0, -3); // strip "/**"
      return checkPath.startsWith(prefix + "/") || checkPath === prefix;
    }

    if (checkIsWildcard) {
      const prefix = checkPath.slice(0, -3); // strip "/**"
      return existingPath.startsWith(prefix + "/") || existingPath === prefix;
    }

    return false;
  }

  claim(
    sessionId: string,
    path: string,
    description: string
  ): { success: boolean; conflict?: { sessionId: string; path: string } } {
    // Check for conflicts with existing claims
    for (const [existingPath, entry] of this.claims.entries()) {
      if (this.pathsOverlap(existingPath, path)) {
        if (entry.sessionId !== sessionId) {
          return {
            success: false,
            conflict: { sessionId: entry.sessionId, path: existingPath },
          };
        }
        // Same session re-claiming overlapping path — update the existing entry
        if (existingPath === path) {
          this.claims.set(path, { sessionId, description, claimedAt: new Date() });
          return { success: true };
        }
      }
    }

    // No conflict — store the new claim
    this.claims.set(path, { sessionId, description, claimedAt: new Date() });
    return { success: true };
  }

  release(sessionId: string, path: string): boolean {
    const entry = this.claims.get(path);
    if (!entry) return false;
    if (entry.sessionId !== sessionId) return false;
    this.claims.delete(path);
    return true;
  }

  releaseAll(sessionId: string): number {
    let count = 0;
    for (const [path, entry] of this.claims.entries()) {
      if (entry.sessionId === sessionId) {
        this.claims.delete(path);
        count++;
      }
    }
    return count;
  }

  listClaims(): Array<{
    path: string;
    sessionId: string;
    description: string;
    claimedAt: Date;
  }> {
    return Array.from(this.claims.entries()).map(([path, entry]) => ({
      path,
      sessionId: entry.sessionId,
      description: entry.description,
      claimedAt: entry.claimedAt,
    }));
  }

  checkConflict(path: string): {
    hasConflict: boolean;
    conflictingClaim?: { sessionId: string; path: string; description: string };
  } {
    for (const [existingPath, entry] of this.claims.entries()) {
      if (this.pathsOverlap(existingPath, path)) {
        return {
          hasConflict: true,
          conflictingClaim: {
            sessionId: entry.sessionId,
            path: existingPath,
            description: entry.description,
          },
        };
      }
    }
    return { hasConflict: false };
  }
}
