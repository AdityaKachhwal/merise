import { money, pct, tone } from './api'
import type { Snapshot } from './api'
import { Empty } from './ui'

const Bar = ({ v }: { v?: number }) => <span className="mini-bar"><i style={{ width: `${Math.max(0, Math.min(100, v ?? 0))}%` }} /></span>

export function RecommendationPanel({ analysis, title, subtitle, loading = false, collapsed = false, onToggle, onOpenStock }: {
  analysis: Snapshot | null; title: string; subtitle?: string; loading?: boolean; collapsed?: boolean; onToggle?: () => void; onOpenStock?: (symbol: string) => void
}) {
  const recs = analysis?.recommendations ?? []
  return <section className="panel">
    <div className="panel-head">
      <div><h2>{title}</h2><p className="sub">{subtitle || 'Up to 5 rule-based swing candidates'}</p></div>
      {onToggle && <button type="button" className="icon-btn" aria-expanded={!collapsed} onClick={onToggle} title={collapsed ? 'Expand saved report' : 'Collapse saved report'}>{collapsed ? '⌄' : '⌃'}</button>}
    </div>
    {!collapsed && <div className="panel-body">
      {loading && <div className="notice">Loading saved analysis…</div>}
      {analysis && recs.length > 0 ? <div className="rec-list">{recs.map((item, index) => <details className="rec" key={`${analysis.runId}-${item.symbol}`}>
        <summary>
          <div className="rec-row">
            <div className="rec-name"><span className="rank">{index + 1}</span><b>{item.symbol}</b><small>{item.momentumDirection ?? 'POSITIVE'}</small></div>
            <div><small>Price</small><span>{money(item.latestClose)}</span></div>
            <div><small>5D</small><span className={tone(item.return5d)}>{pct(item.return5d)}</span></div>
            <div><small>20D</small><span className={tone(item.return20d)}>{pct(item.return20d)}</span></div>
            <div><small>60D</small><span className={tone(item.return60d)}>{pct(item.return60d)}</span></div>
            <div><small>Research score</small><span className="score">{item.researchScore}/100</span></div>
            <div><small>Trend</small><span>{item.trend}</span></div>
            <span className="chev" aria-hidden="true">⌄</span>
          </div>
        </summary>
        <div className="rec-detail">
          <div><h4>Why it surfaced</h4><p>{item.reason}</p></div>
          <div><h4>Risk assessment</h4><p>{item.risk}</p></div>
          <div><h4>Technical indicators</h4>
            <p>Price vs SMA20: {item.sma20 > 0 ? pct(((item.latestClose / item.sma20) - 1) * 100) : '—'} · RSI14: {item.rsi14 === undefined ? '—' : item.rsi14.toFixed(1)} · ATR14: {pct(item.atr14Percent)}</p>
            <p>Volume strength: {item.volumeStrength.toFixed(2)}x · Avg daily traded value: {item.averageDailyTradedValue20 ? money(item.averageDailyTradedValue20) : '—'}</p></div>
          <div><h4>Score breakdown</h4>
            {([['Momentum', item.momentumScore], ['Trend', item.trendScore], ['Volume', item.volumeScore], ['Risk', item.riskScore], ['Liquidity', item.liquidityScore]] as const).map(([l, v]) =>
              <div className="score-row" key={l}><span>{l}</span><Bar v={v} /><em>{v ?? '—'}</em></div>)}
            <p className="muted">The research score measures the strength of available screening signals. It is not a probability of profit.</p></div>
          <div className="rec-wide"><h4>Company and financial research</h4>
            <p>Not available yet. This section will include business details, revenue and profit trends, debt, valuation, announcements and news once source-backed research is integrated. No company fundamentals or news have been assessed in this report.</p>
            {onOpenStock && <button type="button" className="btn ghost small" onClick={() => onOpenStock(item.symbol)}>Open {item.symbol} research</button>}</div>
        </div>
      </details>)}</div>
        : <Empty icon="↗" title={analysis ? 'No stocks passed the initial filters' : 'No analysis run yet'}>{analysis?.note || 'Click Run Market Analysis to screen the imported NSE price history. MineVest returns up to five candidates only when the available evidence meets its initial criteria.'}</Empty>}
    </div>}
  </section>
}
