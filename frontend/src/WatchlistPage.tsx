import { useEffect, useState } from 'react'
import { fetchPrices, money, pct, returnSince, tone } from './api'
import type { Price } from './api'
import { Sparkline } from './charts'
import { Empty, Panel } from './ui'

const KEY = 'minevest.watchlist.v1'
const read = (): string[] => { try { const v = JSON.parse(localStorage.getItem(KEY) || '[]'); return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [] } catch { return [] } }

export default function WatchlistPage({ symbols, onOpenStock }: { symbols: string[]; onOpenStock: (s: string) => void }) {
  const [list, setList] = useState<string[]>(read)
  const [series, setSeries] = useState<Record<string, Price[]>>({})
  const [query, setQuery] = useState('')
  const [error, setError] = useState('')

  useEffect(() => { try { localStorage.setItem(KEY, JSON.stringify(list)) } catch { /* storage unavailable */ } }, [list])
  useEffect(() => {
    let c = false
    const missing = list.filter(s => !series[s])
    if (!missing.length) return
    void Promise.all(missing.map(async s => [s, await fetchPrices(s)] as const)).then(res => {
      if (!c) setSeries(cur => { const n = { ...cur }; res.forEach(([s, p]) => { n[s] = p }); return n })
    })
    return () => { c = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list])

  function add() {
    const s = query.trim().toUpperCase().replace(/\.NS$/, '')
    if (!s) return
    if (symbols.length && !symbols.includes(s)) { setError(`${s} is not in your imported NSE data.`); return }
    if (!list.includes(s)) setList([...list, s])
    setQuery(''); setError('')
  }
  const matches = query ? symbols.filter(x => x.startsWith(query.toUpperCase())).slice(0, 40) : []

  return <div className="stack-y">
    <Panel title="Watchlist" subtitle="Stocks you want to keep an eye on. Stored in this browser.">
      <div className="form-row">
        <input list="watch-symbols" placeholder="Add a symbol e.g. CDSL" value={query} onChange={e => setQuery(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') add() }} aria-label="Symbol to watch" />
        <datalist id="watch-symbols">{matches.map(m => <option key={m} value={m} />)}</datalist>
        <button className="btn primary" onClick={add}>Add to watchlist</button>
      </div>
      {error && <div className="notice warn">{error}</div>}
    </Panel>
    <Panel>
      {!list.length ? <Empty icon="☆" title="Your watchlist is empty">Add symbols from your imported NSE data to track their latest close and returns.</Empty> : <div className="table-wrap"><table className="table">
        <thead><tr><th>Stock</th><th className="r">Latest close</th><th className="r">Day</th><th className="r">1M</th><th className="r">1Y</th><th>Last 60 sessions</th><th /></tr></thead>
        <tbody>{list.map(s => {
          const p = series[s] ?? []
          const last = p[p.length - 1], prev = p[p.length - 2]
          const day = last && prev && prev.close ? ((last.close / prev.close) - 1) * 100 : undefined
          const m1 = returnSince(p, 30), y1 = returnSince(p, 365)
          return <tr key={s}>
            <td><button className="link strong" onClick={() => onOpenStock(s)}>{s}</button></td>
            <td className="r">{last ? money(last.close) : <span className="muted">{series[s] ? 'No data' : 'Loading…'}</span>}</td>
            <td className={`r ${tone(day)}`}>{pct(day, 2)}</td>
            <td className={`r ${tone(m1)}`}>{pct(m1)}</td>
            <td className={`r ${tone(y1)}`}>{pct(y1)}</td>
            <td className="spark-cell"><Sparkline values={p.slice(-60).map(x => x.close)} /></td>
            <td className="r"><button className="link danger" onClick={() => setList(list.filter(x => x !== s))} aria-label={`Remove ${s}`}>Remove</button></td>
          </tr>
        })}</tbody></table></div>}
    </Panel>
  </div>
}
