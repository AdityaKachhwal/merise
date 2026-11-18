import { dateLabel, sliceRange } from './api'
import type { Holding, Price } from './api'

export type Pt = { date: string; value: number }

// Values the CURRENT quantities at imported historical closes.
// Purchase dates are not tracked, so this is a replay, not true historical portfolio value.
export function buildReplay(holdings: Holding[], series: Record<string, Price[]>) {
  const used = holdings.filter(h => series[h.symbol]?.length)
  const excluded = holdings.filter(h => !series[h.symbol]?.length).map(h => h.symbol)
  const invested = used.reduce((a, h) => a + h.quantity * h.averagePrice, 0)
  if (!used.length) return { points: [] as Pt[], used, excluded, invested }
  const start = used.reduce((m, h) => { const d = series[h.symbol][0].tradingDate; return d > m ? d : m }, '')
  const dates = Array.from(new Set(used.flatMap(h => series[h.symbol].map(p => p.tradingDate).filter(d => d >= start)))).sort()
  const idx = used.map(() => 0)
  const last = used.map(h => series[h.symbol][0].close)
  const points = dates.map(date => {
    let value = 0
    used.forEach((h, i) => {
      const s = series[h.symbol]
      while (idx[i] < s.length && s[idx[i]].tradingDate <= date) { last[i] = s[idx[i]].close; idx[i]++ }
      value += h.quantity * last[i]
    })
    return { date, value }
  })
  return { points, used, excluded, invested }
}

export function contributions(holdings: Holding[], series: Record<string, Price[]>, range: string) {
  return holdings.flatMap(h => {
    const s = series[h.symbol]
    if (!s || s.length < 2) return []
    const w = sliceRange(s.map(p => ({ date: p.tradingDate, close: p.close })), range)
    if (w.length < 2) return []
    return [{ symbol: h.symbol, value: h.quantity * (w[w.length - 1].close - w[0].close) }]
  }).sort((a, b) => Math.abs(b.value) - Math.abs(a.value))
}

export type PeriodMode = 'Daily' | 'Monthly' | 'Yearly'
export type PeriodRow = { label: string; value: number; change?: number; changePct?: number }

export function periodic(points: Pt[], mode: PeriodMode, limit = 12): PeriodRow[] {
  if (!points.length) return []
  const len = mode === 'Monthly' ? 7 : 4
  let picked: Pt[]
  if (mode === 'Daily') picked = points.slice(-(limit + 1))
  else {
    const m = new Map<string, Pt>()
    points.forEach(p => m.set(p.date.slice(0, len), p))
    picked = [...m.values()].slice(-(limit + 1))
  }
  const label = (p: Pt) => mode === 'Daily' ? dateLabel(p.date)
    : mode === 'Monthly' ? new Date(`${p.date}T00:00:00`).toLocaleDateString('en-IN', { month: 'short', year: 'numeric' })
    : p.date.slice(0, 4)
  return picked.map((p, i) => {
    const prev = picked[i - 1]
    const change = prev ? p.value - prev.value : undefined
    return { label: label(p), value: p.value, change, changePct: prev && prev.value ? (change! / prev.value) * 100 : undefined }
  }).reverse()
}
