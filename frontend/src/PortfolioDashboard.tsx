import { ChangeEvent, useEffect, useMemo, useState } from 'react'

const API = 'http://localhost:8083/api'
const STORAGE_KEY = 'minevest.portfolio.v1'
const SNAPSHOT_KEY = 'minevest.portfolio.snapshots.v1'
type PortfolioSnapshot = { date: string; invested: number; current: number }

function localDateKey(date = new Date()): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

type Holding = {
  id: string
  symbol: string
  quantity: number
  averagePrice: number
  currentPrice?: number
  updatedAt?: string
}

type ReviewHolding = {
  symbol: string
  assetType?: string
  businessOverview?: string
  businessQuality?: string
  growthDrivers?: string[]
  valuationView?: string
  fundamentalDataStatus?: string
  stockOutlook?: string
  action: 'HOLD' | 'REVIEW' | 'EXIT' | string
  confidence?: string
  reasoning?: string
  risks?: string[]
  dataGaps?: string[]
  holdingAnalysis?: string
  decisionRationale?: string
  monitoringPoints?: string[]
  technicalScore?: number
  fundamentalScore?: number | null
  newsScore?: number | null
  availableScore?: number
  availableScoreMax?: number
  overallScore?: number | null
  scoreStatus?: string
  priceFreshness?: string
  latestTradingDate?: string
  assessedPrice?: number
  priceSource?: string
  return20dPct?: number | null
  drawdown60dPct?: number | null
  unrealisedReturnPct?: number | null
  positiveNewsCount?: number
  negativeNewsCount?: number
}

type Review = {
  summary?: string
  holdings?: ReviewHolding[]
  portfolioRisks?: string[]
  alternatives?: Array<{ symbol: string; rationale: string }>
  disclaimer?: string
}

const money = (value: number) =>
    `₹${Number(value || 0).toLocaleString('en-IN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`

const pct = (value: number) => `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`

function parseCsvLine(line: string): string[] {
  const cells: string[] = []
  let cell = ''
  let quoted = false

  for (let i = 0; i < line.length; i++) {
    const char = line[i]
    if (char === '"' && line[i + 1] === '"' && quoted) {
      cell += '"'
      i++
    } else if (char === '"') {
      quoted = !quoted
    } else if (char === ',' && !quoted) {
      cells.push(cell.trim())
      cell = ''
    } else {
      cell += char
    }
  }

  cells.push(cell.trim())
  return cells
}

function normalizeHeader(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, '')
}

function findColumn(headers: string[], candidates: string[]): number {
  return headers.findIndex(header => candidates.includes(normalizeHeader(header)))
}

function parsePortfolioCsv(text: string): Holding[] {
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter(line => line.trim())
  if (lines.length < 2) throw new Error('The CSV does not contain any holdings.')

  const headers = parseCsvLine(lines[0])
  const symbolCol = findColumn(headers, ['symbol', 'tradingsymbol', 'stock', 'ticker', 'scrip'])
  const quantityCol = findColumn(headers, ['quantity', 'qty', 'netquantity', 'shares', 'holdingsquantity'])
  const averageCol = findColumn(headers, [
    'averageprice', 'avgprice', 'buyaverageprice', 'averagecost', 'costprice',
    'purchaseprice', 'avgcost', 'buyprice',
  ])

  if (symbolCol < 0 || quantityCol < 0 || averageCol < 0) {
    throw new Error('Required columns not found. CSV must contain Symbol, Quantity and Average Price.')
  }

  const rows = lines.slice(1).map((line, index) => {
    const cells = parseCsvLine(line)
    const symbol = (cells[symbolCol] || '').trim().toUpperCase().replace(/\.NS$/, '')
    const quantity = Number((cells[quantityCol] || '').replace(/,/g, ''))
    const averagePrice = Number((cells[averageCol] || '').replace(/[₹,]/g, ''))

    if (!symbol || !Number.isFinite(quantity) || quantity <= 0 ||
        !Number.isFinite(averagePrice) || averagePrice <= 0) return null

    return {
      id: `${symbol}-${Date.now()}-${index}`,
      symbol,
      quantity,
      averagePrice,
    } satisfies Holding
  }).filter((item): item is Holding => item !== null)

  if (!rows.length) throw new Error('No valid holdings found in this CSV.')
  return rows
}

