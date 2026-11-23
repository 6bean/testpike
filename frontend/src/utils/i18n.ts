import { ref, type Ref } from 'vue'
import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'
import epZhCn from 'element-plus/es/locale/lang/zh-cn'
import epEn from 'element-plus/es/locale/lang/en'


export const SUPPORTED_LOCALES = ['zh-CN', 'en-US'] as const
export type TpLocale = (typeof SUPPORTED_LOCALES)[number]

const STORAGE_KEY = ['tp', 'locale'].join('-')


let storeOverride: Storage | undefined
export function setLocaleStorage(store: Storage | undefined): void {
  storeOverride = store
}
function storageRef(): Storage | undefined {
  if (storeOverride) return storeOverride
  try {
    return globalThis.localStorage
  } catch {
    return undefined
  }
}

function readStoredLocale(): TpLocale {
  try {
    const v = storageRef()?.getItem(STORAGE_KEY)
    if (v && (SUPPORTED_LOCALES as readonly string[]).includes(v)) return v as TpLocale
  } catch {
    
  }
  return 'zh-CN'
}

const initialLocale = readStoredLocale()


export const i18n = createI18n({
  legacy: false,
  locale: initialLocale,
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})


export const elLocale: Ref<typeof epZhCn> = ref(
  initialLocale === 'en-US' ? (epEn as typeof epZhCn) : epZhCn,
)


export function getLocale(): TpLocale {
  return i18n.global.locale.value as unknown as TpLocale
}


function applyDocumentLang(locale: TpLocale): void {
  try {
    const html = globalThis.document?.documentElement
    if (html) html.lang = locale === 'en-US' ? 'en' : locale
  } catch {
    
  }
}
applyDocumentLang(initialLocale)


export function setLocale(locale: TpLocale): boolean {
  if (!(SUPPORTED_LOCALES as readonly string[]).includes(locale)) return false
  i18n.global.locale.value = locale as never
  try {
    storageRef()?.setItem(STORAGE_KEY, locale)
  } catch {
    
  }
  elLocale.value = locale === 'en-US' ? (epEn as typeof epZhCn) : epZhCn
  applyDocumentLang(locale)
  return true
}


export function hydrateLocale(): void {
  const stored = readStoredLocale()
  if (stored !== getLocale()) setLocale(stored)
  else applyDocumentLang(stored)
}


export function tErrorCode(code: string | number): string {
  const key = `errorCodes.${code}`
  const hit = i18n.global.t(key)
  return hit === key ? i18n.global.t('errorCodes.unknown') : hit
}

export default i18n
