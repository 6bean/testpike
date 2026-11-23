
import 'element-plus/dist/index.css'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  listComments: vi.fn(),
  createComment: vi.fn(),
  unreadCounts: vi.fn(),
  markRead: vi.fn(),
  uploadAttachment: vi.fn(),
  listAttachments: vi.fn(),
  downloadAttachment: vi.fn(),
}))
vi.mock('@/api/comment', () => api)

const usersApi = vi.hoisted(() => ({ getUsers: vi.fn() }))
vi.mock('@/api/project', () => usersApi)

import CommentPanel from '@/components/CommentPanel.vue'

function comment(over: Record<string, unknown> = {}) {
  return {
    id: 1, hostType: 'case', hostId: 100, source: 1, authorId: 7,
    authorName: 'tester', content: 'hello comment', refKey: null,
    createdAt: '2026-09-21 12:00:00', ...over,
  }
}

async function factory(props: Record<string, unknown> = {}, permOpts: { commentUse?: boolean } = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { id: 7, username: 'tester', roles: ['TESTER'] } as never
  auth.perms = permOpts.commentUse === false ? [] : ['perm:comment:use']
  api.listComments.mockReset().mockResolvedValue([comment()])
  api.createComment.mockReset().mockResolvedValue({ id: 42 })
  api.unreadCounts.mockReset().mockResolvedValue([])
  api.markRead.mockReset().mockResolvedValue(undefined)
  api.uploadAttachment.mockReset().mockResolvedValue({ id: 9, commentId: 42 })
  api.listAttachments.mockReset().mockResolvedValue([])
  api.downloadAttachment.mockReset().mockResolvedValue({ key: 'k1', url: 'http://x/y' })
  usersApi.getUsers.mockReset().mockResolvedValue({ list: [{ id: 7, username: 'tester' }], total: 1 })
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const w = mount(CommentPanel, {
    props: { hostType: 'case', hostId: 100, ...props },
    global: { plugins: [ElementPlus, i18n, pinia] },
  })
  return { w, i18n }
}

describe('T240 权限闸（tick289 缺陷包：无 perm:comment:use 用户面板整体不渲染）', () => {
  beforeEach(() => vi.clearAllMocks())

  it('无 perm:comment:use：不渲染面板零请求（挂即 GET /comments 403 炸 E2E 4xx 闸=回归根因）', async () => {
    const { w } = await factory({}, { commentUse: false })
    expect(w.find('[data-test="comment-panel"]').exists()).toBe(false)
    expect(api.listComments).not.toHaveBeenCalled()
    expect(api.markRead).not.toHaveBeenCalled()
  })

  it('有 perm:comment:use：面板正常渲染并发列表请求（闸不误伤正向链路）', async () => {
    const { w } = await factory({}, { commentUse: true })
    await vi.waitFor(() => expect(api.listComments).toHaveBeenCalledWith('case', 100))
    expect(w.find('[data-test="comment-panel"]').exists()).toBe(true)
  })
})

describe('T240 CommentPanel 评论面板件', () => {
  beforeEach(() => vi.clearAllMocks())

  it('挂载即拉列表+标已读：listComments(case,100)+markRead(case,100)', async () => {
    const { w } = await factory()
    await vi.waitFor(() => expect(api.listComments).toHaveBeenCalledWith('case', 100))
    expect(api.markRead).toHaveBeenCalledWith('case', 100)
    expect(w.find('[data-test="comment-panel"]').exists()).toBe(true)
    expect(w.find('[data-test="comment-list"]').text()).toContain('hello comment')
  })

  it('hostId=null 不加载（宿主未选中静默态）', async () => {
    const { w } = await factory({ hostId: null })
    expect(api.listComments).not.toHaveBeenCalled()
    expect(w.find('[data-test="comment-empty"]').exists()).toBe(true)
  })

  it('正文空=提交禁用；输入后可发：createComment 载荷含 hostType/hostId/content', async () => {
    const { w } = await factory()
    const input = w.find('[data-test="comment-input"]')
    const submit = w.find('[data-test="comment-submit"]')
    expect((submit.element as HTMLButtonElement).disabled).toBe(true)
    await input.setValue('新的评论内容')
    expect((submit.element as HTMLButtonElement).disabled).toBe(false)
    await submit.trigger('click')
    await vi.waitFor(() => expect(api.createComment).toHaveBeenCalled())
    const payload = api.createComment.mock.calls[0][0]
    expect(payload).toMatchObject({ hostType: 'case', hostId: 100, content: '新的评论内容' })
    await vi.waitFor(() => expect(api.listComments).toHaveBeenCalledTimes(2))
    expect((input.element as HTMLTextAreaElement).value).toBe('')
  })

  it('附件链路=先 create 拿 id 再逐件 uploadAttachment（无评论不孤传）', async () => {
    const { w } = await factory()
    await w.find('[data-test="comment-input"]').setValue('带图评论')
    const vm = w.vm as unknown as {
      attachPendingFiles: (f: File[]) => void
      submitWithAttachments: () => Promise<void>
    }
    vm.attachPendingFiles([new File(['abc'], 'shot.png', { type: 'image/png' })])
    await vm.submitWithAttachments()
    expect(api.createComment).toHaveBeenCalled()
    await vi.waitFor(() => expect(api.uploadAttachment).toHaveBeenCalledWith(42, expect.any(File)))
  })

  it('下载=grant{key,url} 消费（url 原样用，前端禁自拼）', async () => {
    const { w } = await factory()
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    const vm = w.vm as unknown as { downloadFile: (id: number) => Promise<void> }
    await vm.downloadFile(9)
    expect(api.downloadAttachment).toHaveBeenCalledWith(9)
    expect(openSpy).toHaveBeenCalledWith('http://x/y', '_blank')
    openSpy.mockRestore()
  })

  it('api 镜像契约闸：comment.ts 七端点 URL/method 与 admin CommentController 对齐', async () => {
    const raw = import.meta.glob('../../src/api/comment.ts', { query: '?raw', import: 'default', eager: true })
    const src = Object.values(raw)[0] as string
    expect(src).toContain("'/comments'")
    expect(src).toContain("'/comments/unread-count'")
    expect(src).toContain("'/comments/mark-read'")
    expect(src).toContain('`/comments/${commentId}/attachments`')
    expect(src).toContain('`/comments/attachments/${attachmentId}/download`')
    expect(src).toContain('postMultipart')
    expect(src).toContain("method: 'post'")
    expect(src).toContain("method: 'get'")
  })

  it('i18n 双写闸：comment 顶级域 zh/en 在位（T238/T239 键同步纪律）', async () => {
    expect((zhCN as Record<string, unknown>).comment).toBeTruthy()
    expect((enUS as Record<string, unknown>).comment).toBeTruthy()
  })

  it('四宿主接线闸：review/case/batch/defect 源码必含 CommentPanel 消费', async () => {
    const raw = import.meta.glob('../../src/views/**/*.vue', { query: '?raw', import: 'default', eager: true })
    const need = [
      'src/views/review/ReviewWorkbenchView.vue',
      'src/views/case/CaseDetailView.vue',
      'src/views/batch/BatchDetailView.vue',
      'src/views/defect/DefectListView.vue',
    ]
    for (const n of need) {
      const hit = Object.entries(raw).find(([p]) => p.endsWith(n))
      expect(hit, `宿主缺评论面板接线: ${n}`).toBeTruthy()
      expect(hit![1], `宿主缺 CommentPanel 消费: ${n}`).toContain('CommentPanel')
    }
  })
})
