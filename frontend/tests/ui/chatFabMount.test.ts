/**
 * stage 1 — chat entry mounting contract (source-as-text, ?raw precedent
 * from menuEmbeddedT407b2). Pins the §thirteen guard-rails that are structural:
 * - MainLayout mounts ChatDock + ChatFab exactly once (single global mount, never per page);
 * - the bubble carries data-test=ai-chat-fab + role=button + aria-label (accessibility group X);
 * - dock-open hides the bubble (mutual exclusion from one source = useChatDock fabVisible);
 * - embedded frames honestly do not render the bubble (guard-rail c);
 * - position/width ride per-user localStorage, never a server key.
 */
import { describe, expect, it } from 'vitest'
import layoutRaw from '@/layouts/MainLayout.vue?raw'
import fabRaw from '@/components/ChatFab.vue?raw'
import dockRaw from '@/components/ChatDock.vue?raw'

describe('chat entry single mount point', () => {
  it('MainLayout mounts ChatFab and ChatDock exactly once each', () => {
    expect((layoutRaw.match(/<ChatFab\s*\/>/g) ?? []).length).toBe(1)
    expect((layoutRaw.match(/<ChatDock\s*\/>/g) ?? []).length).toBe(1)
    expect(layoutRaw).toContain("import ChatFab from '@/components/ChatFab.vue'")
    expect(layoutRaw).toContain("import ChatDock from '@/components/ChatDock.vue'")
  })

  it('no other view mounts a second bubble instance', () => {
    expect((fabRaw.match(/data-test="ai-chat-fab"/g) ?? []).length).toBe(1)
  })
})

describe('bubble accessibility and mutual exclusion', () => {
  it('bubble is a keyboard-reachable button with an aria label', () => {
    expect(fabRaw).toContain('role="button"')
    expect(fabRaw).toContain('aria-label')
    expect(fabRaw).toContain('@keydown.enter')
    expect(fabRaw).toContain('@keydown.space')
  })

  it('bubble hides while the dock is open and in embedded frames', () => {
    expect(fabRaw).toMatch(/v-if="fabVisible && !embedded"/)
    expect(fabRaw).toContain('window.self !== window.top')
  })
})

describe('preferences never touch the server', () => {
  it('bubble position rides localStorage keyed per user', () => {
    expect(fabRaw).toContain('localStorage')
    expect(fabRaw).toContain('authStore.user?.userId')
  })
  it('dock width rides localStorage keyed per user', () => {
    expect(dockRaw).toContain('tp_chat_dock_width')
    expect(dockRaw).toContain('authStore.user?.userId')
  })
})
