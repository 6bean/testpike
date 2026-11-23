
import { journeyGroups } from './menuStructure'
import { menuSynonyms, synonymTabs } from './menuSynonyms'

export interface MenuSearchEntry {
  path: string
  
  titleKey: string
  /** Breadcrumb key chain for one entry = [journey group titleKey] (two-level cap), joined at render time */
  groupTitleKeys: string[]
  
  perm?: string
  
  synonyms: string[]
  /** Host tab to open when the match came from a retired-leaf synonym word
 * ( R24 zero-loss: the regression-suite word must land on
 * /cases?tab=suites, not the default tab). Absent for title hits and
 * default-tab words. */
  tab?: string
}

/** Breadcrumb key chain = [journey group] (two-level cap; the sub-group branch is removed). */
export function buildMenuSearchEntries(): MenuSearchEntry[] {
  const out: MenuSearchEntry[] = []
  for (const grp of journeyGroups) {
    for (const leaf of grp.items ?? []) {
      out.push(toEntry(leaf, [grp.titleKey]))
    }
  }
  return out
}

function toEntry(leaf: { path: string; titleKey: string; perm?: string }, groupTitleKeys: string[]): MenuSearchEntry {
  return {
    path: leaf.path,
    titleKey: leaf.titleKey,
    groupTitleKeys,
    perm: leaf.perm,
    synonyms: menuSynonyms[leaf.path] ?? [],
  }
}

/** Resolve the host tab for a synonym match .
 * Title hits and default-tab words return undefined — the host renders its
 * own default tab then. */
function tabForWord(path: string, word: string): string | undefined {
  return synonymTabs[path]?.[word.toLowerCase()]
}


export function searchMenu(
  keyword: string,
  translate: (key: string) => string,
  hasPerm: (code: string) => boolean,
  limit = 8,
): MenuSearchEntry[] {
  const kw = keyword.trim().toLowerCase()
  if (!kw) return []
  const scored: { entry: MenuSearchEntry; rank: number }[] = []
  for (const entry of buildMenuSearchEntries()) {
    if (entry.perm && !hasPerm(entry.perm)) continue
    const title = translate(entry.titleKey).toLowerCase()
    if (title.includes(kw)) {
      scored.push({ entry, rank: title.startsWith(kw) ? 0 : 1 })
      continue
    }
    const matched = entry.synonyms.filter(
      (s) => s.toLowerCase().includes(kw) || kw.includes(s.toLowerCase()),
    )
    if (matched.length) {
      // An explicit retired-leaf word wins over an incidental substring match
      // (the skill-market word must open the market tab even though the
      // plain skill word also matched).
      const tab = matched.map((w) => tabForWord(entry.path, w)).find((v) => v !== undefined)
      scored.push({ entry: tab ? { ...entry, tab } : entry, rank: 2 })
    }
  }
  return scored
    .sort((a, b) => a.rank - b.rank)
    .slice(0, limit)
    .map((s) => s.entry)
}
