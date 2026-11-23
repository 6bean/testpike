import { reactive, readonly } from 'vue'


const state = reactive({ visible: false, domain: '', anchor: '' })


export const helpDrawerState = readonly(state)

export function openHelp(domain = '', anchor = ''): void {
  state.domain = domain
  state.anchor = anchor
  state.visible = true
}

export function closeHelp(): void {
  state.visible = false
}
