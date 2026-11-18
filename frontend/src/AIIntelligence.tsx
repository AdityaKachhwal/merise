import { useState } from 'react'
import type { ReactNode } from 'react'
import { money, pct, signedMoney, tone } from './api'
import type { ReviewHolding, Snapshot } from './api'
import { RecommendationPanel } from './Recommendations'
import { ActionBadge, Empty, Panel, Segmented } from './ui'
import type { HoldingRow, PortfolioApi } from './usePortfolio'

const level = (c?: string) => { const s = (c || '').toLowerCase(); return s.includes('high') ? 3 : s.includes('med') || s.includes('mod') ? 2 : s.includes('low') ? 1 : 0 }
const List = ({ items }: { items: string[] }) => <ul className="bullets">{items.map((x, i) => <li key={i}>{x}</li>)}</ul>
const Section = ({ title, children, wide = false }: { title: string; children: ReactNode; wide?: boolean }) => <div className={`r-card ${wide ? 'wide' : ''}`}><h3>{title}</h3>{children}</div>

const scoreLabel = (score?: number | null, max?: number) => {
  if (score === null || score === undefined || max === undefined || max <= 0) {
    return 'Not available'
  }
  return `${score} / ${max}`
}

const priceSourceLabel = (source?: string) => {
  switch (source) {
    case 'PORTFOLIO_SUPPLIED_PRICE':
      return 'Portfolio supplied price'
    case 'LAST_STORED_EOD_CLOSE':
      return 'Last stored EOD close'
    case 'UNAVAILABLE':
      return 'Unavailable'
    default:
      return source || 'Unknown'
  }
}

const ScoreRow = ({ label, score, max }: {
  label: string
  score?: number | null
  max?: number
}) => (
    <div className="holding-facts">
    <span>
      <small>{label}</small>
      <b>{scoreLabel(score, max)}</b>
    </span>
    </div>
)


function StockReport({ item, row, onOpen }: { item: ReviewHolding; row?: HoldingRow; onOpen: (s: string) => void }) {
  const lv = level(item.confidence)
  return <article className="report">
    <header className="report-head">
      <div><h2>{item.symbol}</h2>
        <div className="muted">
          {item.assetType || 'Asset type unavailable'}
        </div>
        <button className="link" onClick={() => onOpen(item.symbol)}>Open price research</button></div>
      <div className="verdict">
        <div><small>Stock outlook</small><b>{item.stockOutlook || 'Unavailable'}</b></div>
        <div><small>Assessment</small><ActionBadge action={item.action} /></div>
        <div><small>Confidence</small><span className="conf">{[1, 2, 3].map(i => <i key={i} className={i <= lv ? 'on' : ''} />)}<b>{item.confidence || '—'}</b></span></div>
        <div><small>Fundamental data</small><b>{item.fundamentalDataStatus || 'Not verified'}</b></div>
      </div>
    </header>
    <div className="r-grid">
      {item.businessOverview && <Section title="Business overview">{<p>{item.businessOverview}</p>}</Section>}
      {item.businessQuality && <Section title="Business quality and competitive position"><p>{item.businessQuality}</p></Section>}
      {item.growthDrivers?.length ? <Section title="Growth drivers"><List items={item.growthDrivers} /></Section> : null}
      {item.valuationView && <Section title="Valuation"><p>{item.valuationView}</p></Section>}
      {item.reasoning && <Section title="Investment outlook and reasoning" wide><p className="prose">{item.reasoning}</p></Section>}

      {item.decisionRationale && (
          <Section title="Why MineVest recommends this action" wide>
            <p className="prose">{item.decisionRationale}</p>
          </Section>
      )}

      <Section title="Market price and data freshness" wide>
        <div className="holding-facts">
    <span>
      <small>Assessed price</small>
      <b>{item.assessedPrice != null ? money(item.assessedPrice) : '—'}</b>
    </span>
          <span>
      <small>Price source</small>
      <b>{priceSourceLabel(item.priceSource)}</b>
    </span>
          <span>
      <small>Latest trading date</small>
      <b>{item.latestTradingDate || '—'}</b>
    </span>
          <span>
      <small>Price freshness</small>
      <b>{item.priceFreshness || 'Unknown'}</b>
    </span>
        </div>
        <p className="muted">
          The assessed price and latest stored trading date are shown separately.
          A stored EOD close should not be interpreted as a live quote.
        </p>
      </Section>

      <Section title="Research score coverage" wide>
        <div className="holding-facts">
    <span>
      <small>Technical score</small>
      <b>{scoreLabel(item.technicalScore, item.technicalScoreMax)}</b>
    </span>
          <span>
      <small>Fundamental score</small>
      <b>{scoreLabel(item.fundamentalScore, item.fundamentalScoreMax)}</b>
    </span>
          <span>
      <small>News score</small>
      <b>{scoreLabel(item.newsScore, item.newsScoreMax)}</b>
    </span>
          <span>
      <small>Overall score</small>
      <b>
        {item.overallScore != null
            ? item.overallScore
            : 'Not calculated'}
      </b>
    </span>
          <span>
      <small>Available evidence</small>
      <b>
        {item.availableScore ?? 0} / {item.availableScoreMax ?? 0}
      </b>
    </span>
          <span>
      <small>Fundamental metrics</small>
      <b>{item.fundamentalMetricsAvailable ?? 0} / 4</b>
    </span>
        </div>
        <p className="muted">
          Score status: {item.scoreStatus || 'Unknown'}.
          An incomplete score is intentionally not presented as a complete rating.
        </p>
      </Section>

      {item.monitoringPoints?.length ? (
          <Section title="What to monitor next" wide>
            <List items={item.monitoringPoints} />
          </Section>
      ) : null}

      {item.risks?.length ? <Section title="Business and investment risks"><List items={item.risks} /></Section> : null}
      {item.dataGaps?.length ? <Section title="Missing information"><List items={item.dataGaps} /></Section> : null}
    </div>
    <div className="holding-box">
      <h3>Your holding</h3>
      {row ? <div className="holding-facts">
        <span><small>Quantity</small><b>{row.holding.quantity}</b></span>
        <span><small>Average price</small><b>{money(row.holding.averagePrice)}</b></span>
        <span><small>Current price</small><b>{row.holding.currentPrice ? money(row.holding.currentPrice) : '—'}</b></span>
        <span><small>P&L</small><b className={tone(row.pnl)}>{signedMoney(row.pnl)} ({pct(row.pnlPct, 2)})</b></span>
        <span><small>Portfolio weight</small><b>{row.weight.toFixed(1)}%</b></span>
      </div> : null}
      {item.holdingAnalysis ? <p>{item.holdingAnalysis}</p> : <p className="muted">No holding-specific analysis was returned.</p>}
    </div>
  </article>
}

