/**
 * Single-source project-id to display-name lookup shared by journey pages.
 * JourneyPlanDetailView and JourneyRunTimelineView both render a projectId
 * column and fall back to a formatted label for ids outside the roster, so
 * the mapping (fetch + index + fallback) lives here once instead of twice.
 * The roster is capped by the management page size contract (200) — the same
 * window both pages were using before extraction.
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getProjects } from '@/api/project'

export function useProjectNameLookup() {
  const { t } = useI18n()
  const projectById = ref<Record<number, string>>({})

  /** Load the visible project roster once per page mount. */
  async function loadProjectNames(): Promise<void> {
    const projects = await getProjects({ page: 1, size: 200 })
    const map: Record<number, string> = {}
    for (const p of projects.list) map[p.id] = p.name
    projectById.value = map
  }

  /** Display name for a project id; unknown or not-yet-loaded ids render the shared fallback label. */
  function projectName(id: number): string {
    return projectById.value[id] ?? t('journeys.projectFormat', { id })
  }

  return { projectById, loadProjectNames, projectName }
}
