
import { useEffect, useMemo, useState } from 'react'
import { FinancialResearch } from './FinancialResearch'
import type { ReactNode } from 'react'
import {
  compactMoney,
  dateLabel,
  fetchPrices,
  money,
  pct,
  returnSince,
  shiftDate,
  tone,
} from './api'
import type { Holding, Price, Review, Snapshot } from './api'
import { LineChart, trendColor } from './charts'
import { ActionBadge, Empty, Panel, Segmented, Soon, Stat } from './ui'

const RESEARCH_RANGES = [
  'Latest',
  '1D',
  '10D',
  '1M',
  '3M',
  '6M',
  '1Y',
  '3Y',
  '5Y',
  'ALL',
] as const

type ResearchRange = typeof RESEARCH_RANGES[number]

type ChartPoint = {
  date: string
  value: number
}

function Card({
                title,
                ai,
                children,
              }: {
  title: string
  ai?: boolean
  children: ReactNode
}) {
  return (
      <div className="r-card">
        <h3>
          {title}
          {ai && <span className="badge">AI report</span>}
        </h3>
        {children}
      </div>
  )
}

const List = ({ items }: { items: string[] }) => (
    <ul className="bullets">
      {items.map((item, index) => (
          <li key={index}>{item}</li>
      ))}
    </ul>
)

// Chart ranges use available imported daily closing prices.
function getChartPoints(
    prices: Price[],
    range: ResearchRange
): ChartPoint[] {
  const points = prices.map(price => ({
    date: price.tradingDate,
    value: price.close,
  }))

  if (range === 'ALL') return points
  if (range === 'Latest') return points.slice(-5)
  if (range === '1D') return points.slice(-2)
  if (range === '10D') return points.slice(-10)

  const days: Record<string, number> = {
    '1M': 30,
    '3M': 91,
    '6M': 182,
    '1Y': 365,
    '3Y': 1095,
    '5Y': 1826,
  }

  const lookback = days[range]

  if (!lookback || !points.length) return points

  const latestDate = points[points.length - 1].date
  const cutoff = shiftDate(latestDate, -lookback)

  const firstIndex = points.findIndex(point => point.date >= cutoff)

  return points.slice(firstIndex < 0 ? 0 : firstIndex)
}

// Trading-session returns:
// 1D = latest close vs previous available session.
// 10D = latest close vs close 10 available sessions earlier.
function returnBySessions(
    prices: Price[],
    sessions: number
): number | undefined {
  if (
      !Number.isInteger(sessions) ||
      sessions < 1 ||
      prices.length <= sessions
  ) {
    return undefined
  }

  const latest = prices[prices.length - 1]
  const previous = prices[prices.length - 1 - sessions]

  if (
      !latest ||
      !previous ||
      !Number.isFinite(latest.close) ||
      !Number.isFinite(previous.close) ||
      latest.close <= 0 ||
      previous.close <= 0
  ) {
    return undefined
  }

  return ((latest.close / previous.close) - 1) * 100
}

