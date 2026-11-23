<template>
 
  <div class="markdown-preview" data-test="markdown-preview" v-html="rendered" />
</template>

<script setup lang="ts">

import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ source: string | null | undefined }>()
const { t } = useI18n()

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}


function renderInline(escaped: string): string {
  let s = escaped
  s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
  s = s.replace(/\[([^\]]+)\]\((?!(?:https?:|mailto:))[^\s)]*\)/g, '$1')
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  s = s.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
  s = s.replace(/`([^`]+)`/g, '<code>$1</code>')
  return s
}

function renderBlock(escaped: string): string {
  const out: string[] = []
  const lines = escaped.split(/\r?\n/)
  let i = 0
  let para: string[] = []
  const flushPara = () => {
    if (para.length) {
      out.push(`<p>${para.map(renderInline).join('<br />')}</p>`)
      para = []
    }
  }
  while (i < lines.length) {
    const line = lines[i]
    const fence = /^\s*```/.exec(line)
    if (fence) {
      flushPara()
      const code: string[] = []
      i += 1
      while (i < lines.length && !/^\s*```/.test(lines[i])) {
        code.push(lines[i])
        i += 1
      }
      i += 1
      out.push(`<pre class="md-code"><code>${code.join('\n')}</code></pre>`)
      continue
    }
    const heading = /^(#{1,6})\s+(.*)$/.exec(line)
    if (heading) {
      flushPara()
      const level = heading[1].length
      out.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      i += 1
      continue
    }
    if (/^\s*(---+|\*\*\*+)\s*$/.test(line)) {
      flushPara()
      out.push('<hr />')
      i += 1
      continue
    }
    const ul = /^\s*[-*+]\s+(.*)$/.exec(line)
    const ol = /^\s*\d+[.、]\s+(.*)$/.exec(line)
    if (ul || ol) {
      flushPara()
      const ordered = Boolean(ol)
      const items: string[] = []
      while (i < lines.length) {
        const m = ordered ? /^\s*\d+[.、]\s+(.*)$/.exec(lines[i]) : /^\s*[-*+]\s+(.*)$/.exec(lines[i])
        if (!m) break
        items.push(`<li>${renderInline(m[1])}</li>`)
        i += 1
      }
      out.push(ordered ? `<ol>${items.join('')}</ol>` : `<ul>${items.join('')}</ul>`)
      continue
    }
    // GFM pipe tables : report-style answers are
    // table-heavy and the lexer used to show them as raw pipe rows. Requires
    // a header row plus a --- separator row; escape-first is preserved, so
    // every emitted tag is generated here (zero v-html injection surface).
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|?\s*$/.test(lines[i + 1]) && lines[i + 1].includes('-')) {
      flushPara()
      const splitRow = (row: string) => row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
      const cells = (row: string) => splitRow(row).map((c) => renderInline(c))
      // GFM alignment markers (`:---` left, `---:` right, `:---:` center) in
      // the separator row carry the author's per-column intent; dropping them
      // left the numeric columns of report answers ragged against each other.
      const aligns = splitRow(lines[i + 1]).map((spec) => {
        const left = spec.startsWith(':')
        const right = spec.endsWith(':')
        if (left && right) return 'center'
        if (right) return 'right'
        return ''
      })
      const alignCls = (idx: number) => (aligns[idx] ? ` class="md-cell--${aligns[idx]}"` : '')
      const head = cells(line)
      i += 2
      const bodyRows: string[] = []
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
        bodyRows.push(`<tr>${cells(lines[i]).map((c, k) => `<td${alignCls(k)}>${c}</td>`).join('')}</tr>`)
        i += 1
      }
      out.push(`<div class="md-table-wrap"><table class="md-table"><thead><tr>${head.map((c, k) => `<th${alignCls(k)}>${c}</th>`).join('')}</tr></thead><tbody>${bodyRows.join('')}</tbody></table></div>`)
      continue
    }
    if (!line.trim()) {
      flushPara()
      i += 1
      continue
    }
    para.push(line)
    i += 1
  }
  flushPara()
  return out.join('\n')
}

const rendered = computed(() => {
  const src = props.source ?? ''
  if (!src.trim()) return `<p class="md-empty">${t('components.mdEmpty')}</p>`
  return renderBlock(escapeHtml(src))
})
</script>

<style scoped>
.markdown-preview {
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  /* Report bodies are number-heavy; tabular figures keep columns aligned
     inside tables and inline stats (vision re-score finding). */
  font-variant-numeric: tabular-nums;
}
.markdown-preview :deep(h1),
.markdown-preview :deep(h2),
.markdown-preview :deep(h3),
.markdown-preview :deep(h4),
.markdown-preview :deep(h5),
.markdown-preview :deep(h6) {
  margin: 16px 0 8px;
  font-weight: 600;
}
.markdown-preview :deep(h1) { font-size: 20px; }
.markdown-preview :deep(h2) { font-size: 16px; }
.markdown-preview :deep(h3) { font-size: 15px; }
.markdown-preview :deep(p) { margin: 6px 0; }
.markdown-preview :deep(ul),
.markdown-preview :deep(ol) {
  margin: 6px 0;
  padding-left: 22px;
}
.markdown-preview :deep(code) {
  padding: 2px 4px;
  border-radius: 4px;
  background: var(--tp-fill-color-light, rgba(127, 127, 127, 0.1));
  font-family: var(--el-font-family-mono, monospace);
  font-size: 13px;
}
.markdown-preview :deep(pre.md-code) {
  padding: 10px 12px;
  border: 1px solid var(--tp-border-color);
  border-radius: 6px;
  background: var(--tp-fill-color-light, rgba(127, 127, 127, 0.06));
  overflow: auto;
  white-space: pre-wrap;
}
.markdown-preview :deep(pre.md-code code) {
  padding: 0;
  background: transparent;
}
.markdown-preview :deep(hr) {
  border: none;
  border-top: 1px solid var(--tp-border-color);
  margin: 12px 0;
}
/* Pipe tables render as real grids : bordered header row and
 per-cell padding; wide tables scroll inside the preview instead of
 overflowing the bubble. */
.markdown-preview {
  overflow-x: auto;
}
/* Worst-scale containment: a failure list can run to dozens of rows, so the
   grid scrolls inside its own area with the header row pinned instead of
   stretching the answer bubble. */
.markdown-preview :deep(.md-table-wrap) {
  overflow: auto;
  max-height: 360px;
  margin: 6px 0;
}
.markdown-preview :deep(table.md-table) {
  /* separate (not collapse) keeps the sticky header's borders painted while
     body rows scroll underneath it */
  border-collapse: separate;
  border-spacing: 0;
  border-top: 1px solid var(--tp-border-color);
  border-left: 1px solid var(--tp-border-color);
  font-size: 12px;
}
.markdown-preview :deep(table.md-table th),
.markdown-preview :deep(table.md-table td) {
  border-right: 1px solid var(--tp-border-color);
  border-bottom: 1px solid var(--tp-border-color);
  padding: 4px 8px;
  text-align: left;
}
.markdown-preview :deep(.md-table .md-cell--right) {
  text-align: right;
}
.markdown-preview :deep(.md-table .md-cell--center) {
  text-align: center;
}
.markdown-preview :deep(table.md-table th) {
  /* Solid bubble-coloured base + the usual translucent fill on top: a
     transparent header lets scrolling rows show through the pinned row. */
  background-color: var(--tp-bg-card);
  background-image: linear-gradient(var(--tp-fill-color, rgba(128, 128, 128, 0.08)), var(--tp-fill-color, rgba(128, 128, 128, 0.08)));
  font-weight: 600;
}
.markdown-preview :deep(table.md-table thead th) {
  position: sticky;
  top: 0;
  z-index: 1;
}
.markdown-preview :deep(a) {
  color: var(--el-color-primary);
}
.markdown-preview :deep(.md-empty) {
  color: var(--tp-text-secondary);
}
</style>
