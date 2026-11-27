
import { useEffect, useState } from 'react'
import {
    fetchCompanyResearch,
    refreshCompanyResearch,
} from './api'
import type {
    FincruxResearch,
    StatementType,
} from './api'

function getFinancialData(result: FincruxResearch | null) {
    return result?.data?.data ?? null
}

function FinancialTable({
                            title,
                            rows,
                        }: {
    title: string
    rows?: string[][]
}) {
    if (!rows || rows.length < 2) return null

    const [headers, ...body] = rows

    return (
        <details className="r-card">
            <summary style={{ cursor: 'pointer', fontWeight: 700, padding: '4px' }}>
                {title}
            </summary>

            <div style={{ overflowX: 'auto', marginTop: 14 }}>
                <table className="table compact">
                    <thead>
                    <tr>
                        {headers.map((h, i) => (
                            <th key={i} style={{ whiteSpace: 'nowrap' }}>{h}</th>
                        ))}
                    </tr>
                    </thead>
                    <tbody>
                    {body.map((row, i) => (
                        <tr key={i}>
                            {row.map((cell, j) => (
                                <td key={j} style={{ whiteSpace: 'nowrap' }}>
                                    {cell || '—'}
                                </td>
                            ))}
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>
        </details>
    )
}

function RatioGrid({ ratios }: { ratios?: Record<string, string> }) {
    if (!ratios || !Object.keys(ratios).length) return null

    return (
        <div className="stat-grid">
            {Object.entries(ratios).map(([label, value]) => (
                <div className="stat" key={label}>
                    <span className="muted">{label}</span>
                    <strong>{value || '—'}</strong>
                </div>
            ))}
        </div>
    )
}

export function FinancialResearch({ symbol }: { symbol: string }) {
    const [result, setResult] = useState<FincruxResearch | null>(null)
    const [statementType, setStatementType] =
        useState<StatementType>('consolidated')
    const [loading, setLoading] = useState(false)
    const [refreshing, setRefreshing] = useState(false)
    const [error, setError] = useState('')

    async function loadResearch() {
        if (!symbol) return

        setLoading(true)
        setError('')

        const data = await fetchCompanyResearch(symbol, statementType)
        setResult(data)

        if (!data) {
            setError('Financial research is not available for this stock yet.')
        }

        setLoading(false)
    }

    async function handleRefresh() {
        if (!symbol) return

        setRefreshing(true)
        setError('')

        const data = await refreshCompanyResearch(symbol, statementType)

        if (data) {
            setResult(data)
        } else {
            setError('Unable to refresh financial data. Please try again.')
        }

        setRefreshing(false)
    }

    useEffect(() => {
        void loadResearch()
    }, [symbol, statementType])

    const financial = getFinancialData(result)
    const ratios = financial?.top_ratios

    return (
        <section className="stack-y">
            <div className="panel">
                <div className="panel-head">
                    <div>
                        <h2>Financial Research</h2>
                        <p className="sub">
                            Company financial statements and fundamental indicators · Source: Fincrux
                        </p>
                    </div>

                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                        <select
                            value={statementType}
                            onChange={e => setStatementType(e.target.value as StatementType)}
                            aria-label="Financial statement type"
                        >
                            <option value="consolidated">Consolidated</option>
                            <option value="standalone">Standalone</option>
                        </select>

                        <button
                            className="btn ghost"
                            onClick={handleRefresh}
                            disabled={refreshing || loading || !symbol}
                        >
                            {refreshing ? 'Refreshing…' : '↻ Refresh data'}
                        </button>
                    </div>
                </div>

                <div className="panel-body stack-y">
                    {loading && <p className="muted">Loading financial research…</p>}

                    {error && <p className="muted">{error}</p>}

                    {financial && (
                        <>
                            <div>
                                <h3>{result?.companyName || result?.symbol || symbol}</h3>
                                {financial.about && (
                                    <p className="prose">{financial.about}</p>
                                )}
                            </div>

                            <div>
                                <h3>Key financial indicators</h3>
                                <RatioGrid ratios={ratios} />
                            </div>

                            <div className="stack-y">
                                <h3>Financial statements and performance</h3>

                                <FinancialTable
                                    title="Quarterly results"
                                    rows={financial.quaterly_results}
                                />

                                <FinancialTable
                                    title="Annual profit and loss"
                                    rows={financial.profit_and_loss}
                                />

                                <FinancialTable
                                    title="Balance sheet"
                                    rows={financial.balance_sheet}
                                />

                                <FinancialTable
                                    title="Cash flow"
                                    rows={financial.cash_flows}
                                />

                                <FinancialTable
                                    title="Financial ratios"
                                    rows={financial.ratios}
                                />
                            </div>

                            <div className="stack-y">
                                <h3>Shareholding pattern</h3>

                                <FinancialTable
                                    title="Quarterly shareholding"
                                    rows={financial.shareholding_quarterly}
                                />

                                <FinancialTable
                                    title="Yearly shareholding"
                                    rows={financial.shareholding_yearly}
                                />
                            </div>

                            <p className="fineprint">
                                Source: {result?.source || 'Fincrux'} ·
                                Statement type: {result?.statementType || statementType} ·
                                Retrieved: {result?.fetchedAt
                                ? new Date(result.fetchedAt).toLocaleString('en-IN')
                                : '—'}
                                {' '}· Provider data date: {financial.last_updated_at || '—'}
                            </p>
                        </>
                    )}
                </div>
            </div>
        </section>
    )
}

export function FinancialResearchPreview({
                                             symbols,
                                             onOpenStock,
                                         }: {
    symbols: string[]
    onOpenStock: (symbol: string) => void
}) {
    const [items, setItems] = useState<
        Array<{ symbol: string; result: FincruxResearch | null }>
    >([])

    useEffect(() => {
        let cancelled = false

        async function load() {
            const selected = symbols.slice(0, 5)

            const results = await Promise.all(
                selected.map(async symbol => ({
                    symbol,
                    result: await fetchCompanyResearch(symbol),
                }))
            )

            if (!cancelled) setItems(results)
        }

        void load()

        return () => {
            cancelled = true
        }
    }, [symbols.join(',')])

    if (!symbols.length) return null

    return (
        <section className="panel">
            <div className="panel-head">
                <div>
                    <h2>Company & Financial Research</h2>
                    <p className="sub">
                        Fundamental indicators for your top recommended stocks
                    </p>
                </div>
            </div>

            <div className="panel-body">
                {items.length === 0 ? (
                    <p className="muted">Loading company financial data…</p>
                ) : (
                    <div className="r-grid">
                        {items.map(({ symbol, result }) => {
                            const financial = getFinancialData(result)
                            const ratios = financial?.top_ratios

                            return (
                                <article className="r-card" key={symbol}>
                                    <h3>{result?.companyName || symbol}</h3>

                                    {ratios ? (
                                        <div className="stack-y tight">
                                            <div>
                                                <small className="muted">Current price</small>
                                                <strong>{ratios['Current Price'] || '—'}</strong>
                                            </div>
                                            <div>
                                                <small className="muted">Market cap</small>
                                                <strong>{ratios['Market Cap'] || '—'}</strong>
                                            </div>
                                            <div>
                                                <small className="muted">P/E</small>
                                                <strong>{ratios['Stock P/E'] || '—'}</strong>
                                            </div>
                                            <div>
                                                <small className="muted">ROE</small>
                                                <strong>{ratios.ROE || '—'}</strong>
                                            </div>
                                            <div>
                                                <small className="muted">ROCE</small>
                                                <strong>{ratios.ROCE || '—'}</strong>
                                            </div>
                                        </div>
                                    ) : (
                                        <p className="muted">
                                            Financial research has not been fetched for this stock yet.
                                        </p>
                                    )}

                                    <button
                                        className="btn ghost"
                                        style={{ marginTop: 12 }}
                                        onClick={() => onOpenStock(symbol)}
                                    >
                                        View detailed research →
                                    </button>
                                </article>
                            )
                        })}
                    </div>
                )}
            </div>
        </section>
    )
}
