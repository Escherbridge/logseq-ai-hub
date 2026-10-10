import { describe, test, expect } from "bun:test";
import {
  renderOutline,
  renderPage,
  renderBlock,
  snippet,
  renderSearch,
  renderLinks,
  renderPageList,
  type Node,
} from "../src/services/mcp/graph-render";

function node(uuid: string, content: string, children: Node[] = [], pre?: true): Node {
  return pre ? { uuid, content, pre, children } : { uuid, content, children };
}

// ──────────────────────────────────────────────────────────────────────────────
// renderOutline
// ──────────────────────────────────────────────────────────────────────────────

describe("renderOutline", () => {
  test("nests children under parents by 2-space indent per depth", () => {
    const blocks = [node("a", "Parent", [node("b", "Child")])];
    expect(renderOutline(blocks)).toBe("- Parent  ⟨a⟩\n  - Child  ⟨b⟩");
  });

  test("indents continuation lines of multi-line content to align under the text, without a uuid marker", () => {
    const blocks = [node("x", "Line one\nLine two")];
    expect(renderOutline(blocks)).toBe("- Line one  ⟨x⟩\n  Line two");
  });

  test("strips id:: and collapsed:: lines but keeps other key:: value lines", () => {
    const blocks = [node("u", "id:: 123\ncollapsed:: true\ntype:: note\nBody text")];
    expect(renderOutline(blocks)).toBe("- type:: note  ⟨u⟩\n  Body text");
  });

  test("renders a pre:true page-properties block unindented, bulletless, and without a uuid marker", () => {
    const blocks = [node("p1", "title:: My Page", [], true), node("b1", "Body")];
    expect(renderOutline(blocks)).toBe("title:: My Page\n- Body  ⟨b1⟩");
  });

  test("skips a pre:true block entirely when nothing survives stripping", () => {
    const blocks = [node("p1", "id:: xyz", [], true), node("b1", "Body")];
    expect(renderOutline(blocks)).toBe("- Body  ⟨b1⟩");
  });

  test("renders empty content as '- (empty)'", () => {
    const blocks = [node("e1", "")];
    expect(renderOutline(blocks)).toBe("- (empty)  ⟨e1⟩");
  });

  test("renders a block whose content is only noise properties as '- (empty)'", () => {
    const blocks = [node("e2", "id:: abc\ncollapsed:: true")];
    expect(renderOutline(blocks)).toBe("- (empty)  ⟨e2⟩");
  });

  test("omits the uuid marker entirely when showUuids is false", () => {
    const blocks = [node("u", "Content")];
    expect(renderOutline(blocks, { showUuids: false })).toBe("- Content");
  });

  test("truncates after maxBlocks and appends a footer with the correct remaining count", () => {
    const blocks = Array.from({ length: 5 }, (_, i) => node(`b${i}`, `B${i}`));
    const result = renderOutline(blocks, { maxBlocks: 3 });
    expect(result).toBe(
      "- B0  ⟨b0⟩\n- B1  ⟨b1⟩\n- B2  ⟨b2⟩\n" +
        "… 2 more blocks not shown — raise max_blocks, or read a subtree with block_get.",
    );
  });

  test("does not append a footer when block count is within maxBlocks", () => {
    const blocks = [node("a", "A"), node("b", "B")];
    const result = renderOutline(blocks, { maxBlocks: 10 });
    expect(result).not.toContain("more blocks not shown");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// renderPage
// ──────────────────────────────────────────────────────────────────────────────

describe("renderPage", () => {
  test("renders '(empty page)' when there are no blocks", () => {
    const data = { page: { name: "Empty", uuid: "u", journal: false }, blocks: [] };
    expect(renderPage(data)).toBe("# Empty\n(empty page)");
  });

  test("renders a header followed by the outline, including a pre-block", () => {
    const data = {
      page: { name: "Home", uuid: "u", journal: false },
      blocks: [node("p1", "type:: page", [], true), node("b1", "Hello")],
    };
    expect(renderPage(data)).toBe("# Home\ntype:: page\n- Hello  ⟨b1⟩");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// renderBlock
// ──────────────────────────────────────────────────────────────────────────────

describe("renderBlock", () => {
  test("renders a parent marker when parentUuid is present, and the block as outline root with its children", () => {
    const data = {
      uuid: "b1",
      page: "Home",
      parentUuid: "p0",
      content: "Child content",
      children: [node("c1", "Grandchild")],
    };
    expect(renderBlock(data)).toBe(
      "Block ⟨b1⟩ on [[Home]] · parent ⟨p0⟩\n" +
        "- Child content  ⟨b1⟩\n" +
        "  - Grandchild  ⟨c1⟩",
    );
  });

  test("omits the parent marker when parentUuid is null", () => {
    const data = { uuid: "b1", page: "Home", parentUuid: null, content: "Root block", children: [] };
    expect(renderBlock(data)).toBe("Block ⟨b1⟩ on [[Home]]\n- Root block  ⟨b1⟩");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// snippet
// ──────────────────────────────────────────────────────────────────────────────

describe("snippet", () => {
  test("returns the whole (whitespace-collapsed) string when shorter than width", () => {
    expect(snippet("short   text", ["text"], 160)).toBe("short text");
  });

  test("windows the content centred on the first match, with ellipses on both cut sides", () => {
    const content = "0123456789NEEDLE0123456789";
    expect(snippet(content, ["needle"], 10)).toBe("…56789NEEDL…");
  });

  test("collapses embedded newlines to a single line before windowing", () => {
    expect(snippet("line one\nline two", ["two"], 160)).toBe("line one line two");
  });

  test("falls back to the start of the string when no term matches", () => {
    const content = "a".repeat(200);
    const result = snippet(content, ["zzz"], 20);
    expect(result.startsWith("a")).toBe(true);
    expect(result.endsWith("…")).toBe(true);
    expect(result.startsWith("…")).toBe(false);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// renderSearch
// ──────────────────────────────────────────────────────────────────────────────

describe("renderSearch", () => {
  test("renders 'No matches' when there are no pages and no blocks", () => {
    const data = { query: "q", pages: [], pagesTotal: 0, blocks: [], total: 0 };
    expect(renderSearch(data)).toBe('No matches for "q".');
  });

  test("renders matching pages and a snippet line per block", () => {
    const data = {
      query: "foo bar",
      pages: ["PageA"],
      pagesTotal: 1,
      blocks: [{ uuid: "u1", page: "PageA", content: "foo bar baz" }],
      total: 1,
    };
    expect(renderSearch(data)).toBe(
      'Pages matching "foo bar" (1): PageA\nBlocks: showing 1 of 1\n- [[PageA]] ⟨u1⟩ foo bar baz',
    );
  });

  test("omits the pages line when no pages matched, but still reports blocks", () => {
    const data = { query: "q", pages: [], pagesTotal: 0, blocks: [{ uuid: "u1", page: "P", content: "q here" }], total: 1 };
    const result = renderSearch(data);
    expect(result).not.toContain("Pages matching");
    expect(result).toContain("Blocks: showing 1 of 1");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// renderLinks
// ──────────────────────────────────────────────────────────────────────────────

describe("renderLinks", () => {
  test("says '(none)' inline for an empty outgoing list, and renders grouped backlinks", () => {
    const data = {
      page: "Home",
      outgoing: [] as string[],
      backlinks: [{ page: "Other", blocks: [{ uuid: "bu1", content: "mentions Home here" }] }],
      backlinksTotal: 1,
    };
    expect(renderLinks(data)).toBe(
      "Outgoing links from [[Home]] (0): (none)\nBacklinks to [[Home]] (1):\n- [[Other]]\n  - ⟨bu1⟩ mentions Home here",
    );
  });

  test("renders only the outgoing section when direction is 'out' (backlinks undefined)", () => {
    const data = { page: "Home", outgoing: ["A", "B"] };
    expect(renderLinks(data)).toBe("Outgoing links from [[Home]] (2): A, B");
  });

  test("says '(none)' on its own line for an empty backlinks section when direction is 'in'", () => {
    const data = { page: "Home", backlinks: [] as any[], backlinksTotal: 0 };
    expect(renderLinks(data)).toBe("Backlinks to [[Home]] (0):\n(none)");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// renderPageList
// ──────────────────────────────────────────────────────────────────────────────

describe("renderPageList", () => {
  test("renders an empty list with no footer", () => {
    expect(renderPageList({ total: 0, offset: 0, pages: [] })).toBe("0 of 0 pages");
  });

  test("appends a 'Next page' footer with the correct next offset when more pages remain", () => {
    const data = { total: 5, offset: 0, pages: [{ name: "A" }, { name: "B" }] };
    expect(renderPageList(data)).toBe("2 of 5 pages\n- A\n- B\nNext page: offset 2");
  });

  test("omits the footer when the current page is the last one", () => {
    const data = { total: 2, offset: 0, pages: [{ name: "A" }, { name: "B" }] };
    expect(renderPageList(data)).not.toContain("Next page");
  });

  test("shows the offset in the header and combines updated/journal/tags per page", () => {
    const data = {
      total: 10,
      offset: 5,
      pages: [{ name: "Journal1", updatedAt: Date.UTC(2024, 0, 15), journal: true as const, tags: ["x", "y"] }],
    };
    expect(renderPageList(data)).toBe(
      "1 of 10 pages (offset 5)\n- Journal1 · updated 2024-01-15 · journal · tags: x, y\nNext page: offset 6",
    );
  });
});