export default function AIIntelligence({ portfolio, analysis, busy, rows, onRun, onOpenStock, go }: {
  portfolio: PortfolioApi; analysis: Snapshot | null; busy: boolean; rows: number; onRun: () => void; onOpenStock: (s: string) => void; go: (p: 'portfolio') => void
}) {
  const [tab, setTab] = useState<'Stock reports' | 'Market screening'>('Stock reports')
  const [active, setActive] = useState('')
  const { review, reviewBusy, holdings } = portfolio
  const items = review?.holdings ?? []
  const current = items.find(i => i.symbol === active) ?? items[0]

  return <div className="stack-y">
    <Segmented options={['Stock reports', 'Market screening'] as const} value={tab} onChange={setTab} />
    {tab === 'Market screening' ? <>
      <div className="hero"><p className="muted">Rule-based screening of your imported NSE price history. Up to five candidates, only when evidence meets the criteria.</p>
        <button className="btn primary" disabled={busy || rows === 0} onClick={onRun}>▶ {busy ? 'Analysing market…' : 'Run Market Analysis'}</button></div>
      <RecommendationPanel analysis={analysis} title="Top stock recommendations" subtitle="Latest completed market analysis · up to 5 candidates" onOpenStock={onOpenStock} />
    </> : <>
      <Panel title="AI stock assessment" subtitle="Each company is assessed independently of your portfolio allocation." actions={<button className="btn primary" disabled={reviewBusy || !holdings.length} onClick={() => { void portfolio.runPortfolioReview() }}>{reviewBusy ? 'Analysing stocks…' : '✦ Run AI stock assessment'}</button>}>
        {portfolio.message && <div className="notice">{portfolio.message}</div>}
        {!holdings.length && <Empty icon="₹" title="Add holdings to get AI reports">The assessment runs on the stocks in your portfolio.<div><button className="btn ghost" onClick={() => go('portfolio')}>Go to My Portfolio</button></div></Empty>}
        {holdings.length > 0 && !review && !reviewBusy && <Empty icon="✦" title="No report yet">Run the assessment to get one research report per holding: outlook, business quality, valuation, risks and missing information.</Empty>}
        {reviewBusy && <div className="notice">Researching {holdings.length} stock{holdings.length === 1 ? '' : 's'}. This can take a little while.</div>}
        {review?.summary && <p className="prose">{review.summary}</p>}
        {review?.portfolioRisks?.length ? <div className="r-card wide"><h3>Portfolio-level risks</h3><List items={review.portfolioRisks} /></div> : null}
      </Panel>
      {items.length > 0 && <>
        <div className="pills" role="tablist">{items.map(i => <button key={i.symbol} role="tab" aria-selected={i.symbol === current?.symbol} className={`pill ${i.symbol === current?.symbol ? 'on' : ''}`} onClick={() => setActive(i.symbol)}>{i.symbol}<ActionBadge action={i.action} /></button>)}</div>
        {current && <StockReport item={current} row={portfolio.rows.find(r => r.holding.symbol === current.symbol)} onOpen={onOpenStock} />}
      </>}
      {review?.alternatives?.length ? <Panel title="Alternatives to consider"><div className="alt-list">{review.alternatives.map(a => <div key={a.symbol}><button className="link strong" onClick={() => onOpenStock(a.symbol)}>{a.symbol}</button><p>{a.rationale}</p></div>)}</div></Panel> : null}
      {review && <p className="fineprint">{review.disclaimer || 'AI research is not a guaranteed forecast or an instruction to trade.'}</p>}
    </>}
  </div>
}
