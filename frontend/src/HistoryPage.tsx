import { useMemo, useState } from 'react'
import { RANGES, compactMoney, money, pct, signedMoney, sliceRange, tone } from './api'
import type { Range, Snapshot } from './api'
import { buildReplay, contributions, periodic } from './analytics'
import type { PeriodMode } from './analytics'
import { BarDiverge, COLORS, LineChart, trendColor } from './charts'
import { RecommendationPanel } from './Recommendations'
import { Empty, Panel, Segmented, Soon, Stat } from './ui'
import type { PortfolioApi } from './usePortfolio'

const TABS = ['Performance', 'Transactions', 'Imports & updates', 'AI analysis'] as const
type Tab = typeof TABS[number]
const ACTIVITY_LABEL: Record<string, string> = { PORTFOLIO_IMPORT: 'Portfolio imported', HOLDING_ADDED: 'Holding added', HOLDING_REMOVED: 'Holding removed', PORTFOLIO_CLEARED: 'Portfolio cleared', AI_REVIEW: 'AI stock assessment', MARKET_DATA_IMPORT: 'Market data imported' }
const when = (iso: string) => new Date(iso).toLocaleString('en-IN', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })

function Performance({ portfolio }: { portfolio: PortfolioApi }) {
  const { holdings, series, snapshots } = portfolio
  const [range, setRange] = useState<Range>('1Y')
  const [mode, setMode] = useState<PeriodMode>('Monthly')
  const replay = useMemo(() => buildReplay(holdings, series), [holdings, series])
  const pts = sliceRange(replay.points, range)
  const contrib = useMemo(() => contributions(holdings, series, range), [holdings, series, range])
  const periods = useMemo(() => periodic(replay.points, mode), [replay.points, mode])
  const total = contrib.reduce((a, c) => a + c.value, 0)
  const snapVal = snapshots.map(s => ({ date: s.date, value: s.value }))
  const snapInv = snapshots.map(s => ({ date: s.date, value: s.invested }))
  const first = snapshots[0], last = snapshots[snapshots.length - 1]

  if (!holdings.length) return <Panel><Empty icon="₹" title="No portfolio to analyse">Import or add holdings in My Portfolio. Performance history is built from them.</Empty></Panel>

  return <div className="stack-y">
    <Panel title="Recorded portfolio value" subtitle="Daily snapshots MineVest saved in this browser when prices were matched">
      {snapshots.length >= 2 ? <>
        <div className="stat-grid three">
          <Stat label="Recording since" value={first.date} sub={`${snapshots.length} daily snapshots`} />
          <Stat label="Value change since then" value={signedMoney(last.value - first.value)} tone={tone(last.value - first.value)} sub={pct(first.value ? ((last.value - first.value) / first.value) * 100 : undefined, 2)} />
          <Stat label="Latest P&L" value={signedMoney(last.value - last.invested)} tone={tone(last.value - last.invested)} sub={pct(last.invested ? ((last.value - last.invested) / last.invested) * 100 : undefined, 2)} />
        </div>
        <LineChart height={240} lines={[{ name: 'Current value', color: COLORS.blue, points: snapVal }, { name: 'Invested capital', color: COLORS.muted, dashed: true, points: snapInv }]} />
        <div className="table-wrap short"><table className="table compact"><thead><tr><th>Date</th><th className="r">Invested</th><th className="r">Value</th><th className="r">P&L</th></tr></thead>
          <tbody>{[...snapshots].reverse().slice(0, 30).map(s => <tr key={s.date}><td>{s.date}</td><td className="r">{money(s.invested)}</td><td className="r">{money(s.value)}</td><td className={`r ${tone(s.value - s.invested)}`}>{signedMoney(s.value - s.invested)}</td></tr>)}</tbody></table></div>
      </> : <Empty icon="◔" title="Recording has just started">{snapshots.length === 1 ? `First snapshot saved on ${first.date}. ` : ''}MineVest saves one real value snapshot per day when you open it with matched prices. The chart appears once there are two days. Nothing is back-filled.</Empty>}
    </Panel>

    <Panel title="Holdings replay" subtitle="Today's quantities valued at imported daily closes" actions={<Segmented small options={RANGES} value={range} onChange={setRange} />}>
      <LineChart height={260} lines={[{ name: 'Holdings value', color: trendColor(pts), points: pts }, { name: 'Invested', color: COLORS.muted, dashed: true, points: pts.map(p => ({ date: p.date, value: replay.invested })) }]} />
      <p className="fineprint">Estimate only. Purchase dates are not stored, so this is not your actual historical value.{replay.excluded.length > 0 && ` Excluded (no price data): ${replay.excluded.join(', ')}.`}</p>
    </Panel>

    <div className="grid-2">
      <Panel title="Stock-wise contribution" subtitle={`Change in value of each holding over ${range}`}>
        {contrib.length ? <>
          <p className="chart-head"><strong className={tone(total)}>{signedMoney(total)}</strong><span className="muted">combined</span></p>
          <BarDiverge items={contrib.map(c => ({ label: c.symbol, value: c.value }))} format={v => `${v >= 0 ? '+' : '−'}${compactMoney(Math.abs(v))}`} />
        </> : <Empty title="No price history for your holdings in this period" />}
      </Panel>
      <Panel title="Period performance" subtitle="Value of the holdings replay" actions={<Segmented small options={['Daily', 'Monthly', 'Yearly'] as const} value={mode} onChange={setMode} />}>
        {periods.length ? <div className="table-wrap short"><table className="table compact"><thead><tr><th>{mode === 'Daily' ? 'Date' : mode === 'Monthly' ? 'Month' : 'Year'}</th><th className="r">Value</th><th className="r">Change</th><th className="r">%</th></tr></thead>
          <tbody>{periods.map(p => <tr key={p.label}><td>{p.label}</td><td className="r">{compactMoney(p.value)}</td><td className={`r ${tone(p.change)}`}>{p.change === undefined ? '—' : signedMoney(p.change)}</td><td className={`r ${tone(p.changePct)}`}>{pct(p.changePct, 2)}</td></tr>)}</tbody></table></div> : <Empty title="No price history available" />}
      </Panel>
    </div>
  </div>
}

