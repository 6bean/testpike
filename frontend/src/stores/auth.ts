import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { clearToken, getRefreshToken, getToken, setToken } from '@/api/request'
import * as authApi from '@/api/auth'
import * as rbacApi from '@/api/rbac'
import type { LoginUser, ProjectBrief, UserProgramView } from '@/api/auth'


export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getToken())
  const user = ref<LoginUser | null>(null)
  const projects = ref<ProjectBrief[]>([])
  /** Program lens snapshot from /users/me : empty array = the user
 * has no programs, so every program-aware control stays invisible (zero-touch). */
  const programs = ref<UserProgramView[]>([])
 
  const perms = ref<string[]>([])
 
  const passwordExpired = ref(false)

  const isLoggedIn = computed(() => !!token.value && !!user.value)
  const isAdmin = computed(() => user.value?.roles?.some((r) => r.toUpperCase() === 'ADMIN') ?? false)
  const displayName = computed(() => user.value?.nickName || user.value?.username || '未登录')

  
  function hasPerm(code: string): boolean {
    return isAdmin.value || perms.value.includes(code)
  }

  async function login(username: string, password: string): Promise<void> {
    const result = await authApi.login(username, password)
    setToken(result.accessToken, result.tokenType, result.refreshToken ?? null)
    token.value = result.accessToken
    user.value = result.user
    await loadMe()
  }

  async function loadMe(): Promise<void> {
    const me = await authApi.getMe()
    user.value = me.user
    projects.value = me.projects || []
    programs.value = me.programs || []
    passwordExpired.value = me.passwordExpired === true
    await loadPerms()
  }

 
  async function loadPerms(): Promise<void> {
    try {
      perms.value = await rbacApi.myPermissions()
    } catch {
      perms.value = []
    }
  }

  
  async function updateProfile(payload: { displayName?: string; avatar?: string }): Promise<void> {
    const result = await authApi.updateMyProfile(payload)
    setToken(result.accessToken, result.tokenType)
    token.value = result.accessToken
    user.value = result.user
  }

  
  async function logout(): Promise<void> {
    await authApi.logout(getRefreshToken())
    clearToken()
    token.value = ''
    user.value = null
    projects.value = []
    programs.value = []
    perms.value = []
    passwordExpired.value = false
  }

  return { token, user, projects, programs, perms, passwordExpired, isLoggedIn, isAdmin, displayName, hasPerm, login, loadMe, loadPerms, updateProfile, logout }
})
