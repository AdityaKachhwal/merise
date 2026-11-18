import { useId, useState } from 'react'
import { compactMoney, dateLabel } from './api'
import type { Pt } from './analytics'

export const COLORS = { green: '#22c58b', red: '#f0566a', blue: '#5b8cff', amber: '#f5b04c', muted: '#808ca2' }
export const PALETTE = ['#5b8cff', '#22c58b', '#f5b04c', '#b58bff', '#f0566a', '#4fd1e6', '#e58bd0', '#9aa7bd']
export const trendColor = (pts: Pt[]) => pts.length > 1 && pts[pts.length - 1].value < pts[0].value ? COLORS.red : COLORS.green

export type ChartLine = { name: string; color: string; points: Pt[]; dashed?: boolean }

export function LineChart({ lines, height = 260, format = compactMoney }: { lines: ChartLine[]; height?: number; format?: (v: number) => string }) {
  const gid = useId().replace(/:/g, '')
  const [hover, setHover] = useState<number | null>(null)
  const base = lines[0]?.points ?? []
  const n = base.length
  if (n < 2) return <div className="chart-empty" style={{ height }}>Not enough data points to draw a chart yet.</div>

  let lo = Infinity, hi = -Infinity
  lines.forEach(l => l.points.forEach(p => { if (p.value < lo) lo = p.value; if (p.value > hi) hi = p.value }))
  const pad = (hi - lo || Math.abs(hi) || 1) * 0.08
  const min = lo - pad, max = hi + pad
  const W = 1000, H = 100
  const x = (i: number) => (i / (n - 1)) * W
  const y = (v: number) => H - ((v - min) / (max - min)) * H
  const path = (pts: Pt[]) => pts.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.value).toFixed(1)}`).join(' ')
  const move = (clientX: number, el: HTMLDivElement) => {
    const r = el.getBoundingClientRect()
    setHover(Math.round(Math.min(1, Math.max(0, (clientX - r.left) / r.width)) * (n - 1)))
  }
  const hx = hover === null ? 0 : (hover / (n - 1)) * 100
  const c0 = lines[0].color

  return <div className="linechart-wrap">
    <div className="linechart" style={{ height }}
      onMouseMove={e => move(e.clientX, e.currentTarget)} onMouseLeave={() => setHover(null)}
      onTouchMove={e => move(e.touches[0].clientX, e.currentTarget)} onTouchEnd={() => setHover(null)}>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
        <defs><linearGradient id={gid} x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor={c0} stopOpacity=".26" /><stop offset="1" stopColor={c0} stopOpacity="0" /></linearGradient></defs>
        {[0.25, 0.5, 0.75].map(t => <line key={t} x1="0" x2={W} y1={H * t} y2={H * t} className="grid-line" vectorEffect="non-scaling-stroke" />)}
        <path d={`${path(base)} L${W},${H} L0,${H} Z`} fill={`url(#${gid})`} />
        {lines.map(l => <path key={l.name} d={path(l.points)} fill="none" stroke={l.color} strokeWidth={l.dashed ? 1.4 : 2.2} strokeDasharray={l.dashed ? '5 4' : undefined} strokeLinejoin="round" vectorEffect="non-scaling-stroke" />)}
        {hover !== null && <line x1={x(hover)} x2={x(hover)} y1="0" y2={H} className="cursor-line" vectorEffect="non-scaling-stroke" />}
      </svg>
      <span className="y-label top">{format(max)}</span>
      <span className="y-label bottom">{format(min)}</span>
      {hover !== null && <>
        {lines.map(l => l.points[hover] && <i key={l.name} className="dot" style={{ left: `${hx}%`, top: `${(y(l.points[hover].value) / H) * 100}%`, background: l.color }} />)}
        <div className="tooltip" style={{ left: `${hx}%`, transform: `translateX(${hx < 18 ? '0' : hx > 82 ? '-100%' : '-50%'})` }}>
          <b>{dateLabel(base[hover].date)}</b>
          {lines.map(l => l.points[hover] && <span key={l.name}><i style={{ background: l.color }} />{l.name}<em>{format(l.points[hover].value)}</em></span>)}
        </div>
      </>}
    </div>
    <div className="x-labels"><span>{dateLabel(base[0].date)}</span><span>{dateLabel(base[Math.floor((n - 1) / 2)].date)}</span><span>{dateLabel(base[n - 1].date)}</span></div>
  </div>
}

export function Sparkline({ values, color }: { values: number[]; color?: string }) {
  if (values.length < 2) return <span className="spark-empty">—</span>
  const lo = Math.min(...values), hi = Math.max(...values), r = hi - lo || 1
  const pts = values.map((v, i) => `${(i / (values.length - 1)) * 100},${28 - ((v - lo) / r) * 26}`).join(' ')
  return <svg className="spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true"><polyline points={pts} fill="none" stroke={color ?? (values[values.length - 1] >= values[0] ? COLORS.green : COLORS.red)} strokeWidth="1.8" vectorEffect="non-scaling-stroke" strokeLinejoin="round" /></svg>
}

export function BarDiverge({ items, format }: { items: { label: string; value: number }[]; format: (v: number) => string }) {
  const max = Math.max(...items.map(i => Math.abs(i.value)), 1)
  return <div className="bars">{items.map(i => {
    const w = (Math.abs(i.value) / max) * 50
    return <div className="bar-row" key={i.label}>
      <span className="bar-label">{i.label}</span>
      <span className="bar-track"><i className={i.value >= 0 ? 'pos' : 'neg'} style={{ left: i.value >= 0 ? '50%' : `${50 - w}%`, width: `${w}%` }} /></span>
      <span className={`bar-value ${i.value >= 0 ? 'pos' : 'neg'}`}>{format(i.value)}</span>
    </div>
  })}</div>
}

export function StackBar({ items }: { items: { label: string; value: number }[] }) {
  const total = items.reduce((a, i) => a + i.value, 0) || 1
  return <div>
    <div className="stack">{items.map((i, k) => <i key={i.label} title={`${i.label} ${((i.value / total) * 100).toFixed(1)}%`} style={{ width: `${(i.value / total) * 100}%`, background: PALETTE[k % PALETTE.length] }} />)}</div>
    <div className="legend">{items.slice(0, 8).map((i, k) => <span key={i.label}><i style={{ background: PALETTE[k % PALETTE.length] }} />{i.label} <em>{((i.value / total) * 100).toFixed(1)}%</em></span>)}</div>
  </div>
}
