/**
 * Pure, I/O-free renderers that turn bridge-shaped graph results into compact
 * markdown/text for the MCP tool responses. Text beats pretty-printed JSON
 * here -- it is far cheaper in tokens and reads naturally for an agent that
 * already thinks in Logseq's outline syntax. See `.omc/plans/graph-mcp-contract.md`
 * for the exact result shapes these functions consume.
 */

/** A Logseq block as returned by the bridge: `pre: true` marks the page-properties block. */
export interface Node {
  uuid: string;
  content: string;
  pre?: true;
  children: Node[];
}

export interface RenderOutlineOptions {
  /** Stop rendering after this many non-"pre" blocks (pre-order). Default 200. */
  maxBlocks?: number;
  /** Show the `⟨uuid⟩` marker on each rendered block. Default true. */
  showUuids?: boolean;
}

export interface PageReadResult {
  page: { name: string; uuid: string; journal: boolean };
  blocks: Node[];
}

export interface BlockGetResult {
  uuid: string;
  page: string;
  parentUuid: string | null;
  content: string;
  children: Node[];
}

export interface SearchResult {
  query: string;
  pages: string[];
  pagesTotal: number;
  blocks: Array<{ uuid: string; page: string; content: string }>;
  total: number;
}

export interface LinksResult {
  page: string;
  outgoing?: string[];
  backlinks?: Array<{ page: string; blocks: Array<{ uuid: string; content: string }> }>;
  backlinksTotal?: number;
}

export interface PageListResult {
  total: number;
  offset: number;
  pages: Array<{ name: string; updatedAt?: number; journal?: true; tags?: string[] }>;
}

const DEFAULT_MAX_BLOCKS = 200;

// `id::`/`collapsed::` lines are pure noise once we already show the uuid marker
// ourselves; every other `key:: value` property line is left in place.
const NOISE_PROPERTY_LINE = /^\s*(id|collapsed)::.*$/;

function stripNoiseLines(content: string): string[] {
  return content.split("\n").filter((line) => !NOISE_PROPERTY_LINE.test(line));
}

interface FlatNode {
  node: Node;
  depth: number;
}

function flatten(blocks: Node[], depth: number, out: FlatNode[]): void {
  for (const block of blocks) {
    out.push({ node: block, depth });
    if (block.children.length > 0) flatten(block.children, depth + 1, out);
  }
}

/**
 * Pre-order render of a block forest as an indented markdown outline.
 * `pre: true` blocks (page properties) render unindented, bulletless, and
 * ahead of the budget count -- they are metadata, not graph content.
 */
export function renderOutline(blocks: Node[], opts: RenderOutlineOptions = {}): string {
  const maxBlocks = opts.maxBlocks ?? DEFAULT_MAX_BLOCKS;
  const showUuids = opts.showUuids ?? true;

  const lines: string[] = [];

  for (const node of blocks) {
    if (!node.pre) continue;
    const preLines = stripNoiseLines(node.content);
    if (preLines.length === 0 || preLines.every((line) => line === "")) continue; // nothing left after stripping -- skip entirely
    lines.push(...preLines);
  }

  const flat: FlatNode[] = [];
  flatten(blocks.filter((b) => !b.pre), 0, flat);

  const total = flat.length;
  const shown = Math.min(total, maxBlocks);

  for (let i = 0; i < shown; i++) {
    const { node, depth } = flat[i];
    const indent = "  ".repeat(depth);
    let displayLines = stripNoiseLines(node.content);
    // Covers both a literal "" content (split gives [""]) and content that
    // was entirely id::/collapsed:: noise (filtered down to []).
    if (displayLines.length === 0 || displayLines.every((line) => line === "")) {
      displayLines = ["(empty)"];
    }
    const marker = showUuids ? `  ⟨${node.uuid}⟩` : "";
    lines.push(`${indent}- ${displayLines[0]}${marker}`);
    for (let j = 1; j < displayLines.length; j++) {
      lines.push(`${indent}  ${displayLines[j]}`);
    }
  }

  if (total > maxBlocks) {
    const remaining = total - shown;
    lines.push(`… ${remaining} more blocks not shown — raise max_blocks, or read a subtree with block_get.`);
  }

  return lines.join("\n");
}