export default function HistoryPage({ portfolio, history, selected, historyLoading, collapsed, onToggle, onOpen, onOpenStock }: {
  portfolio: PortfolioApi; history: Snapshot[]; selected: Snapshot | null; historyLoading: boolean; collapsed: boolean; onToggle: () => void; onOpen: (runId: string) => void; onOpenStock: (s: string) => void
}) {
  const [tab, setTab] = useState<Tab>('Performance')
  const aiLog = portfolio.activity.filter(a => a.type === 'AI_REVIEW')
  return <div className="stack-y">
    <Segmented options={TABS} value={tab} onChange={setTab} />

    {tab === 'Performance' && <Performance portfolio={portfolio} />}

    {tab === 'Transactions' && <Panel title="Transaction history"><Soon title="Buy and sell transactions are not recorded">The backend does not store transactions, and portfolio CSV files only carry quantity and average price. Nothing is estimated here. Manual additions and removals you make are listed under Imports and updates.</Soon></Panel>}

    {tab === 'Imports & updates' && <Panel title="Portfolio and data updates" subtitle="Recorded in this browser from the first time this version ran. Earlier activity was not logged.">
      {portfolio.activity.filter(a => a.type !== 'AI_REVIEW').length ? <ol className="timeline">{portfolio.activity.filter(a => a.type !== 'AI_REVIEW').map(a => <li key={a.id}><i /><div><b>{ACTIVITY_LABEL[a.type] ?? a.type}</b><span>{a.detail}</span><small>{when(a.at)}</small></div></li>)}</ol>
        : <Empty icon="⟲" title="No updates recorded yet">Imports, additions and removals will appear here as you make them.</Empty>}
    </Panel>}

    {tab === 'AI analysis' && <>
      <Panel title="Market analysis runs" subtitle="Each run is saved by the backend and never overwritten">
        {history.length ? <ol className="timeline">{history.map(item => <li key={item.runId} className={selected?.runId === item.runId ? 'active' : ''}><i /><div>
          <b>Market analysis <code>{item.runId.slice(0, 8)}</code></b>
          <span>{Number(item.importedPriceRows || 0).toLocaleString('en-IN')} rows · {item.distinctSymbols} symbols{item.recommendations ? ` · ${item.recommendations.length} candidate${item.recommendations.length === 1 ? '' : 's'}` : ''}</span>
          {item.recommendations?.length ? <span className="chips">{item.recommendations.map(r => <em key={r.symbol}>{r.symbol}</em>)}</span> : null}
          <small>{new Date(item.createdAt).toLocaleString('en-IN')} · {item.status}</small>
          <button className="btn ghost small" disabled={historyLoading} onClick={() => onOpen(item.runId)}>View report</button></div></li>)}</ol>
          : <Empty icon="↗" title="No analysis runs yet">Import market data and run your first analysis from the Overview.</Empty>}
      </Panel>
      {selected && <RecommendationPanel analysis={selected} title="Selected historical report" subtitle={`Saved run · ${new Date(selected.createdAt).toLocaleString('en-IN')} · Run ID ${selected.runId.slice(0, 8)}`} loading={historyLoading} collapsed={collapsed} onToggle={onToggle} onOpenStock={onOpenStock} />}
      <Panel title="AI stock assessments" subtitle="Runs started from this browser">
        {aiLog.length ? <ol className="timeline">{aiLog.map(a => <li key={a.id}><i /><div><b>{ACTIVITY_LABEL[a.type]}</b><span>{a.detail}</span><small>{when(a.at)}</small></div></li>)}</ol>
          : <Soon title="Past AI stock reports are not saved">The backend returns each assessment once and does not store it. Only the time and size of each run is recorded here.</Soon>}
      </Panel>
    </>}
  </div>
}
