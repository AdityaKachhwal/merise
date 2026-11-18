import { useEffect, useMemo, useState } from 'react'
import { RANGES, compactMoney, fetchPrices, money, pct, signedMoney, sliceRange, tone } from './api'
import type { Price, Range, Snapshot } from './api'
import { buildReplay } from './analytics'
import { LineChart, Sparkline, trendColor } from './charts'
import { RecommendationPanel } from './Recommendations'
import { Empty, Panel, Segmented, Stat } from './ui'
import type { PageId } from './ui'
import type { PortfolioApi } from './usePortfolio'

export function IndexCard({ label, aliases, symbols }: { label: string; aliases: string[]; symbols: string[] }) {
  const found = symbols.find(s => aliases.includes(s.toUpperCase().replace(/[^A-Z0-9]/g, '')))
  const [prices, setPrices] = useState<Price[]>([])
  useEffect(() => {
    let c = false
    if (found) void fetchPrices(found).then(p => { if (!c) setPrices(p) })
    return () => { c = true }
  }, [found])
  if (!found) return <div className="index-card"><div><b>{label}</b><span className="soon-tag">Not available yet</span></div><p className="muted">Index levels are not part of the imported NSE equity data, so none are shown.</p></div>
  const last = prices[prices.length - 1], prev = prices[prices.length - 2]
  const change = last && prev && prev.close ? ((last.close / prev.close) - 1) * 100 : undefined
  return <div className="index-card"><div><b>{label}</b><small>{found}</small></div>
    <div className="index-val"><strong>{last ? last.close.toLocaleString('en-IN', { maximumFractionDigits: 2 }) : '—'}</strong><span className={tone(change)}>{pct(change, 2)}</span></div>
    <Sparkline values={prices.slice(-60).map(p => p.close)} /></div>
}

export default function Overview({ portfolio, symbols, rows, analysis, busy, onRun, onOpenStock, go }: {
  portfolio: PortfolioApi; symbols: string[]; rows: number; analysis: Snapshot | null; busy: boolean; onRun: () => void; onOpenStock: (s: string) => void; go: (p: PageId) => void
}) {
  const { holdings, totals, rows: hrows, series } = portfolio
  const [range, setRange] = useState<Range>('1Y')
  const replay = useMemo(() => buildReplay(holdings, series), [holdings, series])
  const pts = sliceRange(replay.points, range)
  const delta = pts.length > 1 ? pts[pts.length - 1].value - pts[0].value : undefined
  const deltaPct = delta !== undefined && pts[0].value ? (delta / pts[0].value) * 100 : undefined
  const recs = analysis?.recommendations ?? []
  const avg = (f: (r: (typeof recs)[number]) => number) => recs.length ? recs.reduce((a, r) => a + f(r), 0) / recs.length : undefined
  const top = [...hrows].sort((a, b) => b.current - a.current).slice(0, 6)

  return <div className="stack-y">
    <div className="hero">
      <div><h2 className="hero-title">Today's opportunities</h2><p className="muted">{rows ? `${rows.toLocaleString('en-IN')} market price records · ${symbols.length.toLocaleString('en-IN')} symbols available` : 'Import NSE data to start screening the market.'}</p></div>
      <div className="hero-action"><button className="btn primary" disabled={busy || rows === 0} onClick={onRun}>▶ {busy ? 'Analysing market…' : 'Run Market Analysis'}</button><small className="muted">{rows === 0 ? 'Import NSE data to begin' : 'Price-history screening · up to 5 stocks'}</small></div>
    </div>

    {holdings.length === 0 ? <Panel><Empty icon="₹" title="Your portfolio is empty">Import a broker CSV or add a stock to see value, returns and performance here.<div><button className="btn primary" onClick={() => go('portfolio')}>Set up portfolio</button></div></Empty></Panel> : <>
      <div className="stat-grid">
        <Stat big label="Total portfolio value" value={money(totals.current)} sub={totals.pricedValue < totals.current ? 'Some holdings are valued at cost (no market price)' : `${holdings.length} holdings · all priced`} />
        <Stat label="Invested" value={money(totals.invested)} sub="At your average prices" />
        <Stat label="Overall P&L" value={signedMoney(totals.pnl)} tone={tone(totals.pnl)} sub={pct(totals.pnlPct, 2)} />
        <Stat label="Today's P&L" value={totals.todayCount ? signedMoney(totals.today) : '—'} tone={tone(totals.today)} sub={totals.todayCount ? `${pct(totals.todayPct, 2)} vs previous close · as of ${totals.asOf}` : 'Needs two imported sessions'} />
      </div>

      <div className="grid-2-1">
        <Panel title="Portfolio performance" subtitle="Current holdings valued at imported daily closes" actions={<Segmented small options={RANGES} value={range} onChange={setRange} />}>
          {pts.length > 1 && <div className="chart-head"><strong>{compactMoney(pts[pts.length - 1].value)}</strong><span className={tone(delta)}>{delta !== undefined && signedMoney(delta)} ({pct(deltaPct, 2)}) in {range}</span></div>}
          <LineChart height={280} lines={[
            { name: 'Holdings value', color: trendColor(pts), points: pts },
            { name: 'Invested', color: '#808ca2', dashed: true, points: pts.map(p => ({ date: p.date, value: replay.invested })) },
          ]} />
          <p className="fineprint">Purchase dates are not tracked, so this replays today's quantities against past closes. It is not your actual historical value.{replay.excluded.length > 0 && ` Excluded (no price data): ${replay.excluded.join(', ')}.`}</p>
        </Panel>
        <Panel title="Holdings" subtitle="Largest positions" actions={<button className="link" onClick={() => go('portfolio')}>View all</button>}>
          <div className="mini-list">{top.map(r => <button key={r.holding.id} className="mini-row" onClick={() => onOpenStock(r.holding.symbol)}>
            <div><b>{r.holding.symbol}</b><small>{r.weight.toFixed(1)}% of portfolio</small></div>
            <Sparkline values={(series[r.holding.symbol] ?? []).slice(-30).map(p => p.close)} />
            <div className="right"><span>{compactMoney(r.current)}</span><small className={tone(r.pnl)}>{pct(r.pnlPct, 1)}</small></div>
          </button>)}</div>
        </Panel>
      </div>
    </>}

    <div className="grid-3">
      <IndexCard label="NIFTY 50" aliases={['NIFTY50', 'NIFTY']} symbols={symbols} />
      <IndexCard label="SENSEX" aliases={['SENSEX', 'BSESN']} symbols={symbols} />
      <div className="index-card"><div><b>Market insights</b><small>From the latest analysis run</small></div>
        {recs.length ? <div className="insight-list">
          <span>{recs.length} candidate{recs.length === 1 ? '' : 's'} passed screening</span>
          <span>Avg 20D return <b className={tone(avg(r => r.return20d))}>{pct(avg(r => r.return20d))}</b></span>
          <span>Avg research score <b>{avg(r => r.researchScore)?.toFixed(0)}/100</b></span>
        </div> : <p className="muted">Run a market analysis to see screening insights.</p>}</div>
    </div>

    <RecommendationPanel analysis={analysis} title="Top stock recommendations" subtitle="Latest completed market analysis · up to 5 candidates" onOpenStock={onOpenStock} />
  </div>
}
