// Shared API constant, response types and pure helpers.
// Endpoints, request bodies and response shapes are unchanged from the original app.
export const API = 'http://localhost:8083/api'

export type Recommendation = { symbol: string; latestClose: number; researchScore: number; return5d: number; return20d: number; return60d: number; sma20: number; sma50: number; volumeStrength: number; volatility20d: number; drawdown60d: number; trend: string; reason: string; risk: string; rsi14?: number; atr14Percent?: number; averageDailyTradedValue20?: number; momentumScore?: number; trendScore?: number; volumeScore?: number; riskScore?: number; liquidityScore?: number; setup?: string }
export type Snapshot = { runId: string; createdAt: string; status: string; importedPriceRows: number; distinctSymbols: number; note: string; recommendations?: Recommendation[] }
export type Price = { tradingDate: string; symbol: string; series: string; open: number; high: number; low: number; close: number; volume: number }


export type MarketNews = {
  id: number
  symbol: string
  headline: string
  summary?: string
  source: string
  url: string
  publishedAt: string
  sentimentTag?: string
}

export async function fetchMarketNews(): Promise<MarketNews[]> {
  try {
    const response = await fetch(`${API}/news/market`)

    if (!response.ok) return []

    const result = await response.json()
    return Array.isArray(result) ? result : []
  } catch {
    return []
  }
}


export type Holding = { id: string; symbol: string; quantity: number; averagePrice: number; currentPrice?: number; updatedAt?: string }
export type ReviewHolding = {
  symbol: string
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

  // Decision evidence
  assetType?: string
  decisionRationale?: string
  monitoringPoints?: string[]

  // Price and freshness
  priceFreshness?: string
  latestTradingDate?: string | null
  assessedPrice?: number | null
  priceSource?: string

  // Score breakdown
  technicalScore?: number | null
  technicalScoreMax?: number
  fundamentalScore?: number | null
  fundamentalScoreMax?: number
  fundamentalMetricsAvailable?: number
  newsScore?: number | null
  newsScoreMax?: number
  availableScore?: number
  availableScoreMax?: number
  overallScore?: number | null
  scoreStatus?: string

  // Additional evidence
  return20dPct?: number | null
  drawdown60dPct?: number | null
  unrealisedReturnPct?: number | null
  positiveNewsCount?: number
  negativeNewsCount?: number
}
export type Review = {
  summary?: string
  holdings?: ReviewHolding[]
  portfolioRisks?: string[]
  alternatives?: Array<{ symbol: string; rationale: string }>
  disclaimer?: string
}

export const money = (value: number) => `₹${Number(value || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
export const signedMoney = (v: number) => `${v >= 0 ? '+' : '−'}${money(Math.abs(v))}`
export const compactMoney = (v: number) => {
  const a = Math.abs(v), s = v < 0 ? '−' : ''
  if (a >= 1e7) return `${s}₹${(a / 1e7).toFixed(2)} Cr`
  if (a >= 1e5) return `${s}₹${(a / 1e5).toFixed(2)} L`
  return `${s}${money(a)}`
}
export const dateLabel = (value?: string) => value ? new Date(`${value.slice(0, 10)}T00:00:00`).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—'
export const pct = (value?: number, digits = 1) => value === undefined || !Number.isFinite(value) ? '—' : `${value >= 0 ? '+' : ''}${value.toFixed(digits)}%`
export const tone = (v?: number) => v === undefined || !Number.isFinite(v) || v === 0 ? '' : v > 0 ? 'pos' : 'neg'

export const RANGES = ['1D', '1W', '1M', '3M', '6M', '1Y', 'ALL'] as const
export type Range = typeof RANGES[number]
export const STOCK_RANGES = ['1M', '3M', '6M', '1Y', '3Y', '5Y', 'ALL'] as const
export type StockRange = typeof STOCK_RANGES[number]

export function shiftDate(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00`)
  d.setDate(d.getDate() + days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const DAYS: Record<string, number> = { '1W': 7, '1M': 30, '3M': 91, '6M': 182, '1Y': 365, '3Y': 1095, '5Y': 1826 }
export function sliceRange<T extends { date: string }>(pts: T[], range: string): T[] {
  if (range === 'ALL' || !pts.length) return pts
  if (range === '1D') return pts.slice(-2)
  const days = DAYS[range]
  if (!days) return pts
  const cut = shiftDate(pts[pts.length - 1].date, -days)
  const i = pts.findIndex(p => p.date >= cut)
  return pts.slice(i < 0 ? 0 : i)
}

export const sortPrices = (p: Price[]) => [...p].sort((a, b) => String(a.tradingDate).localeCompare(String(b.tradingDate)))

// Same endpoint and response model as before: GET /market-data/prices?symbol=
export async function fetchPrices(symbol: string): Promise<Price[]> {
  try {
    const response = await fetch(`${API}/market-data/prices?symbol=${encodeURIComponent(symbol)}`)
    if (!response.ok) return []
    const prices = await response.json()
    return Array.isArray(prices) ? sortPrices(prices as Price[]) : []
  } catch {
    return []
  }
}

export function lastClose(prices?: Price[]): number | undefined {
  const v = Number(prices?.[prices.length - 1]?.close)
  return Number.isFinite(v) && v > 0 ? v : undefined
}

// Return over `days` calendar days. Only shown when imported history actually covers the period.
export function returnSince(p: Price[], days: number): number | undefined {
  if (p.length < 2) return undefined
  const latest = p[p.length - 1]
  const cutoff = shiftDate(latest.tradingDate, -days)
  if (p[0].tradingDate > shiftDate(cutoff, 10)) return undefined
  const prior = p.find(x => x.tradingDate >= cutoff)
  return prior && prior.close ? ((latest.close / prior.close) - 1) * 100 : undefined
}
