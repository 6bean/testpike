import type { Directive } from 'vue'
import { useAuthStore } from '@/stores/auth'


export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const auth = useAuthStore()
    const required = binding.value
    const codes = Array.isArray(required) ? required : [required]
    if (!codes.some((c) => auth.hasPerm(c))) {
      el.parentNode?.removeChild(el)
    }
  },
}
