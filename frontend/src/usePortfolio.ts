import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ChangeEvent } from 'react'
import { API, fetchPrices, lastClose, money } from './api'
import type { Holding, Price, Review } from './api'

// Original storage key is unchanged, so existing saved portfolios keep working.
const STORAGE_KEY = 'minevest.portfolio.v1'
// New, additive keys. They only hold what this browser actually observed from now on.
const SNAPSHOT_KEY = 'minevest.portfolio.snapshots.v1'
const ACTIVITY_KEY = 'minevest.portfolio.activity.v1'

export type ValueSnapshot = { date: string; invested: number; value: number }
export type ActivityType = 'PORTFOLIO_IMPORT' | 'HOLDING_ADDED' | 'HOLDING_REMOVED' | 'PORTFOLIO_CLEARED' | 'AI_REVIEW' | 'MARKET_DATA_IMPORT'
export type Activity = { id: string; at: string; type: ActivityType; detail: string }
export type HoldingRow = { holding: Holding; invested: number; current: number; pnl: number; pnlPct: number; priced: boolean; dayChange?: number; dayPct?: number; weight: number }

function readJson<T>(key: string, fallback: T): T {
  try {
    const saved = localStorage.getItem(key)
    return saved ? JSON.parse(saved) as T : fallback
  } catch { return fallback }
}
function writeJson(key: string, value: unknown) {
  try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* storage unavailable */ }
}
const localDate = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function parseCsvLine(line: string): string[] {
  const cells: string[] = []
  let cell = ''
  let quoted = false
  for (let i = 0; i < line.length; i++) {
    const char = line[i]
    if (char === '"' && line[i + 1] === '"' && quoted) { cell += '"'; i++ }
    else if (char === '"') quoted = !quoted
    else if (char === ',' && !quoted) { cells.push(cell.trim()); cell = '' }
    else cell += char
  }
  cells.push(cell.trim())
  return cells
}
const normalizeHeader = (value: string) => value.toLowerCase().replace(/[^a-z0-9]/g, '')
const findColumn = (headers: string[], candidates: string[]) => headers.findIndex(h => candidates.includes(normalizeHeader(h)))

function parsePortfolioCsv(text: string): Holding[] {
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter(line => line.trim())
  if (lines.length < 2) throw new Error('The CSV does not contain any holdings.')
  const headers = parseCsvLine(lines[0])
  const symbolCol = findColumn(headers, ['symbol', 'tradingsymbol', 'stock', 'ticker', 'scrip'])
  const quantityCol = findColumn(headers, ['quantity', 'qty', 'netquantity', 'shares', 'holdingsquantity'])
  const averageCol = findColumn(headers, ['averageprice', 'avgprice', 'buyaverageprice', 'averagecost', 'costprice', 'purchaseprice', 'avgcost', 'buyprice'])
  if (symbolCol < 0 || quantityCol < 0 || averageCol < 0) {
    throw new Error('Required columns not found. CSV must contain Symbol, Quantity and Average Price.')
  }
  const rows = lines.slice(1).map((line, index) => {
    const cells = parseCsvLine(line)
    const symbol = (cells[symbolCol] || '').trim().toUpperCase().replace(/\.NS$/, '')
    const quantity = Number((cells[quantityCol] || '').replace(/,/g, ''))
    const averagePrice = Number((cells[averageCol] || '').replace(/[₹,]/g, ''))
    if (!symbol || !Number.isFinite(quantity) || quantity <= 0 || !Number.isFinite(averagePrice) || averagePrice <= 0) return null
    return { id: `${symbol}-${Date.now()}-${index}`, symbol, quantity, averagePrice } satisfies Holding
  }).filter((item): item is Holding => item !== null)
  if (!rows.length) throw new Error('No valid holdings found in this CSV.')
  return rows
}