export function renderPage(data: PageReadResult, opts: RenderOutlineOptions = {}): string {
  const header = `# ${data.page.name}`;
  if (!data.blocks || data.blocks.length === 0) {
    return `${header}\n(empty page)`;
  }
  return `${header}\n${renderOutline(data.blocks, opts)}`;
}

export function renderBlock(data: BlockGetResult, opts: RenderOutlineOptions = {}): string {
  const parentPart = data.parentUuid ? ` · parent ⟨${data.parentUuid}⟩` : "";
  const header = `Block ⟨${data.uuid}⟩ on [[${data.page}]]${parentPart}`;
  const root: Node = { uuid: data.uuid, content: data.content, children: data.children };
  return `${header}\n${renderOutline([root], opts)}`;
}

/**
 * Single-line, whitespace-collapsed window of `content` centred on the
 * earliest case-insensitive match of any of `terms`. Falls back to the
 * start of the string when no term matches.
 */
export function snippet(content: string, terms: string[], width = 160): string {
  const flat = content.replace(/\s+/g, " ").trim();
  if (flat.length <= width) return flat;

  const lower = flat.toLowerCase();
  let matchIndex = -1;
  for (const term of terms) {
    if (!term) continue;
    const idx = lower.indexOf(term.toLowerCase());
    if (idx !== -1 && (matchIndex === -1 || idx < matchIndex)) matchIndex = idx;
  }
  if (matchIndex === -1) matchIndex = 0;

  const half = Math.floor(width / 2);
  let start = Math.max(0, matchIndex - half);
  let end = start + width;
  if (end > flat.length) {
    end = flat.length;
    start = Math.max(0, end - width);
  }

  const prefix = start > 0 ? "…" : "";
  const suffix = end < flat.length ? "…" : "";
  return prefix + flat.slice(start, end) + suffix;
}

export function renderSearch(data: SearchResult): string {
  const lines: string[] = [];
  if (data.pages.length > 0) {
    lines.push(`Pages matching "${data.query}" (${data.pagesTotal}): ${data.pages.join(", ")}`);
  }
  if (data.total === 0 && data.pages.length === 0) {
    return `No matches for "${data.query}".`;
  }

  const terms = data.query.split(/\s+/).filter(Boolean);
  lines.push(`Blocks: showing ${data.blocks.length} of ${data.total}`);
  for (const block of data.blocks) {
    lines.push(`- [[${block.page}]] ⟨${block.uuid}⟩ ${snippet(block.content, terms)}`);
  }
  return lines.join("\n");
}

export function renderLinks(data: LinksResult): string {
  const lines: string[] = [];

  if (data.outgoing !== undefined) {
    const list = data.outgoing.length > 0 ? data.outgoing.join(", ") : "(none)";
    lines.push(`Outgoing links from [[${data.page}]] (${data.outgoing.length}): ${list}`);
  }

  if (data.backlinks !== undefined) {
    lines.push(`Backlinks to [[${data.page}]] (${data.backlinksTotal ?? 0}):`);
    if (data.backlinks.length === 0) {
      lines.push("(none)");
    } else {
      for (const group of data.backlinks) {
        lines.push(`- [[${group.page}]]`);
        for (const block of group.blocks) {
          lines.push(`  - ⟨${block.uuid}⟩ ${snippet(block.content, [data.page], 120)}`);
        }
      }
    }
  }

  return lines.join("\n");
}

function formatDate(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

export function renderPageList(data: PageListResult): string {
  const shown = data.pages.length;
  const offsetPart = data.offset > 0 ? ` (offset ${data.offset})` : "";
  const lines: string[] = [`${shown} of ${data.total} pages${offsetPart}`];

  for (const page of data.pages) {
    let line = `- ${page.name}`;
    if (page.updatedAt !== undefined) line += ` · updated ${formatDate(page.updatedAt)}`;
    if (page.journal) line += ` · journal`;
    if (page.tags && page.tags.length > 0) line += ` · tags: ${page.tags.join(", ")}`;
    lines.push(line);
  }

  if (data.offset + shown < data.total) {
    lines.push(`Next page: offset ${data.offset + shown}`);
  }

  return lines.join("\n");
}
