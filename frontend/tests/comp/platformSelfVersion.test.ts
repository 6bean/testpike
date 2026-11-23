
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  getLatestManifest: vi.fn(),
  listReleaseManifests: vi.fn(),
  getPlatformVersion: vi.fn(),
  // added a candidate pull inside load — stub it so the banner chain
  // is not blocked by an unmocked network call in browser mode.
  listReleaseCandidates: vi.fn(),
  getProjects: vi.fn(),
}))
vi.mock('@/api/cluster', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return {
    ...actual,
    getLatestManifest: api.getLatestManifest,
    listReleaseManifests: api.listReleaseManifests,
    getPlatformVersion: api.getPlatformVersion,
    listReleaseCandidates: api.listReleaseCandidates,
  }
})
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, getProjects: api.getProjects }
})

import UpgradeCenterView from '@/views/cluster/UpgradeCenterView.vue'
import clusterApiRaw from '@/api/cluster?raw'
import viewRaw from '@/views/cluster/UpgradeCenterView.vue?raw'


function platformInfo(over: Record<string, unknown> = {}) {
  return {
    runningVersion: '1.4.2',
    buildTime: '2026-10-12T10:30:00',
    latestVersion: '1.4.3',
    verdict: 'BEHIND',
    ...over,
  }
}

async function factory() {
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const pinia = createPinia()
  setActivePinia(pinia)
  const w = mount(UpgradeCenterView, { global: { plugins: [ElementPlus, i18n, pinia] } })
  await flushPromises()
  return w
}

beforeEach(() => {
  api.getLatestManifest.mockReset().mockResolvedValue(null)
  api.listReleaseManifests.mockReset().mockResolvedValue([])
  api.getPlatformVersion.mockReset().mockResolvedValue(platformInfo())
  api.listReleaseCandidates.mockReset().mockResolvedValue([])
  api.getProjects.mockReset().mockResolvedValue({ list: [], total: 0 })
})

describe('平台自知版本横幅（T349）', () => {
  it('api 镜像闸：PlatformVersionInfo 四字段+platform-version 口路径对齐 admin 桥', () => {
    expect(clusterApiRaw).toMatch(/runningVersion:\s*string\s*\|\s*null/)
    expect(clusterApiRaw).toMatch(/buildTime:\s*string\s*\|\s*null/)
    expect(clusterApiRaw).toMatch(/latestVersion:\s*string\s*\|\s*null/)
    expect(clusterApiRaw).toMatch(/verdict:\s*string/)
    expect(clusterApiRaw).toContain('/cluster/release-manifests/platform-version')
  })

  it('消费位源码闸：横幅消费构建期常量 __APP_VERSION__（与 dist/version.json 同源）', () => {
    expect(viewRaw).toContain('__APP_VERSION__')
    expect(viewRaw).toContain('__APP_BUILD_TIME__')
    expect(viewRaw).toContain('upgrade.selfFrontendVersion')
  })

  it('i18n 镜像闸：selfVersion 键族+五判词 zh-CN/en-US 双写全命中', () => {
    const keys = [
      'selfVersionTitle', 'selfVersionLatest', 'selfVersionBuilt',
      'selfVersionUnknown', 'selfFrontendVersion',
    ]
    for (const locale of [zhCN, enUS] as const) {
      const up = (locale as Record<string, any>).upgrade
      for (const k of keys) expect(up[k], `upgrade.${k}`).toBeTruthy()
      for (const v of ['UP_TO_DATE', 'BEHIND', 'RUNNING_AHEAD_UNREGISTERED', 'NO_RELEASE', 'UNKNOWN_BUILD']) {
        expect(up.selfVerdict[v], `upgrade.selfVerdict.${v}`).toBeTruthy()
      }
    }
  })

  it('态①接口正常：横幅渲运行版本+判词+最新版本+构建时间+前端版本消费位', async () => {
    const w = await factory()
    const banner = w.find('[data-test="upgrade-self-version-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.find('[data-test="upgrade-self-running"]').text()).toBe('1.4.2')
    expect(banner.find('[data-test="upgrade-self-verdict"]').text()).toContain('落后于最新发布')
    expect(banner.find('[data-test="upgrade-self-latest"]').text()).toContain('1.4.3')
    expect(banner.find('[data-test="upgrade-self-build-time"]').text()).toContain('2026-10-12 10:30:00')
    expect(banner.find('[data-test="upgrade-self-frontend-version"]').text()).toContain('界面构建版本')
  })

  it('态①补充：UNKNOWN_BUILD=诚实缺省不炸页（运行版本空值直显缺省文案）', async () => {
    api.getPlatformVersion.mockResolvedValue(platformInfo({ runningVersion: null, buildTime: null, latestVersion: null, verdict: 'UNKNOWN_BUILD' }))
    const w = await factory()
    const banner = w.find('[data-test="upgrade-self-version-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.find('[data-test="upgrade-self-running"]').text()).toContain('未知')
    expect(banner.find('[data-test="upgrade-self-build-time"]').exists()).toBe(false)
    expect(w.find('[data-test="upgrade-center-page"]').exists()).toBe(true)
  })

  it('态②接口 reject：横幅整体缺席+页面不炸（清单区独立呈现互不连坐）', async () => {
    api.getPlatformVersion.mockRejectedValue(new Error('boom'))
    const w = await factory()
    expect(w.find('[data-test="upgrade-self-version-banner"]').exists()).toBe(false)
    expect(w.find('[data-test="upgrade-empty-state"]').exists()).toBe(true)
    expect(w.find('[data-test="upgrade-center-page"]').exists()).toBe(true)
  })
})
