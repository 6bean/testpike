
export function bizErrorMsg(e: unknown, fallback: string): string {
  if (e instanceof Error && e.message) {
    return e.message
  }
  const anyE = e as { response?: { data?: { msg?: string; message?: string } } }
  return anyE?.response?.data?.msg || anyE?.response?.data?.message || fallback
}
