
import { useEffect, useState } from 'react'
import { fetchPrices, fetchMarketNews, pct, tone } from './api'
import type { Price, Snapshot, MarketNews } from './api'
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

function MarketNewsSection() {
    const [news, setNews] = useState<MarketNews[]>([])
    const [loading, setLoading] = useState(true)
    const [failed, setFailed] = useState(false)

    useEffect(() => {
        let cancelled = false

        async function loadNews() {
            setLoading(true)
            setFailed(false)

            try {
                const items = await fetchMarketNews()

                if (!cancelled) {
                    setNews(items)
                }
            } catch {
                if (!cancelled) setFailed(true)
            } finally {
                if (!cancelled) setLoading(false)
            }
        }

        void loadNews()

        return () => {
            cancelled = true
        }
    }, [])

    const dateLabel = (value: string) => {
        const date = new Date(value)

        if (Number.isNaN(date.getTime())) return ''

        return date.toLocaleString('en-IN', {
            day: '2-digit',
            month: 'short',
            hour: '2-digit',
            minute: '2-digit',
        })
    }

    return (
        <section className="panel market-news-panel">
            <div className="panel-head">
                <div>
                    <h2>Trending Market News</h2>
                    <p className="sub">
                        Latest Indian market stories across stocks, sectors and commodities
                    </p>
                </div>
                <span className="news-live">
                    <i />
                    Market updates
                </span>
            </div>

            <div className="panel-body">
                {loading ? (
                    <div className="news-empty">
                        Loading latest market news…
                    </div>
                ) : failed ? (
                    <div className="news-empty">
                        Market news is temporarily unavailable. Please try again later.
                    </div>
                ) : news.length === 0 ? (
                    <div className="news-empty">
                        No recent market headlines are available yet.
                        The feed will appear here after the first successful refresh.
                    </div>
                ) : (
                    <div className="market-news-grid">
                        {news.slice(0, 5).map((item, index) => (
                            <article className="market-news-card" key={item.id}>
                                <div className="news-card-top">
                                    <span className="news-number">
                                        {String(index + 1).padStart(2, '0')}
                                    </span>
                                    <span className="news-source">
                                        {item.source || 'Market news'}
                                    </span>
                                </div>

                                <h3>{item.headline}</h3>

                                <div className="news-card-bottom">
                                    <time dateTime={item.publishedAt}>
                                        {dateLabel(item.publishedAt)}
                                    </time>

                                    {item.url && (
                                        <a
                                            href={item.url}
                                            target="_blank"
                                            rel="noopener noreferrer"
                                        >
                                            Read article ↗
                                        </a>
                                    )}
                                </div>
                            </article>
                        ))}
                    </div>
                )}
            </div>
        </section>
    )
}

export default function Overview({
                                     symbols,
                                     analysis,
                                     onOpenStock,
                                     onRunAnalysis,
                                     busy,
                                 }: {
    symbols: string[]
    analysis: Snapshot | null
    onOpenStock: (symbol: string) => void
    onRunAnalysis: () => void
    busy: boolean
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

            {/* Top 5 recommendations heading and action */}
            <div
                className="panel-head"
                style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 16,
                    flexWrap: 'wrap',
                }}
            >
                <div>
                    <h2>Top 5 Stock Recommendations</h2>
                    <p className="sub">
                        Latest completed market analysis · up to 5 qualifying stocks
                    </p>
                </div>

                <button
                    className="btn ghost"
                    disabled={busy}
                    onClick={onRunAnalysis}
                >
                    {busy ? 'Analysing market…' : '▶ Run Market Analysis'}
                </button>
            </div>

            {/* Existing recommendation display */}
            <RecommendationPanel
                analysis={topFiveAnalysis}
                title="Top 5 Stock Recommendations"
                subtitle="Latest completed market analysis · up to 5 qualifying stocks"
                onOpenStock={onOpenStock}
            />

            {/* Stock search */}
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

            <MarketNewsSection />
        </div>
    )
}
