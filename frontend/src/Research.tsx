import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { STOCK_RANGES, compactMoney, dateLabel, fetchPrices, money, pct, returnSince, shiftDate, sliceRange, tone } from './api'
import type { Holding, Price, Review, Snapshot, StockRange } from './api'
import { LineChart, trendColor } from './charts'
import { ActionBadge, Empty, Panel, Segmented, Soon, Stat } from './ui'

function Card({ title, ai, children }: { title: string; ai?: boolean; children: ReactNode }) {
  return <div className="r-card"><h3>{title}{ai && <span className="badge">AI report</span>}</h3>{children}</div>
}
const List = ({ items }: { items: string[] }) => <ul className="bullets">{items.map((x, i) => <li key={i}>{x}</li>)}</ul>

export default function Research({ symbols, symbol, onSelect, analysis, review, holdings }: {
  symbols: string[]; symbol: string; onSelect: (s: string) => void; analysis: Snapshot | null; review: Review | null; holdings: Holding[]
}) {
  const [prices, setPrices] = useState<Price[]>([])
  const [loading, setLoading] = useState(false)
  const [range, setRange] = useState<StockRange>('1Y')
  const [query, setQuery] = useState('')

  useEffect(() => {
    let c = false
    if (!symbol) { setPrices([]); return }
    setLoading(true)
    void fetchPrices(symbol).then(p => { if (!c) { setPrices(p); setLoading(false) } })
    return () => { c = true }
  }, [symbol])

  const s = useMemo(() => {
    if (!prices.length) return null
    const latest = prices[prices.length - 1], prev = prices[prices.length - 2]
    const ath = prices.reduce((b, i) => i.high > b.high ? i : b, prices[0])
    const atl = prices.reduce((b, i) => i.low < b.low ? i : b, prices[0])
    const yr = prices.filter(p => p.tradingDate >= shiftDate(latest.tradingDate, -365))
    return {
      latest, ath, atl,
      day: prev && prev.close ? ((latest.close / prev.close) - 1) * 100 : undefined,
      hi52: yr.length ? Math.max(...yr.map(p => p.high)) : undefined,
      lo52: yr.length ? Math.min(...yr.map(p => p.low)) : undefined,
    }
  }, [prices])

  const monthly = useMemo(() => {
    const grouped = new Map<string, Price[]>()
    prices.forEach(item => { const k = item.tradingDate.slice(0, 7); grouped.set(k, [...(grouped.get(k) || []), item]) })
    const byMonth = Array.from({ length: 12 }, (_, i) => ({ month: new Date(2000, i, 1).toLocaleString('en', { month: 'short' }), values: [] as number[] }))
    const months = [...grouped.entries()].sort(([a], [b]) => a.localeCompare(b))
    for (let i = 1; i < months.length; i++) {
      const [key, current] = months[i]
      const [previousKey, previous] = months[i - 1]
      const pd = new Date(`${previousKey}-01T00:00:00`)
      pd.setMonth(pd.getMonth() + 1)
      const consecutive = `${pd.getFullYear()}-${String(pd.getMonth() + 1).padStart(2, '0')}` === key
      const prevClose = previous[previous.length - 1].close
      if (consecutive && prevClose > 0) byMonth[Number(key.slice(5, 7)) - 1].values.push(((current[current.length - 1].close / prevClose) - 1) * 100)
    }
    return byMonth.map(m => ({ ...m, average: m.values.length ? m.values.reduce((a, b) => a + b, 0) / m.values.length : undefined }))
  }, [prices])

  const pts = sliceRange(prices.map(p => ({ date: p.tradingDate, value: p.close })), range)
  const ai = review?.holdings?.find(h => h.symbol === symbol)
  const screen = analysis?.recommendations?.find(r => r.symbol === symbol)
  const held = holdings.find(h => h.symbol === symbol)
  const matches = query ? symbols.filter(x => x.startsWith(query.toUpperCase())).slice(0, 40) : []

  function pick(v: string) {
    setQuery(v)
    const u = v.trim().toUpperCase()
    if (symbols.includes(u)) { onSelect(u); setQuery('') }
  }

  if (!symbols.length) return <Panel><Empty icon="⌕" title="No market data imported">Import NSE data to research individual stocks.</Empty></Panel>

  return <div className="stack-y">
    <Panel>
      <div className="r-head">
        <div><h2 className="r-symbol">{symbol || 'Stock research'}</h2><p className="muted">NSE · imported daily market data{held && ` · You hold ${held.quantity} @ ${money(held.averagePrice)}`}</p></div>
        <div className="r-controls">
          <div><input list="research-symbols" placeholder="Search symbol…" value={query} onChange={e => pick(e.target.value)} aria-label="Search symbol" /><datalist id="research-symbols">{matches.map(m => <option key={m} value={m} />)}</datalist></div>
          <div className="r-price"><strong>{s ? money(s.latest.close) : '—'}</strong><span className={tone(s?.day)}>{pct(s?.day, 2)} <small>vs previous close · {s ? dateLabel(s.latest.tradingDate) : ''}</small></span></div>
        </div>
      </div>
    </Panel>

    <div className="stat-grid">
      <Stat label="52-week high" value={s?.hi52 ? money(s.hi52) : '—'} sub="Within imported data" />
      <Stat label="52-week low" value={s?.lo52 ? money(s.lo52) : '—'} sub="Within imported data" />
      <Stat label="High in imported data" value={s ? money(s.ath.high) : '—'} sub={s ? `${dateLabel(s.ath.tradingDate)} · ${pct(((s.latest.close / s.ath.high) - 1) * 100)} away` : 'No data'} />
      <Stat label="Low in imported data" value={s ? money(s.atl.low) : '—'} sub={s ? dateLabel(s.atl.tradingDate) : 'No data'} />
      <Stat label="Imported sessions" value={prices.length.toLocaleString('en-IN')} sub="Coverage may be incomplete" />
    </div>

    <div className="grid-2-1">
      <Panel title="Price history" subtitle="Daily closing prices from your imported NSE data" actions={<Segmented small options={STOCK_RANGES} value={range} onChange={setRange} />}>
        {loading ? <div className="chart-empty" style={{ height: 280 }}>Loading price history…</div> : pts.length > 1 ? <LineChart height={300} format={compactMoney} lines={[{ name: 'Close', color: trendColor(pts), points: pts }]} /> : <Empty title="No price history for this symbol yet" />}
      </Panel>
      <Panel title="Historical returns" subtitle="Shown only where imported history covers the period">
        <table className="table compact"><tbody>{([['1 month', 30], ['3 months', 91], ['6 months', 182], ['1 year', 365], ['3 years', 1095], ['5 years', 1826]] as const).map(([l, d]) => {
          const v = returnSince(prices, d)
          return <tr key={l}><td>{l}</td><td className={`r strong ${tone(v)}`}>{pct(v)}</td></tr>
        })}</tbody></table>
        <p className="fineprint">Price return only. Corporate-action adjustments are not yet applied.</p>
      </Panel>
    </div>

    <div className="grid-2">
      <Panel title="AI stock outlook" subtitle="From the latest AI stock assessment">
        {ai ? <div className="stack-y tight">
          <div className="verdict"><div><small>Outlook</small><b>{ai.stockOutlook || 'Unavailable'}</b></div><div><small>Assessment</small><ActionBadge action={ai.action} /></div><div><small>Confidence</small><b>{ai.confidence || '—'}</b></div></div>
          {ai.reasoning ? <p className="prose">{ai.reasoning}</p> : <p className="muted">No reasoning was returned for this stock.</p>}
        </div> : <Soon title="No AI assessment for this stock">Add {symbol || 'this stock'} to your portfolio and run the AI stock assessment to see the outlook, reasoning and HOLD / REVIEW / EXIT view here.</Soon>}
      </Panel>
      <Panel title="Monthly seasonality" subtitle="Average month-to-month closing change. Descriptive, not predictive.">
        <div className="months">{monthly.map(m => <div key={m.month} className={`month ${m.average === undefined ? '' : m.average >= 0 ? 'good' : 'bad'}`}><b>{m.month}</b><span className={tone(m.average)}>{pct(m.average)}</span><small>{m.values.length} samples</small></div>)}</div>
        <p className="fineprint">Small samples can be misleading.</p>
      </Panel>
    </div>

    <h3 className="section-title">Company research</h3>
    <div className="r-grid">
      <Card title="Company overview" ai={!!ai?.businessOverview}>{ai?.businessOverview ? <p>{ai.businessOverview}</p> : <Soon title="Business details">Source-backed company profiles are not integrated yet.</Soon>}</Card>
      <Card title="Business quality and competitive position" ai={!!ai?.businessQuality}>{ai?.businessQuality ? <p>{ai.businessQuality}</p> : <Soon title="Business quality">Requires fundamentals and competitor data.</Soon>}</Card>
      <Card title="Revenue and profit trends"><Soon title="Financial statements">No revenue or profit data is available from the backend.</Soon></Card>
      <Card title="Financial ratios"><Soon title="Ratios">ROE, ROCE, margins and debt ratios need fundamentals data.</Soon></Card>
      <Card title="Valuation" ai={!!ai?.valuationView}>{ai?.valuationView ? <p>{ai.valuationView}</p> : <Soon title="Valuation metrics">P/E, P/B and peer comparisons need fundamentals data.</Soon>}</Card>
      <Card title="Growth drivers" ai={!!ai?.growthDrivers?.length}>{ai?.growthDrivers?.length ? <List items={ai.growthDrivers} /> : <Soon title="Growth drivers">Appear after an AI assessment of this stock.</Soon>}</Card>
      <Card title="Business risks" ai={!!ai?.risks?.length}>{ai?.risks?.length ? <List items={ai.risks} /> : <Soon title="Business risks">Appear after an AI assessment of this stock.</Soon>}</Card>
      <Card title="Screening signals">{screen ? <div><p>Research score <b>{screen.researchScore}/100</b> · {screen.trend}{screen.setup ? ` · ${screen.setup}` : ''}</p><p className="muted">{screen.reason}</p></div> : <Soon title="Not in the latest screening">This stock was not among the latest market analysis candidates.</Soon>}</Card>
    </div>
    {ai?.dataGaps?.length ? <Panel title="Missing information"><List items={ai.dataGaps} /></Panel> : null}
    <p className="fineprint">Historical data is not a forecast or investment recommendation. Scenario analysis needs benchmark and macroeconomic datasets and is not available yet.</p>
  </div>
}
