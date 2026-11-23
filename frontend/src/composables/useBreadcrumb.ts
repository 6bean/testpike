import { ref } from 'vue'




export interface BreadcrumbItem {
  
  key: string
  title: string
  
  path: string | null
}


const titles = ref<Record<string, string>>({})


export function setBreadcrumbTitle(routeName: string, title: string | null): void {
  if (title) {
    titles.value[routeName] = title
  } else {
    delete titles.value[routeName]
  }
}


export function useBreadcrumbTitles() {
  return titles
}