export function usePortfolio() {
  const [holdings, setHoldings] = useState<Holding[]>([])
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [review, setReview] = useState<Review | null>(null)
  const [reviewBusy, setReviewBusy] = useState(false)
  const [series, setSeries] = useState<Record<string, Price[]>>({})
  const [fresh, setFresh] = useState(false)
  const [snapshots, setSnapshots] = useState<ValueSnapshot[]>(() => readJson<ValueSnapshot[]>(SNAPSHOT_KEY, []))
  const [activity, setActivity] = useState<Activity[]>(() => readJson<Activity[]>(ACTIVITY_KEY, []))

  const log = useCallback((type: ActivityType, detail: string) => {
    setActivity(cur => [{ id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, at: new Date().toISOString(), type, detail }, ...cur].slice(0, 300))
  }, [])

  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      if (saved) setHoldings(JSON.parse(saved) as Holding[])
    } catch {
      setMessage('Could not read the saved portfolio. You can import it again.')
    } finally {
      setLoaded(true)
    }
  }, [])

  useEffect(() => { if (loaded) writeJson(STORAGE_KEY, holdings) }, [holdings, loaded])
  useEffect(() => { writeJson(SNAPSHOT_KEY, snapshots) }, [snapshots])
  useEffect(() => { writeJson(ACTIVITY_KEY, activity) }, [activity])

  // Refresh prices once after the initial load (same behaviour as before).
  useEffect(() => {
    if (!loaded || !holdings.length) return
    let cancelled = false
    async function refreshPrices() {
      const results = await Promise.all(holdings.map(async h => ({ h, prices: await fetchPrices(h.symbol) })))
      if (cancelled) return
      setSeries(cur => {
        const next = { ...cur }
        results.forEach(r => { if (r.prices.length) next[r.h.symbol] = r.prices })
        return next
      })
      setHoldings(current => current.map(existing => {
        const r = results.find(x => x.h.id === existing.id)
        return r ? { ...existing, currentPrice: lastClose(r.prices), updatedAt: new Date().toISOString() } : existing
      }))
      setFresh(true)
    }
    void refreshPrices()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded])

  // Record one real value snapshot per day, only when every holding has a freshly matched price.
  useEffect(() => {
    if (!loaded || !fresh || !holdings.length || holdings.some(h => !h.currentPrice)) return
    const date = localDate()
    const invested = holdings.reduce((a, h) => a + h.quantity * h.averagePrice, 0)
    const value = holdings.reduce((a, h) => a + h.quantity * (h.currentPrice ?? 0), 0)
    setSnapshots(cur => [...cur.filter(s => s.date !== date), { date, invested, value }].sort((a, b) => a.date.localeCompare(b.date)).slice(-1500))
  }, [holdings, loaded, fresh])

  const view = useMemo(() => {
    let invested = 0, current = 0, pricedValue = 0, today = 0, todayBase = 0, todayCount = 0, asOf = ''
    const base = holdings.map(holding => {
      const inv = holding.quantity * holding.averagePrice
      const cur = holding.quantity * (holding.currentPrice ?? holding.averagePrice)
      const s = series[holding.symbol]
      let dayChange: number | undefined, dayPct: number | undefined
      if (s && s.length >= 2) {
        const a = s[s.length - 1], b = s[s.length - 2]
        dayChange = holding.quantity * (a.close - b.close)
        dayPct = b.close ? ((a.close / b.close) - 1) * 100 : undefined
        today += dayChange; todayBase += holding.quantity * b.close; todayCount++
        if (a.tradingDate > asOf) asOf = a.tradingDate
      }
      invested += inv; current += cur
      if (holding.currentPrice) pricedValue += cur
      return { holding, invested: inv, current: cur, pnl: cur - inv, pnlPct: inv ? ((cur - inv) / inv) * 100 : 0, priced: !!holding.currentPrice, dayChange, dayPct }
    })
    const rows: HoldingRow[] = base.map(r => ({ ...r, weight: current ? (r.current / current) * 100 : 0 }))
    const pnl = current - invested
    return { rows, totals: { invested, current, pricedValue, pnl, pnlPct: invested ? (pnl / invested) * 100 : 0, today, todayPct: todayBase ? (today / todayBase) * 100 : undefined, todayCount, asOf } }
  }, [holdings, series])

  async function importCsv(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) return
    setBusy(true)
    setMessage('')
    setReview(null)
    try {
      const parsed = parsePortfolioCsv(await file.text())
      const results = await Promise.all(parsed.map(async holding => ({ holding, prices: await fetchPrices(holding.symbol) })))
      const priced = results.map(r => ({ ...r.holding, currentPrice: lastClose(r.prices) }))
      const next: Record<string, Price[]> = {}
      results.forEach(r => { if (r.prices.length) next[r.holding.symbol] = r.prices })
      setSeries(next)
      setHoldings(priced)
      setFresh(true)
      const matched = priced.filter(x => x.currentPrice).length
      setMessage(`Imported ${priced.length} holding(s). ${matched} current prices matched your market data.`)
      log('PORTFOLIO_IMPORT', `${file.name} · ${priced.length} holdings · ${matched} priced`)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Portfolio import failed.')
    } finally {
      setBusy(false)
      event.target.value = ''
    }
  }

  async function attachPrice(sym: string) {
    const prices = await fetchPrices(sym)
    if (!prices.length) return
    setSeries(cur => ({ ...cur, [sym]: prices }))
    setHoldings(cur => cur.map(h => h.symbol === sym ? { ...h, currentPrice: lastClose(prices), updatedAt: new Date().toISOString() } : h))
    setFresh(true)
  }

  function addHolding(rawSymbol: string, rawQty: string, rawAvg: string): boolean {
    const cleanSymbol = rawSymbol.trim().toUpperCase().replace(/\.NS$/, '')
    const qty = Number(rawQty)
    const avg = Number(rawAvg)
    if (!cleanSymbol || !Number.isFinite(qty) || qty <= 0 || !Number.isFinite(avg) || avg <= 0) {
      setMessage('Enter a valid stock symbol, quantity and average purchase price.')
      return false
    }
    setHoldings(current => {
      const existing = current.find(item => item.symbol === cleanSymbol)
      if (existing) {
        const totalQty = existing.quantity + qty
        const blendedAverage = ((existing.quantity * existing.averagePrice) + (qty * avg)) / totalQty
        return current.map(item => item.id === existing.id ? { ...item, quantity: totalQty, averagePrice: blendedAverage } : item)
      }
      return [...current, { id: `${cleanSymbol}-${Date.now()}`, symbol: cleanSymbol, quantity: qty, averagePrice: avg }]
    })
    setMessage(`${cleanSymbol} added to your portfolio.`)
    setReview(null)
    log('HOLDING_ADDED', `${cleanSymbol} · ${qty} @ ${money(avg)}`)
    void attachPrice(cleanSymbol)
    return true
  }

  function removeHolding(id: string) {
    const target = holdings.find(h => h.id === id)
    setHoldings(current => current.filter(item => item.id !== id))
    setReview(null)
    if (target) log('HOLDING_REMOVED', `${target.symbol} · ${target.quantity} shares`)
  }

  async function runPortfolioReview() {
    if (!holdings.length) {
      setMessage('Import or add at least one holding before running AI analysis.')
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
      if (!response.ok) throw new Error(result.error || 'AI portfolio review is not available yet.')
      setReview(result)
      log('AI_REVIEW', `AI stock assessment · ${holdings.length} holdings`)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'AI review failed.')
    } finally {
      setReviewBusy(false)
    }
  }

  function clearHoldings() {
    setHoldings([]); setSeries({}); setReview(null)
    log('PORTFOLIO_CLEARED', 'All holdings removed from this browser')
  }
  function clearRecorded() { setSnapshots([]); setActivity([]) }

  return { holdings, rows: view.rows, totals: view.totals, series, loaded, busy, message, setMessage, review, reviewBusy, snapshots, activity, importCsv, addHolding, removeHolding, runPortfolioReview, clearHoldings, clearRecorded, log }
}

export type PortfolioApi = ReturnType<typeof usePortfolio>
