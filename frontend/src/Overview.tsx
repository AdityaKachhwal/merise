
import { useEffect, useState } from 'react'
import { fetchPrices, pct, tone } from './api'
import type { Price, Snapshot } from './api'
import { Sparkline } from './charts'
import { RecommendationPanel } from './Recommendations'

export function IndexCard({
                              label,
                              aliases,
                              symbols,
                          }: {
    label: string
    aliases: string[]
    symbols: string[]
}) {
    const found = symbols.find(s =>
        aliases.includes(s.toUpperCase().replace(/[^A-Z0-9]/g, ''))
    )

    const [prices, setPrices] = useState<Price[]>([])

    useEffect(() => {
        let cancelled = false

        if (found) {
            void fetchPrices(found).then(p => {
                if (!cancelled) setPrices(p)
            })
        }

        return () => {
            cancelled = true
        }
    }, [found])

    if (!found) {
        return (
            <div className="index-card">
                <div>
                    <b>{label}</b>
                    <span className="soon-tag">Not available yet</span>
                </div>
                <p className="muted">
                    Index levels are not part of the imported NSE equity data.
                </p>
            </div>
        )
    }

    const last = prices[prices.length - 1]
    const prev = prices[prices.length - 2]

    const change =
        last && prev && prev.close
            ? ((last.close / prev.close) - 1) * 100
            : undefined

    return (
        <div className="index-card">
            <div>
                <b>{label}</b>
                <small>{found}</small>
            </div>

            <div className="index-val">
                <strong>
                    {last
                        ? last.close.toLocaleString('en-IN', {
                            maximumFractionDigits: 2,
                        })
                        : '—'}
                </strong>
                <span className={tone(change)}>{pct(change, 2)}</span>
            </div>

            <Sparkline values={prices.slice(-60).map(p => p.close)} />
        </div>
    )
}

export default function Overview({
                                   symbols,
                                   analysis,
                                   onOpenStock,
                                 }: {
  symbols: string[]
  analysis: Snapshot | null
  onOpenStock: (symbol: string) => void
}) {
  const [search, setSearch] = useState('')

  const matches = search.trim()
      ? symbols
          .filter(s => s.startsWith(search.trim().toUpperCase()))
          .slice(0, 40)
      : []

  const handleSearch = (value: string) => {
    setSearch(value)

    const symbol = value.trim().toUpperCase()
    if (symbols.includes(symbol)) {
      setSearch('')
      onOpenStock(symbol)
    }
  }

  const topFiveAnalysis = analysis
      ? {
        ...analysis,
        recommendations: (analysis.recommendations ?? []).slice(0, 5),
      }
      : null

  return (
      <div className="stack-y">
        <RecommendationPanel
            analysis={topFiveAnalysis}
            title="Top 5 Stock Recommendations"
            subtitle="Latest completed market analysis · up to 5 qualifying stocks"
            onOpenStock={onOpenStock}
        />

        <section className="panel">
          <div className="panel-head">
            <div>
              <h2>Search any stock</h2>
              <p className="sub">
                Find an NSE-listed stock and open its detailed research.
              </p>
            </div>
          </div>

          <div className="panel-body">
            <input
                className="search"
                style={{ width: '100%', maxWidth: 520 }}
                list="overview-stock-symbols"
                placeholder="Enter stock symbol, e.g. RELIANCE"
                value={search}
                onChange={e => handleSearch(e.target.value)}
                aria-label="Search any NSE stock"
            />

            <datalist id="overview-stock-symbols">
              {matches.map(symbol => (
                  <option key={symbol} value={symbol} />
              ))}
            </datalist>

            {!symbols.length && (
                <p className="muted">
                  No stock symbols are currently available. Import NSE data from
                  Settings when you are ready.
                </p>
            )}

            {search.trim() && matches.length === 0 && (
                <p className="muted">
                  No matching symbol found.
                </p>
            )}
          </div>
        </section>
      </div>
  )
}
