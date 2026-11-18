import { useState } from 'react'
import { money, pct, signedMoney, tone } from './api'
import { StackBar } from './charts'
import { Empty, Panel, Stat } from './ui'
import type { PageId } from './ui'
import type { PortfolioApi } from './usePortfolio'

export default function PortfolioPage({ portfolio, onOpenStock, go }: { portfolio: PortfolioApi; onOpenStock: (s: string) => void; go: (p: PageId) => void }) {
  const { holdings, rows, totals, busy, message, review, reviewBusy } = portfolio
  const [symbol, setSymbol] = useState('')
  const [quantity, setQuantity] = useState('')
  const [averagePrice, setAveragePrice] = useState('')

  function add() {
    if (portfolio.addHolding(symbol, quantity, averagePrice)) { setSymbol(''); setQuantity(''); setAveragePrice('') }
  }

  return <div className="stack-y">
    <div className="stat-grid">
      <Stat big label="Current value" value={money(totals.current)} />
      <Stat label="Invested value" value={money(totals.invested)} />
      <Stat label="Unrealised P&L" value={signedMoney(totals.pnl)} tone={tone(totals.pnl)} sub={pct(totals.pnlPct, 2)} />
      <Stat label="Today's P&L" value={totals.todayCount ? signedMoney(totals.today) : '—'} tone={tone(totals.today)} sub={totals.todayCount ? pct(totals.todayPct, 2) : 'Needs two imported sessions'} />
    </div>
    {message && <div className="notice" role="status">{message}</div>}
    {holdings.length > 0 && totals.pricedValue < totals.current && <div className="notice warn">Some holdings have no matching imported market price. Their current value is temporarily shown at average cost.</div>}

    <div className="grid-2-1">
      <Panel title="Add a holding" subtitle="Manual entry. Re-adding a symbol blends the average price.">
        <div className="form-row">
          <input placeholder="Symbol e.g. CDSL" value={symbol} onChange={e => setSymbol(e.target.value)} aria-label="Symbol" />
          <input type="number" min="0" placeholder="Quantity" value={quantity} onChange={e => setQuantity(e.target.value)} aria-label="Quantity" />
          <input type="number" min="0" step="0.01" placeholder="Average price ₹" value={averagePrice} onChange={e => setAveragePrice(e.target.value)} aria-label="Average price" />
          <button className="btn primary" onClick={add}>Add stock</button>
        </div>
      </Panel>
      <Panel title="Import portfolio" subtitle="CSV with Symbol, Quantity and Average Price">
        <label className="btn ghost">{busy ? 'Importing…' : '↑ Import portfolio CSV'}<input type="file" accept=".csv,text/csv" disabled={busy} onChange={portfolio.importCsv} hidden /></label>
      </Panel>
    </div>

    {rows.length > 1 && <Panel title="Allocation" subtitle="Share of current value"><StackBar items={[...rows].sort((a, b) => b.current - a.current).map(r => ({ label: r.holding.symbol, value: r.current }))} /></Panel>}

    <Panel title={`Your holdings (${holdings.length})`} actions={<button className="btn primary" disabled={reviewBusy || !holdings.length} onClick={() => { void portfolio.runPortfolioReview() }}>{reviewBusy ? 'Analysing stocks…' : '✦ Run AI stock assessment'}</button>}>
      {review && <div className="notice good">AI assessment ready. <button className="link" onClick={() => go('ai')}>Open the stock reports</button></div>}
      {!holdings.length ? <Empty icon="＋" title="No holdings yet">Import a broker CSV or add your first stock manually.</Empty> : <div className="table-wrap"><table className="table">
        <thead><tr><th>Stock</th><th className="r">Qty</th><th className="r">Avg. price</th><th className="r">Current price</th><th className="r">Day</th><th className="r">Invested</th><th className="r">Current</th><th className="r">P&L</th><th className="r">Weight</th><th /></tr></thead>
        <tbody>{rows.map(r => <tr key={r.holding.id}>
          <td><button className="link strong" onClick={() => onOpenStock(r.holding.symbol)}>{r.holding.symbol}</button></td>
          <td className="r">{r.holding.quantity}</td>
          <td className="r">{money(r.holding.averagePrice)}</td>
          <td className="r">{r.holding.currentPrice ? money(r.holding.currentPrice) : <span className="muted">Price unavailable</span>}</td>
          <td className={`r ${tone(r.dayPct)}`}>{pct(r.dayPct, 2)}</td>
          <td className="r">{money(r.invested)}</td>
          <td className="r">{money(r.current)}</td>
          <td className={`r strong ${tone(r.pnl)}`}>{signedMoney(r.pnl)}<small>{pct(r.pnlPct, 2)}</small></td>
          <td className="r">{r.weight.toFixed(1)}%</td>
          <td className="r"><button className="link danger" onClick={() => portfolio.removeHolding(r.holding.id)} aria-label={`Remove ${r.holding.symbol}`}>Remove</button></td>
        </tr>)}</tbody></table></div>}
    </Panel>
    <p className="fineprint">CSV import expects Symbol, Quantity and Average Price columns. Holdings are stored in this browser. Live prices require the symbol to exist in MineVest's imported NSE data. Excel and CAS parsing can be added after validating the source formats.</p>
  </div>
}
