/**
 * Requirement-page door contracts for the two remaining fixes.
 *
 * Case-number detail door: inside the "link cases" dialog a case number
 * must open the case detail without leaving the dialog. The detail page is a
 * 3300-line routed view; a second read-only detail implementation inside the
 * dialog would drift from it (single-source rule), so the shared door wraps
 * the real page component in an embedded posture instead.
 *
 * Favourite folded into the more-menu: the action column shows at most two
 * direct buttons plus a "more" dropdown (FB band rules). The star button
 * rendered next to RowActions is a third direct control outside that band;
 * the favorite action must live inside the dropdown. Toggling itself must
 * stay one implementation: FavoriteButton and the list-page dropdown row
 * both consume the same useFavoriteToggle composable - the API call in two
 * files would be the duplication this rule exists to prevent.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')

function read(rel: string): string {
  return readFileSync(join(SRC, rel), 'utf8')
}

describe('linked-case detail door', () => {
  const detail = read('views/case/CaseDetailView.vue')
  const door = read('components/CaseDetailDialog.vue')
  const list = read('views/requirement/RequirementListView.vue')

  it('detail page supports an embedded posture driven by a case-id prop', () => {
    expect(door, 'shared door component must exist and mount the real detail page')
      .toMatch(/import CaseDetailView from '@\/views\/case\/CaseDetailView\.vue'/)
    expect(detail).toMatch(/defineProps/)
    expect(detail).toMatch(/embedded/)
    expect(detail, 'embedded mode must hide the back button (the dialog chrome closes it)')
      .toMatch(/v-if="!embedded"[^>]*data-test="detail-back"|v-if="!embedded"[\s\S]{0,120}data-test="detail-back"/)
    expect(detail, 'case id resolves from the prop when embedded, route param otherwise')
      .toMatch(/props\.caseId[\s\S]{0,80}route\.params\.id/)
  })

  it('door dialog keeps the shared centered posture', () => {
    const start = door.indexOf('<el-dialog')
    expect(start).toBeGreaterThan(-1)
    const tag = door.slice(start, door.indexOf('>', start))
    expect(tag).toMatch(/\balign-center\b(?![-\w])/)
    expect(tag, 'chunk-id style live binding: the dialog must destroy on close so the key remount re-reads')
      .toMatch(/destroy-on-close/)
  })

  it('both case-number faces in the link dialog open the door (linked rows + candidate table)', () => {
    expect(list).toMatch(/<CaseDetailDialog/)
    expect(list, 'the linked-case row number must be clickable through the door')
      .toMatch(/data-test="req-linked-case-detail"/)
    expect(list, 'the candidate table number column must be clickable through the door')
      .toMatch(/data-test="req-candidate-case-detail"/)
  })
})

describe('favorite folded into the row more-menu', () => {
  const list = read('views/requirement/RequirementListView.vue')
  const star = read('components/FavoriteButton.vue')
  const toggle = read('composables/useFavoriteToggle.ts')

  it('action column renders no standalone star button any more', () => {
    const start = list.indexOf('<el-table-column :label="t(\'common.action\')"')
    expect(start, 'action column must exist').toBeGreaterThan(-1)
    const col = list.slice(start, start + 1600)
    expect(col, 'FB band: star button is a third direct control, fold it into RowActions')
      .not.toMatch(/<FavoriteButton/)
  })

  it('favorite is a row command resolved through reqRowActions/onReqRowCmd', () => {
    expect(list).toMatch(/key:\s*['"]fav['"]/)
    expect(list).toMatch(/key === ['"]fav['"]/)
  })

  it('toggle logic lives once: composable owns the api calls, star button consumes it', () => {
    expect(toggle).toMatch(/addFavorite\(/)
    expect(toggle).toMatch(/removeFavorite\(/)
    expect(star, 'FavoriteButton must import the shared toggle instead of calling the api directly')
      .toMatch(/import \{ useFavoriteToggle \} from '@\/composables\/useFavoriteToggle'/)
    expect(star, 'after extraction the button itself must not call the favorite api')
      .not.toMatch(/\bawait (addFavorite|removeFavorite)\(/)
    expect(list, 'the requirement page must consume the same single-source toggle')
      .toMatch(/useFavoriteToggle/)
  })
})
