import { computed } from 'vue'
import { getLocale, setLocale, SUPPORTED_LOCALES, type TpLocale } from '../utils/i18n'


export default function useLocale() {
  const locale = computed<TpLocale>(() => getLocale())
  const supportedLocales = SUPPORTED_LOCALES

  function switchTo(next: TpLocale): boolean {
    return setLocale(next)
  }

  return { locale, supportedLocales, switchTo }
}
