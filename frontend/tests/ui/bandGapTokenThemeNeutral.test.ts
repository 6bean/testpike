// Contract: the vertical band-gap token is a theme-neutral layout value.
// The 2026-11-11 dialog capture proved the token once lived inside the
// html.dark block, so every consumer that reads var(--tp-band-gap) without a
// fallback collapsed to a 0px gap in the light palette. Keep the declaration
// in :root and out of any theme-class scope, for every theme-driven token of
// the layout family.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const themePath = resolve(__dirname, "../../src/styles/theme.scss");
const theme = readFileSync(themePath, "utf8");

function topBlocks(source: string): { selector: string; body: string }[] {
  const blocks: { selector: string; body: string }[] = [];
  const re = /([^\r\n]+)\s*\{/g;
  let m = re.exec(source);
  while (m) {
    const selector = m[1].trim();
    if (!selector || selector.startsWith("/*") || selector.startsWith("*")) {
      m = re.exec(source);
      continue;
    }
    let depth = 1;
    let i = re.lastIndex;
    while (i < source.length && depth > 0) {
      if (source[i] === "{") depth += 1;
      else if (source[i] === "}") depth -= 1;
      i += 1;
    }
    blocks.push({ selector, body: source.slice(re.lastIndex, i - 1) });
    re.lastIndex = i;
    m = re.exec(source);
  }
  return blocks;
}

describe("band-gap token is theme-neutral", () => {
  it("declares --tp-band-gap in :root and never inside a theme-class scope", () => {
    const blocks = topBlocks(theme);
    const root = blocks.find((b) => b.selector === ":root");
    expect(root, "a :root block exists in theme.scss").toBeTruthy();
    expect(root!.body).toMatch(/--tp-band-gap:\s*16px/);
    const dark = blocks.filter((b) =>
      /(^|[\s,{])html\.dark/.test(b.selector));
    for (const block of dark) {
      expect(block.body, `--tp-band-gap must not return to ${block.selector}`).not.toMatch(
        /--tp-band-gap:/
      );
    }
  });

  it("dialog body rhythm consumes the shared token (no page-local gap magic numbers)", () => {
    const dialog = readFileSync(
      resolve(__dirname, "../../src/components/FileUploadDialog.vue"),
      "utf8"
    );
    expect(dialog).toMatch(/gap:\s*var\(--tp-band-gap\)/);
  });
});
