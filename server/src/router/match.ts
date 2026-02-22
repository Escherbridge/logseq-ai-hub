/**
 * Matches a URL pathname against a route pattern with :param placeholders.
 * Returns extracted params or null if no match.
 *
 * Examples:
 *   matchRoute("/api/jobs/:id", "/api/jobs/my-job") => { id: "my-job" }
 *   matchRoute("/api/mcp/servers/:id/tools", "/api/mcp/servers/fs/tools") => { id: "fs" }
 *   matchRoute("/api/jobs/:id", "/api/skills/foo") => null
 */
export function matchRoute(
  pattern: string,
  pathname: string
): Record<string, string> | null {
  const patternParts = pattern.split("/");
  const pathParts = pathname.split("/");

  if (patternParts.length !== pathParts.length) return null;

  const params: Record<string, string> = {};

  for (let i = 0; i < patternParts.length; i++) {
    if (patternParts[i].startsWith(":")) {
      params[patternParts[i].slice(1)] = decodeURIComponent(pathParts[i]);
    } else if (patternParts[i] !== pathParts[i]) {
      return null;
    }
  }

  return params;
}
