import { useRouter } from 'vue-router'


export function useGoBack(fallbackPath: string): () => void {
  const router = useRouter()
  return function goBack(): void {
    if (window.history.length > 1) {
      router.back()
    } else {
      void router.push(fallbackPath)
    }
  }
}