async function fetchLatestPrice(symbol: string): Promise<number | undefined> {
  try {
    const response = await fetch(
        `${API}/market-data/prices?symbol=${encodeURIComponent(symbol)}`
    )
    if (!response.ok) return undefined
    const prices = await response.json()
    if (!Array.isArray(prices) || !prices.length) return undefined
    const sortedPrices = [...prices].sort((a, b) =>
        String(a.tradingDate).localeCompare(String(b.tradingDate))
    )
    const latest = sortedPrices[sortedPrices.length - 1]
    const value = Number(latest?.close)
    return Number.isFinite(value) && value > 0 ? value : undefined
  } catch {
    return undefined
  }
}

function actionColor(action: string): string {
  if (action === 'EXIT') return '#b42318'
  if (action === 'REVIEW') return '#a15c00'
  return '#16794b'
}

function metric(label: string, value: string) {
  return <div style={{ background: 'var(--surface-soft, #f6f8fb)', border: '1px solid var(--line)', borderRadius: 10, padding: 11 }}>
    <small style={{ display: 'block', color: 'var(--muted)', marginBottom: 5 }}>{label}</small>
    <b style={{ fontSize: 13 }}>{value}</b>
  </div>
}

export default function PortfolioDashboard() {
  const [holdings, setHoldings] = useState<Holding[]>([])
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [symbol, setSymbol] = useState('')
  const [quantity, setQuantity] = useState('')
  const [averagePrice, setAveragePrice] = useState('')
  const [review, setReview] = useState<Review | null>(null)
  const [reviewBusy, setReviewBusy] = useState(false)
  const [snapshots, setSnapshots] = useState<PortfolioSnapshot[]>([])
  const [snapshotReady, setSnapshotReady] = useState(false)
  const [performanceRange, setPerformanceRange] = useState<'7' | '30' | '90' | '180' | '365' | 'all'>('30')

  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      if (saved) setHoldings(JSON.parse(saved) as Holding[])
      const savedSnapshots = localStorage.getItem(SNAPSHOT_KEY)
      if (savedSnapshots) setSnapshots(JSON.parse(savedSnapshots) as PortfolioSnapshot[])
    } catch {
      setMessage('Could not read the saved portfolio. You can import it again.')
    } finally {
      setSnapshotReady(true)
      setLoaded(true)
    }
  }, [])

  useEffect(() => {
    if (loaded) localStorage.setItem(STORAGE_KEY, JSON.stringify(holdings))
  }, [holdings, loaded])

  useEffect(() => {
    if (!loaded || !holdings.length) return
    let cancelled = false

    async function refreshPrices() {
      const updated = await Promise.all(holdings.map(async holding => ({
        ...holding,
        currentPrice: await fetchLatestPrice(holding.symbol),
        updatedAt: new Date().toISOString(),
      })))
      if (!cancelled) {
        setHoldings(current => current.map(existing =>
            updated.find(item => item.id === existing.id) || existing
        ))
      }
    }

    void refreshPrices()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded])

  const totals = useMemo(() => holdings.reduce((acc, holding) => {
    const invested = holding.quantity * holding.averagePrice
    const current = holding.quantity * (holding.currentPrice ?? holding.averagePrice)
    return {
      invested: acc.invested + invested,
      current: acc.current + current,
      pricedValue: acc.pricedValue + (holding.currentPrice ? current : 0),
    }
  }, { invested: 0, current: 0, pricedValue: 0 }), [holdings])

  const pnl = totals.current - totals.invested
  const pnlPercent = totals.invested ? (pnl / totals.invested) * 100 : 0

  useEffect(() => {
    if (!loaded || !snapshotReady || !holdings.length) return
    if (!holdings.every(item => Number.isFinite(item.currentPrice) && (item.currentPrice ?? 0) > 0)) return
    const date = localDateKey()
    const snapshot: PortfolioSnapshot = { date, invested: totals.invested, current: totals.current }
    setSnapshots(current => {
      const next = [...current.filter(item => item.date !== date), snapshot].sort((a, b) => a.date.localeCompare(b.date))
      localStorage.setItem(SNAPSHOT_KEY, JSON.stringify(next))
      return next
    })
  }, [loaded, snapshotReady, holdings, totals.invested, totals.current])

  const visibleSnapshots = useMemo(() => {
    if (performanceRange === 'all') return snapshots
    const cutoff = new Date()
    cutoff.setDate(cutoff.getDate() - Number(performanceRange))
    return snapshots.filter(item => new Date(`${item.date}T00:00:00`) >= cutoff)
  }, [snapshots, performanceRange])

  const allocation = useMemo(() => holdings.map(item => ({
    symbol: item.symbol,
    value: item.quantity * (item.currentPrice ?? item.averagePrice),
  })).sort((a, b) => b.value - a.value), [holdings])
  const allocationTotal = allocation.reduce((sum, item) => sum + item.value, 0)
  const holdingPerformance = useMemo(() => holdings
      .filter(item => Number.isFinite(item.currentPrice) && (item.currentPrice ?? 0) > 0)
      .map(item => {
        const invested = item.quantity * item.averagePrice
        const current = item.quantity * (item.currentPrice ?? item.averagePrice)
        return { symbol: item.symbol, pnl: current - invested, returnPct: invested ? ((current - invested) / invested) * 100 : 0 }
      })
      .sort((a, b) => b.returnPct - a.returnPct), [holdings])
  const allocationColors = ['#4568f5', '#16a879', '#f3a640', '#8b68d8', '#e16c83', '#36a3b8', '#8291a8']
  const allocationGradient = allocation.length ? `conic-gradient(${allocation.map((item, index) => {
    const before = allocation.slice(0, index).reduce((sum, entry) => sum + entry.value, 0) / allocationTotal * 100
    const after = before + item.value / allocationTotal * 100
    return `${allocationColors[index % allocationColors.length]} ${before}% ${after}%`
  }).join(', ')})` : 'conic-gradient(#e9edf4 0 100%)'
  const performancePoints = useMemo(() => {
    if (!visibleSnapshots.length) return ''
    const values = visibleSnapshots.map(item => item.current)
    const min = Math.min(...values)
    const max = Math.max(...values)
    const range = max - min || 1
    return values.map((value, index) => `${visibleSnapshots.length === 1 ? 300 : 12 + (index / (visibleSnapshots.length - 1)) * 576},${166 - ((value - min) / range) * 138}`).join(' ')
  }, [visibleSnapshots])

  async function importCsv(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) return
    setBusy(true)
    setMessage('')
    setReview(null)
    try {
      const parsed = parsePortfolioCsv(await file.text())
      const priced = await Promise.all(parsed.map(async holding => ({
        ...holding,
        currentPrice: await fetchLatestPrice(holding.symbol),
      })))
      setHoldings(priced)
      setMessage(`Imported ${priced.length} holding(s). ${priced.filter(x => x.currentPrice).length} current prices matched your market data.`)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Portfolio import failed.')
    } finally {
      setBusy(false)
      event.target.value = ''
    }
  }

  function addHolding() {
    const cleanSymbol = symbol.trim().toUpperCase().replace(/\.NS$/, '')
    const qty = Number(quantity)
    const avg = Number(averagePrice)
    if (!cleanSymbol || !Number.isFinite(qty) || qty <= 0 ||
        !Number.isFinite(avg) || avg <= 0) {
      setMessage('Enter a valid stock symbol, quantity and average purchase price.')
      return
    }
    setHoldings(current => {
      const existing = current.find(item => item.symbol === cleanSymbol)
      if (existing) {
        const totalQty = existing.quantity + qty
        const blendedAverage = ((existing.quantity * existing.averagePrice) + (qty * avg)) / totalQty
        return current.map(item => item.id === existing.id
            ? { ...item, quantity: totalQty, averagePrice: blendedAverage }
            : item)
      }
      return [...current, { id: `${cleanSymbol}-${Date.now()}`, symbol: cleanSymbol, quantity: qty, averagePrice: avg }]
    })
    setSymbol('')
    setQuantity('')
    setAveragePrice('')
    setMessage(`${cleanSymbol} added to your portfolio.`)
    setReview(null)
  }

  function removeHolding(id: string) {
    setHoldings(current => current.filter(item => item.id !== id))
    setReview(null)
  }

  async function runPortfolioReview() {
    if (!holdings.length) {
      setMessage('Import or add at least one holding before running stock intelligence.')
      return
    }
    setReviewBusy(true)
    setMessage('')
    setReview(null)
    try {
      const response = await fetch(`${API}/ai/portfolio-review`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          holdings: holdings.map(item => ({
            symbol: item.symbol,
            quantity: item.quantity,
            averagePrice: item.averagePrice,
            currentPrice: item.currentPrice ?? null,
            investedValue: item.quantity * item.averagePrice,
            currentValue: item.currentPrice ? item.quantity * item.currentPrice : null,
          })),
          objective: 'Maximum returns with willingness to switch holdings',
          horizon: '12-24 months',
        }),
      })
      const result = await response.json()
      if (!response.ok) throw new Error(result.error || 'Portfolio review is not available yet.')
      setReview(result)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Portfolio review failed.')
    } finally {
      setReviewBusy(false)
    }
  }

  const panelStyle = { background: 'var(--card)', border: '1px solid var(--line)', borderRadius: 18, padding: 22, boxShadow: '0 8px 28px rgba(18,35,68,.045)' } as const
  const inputStyle = { width: '100%', minWidth: 0, padding: '11px 12px', border: '1px solid var(--line)', borderRadius: 10, background: '#fff', color: 'var(--ink)' } as const
  const buttonStyle = { border: 0, borderRadius: 10, padding: '11px 15px', background: 'var(--blue)', color: '#fff', fontWeight: 750, cursor: 'pointer' } as const

  return <section className="portfolio-workspace">
    <div className="portfolio-heading"><div><span className="eyebrow">YOUR MONEY, CLEARLY</span><h2>Portfolio overview</h2><p>Track your holdings, understand performance and review each stock independently.</p></div><label className="primary-action">{busy ? 'Importing…' : '↑ Import portfolio CSV'}<input type="file" accept=".csv,text/csv" disabled={busy} onChange={importCsv} /></label></div>
    {message && <div role="status" className="message portfolio-message">{message}</div>}

    <div className="portfolio-kpis">
      <article className="portfolio-kpi"><span>Invested amount</span><strong>{money(totals.invested)}</strong><small>Based on your average buy prices</small></article>
      <article className="portfolio-kpi"><span>Current value</span><strong>{money(totals.current)}</strong><small>{totals.pricedValue > 0 ? 'Latest available imported prices' : 'Awaiting matching market prices'}</small></article>
      <article className="portfolio-kpi"><span>Unrealised P&amp;L</span><strong className={pnl >= 0 ? 'positive' : 'negative'}>{pnl >= 0 ? '+' : '−'}{money(Math.abs(pnl))}</strong><small className={pnl >= 0 ? 'positive' : 'negative'}>{pct(pnlPercent)} overall return</small></article>
      <article className="portfolio-kpi"><span>Holdings</span><strong>{holdings.length}</strong><small>Individual stocks and ETFs</small></article>
    </div>

    <div className="portfolio-chart-grid">
      <section className="portfolio-card performance-card">
        <div className="portfolio-card-head"><div><h3>Portfolio performance</h3><p>Daily values recorded by MineVest from the day tracking is enabled</p></div><div className="range-tabs">{([{value:'7',label:'1W'},{value:'30',label:'1M'},{value:'90',label:'3M'},{value:'180',label:'6M'},{value:'365',label:'1Y'},{value:'all',label:'ALL'}] as const).map(item => <button type="button" key={item.value} className={performanceRange === item.value ? 'active' : ''} onClick={() => setPerformanceRange(item.value)}>{item.label}</button>)}</div></div>
        {visibleSnapshots.length > 1 ? <><div className="portfolio-line-chart"><div className="chart-y-labels"><span>{money(Math.max(...visibleSnapshots.map(x => x.current)))}</span><span>{money(Math.min(...visibleSnapshots.map(x => x.current)))}</span></div><svg viewBox="0 0 600 180" preserveAspectRatio="none"><defs><linearGradient id="portfolioFill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor="#4568f5" stopOpacity=".22"/><stop offset="100%" stopColor="#4568f5" stopOpacity="0"/></linearGradient></defs><polygon points={`12,180 ${performancePoints} 588,180`} fill="url(#portfolioFill)"/><polyline points={performancePoints} fill="none" stroke="#4568f5" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke"/></svg></div><div className="chart-date-labels"><span>{visibleSnapshots[0].date}</span><span>{visibleSnapshots[visibleSnapshots.length - 1].date}</span></div></> : <div className="chart-empty"><span className="chart-empty-icon">⌁</span><b>{visibleSnapshots.length === 1 ? 'Your first snapshot is saved' : 'Portfolio history is starting'}</b><p>MineVest records one real valuation per day when you open the app and refresh prices. More points will appear here over time. No past performance is estimated.</p></div>}
        <div className="chart-legend"><span><i className="legend-blue"/> Current portfolio value</span><span><i className="legend-grey"/> Daily snapshots</span></div>
      </section>

      <section className="portfolio-card allocation-card"><div className="portfolio-card-head"><div><h3>Portfolio allocation</h3><p>Current value by holding</p></div></div>
        {allocation.length ? <><div className="allocation-visual"><div className="donut" style={{ background: allocationGradient }}><div><b>{money(allocationTotal)}</b><small>Total value</small></div></div></div><div className="allocation-legend">{allocation.map((item, index) => <div className="allocation-row" key={item.symbol}><span><i style={{ background: allocationColors[index % allocationColors.length] }}/>{item.symbol}</span><b>{allocationTotal ? (item.value / allocationTotal * 100).toFixed(1) : '0.0'}%</b></div>)}</div></> : <div className="chart-empty compact"><b>No holdings to display</b><p>Import your broker CSV or add a stock to see allocation.</p></div>}
      </section>
    </div>

    <div className="portfolio-secondary-grid">
      <section className="portfolio-card"><div className="portfolio-card-head"><div><h3>Invested vs current value</h3><p>Side-by-side view of your capital and today's valuation</p></div></div><div className="value-comparison"><div><span>Invested</span><b>{money(totals.invested)}</b><i style={{ width: '100%' }}/></div><div><span>Current</span><b>{money(totals.current)}</b><i className={pnl >= 0 ? 'bar-positive' : 'bar-negative'} style={{ width: `${totals.invested ? Math.max(3, Math.min(100, totals.current / totals.invested * 100)) : 0}%` }}/></div></div><p className="chart-footnote">Current value uses the latest available stored price. Missing prices temporarily use average cost.</p></section>
      <section className="portfolio-card"><div className="portfolio-card-head"><div><h3>Sector allocation</h3><p>Industry exposure across your holdings</p></div></div><div className="sector-placeholder"><span>▦</span><b>Sector classification not connected yet</b><p>MineVest does not currently have verified sector metadata for every holding. We will add this once a reliable company-sector source is connected.</p></div></section>
    </div>

    <div className="portfolio-performers">
      <section className="portfolio-card performer-card"><div className="portfolio-card-head"><div><h3>Best performer</h3><p>Highest unrealised return among priced holdings</p></div></div>
        {holdingPerformance.length ? <div className="performer-value"><b>{holdingPerformance[0].symbol}</b><strong className={holdingPerformance[0].returnPct >= 0 ? 'positive' : 'negative'}>{pct(holdingPerformance[0].returnPct)}</strong><small>{holdingPerformance[0].pnl >= 0 ? '+' : '−'}{money(Math.abs(holdingPerformance[0].pnl))}</small></div> : <div className="chart-empty compact"><p>Available after current prices are matched.</p></div>}
      </section>
      <section className="portfolio-card performer-card"><div className="portfolio-card-head"><div><h3>Weakest performer</h3><p>Lowest unrealised return among priced holdings</p></div></div>
        {holdingPerformance.length ? <div className="performer-value"><b>{holdingPerformance[holdingPerformance.length - 1].symbol}</b><strong className={holdingPerformance[holdingPerformance.length - 1].returnPct >= 0 ? 'positive' : 'negative'}>{pct(holdingPerformance[holdingPerformance.length - 1].returnPct)}</strong><small>{holdingPerformance[holdingPerformance.length - 1].pnl >= 0 ? '+' : '−'}{money(Math.abs(holdingPerformance[holdingPerformance.length - 1].pnl))}</small></div> : <div className="chart-empty compact"><p>Available after current prices are matched.</p></div>}
      </section>
    </div>

    <section className="portfolio-card holding-editor"><div className="portfolio-card-head"><div><h3>Add holding manually</h3><p>Add a stock or update an existing position.</p></div></div><div className="holding-form"><input style={inputStyle} placeholder="Symbol e.g. CDSL" value={symbol} onChange={e => setSymbol(e.target.value)} /><input style={inputStyle} type="number" min="0" placeholder="Quantity" value={quantity} onChange={e => setQuantity(e.target.value)} /><input style={inputStyle} type="number" min="0" step="0.01" placeholder="Average buy price ₹" value={averagePrice} onChange={e => setAveragePrice(e.target.value)} /><button style={buttonStyle} onClick={addHolding}>＋ Add stock</button></div></section>

    <section className="portfolio-card holdings-card"><div className="portfolio-card-head"><div><h3>Your holdings <span className="count-pill">{holdings.length}</span></h3><p>Holding performance is shown separately from the investment outlook.</p></div><button style={buttonStyle} disabled={reviewBusy || !holdings.length} onClick={runPortfolioReview}>{reviewBusy ? 'Analysing stocks…' : '✦ Run Stock Intelligence'}</button></div>
      {!holdings.length ? <div className="chart-empty compact"><b>Your portfolio is empty</b><p>Import a broker CSV or add your first stock above.</p></div> : <div className="holdings-table-wrap"><table className="holdings-table"><thead><tr>{['Stock','Qty','Avg. price','Current price','Invested','Current value','P&L',''].map(label => <th key={label}>{label}</th>)}</tr></thead><tbody>{holdings.map(item => { const invested = item.quantity * item.averagePrice; const current = item.quantity * (item.currentPrice ?? item.averagePrice); const gain = current - invested; const gainPct = invested ? gain / invested * 100 : 0; return <tr key={item.id}><td><b>{item.symbol}</b></td><td>{item.quantity}</td><td>{money(item.averagePrice)}</td><td>{item.currentPrice ? money(item.currentPrice) : 'Unavailable'}</td><td>{money(invested)}</td><td>{money(current)}</td><td className={gain >= 0 ? 'positive' : 'negative'}><b>{gain >= 0 ? '+' : '−'}{money(Math.abs(gain))}</b><small>{pct(gainPct)}</small></td><td><button className="remove-holding" onClick={() => removeHolding(item.id)}>Remove</button></td></tr> })}</tbody></table></div>}
    </section>

    {review && <section className="portfolio-card ai-review-card">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <div><span className="eyebrow">RULE-BASED PORTFOLIO REVIEW</span><h3 style={{ margin: '5px 0' }}>Stock Intelligence Report</h3></div>
        <span style={{ fontSize: 12, padding: '6px 10px', borderRadius: 20, background: '#edf2ff', color: '#344eaa', fontWeight: 700 }}>Deterministic engine</span>
      </div>
      <p style={{ color: 'var(--muted, #7b8494)', fontSize: 13 }}>Investment outlook and your purchase performance are different things. A temporary unrealised loss, by itself, is not an EXIT signal.</p>
      {review.summary && <p style={{ lineHeight: 1.65 }}>{review.summary}</p>}

      {review.holdings?.map(item => {
        const holding = holdings.find(h => h.symbol === item.symbol)
        const invested = holding ? holding.quantity * holding.averagePrice : 0
        const current = holding?.currentPrice ? holding.quantity * holding.currentPrice : undefined
        const holdingReturn = invested && current !== undefined ? ((current - invested) / invested) * 100 : item.unrealisedReturnPct
        const sourceLabel = item.priceSource === 'PORTFOLIO_SUPPLIED_PRICE'
            ? 'Portfolio-supplied price'
            : item.priceSource === 'HISTORICAL_EOD'
                ? 'Last stored EOD close'
                : item.priceSource || 'Price source unavailable'

        return <article key={item.symbol} style={{ padding: '20px 0', borderTop: '1px solid var(--line)' }}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
            <h3 style={{ margin: 0 }}>{item.symbol}</h3>
            <span style={{ fontSize: 11, padding: '4px 8px', borderRadius: 20, background: '#eef2f7', color: '#475467', fontWeight: 700 }}>{item.assetType || 'ASSET'}</span>
            <span style={{ fontSize: 12, padding: '5px 10px', borderRadius: 6, background: `${actionColor(item.action)}15`, color: actionColor(item.action), fontWeight: 800 }}>{item.action}</span>
            {item.stockOutlook && <b style={{ fontSize: 12 }}>{item.stockOutlook}</b>}
            {item.confidence && <small style={{ color: 'var(--muted)' }}>Confidence: {item.confidence}</small>}
          </div>

          <div style={{ marginTop: 14 }}>
            <h4 style={{ marginBottom: 8 }}>Holding performance</h4>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(145px, 1fr))', gap: 8 }}>
              {metric('Average buy price', holding ? money(holding.averagePrice) : 'Not supplied')}
              {metric('Current portfolio price', holding?.currentPrice ? money(holding.currentPrice) : 'Unavailable')}
              {metric('Unrealised return', holdingReturn == null ? 'Unavailable' : pct(holdingReturn))}
              {metric('Unrealised P&L', current === undefined || !holding ? 'Unavailable' : `${current - invested >= 0 ? '+' : '−'}${money(Math.abs(current - invested))}`)}
            </div>
          </div>

          <div style={{ marginTop: 14 }}>
            <h4 style={{ marginBottom: 8 }}>Investment outlook</h4>
            {item.decisionRationale && <p style={{ lineHeight: 1.6 }}>{item.decisionRationale}</p>}
            {item.reasoning && <p style={{ lineHeight: 1.6 }}>{item.reasoning}</p>}
            {!item.decisionRationale && !item.reasoning && <p>There is not enough evidence to provide a detailed rationale.</p>}
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(145px, 1fr))', gap: 8, marginTop: 12 }}>
            {item.assessedPrice !== undefined && metric('Assessment price', money(item.assessedPrice))}
            {metric('Price source', sourceLabel)}
            {metric('Latest trading date', item.latestTradingDate || 'Unavailable')}
            {metric('Price freshness', item.priceFreshness || 'Unknown')}
            {item.return20dPct !== null && item.return20dPct !== undefined && metric('20-session return', pct(item.return20dPct))}
            {item.drawdown60dPct !== null && item.drawdown60dPct !== undefined && metric('60-session drawdown', pct(item.drawdown60dPct))}
          </div>

          {(item.overallScore !== undefined && item.overallScore !== null)
              ? <p style={{ marginTop: 12, fontSize: 13 }}><b>Available-data score: {item.overallScore}</b> <span style={{ color: 'var(--muted)' }}>({item.scoreStatus || 'Complete'})</span></p>
              : <p style={{ marginTop: 12, fontSize: 13, color: 'var(--muted)' }}>Overall score withheld: {item.scoreStatus || 'required data is incomplete'}. Missing data is not treated as a positive score.</p>}

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(145px, 1fr))', gap: 8, marginTop: 10 }}>
            {item.technicalScore !== undefined && metric('Technical score', String(item.technicalScore))}
            {item.fundamentalScore !== undefined && item.fundamentalScore !== null && metric('Fundamental score', String(item.fundamentalScore))}
            {item.newsScore !== undefined && item.newsScore !== null && metric('News score', String(item.newsScore))}
            {item.availableScore !== undefined && metric('Available score points', `${item.availableScore}${item.availableScoreMax !== undefined ? ` / ${item.availableScoreMax}` : ''}`)}
          </div>

          {item.businessOverview && <><h4>Business overview</h4><p>{item.businessOverview}</p></>}
          {item.businessQuality && <><h4>Business quality</h4><p>{item.businessQuality}</p></>}
          {item.growthDrivers?.length ? <><h4>Growth drivers</h4><ul>{item.growthDrivers.map((x, i) => <li key={i}>{x}</li>)}</ul></> : null}
          {item.valuationView && <><h4>Valuation</h4><p>{item.valuationView}</p></>}

          {item.holdingAnalysis && <><h4>Holding context</h4><p style={{ lineHeight: 1.6 }}>{item.holdingAnalysis}</p></>}
          {item.monitoringPoints?.length ? <><h4>What to monitor next</h4><ul>{item.monitoringPoints.map((point, i) => <li key={i}>{point}</li>)}</ul></> : null}
          {item.risks?.length ? <><h4>Risks</h4><ul>{item.risks.map((risk, i) => <li key={i}>{risk}</li>)}</ul></> : null}
          {item.dataGaps?.length ? <><h4>Data gaps</h4><ul>{item.dataGaps.map((gap, i) => <li key={i}>{gap}</li>)}</ul></> : null}
          <small style={{ color: 'var(--muted, #7b8494)' }}>Fundamental data: {item.fundamentalDataStatus || 'Not verified'} · Positive news: {item.positiveNewsCount ?? 'N/A'} · Negative news: {item.negativeNewsCount ?? 'N/A'}</small>
        </article>
      })}

      {review.portfolioRisks?.length ? <><h4>Portfolio-level risks</h4><ul>{review.portfolioRisks.map((risk, i) => <li key={i}>{risk}</li>)}</ul></> : null}
      {review.alternatives?.length ? <><h4>Alternative opportunities to research</h4><ul>{review.alternatives.map(item => <li key={item.symbol}><b>{item.symbol}:</b> {item.rationale}</li>)}</ul></> : null}
      <p style={{ color: 'var(--muted, #7b8494)', fontSize: 12 }}>{review.disclaimer || 'This rule-based analysis is decision support, not a guaranteed forecast or an instruction to trade. Verify data and consider your risk tolerance before acting.'}</p>
    </section>}

    <p className="portfolio-disclaimer">CSV import expects Symbol, Quantity and Average Price columns. Holdings and daily valuation snapshots are stored in this browser. The displayed imported price is the latest available stored market-data close; it is not represented as a live quote. Historical charts begin recording from now; previous values are not fabricated.</p>
  </section>
}
