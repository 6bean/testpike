<template>
  <div class="page-root dashboard-root">
    <div class="page-card welcome-card">
      <div class="welcome-text">
        <div class="welcome-title">{{ t('dashboard.welcome', { name: authStore.displayName }) }}</div>
        <div class="welcome-sub text-secondary">{{ t('common.appFullName') }}</div>
      </div>
      <div class="welcome-actions">
        <el-button type="primary" data-test="quick-create-project" @click="goCreate">
          <el-icon><Plus /></el-icon>{{ t('dashboard.createProject') }}
        </el-button>
      </div>
    </div>

 
    <JourneyCardsRow />

 
    <OnboardingOverlay />

 
    <el-row :gutter="16" class="stat-row" data-test="dashboard-todo-cards">
      <el-col :xs="24" :sm="8">
        <div class="page-card stat-card clickable" data-test="todo-review-card" @click="goTodo('/review')">
          <el-icon :size="30" color="var(--tp-warning)"><EditPen /></el-icon>
          <div>
            <div class="stat-num" :class="{ 'stat-zero': (overview?.pendingReviewCount ?? 1) === 0 }">{{ overview?.pendingReviewCount ?? '-' }}</div>
            <div class="stat-label">{{ t('dashboard.pendingReview') }}</div>
 
            <div v-if="overview && overview.overdueReviewCount > 0" class="overdue-badge" data-test="review-overdue-badge">
              {{ t('dashboard.overdueBadge', { n: overview.overdueReviewCount }) }}
            </div>
            <div v-if="overview && overview.unassignedReviewCount > 0" class="unassigned-hint" data-test="review-unassigned-hint">
              {{ t('dashboard.unassignedHint', { n: overview.unassignedReviewCount }) }}
            </div>
            <div v-if="overview" class="stat-hint">{{ overview.pendingReviewCount > 0 ? t('dashboard.hintReviewGo') : t('dashboard.hintReviewIdle') }}</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="8">
        <div class="page-card stat-card clickable" data-test="todo-task-card" @click="goTodo('/execution')">
          <el-icon :size="30" color="#38bdf8"><Loading v-if="running" /><Promotion v-else /></el-icon>
          <div>
            <div class="stat-num" :class="{ 'stat-zero': (overview?.runningTaskCount ?? 1) === 0 }">{{ overview?.runningTaskCount ?? '-' }}</div>
            <div class="stat-label">{{ t('dashboard.runningTask') }}</div>
            <div v-if="overview" class="stat-hint">{{ overview.runningTaskCount > 0 ? t('dashboard.hintTaskGo') : t('dashboard.hintTaskIdle') }}</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :sm="8">
        <div class="page-card stat-card clickable" data-test="todo-defect-card" @click="goTodo('/defects')">
          <el-icon :size="30" color="var(--tp-danger)"><Warning /></el-icon>
          <div>
            <div class="stat-num" :class="{ 'stat-zero': (overview?.unclosedDefectCount ?? 1) === 0, 'stat-danger': (overview?.unclosedDefectCount ?? 0) > 0 }">{{ overview?.unclosedDefectCount ?? '-' }}</div>
            <div class="stat-label">{{ t('dashboard.unclosedDefect') }}</div>
            <div v-if="overview" class="stat-hint">{{ overview.unclosedDefectCount > 0 ? t('dashboard.hintDefectGo') : t('dashboard.hintDefectIdle') }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

 
    <FavoriteSection />

    <el-row :gutter="16" class="bottom-row">
      
      <el-col :xs="24" :sm="14">
        <div class="page-card section-card">
          <div class="section-title">{{ t('dashboard.myProjects') }}</div>
          <DataTablePage :data="overview?.projects ?? []" data-test="my-project-table" :loading="!overview" :empty-hint="t('dashboard.noProjectShort')">
            <el-table-column prop="name" :label="t('common.name')" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="project-name" @click="router.push(`/projects/${row.id}`)">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('dashboard.caseCount')" width="88" align="right">
              <template #default="{ row }">
                <span class="case-count" data-test="project-case-count">{{ row.caseCount }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="code" :label="t('project.code')" width="118" show-overflow-tooltip />
            <el-table-column :label="t('common.action')" width="96" align="right">
              <template #default="{ row }">
                <el-button link type="primary" class="enter-link" @click="router.push(`/projects/${row.id}`)">{{ t('common.enterProject') }}</el-button>
              </template>
            </el-table-column>
          </DataTablePage>
          <el-empty v-if="overview && overview.projects.length === 0" :description="t('dashboard.noProject')" :image-size="90" />
        </div>
      </el-col>
      
      <el-col :xs="24" :sm="10">
        <div class="page-card section-card activity-card">
          <div class="section-title">{{ t('dashboard.activities') }}</div>
          <div v-if="!overview || overview.activities.length === 0" class="activity-empty text-secondary">
            {{ t('dashboard.noActivity') }}
          </div>
          <div
            v-for="act in overview?.activities ?? []"
            :key="act.id"
            class="activity-item"
            data-test="activity-item"
            @click="goActivity(act)"
          >
            <el-tag :type="activityTag(act.eventType)" size="small">{{ activityLabel(act.eventType) }}</el-tag>
            <span class="activity-title">{{ act.title }}</span>
            <span class="activity-time text-secondary" :title="act.createdAt">{{ timeAgo(act.createdAt) }}</span>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Plus, EditPen, Promotion, Loading, Warning } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { getDashboardOverview, type ActivityItem, type DashboardOverview } from '@/api/dashboard'
import { eventLabel, eventTagType, notificationTarget } from '@/api/notification'
import { timeAgo } from '@/utils/timeAgo'
import FavoriteSection from '@/components/FavoriteSection.vue'
import JourneyCardsRow from '@/components/JourneyCardsRow.vue'
import OnboardingOverlay from '@/components/OnboardingOverlay.vue'

const router = useRouter()
const { t } = useI18n()
const authStore = useAuthStore()

const overview = ref<DashboardOverview | null>(null)
const running = computed(() => (overview.value?.runningTaskCount ?? 0) > 0)
let timer: ReturnType<typeof setInterval> | null = null

async function load(): Promise<void> {
  try {
    overview.value = await getDashboardOverview()
  } catch {
    if (!overview.value) overview.value = null
  }
}

function goCreate(): void {
  router.push('/projects?create=1')
}


function goTodo(path: string): void {
  router.push(path)
}


function goActivity(act: ActivityItem): void {
  const target = notificationTarget(act)
  if (target) router.push(target)
  else router.push('/notifications')
}

function activityLabel(eventType: string): string {
  return eventLabel(eventType)
}

function activityTag(eventType: string): 'success' | 'danger' | 'info' {
  return eventTagType(eventType)
}

onMounted(() => {
  void load()
  timer = setInterval(() => void load(), 60_000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped lang="scss">
.dashboard-root {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.welcome-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(120deg, var(--tp-primary-weak), transparent 60%);
}

.welcome-title {
  font-size: 20px;
  font-weight: 600;
}

.welcome-sub {
  margin-top: 6px;
}

.stat-row {
  .stat-card {
    display: flex;
    align-items: center;
    gap: 12px;
  }
}

.bottom-row {
  margin-top: 16px;
  clear: both;
}

.clickable {
  cursor: pointer;
  transition: transform 0.15s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 6px 18px rgb(0 0 0 / 18%);
  }
}

.stat-num {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.3;
}

.stat-label {
  font-size: 12px;
  color: var(--tp-text-secondary);
  margin-top: 4px;
}

.stat-hint {
  font-size: 11px;
  color: var(--tp-text-secondary);
  margin-top: 2px;
  opacity: 0.85;
}


.overdue-badge {
  display: inline-block;
  margin-top: 4px;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: var(--tp-danger, #ef4444);
}

.unassigned-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--tp-warning, #f59e0b);
}

.stat-zero {
  color: var(--tp-success);
}

.stat-danger {
  color: var(--tp-danger);
}

.section-card {
  .section-title {
    font-size: 16px;
    font-weight: 600;
    margin-bottom: 12px;
  }
}

.activity-card {
  min-height: 180px;
}

.activity-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
}

.activity-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--tp-border, rgb(128 128 128 / 15%));
  cursor: pointer;

  &:last-child {
    border-bottom: none;
  }

  &:hover .activity-title {
    color: var(--tp-primary, #38bdf8);
  }
}

.activity-title {
  flex: 1;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-time {
  font-size: 12px;
  flex-shrink: 0;
}

.project-name {
  font-weight: 500;
  cursor: pointer;

  &:hover {
    color: var(--tp-primary);
    text-decoration: underline;
  }
}

.case-count {
  font-weight: 600;
  color: var(--tp-primary);
  font-variant-numeric: tabular-nums;
}
</style>
