import type { ChangeEvent } from 'react'
import { API, pct, tone } from './api'
import type { Snapshot } from './api'
import { IndexCard } from './Overview'
import { Panel, Soon, Stat } from './ui'
import type { PortfolioApi } from './usePortfolio'

export function InsightsPage({ symbols, rows, analysis, history, onOpenStock }: { symbols: string[]; rows: number; analysis: Snapshot | null; history: Snapshot[]; onOpenStock: (s: string) => void }) {
  const recs = analysis?.recommendations ?? []
  const trends = recs.reduce<Record<string, number>>((m, r) => { m[r.trend] = (m[r.trend] || 0) + 1; return m }, {})
  const avg = (f: (r: (typeof recs)[number]) => number) => recs.length ? recs.reduce((a, r) => a + f(r), 0) / recs.length : undefined
  return <div className="stack-y">
    <div className="grid-3">
      <IndexCard label="NIFTY 50" aliases={['NIFTY50', 'NIFTY']} symbols={symbols} />
      <IndexCard label="SENSEX" aliases={['SENSEX', 'BSESN']} symbols={symbols} />
      <div className="index-card"><div><b>Data coverage</b></div><div className="insight-list"><span><b>{rows.toLocaleString('en-IN')}</b> price records</span><span><b>{symbols.length.toLocaleString('en-IN')}</b> symbols</span><span><b>{history.length}</b> saved analysis runs</span></div></div>
    </div>
    <Panel title="What the latest screening found" subtitle={analysis ? `Run ${analysis.runId.slice(0, 8)} · ${new Date(analysis.createdAt).toLocaleString('en-IN')}` : 'No analysis run yet'}>
      {recs.length ? <>
        <div className="stat-grid three">
          <Stat label="Candidates" value={String(recs.length)} sub="Passed the initial filters" />
          <Stat label="Average 20-day return" value={pct(avg(r => r.return20d))} tone={tone(avg(r => r.return20d))} />
          <Stat label="Average research score" value={`${avg(r => r.researchScore)?.toFixed(0)}/100`} />
        </div>
        <div className="chips big">{Object.entries(trends).map(([t, n]) => <em key={t}>{t} · {n}</em>)}</div>
        <div className="mini-list">{recs.map(r => <button key={r.symbol} className="mini-row" onClick={() => onOpenStock(r.symbol)}><div><b>{r.symbol}</b><small>{r.setup || r.trend}</small></div><span className="muted">{r.reason}</span><div className="right"><span className={tone(r.return20d)}>{pct(r.return20d)}</span><small>20D</small></div></button>)}</div>
      </> : <p className="muted">{analysis?.note || 'Run a market analysis from the Overview to see insights here.'}</p>}
    </Panel>
    <div className="grid-2">
      <Soon title="News and catalysts">No news or announcements are integrated yet.</Soon>
      <Soon title="Sector and breadth">Sector performance and market breadth need an index and classification dataset.</Soon>
      <Soon title="Institutional flows">FII and DII activity is not available from the backend.</Soon>
      <Soon title="Macro and scenarios">Benchmark and macroeconomic datasets are not connected.</Soon>
    </div>
  </div>
}

export function SettingsPage({ health, busy, portfolio, onImport, onRefresh }: { health: string; busy: boolean; portfolio: PortfolioApi; onImport: (e: ChangeEvent<HTMLInputElement>) => void; onRefresh: () => void }) {
  const ok = health === 'Market data connected'
  return <div className="stack-y">
    <Panel title="Backend connection" actions={<button className="btn ghost small" onClick={onRefresh}>Check again</button>}>
      <div className="kv"><span>Status</span><b className={ok ? 'pos' : 'neg'}>{health}</b></div>
      <div className="kv"><span>API address</span><code>{API}</code></div>
      <p className="fineprint">The API address is set in the source and is not editable here.</p>
    </Panel>
    <Panel title="Market data" subtitle="NSE price files (.zip or .csv)">
      <label className="btn ghost">{busy ? 'Importing…' : '＋ Import / update NSE data'}<input type="file" accept=".zip,.csv" disabled={busy} onChange={onImport} hidden /></label>
    </Panel>
    <Panel title="Data stored in this browser" subtitle="Portfolio, watchlist and recorded history never leave this device.">
      <div className="kv"><span>Holdings</span><b>{portfolio.holdings.length}</b></div>
      <div className="kv"><span>Daily value snapshots</span><b>{portfolio.snapshots.length}</b></div>
      <div className="kv"><span>Activity entries</span><b>{portfolio.activity.length}</b></div>
      <div className="btn-row">
        <button className="btn danger" disabled={!portfolio.holdings.length} onClick={() => { if (window.confirm('Remove all holdings from this browser?')) portfolio.clearHoldings() }}>Clear holdings</button>
        <button className="btn danger" onClick={() => { if (window.confirm('Delete recorded snapshots and activity?')) portfolio.clearRecorded() }}>Clear recorded history</button>
      </div>
    </Panel>
    <p className="fineprint">MineVest is research support only. It does not trade and does not guarantee returns.</p>
  </div>
}
