

let mermaidApi: typeof import('mermaid').default | null = null
let initializedTheme: 'dark' | 'default' | null = null


export async function renderMermaidSvg(
  id: string,
  text: string,
  theme: 'dark' | 'default',
): Promise<string> {
  if (!mermaidApi || initializedTheme !== theme) {
    const mod = await import('mermaid')
    mermaidApi = mod.default
    mermaidApi.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme,
    })
    initializedTheme = theme
  }
  const { svg } = await mermaidApi.render(id, text)
  return svg
}
