
import { ChangeEvent, Fragment, useEffect, useMemo, useState } from 'react'
import PortfolioDashboard from './PortfolioDashboard.tsx'

const API = 'http://localhost:8083/api'
type Recommendation = { symbol: string; latestClose: number; researchScore: number; return5d: number; return20d: number; return60d: number; sma20: number; sma50: number; volumeStrength: number; volatility20d: number; drawdown60d: number; trend: string; reason: string; risk: string; rsi14?: number; atr14Percent?: number; averageDailyTradedValue20?: number; momentumScore?: number; trendScore?: number; volumeScore?: number; riskScore?: number; liquidityScore?: number; setup?: string }
type Snapshot = { runId: string; createdAt: string; status: string; importedPriceRows: number; distinctSymbols: number; note: string; recommendations?: Recommendation[] }
type Price = { tradingDate: string; symbol: string; series: string; open: number; high: number; low: number; close: number; volume: number }
type StockSummary = { symbol: string; latest: Price; ath: Price; atl: Price; oneYear?: number; threeYear?: number; fiveYear?: number; changeToday?: number }

const money = (value: number) => `₹${Number(value).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
const dateLabel = (value: string) => value ? new Date(`${value}T00:00:00`).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—'
const pct = (value?: number) => value === undefined || !Number.isFinite(value) ? '—' : `${value >= 0 ? '+' : ''}${value.toFixed(1)}%`

function RecommendationPanel({ analysis, title, subtitle, loading = false, collapsed = false, onToggle }: { analysis: Snapshot | null; title: string; subtitle?: string; loading?: boolean; collapsed?: boolean; onToggle?: () => void }) {
  return <section className="panel recommendations">
    <div className="panel-head"><div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, width: '100%', flexWrap: 'wrap' }}><h2>{title}</h2>{onToggle && <button type="button" aria-expanded={!collapsed} onClick={onToggle} title={collapsed ? 'Expand saved report' : 'Collapse saved report'} style={{ border: '1px solid var(--border, #e5e7eb)', background: 'transparent', color: 'var(--muted, #7b8494)', borderRadius: 7, width: 32, height: 32, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 17, cursor: 'pointer' }}>{collapsed ? '⌄' : '⌃'}</button>}</div><span className="hint">{subtitle || 'Up to 5 rule-based swing candidates'}</span></div>
    {!collapsed && <>{loading && <div className="message">Loading saved analysis…</div>}
      {analysis?.recommendations?.length ? (
          <div style={{ width: '100%', overflowX: 'auto' }}>
            <table style={{ width: '100%', minWidth: '850px', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead><tr>{['Company', 'Current price', '5D return', '20D return', '60D return', 'Research score', 'Trend'].map(label => <th key={label} style={{ padding: '14px 12px', borderBottom: '1px solid var(--border, #e5e7eb)', fontSize: '12px', fontWeight: 700, whiteSpace: 'nowrap' }}>{label}</th>)}</tr></thead>
              <tbody>
              {analysis.recommendations.map((item, index) => <Fragment key={`${analysis.runId}-${item.symbol}`}>
                <tr>
                  <td style={{ padding: '16px 12px', borderBottom: '1px solid var(--border, #e5e7eb)', fontWeight: 700, whiteSpace: 'nowrap' }}><span style={{ color: 'var(--muted, #7b8494)', marginRight: 7 }}>{index + 1}.</span>{item.symbol}</td>
                  <td style={{ padding: '16px 12px', borderBottom: '1px solid var(--border, #e5e7eb)', whiteSpace: 'nowrap', fontWeight: 600 }}>{money(item.latestClose)}</td>
                  {[item.return5d, item.return20d, item.return60d].map((value, i) => <td key={i} style={{ padding: '16px 12px', borderBottom: '1px solid var(--border, #e5e7eb)', whiteSpace: 'nowrap', color: value >= 0 ? '#168653' : '#d04444', fontWeight: 600 }}>{pct(value)}</td>)}
                  <td style={{ padding: '16px 12px', borderBottom: '1px solid var(--border, #e5e7eb)', whiteSpace: 'nowrap' }}><span style={{ display: 'inline-block', padding: '5px 9px', borderRadius: 7, background: 'var(--surface-soft, #eef3ff)', fontWeight: 700 }}>{item.researchScore}/100</span></td>
                  <td style={{ padding: '16px 12px', borderBottom: '1px solid var(--border, #e5e7eb)', whiteSpace: 'nowrap' }}>{item.trend}</td>
                </tr>
                <tr><td colSpan={7} style={{ padding: '0 12px 14px', borderBottom: '1px solid var(--border, #e5e7eb)' }}>
                  <details>
                    <summary style={{ cursor: 'pointer', color: 'var(--accent, #4778ff)', fontSize: '13px', fontWeight: 600, padding: '7px 0' }}>View complete stock analysis · {item.setup || item.trend}</summary>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 14, padding: '10px 0 4px', fontSize: '13px', lineHeight: 1.6 }}>
                      <div><b>Why it surfaced</b><p style={{ margin: '5px 0 0' }}>{item.reason}</p></div>
                      <div><b>Risk assessment</b><p style={{ margin: '5px 0 0' }}>{item.risk}</p></div>
                      <div><b>Technical indicators</b><p style={{ margin: '5px 0 0' }}>5-day return: {pct(item.return5d)} · 20-day return: {pct(item.return20d)} · 60-day return: {pct(item.return60d)}</p><p style={{ margin: '3px 0 0' }}>Price vs SMA20: {item.sma20 > 0 ? pct(((item.latestClose / item.sma20) - 1) * 100) : '—'} · RSI14: {item.rsi14 === undefined ? '—' : item.rsi14.toFixed(1)} · ATR14: {pct(item.atr14Percent)}</p><p style={{ margin: '3px 0 0' }}>Volume strength: {item.volumeStrength.toFixed(2)}x · Average daily traded value: {item.averageDailyTradedValue20 ? money(item.averageDailyTradedValue20) : '—'}</p></div>
                      <div><b>Score breakdown</b><p style={{ margin: '5px 0 0' }}>Momentum {item.momentumScore ?? '—'} · Trend {item.trendScore ?? '—'} · Volume {item.volumeScore ?? '—'} · Risk {item.riskScore ?? '—'} · Liquidity {item.liquidityScore ?? '—'}</p><p style={{ margin: '3px 0 0', color: 'var(--muted, #7b8494)' }}>Research score measures the strength of available screening signals. It is not a probability of profit.</p></div>
                      <div style={{ gridColumn: '1 / -1', paddingTop: 10, borderTop: '1px solid var(--border, #e5e7eb)' }}><b>Company and financial research</b><p style={{ margin: '5px 0 0' }}>Not available yet. This section will include company business details, revenue and profit trends, debt, valuation, recent announcements and relevant news once source-backed research is integrated.</p><span style={{ color: 'var(--muted, #7b8494)', fontSize: 12 }}>No company fundamentals or news have been assessed in this report.</span></div>
                    </div>
                  </details>
                </td></tr>
              </Fragment>)}
              </tbody>
            </table>
          </div>
      ) : <div className="empty-recommendations"><div className="empty-icon">↗</div><b>{analysis ? 'No stocks passed the initial filters' : 'No analysis run yet'}</b><p>{analysis?.note || 'Click Run Market Analysis to screen the imported NSE price history. MineVest will return up to five candidates only when the available evidence meets its initial criteria.'}</p></div>}</>}
  </section>
}

export default function App() {
  const [health, setHealth] = useState('Connecting to market data…')
  const [symbols, setSymbols] = useState<string[]>([])
  const [rows, setRows] = useState(0)
  const [prices, setPrices] = useState<Price[]>([])
  const [symbol, setSymbol] = useState('')
  const [history, setHistory] = useState<Snapshot[]>([])
  const [latestAnalysis, setLatestAnalysis] = useState<Snapshot | null>(null)
  const [selectedHistory, setSelectedHistory] = useState<Snapshot | null>(null)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyReportCollapsed, setHistoryReportCollapsed] = useState(false)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [detailOpen, setDetailOpen] = useState(false)
  const [historySearch, setHistorySearch] = useState('')
  const [historyRange, setHistoryRange] = useState<'all' | '10' | '30' | '90' | 'custom'>('all')
  const [historyFrom, setHistoryFrom] = useState('')
  const [historyTo, setHistoryTo] = useState('')

  async function refresh(preferredSymbol?: string) {
    try {
      const [h, s, hist] = await Promise.all([
        fetch(`${API}/health`).then(r => r.json()),
        fetch(`${API}/stocks`).then(r => r.json()),
        fetch(`${API}/history`).then(r => r.json())
      ])
      setHealth(h.status === 'UP' ? 'Market data connected' : 'Backend unavailable')
      const available = (s.symbols || []) as string[]
      setSymbols(available)
      setRows(s.totalPriceRows || 0)
      setHistory(hist || [])
      setLatestAnalysis(current => current || (hist && hist.length ? hist[0] : null))
      const nextSymbol = preferredSymbol || symbol || available[0] || ''
      if (nextSymbol) {
        setSymbol(nextSymbol)
        const p = await fetch(`${API}/market-data/prices?symbol=${encodeURIComponent(nextSymbol)}`).then(r => r.json())
        setPrices(p || [])
      } else {
        setPrices([])
      }
    } catch {
      setHealth('Backend not connected — start Spring Boot on port 8083')
    }
  }

  useEffect(() => { void refresh() }, [])

  async function importFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) return
    setBusy(true)
    setMessage('')
    try {
      const form = new FormData()
      form.append('file', file)
      const response = await fetch(`${API}/market-data/import`, { method: 'POST', body: form })
      const result = await response.json()
      if (!response.ok) throw new Error(result.error || 'Import failed')
      setMessage(`Imported ${Number(result.rowsRead || 0).toLocaleString('en-IN')} rows. ${result.message || ''}`)
      await refresh()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Import failed')
    } finally {
      setBusy(false)
      event.target.value = ''
    }
  }

  async function runAnalysis() {
    setBusy(true)
    setMessage('')
    try {
      const response = await fetch(`${API}/analysis/run`, { method: 'POST' })
      const result = await response.json()
      if (!response.ok) throw new Error(result.error || 'Market analysis failed')
      setLatestAnalysis(result)
      const count = (result.recommendations || []).length
      setMessage(`Analysis completed · ${count} qualifying stock${count === 1 ? '' : 's'} found · ${result.runId}`)
      await refresh()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Market analysis failed')
    } finally {
      setBusy(false)
    }
  }

  async function openHistory(runId: string) {
    setHistoryLoading(true)
    setMessage('')
    try {
      const response = await fetch(`${API}/history/${encodeURIComponent(runId)}`)
      const result = await response.json()
      if (!response.ok) throw new Error(result.error || 'Could not load the saved analysis')
      setSelectedHistory(result)
      setHistoryReportCollapsed(false)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not load the saved analysis')
    } finally {
      setHistoryLoading(false)
    }
  }

  async function loadSymbol(value: string) {
    setSymbol(value)
    if (!value) return
    try {
      const p = await fetch(`${API}/market-data/prices?symbol=${encodeURIComponent(value)}`).then(r => r.json())
      setPrices(p || [])
    } catch {
      setPrices([])
    }
  }

  const displayedAnalysis = selectedHistory || latestAnalysis

  const sortedPrices = useMemo(() => [...prices].sort((a, b) => a.tradingDate.localeCompare(b.tradingDate)), [prices])
  const stock = useMemo<StockSummary | null>(() => {
    if (!sortedPrices.length) return null
    const latest = sortedPrices[sortedPrices.length - 1]
    const ath = sortedPrices.reduce((best, item) => item.high > best.high ? item : best, sortedPrices[0])
    const atl = sortedPrices.reduce((best, item) => item.low < best.low ? item : best, sortedPrices[0])
    const since = (days: number) => {
      const cutoff = new Date(`${latest.tradingDate}T00:00:00`)
      cutoff.setDate(cutoff.getDate() - days)
      const prior = sortedPrices.find(item => new Date(`${item.tradingDate}T00:00:00`) >= cutoff)
      return prior && prior.close ? ((latest.close / prior.close) - 1) * 100 : undefined
    }
    return { symbol, latest, ath, atl, oneYear: since(365), threeYear: since(365 * 3), fiveYear: since(365 * 5) }
  }, [sortedPrices, symbol])

  const chartPoints = useMemo(() => {
    if (!sortedPrices.length) return ''
    const sample = sortedPrices.length > 80 ? sortedPrices.filter((_, i) => i % Math.ceil(sortedPrices.length / 80) === 0) : sortedPrices
    const min = Math.min(...sample.map(p => p.low))
    const max = Math.max(...sample.map(p => p.high))
    const range = max - min || 1
    return sample.map((p, i) => `${sample.length === 1 ? 0 : (i / (sample.length - 1)) * 600},${174 - ((p.close - min) / range) * 155}`).join(' ')
  }, [sortedPrices])

  const monthly = useMemo(() => {
    const grouped = new Map<string, Price[]>()
    sortedPrices.forEach(item => {
      const key = item.tradingDate.slice(0, 7)
      grouped.set(key, [...(grouped.get(key) || []), item])
    })
    const byMonth = Array.from({ length: 12 }, (_, i) => ({ month: new Date(2000, i, 1).toLocaleString('en', { month: 'short' }).toUpperCase(), values: [] as number[] }))
    const months = [...grouped.entries()].sort(([a], [b]) => a.localeCompare(b))
    for (let i = 1; i < months.length; i++) {
      const [key, current] = months[i]
      const [previousKey, previous] = months[i - 1]
      const previousDate = new Date(`${previousKey}-01T00:00:00`)
      previousDate.setMonth(previousDate.getMonth() + 1)
      const consecutive = `${previousDate.getFullYear()}-${String(previousDate.getMonth() + 1).padStart(2, '0')}` === key
      const last = current[current.length - 1]
      const previousClose = previous[previous.length - 1].close
      if (consecutive && previousClose > 0) byMonth[Number(key.slice(5, 7)) - 1].values.push(((last.close / previousClose) - 1) * 100)
    }
    return byMonth.map(item => ({ ...item, average: item.values.length ? item.values.reduce((a, b) => a + b, 0) / item.values.length : undefined }))
  }, [sortedPrices])

  const filteredHistory = useMemo(() => {
    const query = historySearch.trim().toLowerCase()
    const now = new Date()
    const from = historyRange === 'custom' && historyFrom ? new Date(`${historyFrom}T00:00:00`) : null
    const to = historyRange === 'custom' && historyTo ? new Date(`${historyTo}T23:59:59`) : null
    const cutoff = historyRange !== 'all' && historyRange !== 'custom'
      ? new Date(now.getTime() - Number(historyRange) * 24 * 60 * 60 * 1000)
      : null
    return history.filter(item => {
      const created = new Date(item.createdAt)
      if (cutoff && created < cutoff) return false
      if (from && created < from) return false
      if (to && created > to) return false
      if (!query) return true
      return [item.runId, item.createdAt, item.status, item.note, String(item.distinctSymbols), String(item.importedPriceRows), ...(item.recommendations || []).map(stock => stock.symbol)]
        .join(' ').toLowerCase().includes(query)
    })
  }, [history, historySearch, historyRange, historyFrom, historyTo])

  return <div className="page-shell">
    <header className="topbar">
      <div className="brand"><span className="brand-mark">M</span><span>MineVest</span></div>
      <div className="topbar-right">
        <span className={`connection ${health === 'Market data connected' ? 'ok' : ''}`}><i />{health}</span>
        <label className="import-link">＋ Import NSE data<input type="file" accept=".zip,.csv" disabled={busy} onChange={importFile} /></label>
      </div>
    </header>

    {!detailOpen ? <main className="dashboard">
      <section className="hero">
        <div>
          <h1>Today's opportunities</h1>
          <p className="subtitle">Price-based Indian stock opportunities worth researching today.</p>
          <div className="status-line"><i />{rows ? `${rows.toLocaleString('en-IN')} market price records imported · ${symbols.length.toLocaleString('en-IN')} symbols available` : 'Your personal Indian stock research workspace'}</div>
        </div>
        <div className="hero-action-wrap">
          <button className="run-button" disabled={busy || rows === 0} onClick={runAnalysis}>▶ &nbsp; {busy ? 'Analysing market…' : 'Run Market Analysis'}</button>
          <span className="button-note">{rows === 0 ? 'Import NSE data to begin' : 'Price-history screening · up to 5 stocks'}</span>
        </div>
      </section>

      {message && <div className="message">{message}</div>}

      <RecommendationPanel analysis={latestAnalysis} title="Top stock recommendations" subtitle="Latest completed market analysis · up to 5 candidates" />

      {selectedHistory && <RecommendationPanel
          analysis={selectedHistory}
          title="Selected historical report"
          subtitle={`Saved run · ${new Date(selectedHistory.createdAt).toLocaleString('en-IN')} · Run ID ${selectedHistory.runId.slice(0, 8)}`}
          loading={historyLoading}
          collapsed={historyReportCollapsed}
          onToggle={() => setHistoryReportCollapsed(value => !value)}
      />}
      <PortfolioDashboard />

      <div className="data-strip">
        <div><span className="data-dot"/><span><b>{rows.toLocaleString('en-IN')}</b> price records imported</span></div>
        <div><b>{symbols.length.toLocaleString('en-IN')}</b> symbols available</div>
        <div><b>{history.length}</b> saved analysis runs</div>
        <label className="quiet-import">Import / update data<input type="file" accept=".zip,.csv" disabled={busy} onChange={importFile} /></label>
      </div>

      <section className="history-section panel history-panel">
        <div className="section-head"><div><span className="eyebrow">YOUR RESEARCH LOG</span><h2>Recommendation history</h2><p>Every completed market analysis is saved separately. Search reports or choose the period you want to review.</p></div><span className="history-total">{filteredHistory.length} of {history.length} runs</span></div>
        <div className="history-toolbar">
          <label className="history-search"><span>⌕</span><input value={historySearch} onChange={e => setHistorySearch(e.target.value)} placeholder="Search symbol, run ID or report…" aria-label="Search recommendation history" /></label>
          <div className="history-range" role="group" aria-label="History date range">
            {([{value:'all',label:'All history'},{value:'10',label:'10 days'},{value:'30',label:'30 days'},{value:'90',label:'90 days'},{value:'custom',label:'Custom'}] as const).map(option => <button type="button" key={option.value} className={historyRange === option.value ? 'active' : ''} onClick={() => setHistoryRange(option.value)}>{option.label}</button>)}
          </div>
        </div>
        {historyRange === 'custom' && <div className="history-custom"><label>From <input type="date" value={historyFrom} max={historyTo || undefined} onChange={e => setHistoryFrom(e.target.value)} /></label><label>To <input type="date" value={historyTo} min={historyFrom || undefined} onChange={e => setHistoryTo(e.target.value)} /></label><button type="button" className="clear-filter" onClick={() => { setHistoryFrom(''); setHistoryTo('') }}>Clear dates</button></div>}
        <div className="history-list">
          {filteredHistory.length ? filteredHistory.map(item => <button type="button" className="history-row" key={item.runId} onClick={() => void openHistory(item.runId)} disabled={historyLoading}>
            <div className="history-run-icon">↗</div><div className="history-run-main"><b>Market analysis</b><small>{new Date(item.createdAt).toLocaleString('en-IN')}</small><small className="history-symbols">{(item.recommendations || []).map(stock => stock.symbol).join(' · ') || 'No qualifying stocks in this run'}</small></div>
            <div className="history-run-meta"><b>{Number(item.importedPriceRows || 0).toLocaleString('en-IN')}</b><small>price rows</small></div><div className="history-run-meta"><b>{item.distinctSymbols}</b><small>symbols</small></div><code>{item.runId.slice(0, 8)}</code><span className="history-view">View report →</span>
          </button>) : <div className="history-empty">{history.length ? 'No reports match your search or selected date range.' : 'No analysis runs yet. Import market data and run your first analysis.'}</div>}
        </div>
      </section>
      <footer>MineVest · Find. Invest. Grow. <span>Research support only · No trading or guaranteed returns</span></footer>
    </main> : <main className="detail-view">
      <button className="back-button" onClick={() => setDetailOpen(false)}>← Back to recommendations</button>
      <section className="panel detail-top">
        <div><h1>{symbol || 'Stock research'}</h1><div className="ticker">NSE · Imported daily market data</div></div>
        <div className="detail-controls"><select value={symbol} onChange={e => void loadSymbol(e.target.value)}>{symbols.map(s => <option key={s} value={s}>{s}</option>)}</select><div className="detail-price">{stock ? money(stock.latest.close) : '—'}<small>Latest imported close</small></div></div>
      </section>
      <div className="metric-grid">
        <div className="panel metric"><label>All-time high in imported data</label><strong>{stock ? money(stock.ath.high) : '—'}</strong><small>{stock ? dateLabel(stock.ath.tradingDate) : 'No data'}</small></div>
        <div className="panel metric"><label>Distance from imported high</label><strong className="negative">{stock ? pct(((stock.latest.close / stock.ath.high) - 1) * 100) : '—'}</strong><small>Based on imported history only</small></div>
        <div className="panel metric"><label>All-time low in imported data</label><strong>{stock ? money(stock.atl.low) : '—'}</strong><small>{stock ? dateLabel(stock.atl.tradingDate) : 'No data'}</small></div>
        <div className="panel metric"><label>Imported trading sessions</label><strong>{sortedPrices.length.toLocaleString('en-IN')}</strong><small>Historical coverage may be incomplete</small></div>
      </div>
      <div className="two-col">
        <section className="panel content-panel"><h3>Long-term price behaviour</h3><p className="desc">Daily closing prices from the NSE data you have imported.</p>
          {chartPoints ? <><div className="chart"><svg viewBox="0 0 600 190" preserveAspectRatio="none"><polyline points={chartPoints} fill="none" stroke="#315efb" strokeWidth="3" vectorEffect="non-scaling-stroke" /></svg></div><div className="chart-label"><span>{dateLabel(sortedPrices[0]?.tradingDate)}</span><span>{dateLabel(sortedPrices[sortedPrices.length - 1]?.tradingDate)}</span></div></> : <div className="panel-empty">No price history for this symbol yet.</div>}
        </section>
        <section className="panel content-panel"><h3>Historical returns</h3><p className="desc">Approximate price return using the available imported daily history.</p>
          <table className="simple-table"><tbody><tr><td>1 year</td><td className={stock?.oneYear !== undefined && stock.oneYear >= 0 ? 'positive' : 'negative'}>{pct(stock?.oneYear)}</td></tr><tr><td>3 years</td><td>{pct(stock?.threeYear)}</td></tr><tr><td>5 years</td><td>{pct(stock?.fiveYear)}</td></tr></tbody></table>
          <div className="note">A return is shown only when the imported data covers the relevant period. Corporate-action adjustments are not yet applied.</div>
        </section>
      </div>
      <div className="two-col">
        <section className="panel content-panel"><h3>Monthly seasonality</h3><p className="desc">Average monthly price change across the imported history. Descriptive, not predictive.</p>
          <div className="months">{monthly.map(item => <div className={`month ${item.average === undefined ? '' : item.average >= 0 ? 'good' : 'bad'}`} key={item.month}><b>{item.month}</b><span className={item.average === undefined ? '' : item.average >= 0 ? 'positive' : 'negative'}>{item.average === undefined ? '—' : pct(item.average)}</span><small>{item.values.length} samples</small></div>)}</div>
          <div className="note">Monthly values use month-to-month closing-price changes. Small samples can be misleading.</div>
        </section>
        <section className="panel content-panel"><h3>Market scenario analysis</h3><p className="desc">Historical sensitivity will be added after benchmark and macroeconomic datasets are connected.</p><div className="panel-empty">Not available yet — no scenario conclusions are inferred from price data alone.</div></section>
      </div>
      <section className="panel research-panel"><h3>Why is this stock recommended today?</h3><div className="panel-empty">Recommendations are not active yet. This page currently displays historical NSE price data only; company fundamentals, news catalysts and source-backed AI research are coming next.</div></section>
      <footer>MineVest · Stock research <span>Historical data is not a forecast or investment recommendation.</span></footer>
    </main>}
  </div>
}