export default function Research({
                                   symbols,
                                   symbol,
                                   onSelect,
                                   analysis,
                                   review,
                                   holdings,
                                 }: {
  symbols: string[]
  symbol: string
  onSelect: (symbol: string) => void
  analysis: Snapshot | null
  review: Review | null
  holdings: Holding[]
}) {
  const [priceState, setPriceState] = useState<{
    symbol: string
    rows: Price[]
  }>({
    symbol: '',
    rows: [],
  })

  const [loadingSymbol, setLoadingSymbol] = useState('')
  const [range, setRange] = useState<ResearchRange>('1Y')
  const [query, setQuery] = useState('')

  // Keep price data associated with its symbol.
  // This prevents showing the previous stock's data after a symbol change.
  useEffect(() => {
    let cancelled = false

    if (!symbol) {
      setPriceState({ symbol: '', rows: [] })
      setLoadingSymbol('')
      return
    }

    setLoadingSymbol(symbol)

    void fetchPrices(symbol).then(rows => {
      if (cancelled) return

      setPriceState({
        symbol,
        rows,
      })

      setLoadingSymbol('')
    })

    return () => {
      cancelled = true
    }
  }, [symbol])

  // Only use prices belonging to the currently selected symbol.
  const prices =
      priceState.symbol === symbol ? priceState.rows : []

  const loading = Boolean(symbol) && loadingSymbol === symbol

  const stock = useMemo(() => {
    if (!prices.length) return null

    const latest = prices[prices.length - 1]
    const previous = prices[prices.length - 2]

    const ath = prices.reduce(
        (best, item) => item.high > best.high ? item : best,
        prices[0]
    )

    const atl = prices.reduce(
        (best, item) => item.low < best.low ? item : best,
        prices[0]
    )

    const yearStart = shiftDate(latest.tradingDate, -365)

    const yearPrices = prices.filter(
        price => price.tradingDate >= yearStart
    )

    const dayReturn =
        previous &&
        previous.close > 0 &&
        latest.close > 0
            ? ((latest.close / previous.close) - 1) * 100
            : undefined

    return {
      latest,
      previous,
      ath,
      atl,
      dayReturn,
      high52: yearPrices.length
          ? Math.max(...yearPrices.map(price => price.high))
          : undefined,
      low52: yearPrices.length
          ? Math.min(...yearPrices.map(price => price.low))
          : undefined,
    }
  }, [prices])

  // Monthly seasonality based on consecutive imported calendar months.
  const monthly = useMemo(() => {
    const grouped = new Map<string, Price[]>()

    prices.forEach(price => {
      const key = price.tradingDate.slice(0, 7)
      const existing = grouped.get(key) || []

      grouped.set(key, [...existing, price])
    })

    const byMonth = Array.from(
        { length: 12 },
        (_, index) => ({
          month: new Date(2000, index, 1).toLocaleString(
              'en',
              { month: 'short' }
          ),
          values: [] as number[],
        })
    )

    const months = [...grouped.entries()].sort(
        ([a], [b]) => a.localeCompare(b)
    )

    for (let index = 1; index < months.length; index++) {
      const [currentKey, currentPrices] = months[index]
      const [previousKey, previousPrices] = months[index - 1]

      const previousDate = new Date(
          `${previousKey}-01T00:00:00`
      )

      previousDate.setMonth(previousDate.getMonth() + 1)

      const expectedNextMonth =
          `${previousDate.getFullYear()}-${String(
              previousDate.getMonth() + 1
          ).padStart(2, '0')}`

      if (expectedNextMonth !== currentKey) continue

      const previousClose =
          previousPrices[previousPrices.length - 1]?.close

      const currentClose =
          currentPrices[currentPrices.length - 1]?.close

      if (
          !previousClose ||
          !currentClose ||
          previousClose <= 0
      ) {
        continue
      }

      const monthIndex = Number(currentKey.slice(5, 7)) - 1

      byMonth[monthIndex].values.push(
          ((currentClose / previousClose) - 1) * 100
      )
    }

    return byMonth.map(month => ({
      ...month,
      average: month.values.length
          ? month.values.reduce((sum, value) => sum + value, 0) /
          month.values.length
          : undefined,
    }))
  }, [prices])

  const chartPoints = useMemo(
      () => getChartPoints(prices, range),
      [prices, range]
  )

  const ai = review?.holdings?.find(
      holding => holding.symbol === symbol
  )

  const screening = analysis?.recommendations?.find(
      recommendation => recommendation.symbol === symbol
  )

  const holding = holdings.find(
      item => item.symbol === symbol
  )

  const matches = query
      ? symbols
          .filter(item => item.startsWith(query.toUpperCase()))
          .slice(0, 40)
      : []

  function handleSearch(value: string) {
    setQuery(value)

    const selected = value.trim().toUpperCase()

    if (symbols.includes(selected)) {
      onSelect(selected)
      setQuery('')
    }
  }

  if (!symbols.length) {
    return (
        <Panel>
          <Empty icon="⌕" title="No market data imported">
            Import NSE data to research individual stocks.
          </Empty>
        </Panel>
    )
  }

  const historicalReturns = [
    {
      label: '1 day',
      value: returnBySessions(prices, 1),
    },
    {
      label: '10 trading days',
      value: returnBySessions(prices, 10),
    },
    {
      label: '1 month',
      value: returnSince(prices, 30),
    },
    {
      label: '3 months',
      value: returnSince(prices, 91),
    },
    {
      label: '6 months',
      value: returnSince(prices, 182),
    },
    {
      label: '1 year',
      value: returnSince(prices, 365),
    },
    {
      label: '3 years',
      value: returnSince(prices, 1095),
    },
    {
      label: '5 years',
      value: returnSince(prices, 1826),
    },
  ]

  return (
      <div className="stack-y">

        {/* STOCK HEADER */}
        <Panel>
          <div className="r-head">
            <div>
              <h2 className="r-symbol">
                {symbol || 'Stock research'}
              </h2>

              <p className="muted">
                NSE · Imported daily market data
                {holding &&
                    ` · You hold ${holding.quantity} @ ${money(
                        holding.averagePrice
                    )}`}
              </p>
            </div>

            <div className="r-controls">
              <div>
                <input
                    list="research-symbols"
                    placeholder="Search symbol…"
                    value={query}
                    onChange={event =>
                        handleSearch(event.target.value)
                    }
                    aria-label="Search symbol"
                />

                <datalist id="research-symbols">
                  {matches.map(item => (
                      <option key={item} value={item} />
                  ))}
                </datalist>
              </div>

              <div className="r-price">
                <strong>
                  {stock ? money(stock.latest.close) : '—'}
                </strong>

                <span className={tone(stock?.dayReturn)}>
                {pct(stock?.dayReturn, 2)}

                  <small>
                  vs previous close · Latest available close:{' '}
                    {stock
                        ? dateLabel(stock.latest.tradingDate)
                        : '—'}
                </small>
              </span>
              </div>
            </div>
          </div>
        </Panel>

        {/* PRICE STATISTICS */}
        <div className="stat-grid">
          <Stat
              label="52-week high"
              value={
                stock?.high52
                    ? money(stock.high52)
                    : '—'
              }
              sub="Within imported data"
          />

          <Stat
              label="52-week low"
              value={
                stock?.low52
                    ? money(stock.low52)
                    : '—'
              }
              sub="Within imported data"
          />

          <Stat
              label="High in imported data"
              value={
                stock
                    ? money(stock.ath.high)
                    : '—'
              }
              sub={
                stock
                    ? `${dateLabel(stock.ath.tradingDate)} · ${pct(
                        ((stock.latest.close / stock.ath.high) - 1) * 100
                    )} away`
                    : 'No data'
              }
          />

          <Stat
              label="Low in imported data"
              value={
                stock
                    ? money(stock.atl.low)
                    : '—'
              }
              sub={
                stock
                    ? dateLabel(stock.atl.tradingDate)
                    : 'No data'
              }
          />

          <Stat
              label="Imported sessions"
              value={prices.length.toLocaleString('en-IN')}
              sub="Coverage may be incomplete"
          />
        </div>

        {/* PRICE HISTORY AND RETURNS */}
        <div className="grid-2-1">

          <Panel
              title="Price history"
              subtitle="Daily closing prices from your imported NSE data"
              actions={
                <Segmented
                    small
                    options={RESEARCH_RANGES}
                    value={range}
                    onChange={setRange}
                />
              }
          >
            {loading ? (
                <div
                    className="chart-empty"
                    style={{ height: 280 }}
                >
                  Loading price history…
                </div>
            ) : chartPoints.length > 1 ? (
                <>
                  <LineChart
                      height={300}
                      format={compactMoney}
                      lines={[
                        {
                          name: 'Close',
                          color: trendColor(chartPoints),
                          points: chartPoints,
                        },
                      ]}
                  />

                  <p className="fineprint">
                    {range === 'Latest'
                        ? 'Showing the last 5 available trading sessions.'
                        : range === '1D'
                            ? 'Showing the latest and previous available trading sessions.'
                            : range === '10D'
                                ? 'Showing the last 10 available trading sessions.'
                                : range === 'ALL'
                                    ? 'Showing all available imported daily closing prices.'
                                    : `Showing imported daily closing prices for ${range}.`}
                  </p>
                </>
            ) : (
                <Empty title="Not enough price history">
                  {prices.length === 1
                      ? 'Only one closing price is available. At least two sessions are needed for a chart.'
                      : 'No price history is available for this stock and range.'}
                </Empty>
            )}
          </Panel>

          <Panel
              title="Historical returns"
              subtitle="Calculated from available imported closing prices"
          >
            {loading ? (
                <div className="chart-empty">
                  Loading historical returns…
                </div>
            ) : (
                <table className="table compact">
                  <tbody>
                  {historicalReturns.map(({ label, value }) => (
                      <tr key={label}>
                        <td>{label}</td>
                        <td className={`r strong ${tone(value)}`}>
                          {pct(value)}
                        </td>
                      </tr>
                  ))}
                  </tbody>
                </table>
            )}

            <p className="fineprint">
              1D and 10D use trading sessions, excluding weekends
              and market holidays. Longer periods retain the
              existing calendar-day lookback. Price return only.
              Corporate-action adjustments are not yet applied.
            </p>
          </Panel>
        </div>

        {/* AI OUTLOOK AND SEASONALITY */}
        <div className="grid-2">

          <Panel
              title="AI stock outlook"
              subtitle="From the latest AI stock assessment"
          >
            {ai ? (
                <div className="stack-y tight">
                  <div className="verdict">
                    <div>
                      <small>Outlook</small>
                      <b>{ai.stockOutlook || 'Unavailable'}</b>
                    </div>

                    <div>
                      <small>Assessment</small>
                      <ActionBadge action={ai.action} />
                    </div>

                    <div>
                      <small>Confidence</small>
                      <b>{ai.confidence || '—'}</b>
                    </div>
                  </div>

                  {ai.reasoning ? (
                      <p className="prose">{ai.reasoning}</p>
                  ) : (
                      <p className="muted">
                        No reasoning was returned for this stock.
                      </p>
                  )}
                </div>
            ) : (
                <Soon title="No AI assessment for this stock">
                  Add {symbol || 'this stock'} to your portfolio
                  and run the AI stock assessment to see the outlook,
                  reasoning and HOLD / REVIEW / EXIT view here.
                </Soon>
            )}
          </Panel>

          <Panel
              title="Monthly seasonality"
              subtitle="Average month-to-month closing change. Descriptive, not predictive."
          >
            <div className="months">
              {monthly.map(month => (
                  <div
                      key={month.month}
                      className={`month ${
                          month.average === undefined
                              ? ''
                              : month.average >= 0
                                  ? 'good'
                                  : 'bad'
                      }`}
                  >
                    <b>{month.month}</b>
                    <span className={tone(month.average)}>
                  {pct(month.average)}
                </span>
                    <small>{month.values.length} samples</small>
                  </div>
              ))}
            </div>

            <p className="fineprint">
              Small samples can be misleading.
            </p>
          </Panel>
        </div>

        {/* FINANCIAL RESEARCH FROM FINCRUX */}
        <FinancialResearch symbol={symbol} />

        {/* AI-POWERED COMPANY ANALYSIS */}
        <h3 className="section-title">
          AI-powered company analysis
        </h3>

        <div className="r-grid">

          <Card
              title="Business quality and competitive position"
              ai={!!ai?.businessQuality}
          >
            {ai?.businessQuality ? (
                <p>{ai.businessQuality}</p>
            ) : (
                <Soon title="Business quality">
                  An AI assessment will evaluate competitive position,
                  business strength and industry standing.
                </Soon>
            )}
          </Card>

          <Card
              title="AI valuation assessment"
              ai={!!ai?.valuationView}
          >
            {ai?.valuationView ? (
                <p>{ai.valuationView}</p>
            ) : (
                <Soon title="Valuation assessment">
                  An AI assessment will interpret valuation metrics
                  and compare them with business performance.
                </Soon>
            )}
          </Card>

          <Card
              title="Growth drivers"
              ai={!!ai?.growthDrivers?.length}
          >
            {ai?.growthDrivers?.length ? (
                <List items={ai.growthDrivers} />
            ) : (
                <Soon title="Growth drivers">
                  Key business and industry growth opportunities
                  will appear after an AI assessment.
                </Soon>
            )}
          </Card>

          <Card
              title="Business risks"
              ai={!!ai?.risks?.length}
          >
            {ai?.risks?.length ? (
                <List items={ai.risks} />
            ) : (
                <Soon title="Business risks">
                  Important business, financial and industry risks
                  will appear after an AI assessment.
                </Soon>
            )}
          </Card>

          <Card title="Screening signals">
            {screening ? (
                <div>
                  <p>
                    Research score{' '}
                    <b>{screening.researchScore}/100</b>
                    {' · '}
                    {screening.trend}
                    {screening.setup
                        ? ` · ${screening.setup}`
                        : ''}
                  </p>

                  <p className="muted">
                    {screening.reason}
                  </p>
                </div>
            ) : (
                <Soon title="Not in the latest screening">
                  This stock was not among the latest market
                  analysis candidates.
                </Soon>
            )}
          </Card>
        </div>

        {ai?.dataGaps?.length ? (
            <Panel title="Missing information">
              <List items={ai.dataGaps} />
            </Panel>
        ) : null}

        <p className="fineprint">
          Historical data is not a forecast or investment
          recommendation. Scenario analysis needs benchmark and
          macroeconomic datasets and is not available yet.
        </p>
      </div>
  )
}
