import { useEffect, useState } from 'react'
import type { ChangeEvent } from 'react'
import { API } from './api'
import type { Snapshot } from './api'
import { usePortfolio } from './usePortfolio'
import { Icon, NAV } from './ui'
import type { PageId } from './ui'
import Overview from './Overview'
import PortfolioPage from './PortfolioPage'
import Research from './Research'
import AIIntelligence from './AIIntelligence'
import WatchlistPage from './WatchlistPage'
import HistoryPage from './HistoryPage'
import { InsightsPage, SettingsPage } from './MorePages'

const pageFromHash = (): PageId => {
  const id = window.location.hash.replace('#/', '')
  return NAV.find(n => n.id === id)?.id ?? 'overview'
}

export default function App() {
  const portfolio = usePortfolio()
  const [page, setPage] = useState<PageId>(pageFromHash)
  const [health, setHealth] = useState('Connecting to market data…')
  const [symbols, setSymbols] = useState<string[]>([])
  const [rows, setRows] = useState(0)
  const [history, setHistory] = useState<Snapshot[]>([])
  const [latestAnalysis, setLatestAnalysis] = useState<Snapshot | null>(null)
  const [selectedHistory, setSelectedHistory] = useState<Snapshot | null>(null)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyReportCollapsed, setHistoryReportCollapsed] = useState(false)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [researchSymbol, setResearchSymbol] = useState('')
  const [search, setSearch] = useState('')

  const go = (p: PageId) => { window.location.hash = `#/${p}`; window.scrollTo(0, 0) }
  useEffect(() => {
    const onHash = () => setPage(pageFromHash())
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])
  const openStock = (s: string) => { setResearchSymbol(s); go('research') }

  async function refresh() {
    try {
      const [h, s, hist] = await Promise.all([
        fetch(`${API}/health`).then(r => r.json()),
        fetch(`${API}/stocks`).then(r => r.json()),
        fetch(`${API}/history`).then(r => r.json()),
      ])
      setHealth(h.status === 'UP' ? 'Market data connected' : 'Backend unavailable')
      setSymbols((s.symbols || []) as string[])
      setRows(s.totalPriceRows || 0)
      setHistory(Array.isArray(hist) ? hist : [])
      setLatestAnalysis(current => current || (hist && hist.length ? hist[0] : null))
    } catch {
      setHealth('Backend not connected — start Spring Boot on port 8083')
    }
  }
  useEffect(() => { void refresh() }, [])

  async function importFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) return
    setBusy(true)
    setMessage('')
    try {
      const form = new FormData()
      form.append('file', file)
      const response = await fetch(`${API}/market-data/import`, { method: 'POST', body: form })
      const result = await response.json()
      if (!response.ok) throw new Error(result.error || 'Import failed')
      setMessage(`Imported ${Number(result.rowsRead || 0).toLocaleString('en-IN')} rows. ${result.message || ''}`)
      portfolio.log('MARKET_DATA_IMPORT', `${file.name} · ${Number(result.rowsRead || 0).toLocaleString('en-IN')} rows`)
      await refresh()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Import failed')
    } finally {
      setBusy(false)
      event.target.value = ''
    }
  }

  async function runAnalysis() {
    setBusy(true)
    setMessage('')
    try {
      const response = await fetch(`${API}/analysis/run`, { method: 'POST' })
      const result = await response.json()
      if (!response.ok) throw new Error(result.error || 'Market analysis failed')
      setLatestAnalysis(result)
      const count = (result.recommendations || []).length
      setMessage(`Analysis completed · ${count} qualifying stock${count === 1 ? '' : 's'} found · ${result.runId}`)
      await refresh()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Market analysis failed')
    } finally {
      setBusy(false)
    }
  }

  async function openHistory(runId: string) {
    setHistoryLoading(true)
    setMessage('')
    try {
      const response = await fetch(`${API}/history/${encodeURIComponent(runId)}`)
      const result = await response.json()
      if (!response.ok) throw new Error(result.error || 'Could not load the saved analysis')
      setSelectedHistory(result)
      setHistoryReportCollapsed(false)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not load the saved analysis')
    } finally {
      setHistoryLoading(false)
    }
  }

  const connected = health === 'Market data connected'
  const title = NAV.find(n => n.id === page)?.label ?? ''
  const symbol = researchSymbol || symbols[0] || ''
  const matches = search ? symbols.filter(s => s.startsWith(search.toUpperCase())).slice(0, 40) : []
  const onSearch = (v: string) => {
    setSearch(v)
    const u = v.trim().toUpperCase()
    if (symbols.includes(u)) { setSearch(''); openStock(u) }
  }

  return <div className="app">
    <aside className="sidebar">
      <div className="brand"><span className="brand-mark">M</span><span>MineVest</span></div>
      <nav aria-label="Primary">{NAV.map(n => <button key={n.id} className={`nav-item ${page === n.id ? 'on' : ''}`} aria-current={page === n.id ? 'page' : undefined} onClick={() => go(n.id)}><Icon name={n.id} />{n.label}</button>)}</nav>
      <div className={`conn ${connected ? 'ok' : ''}`}><i />{health}</div>
    </aside>
    <div className="main">
      <header className="topbar">
        <h1 className="page-title">{title}</h1>
        <div className="top-actions">
          <input
              className="search"
              list="global-symbols"
              placeholder="Search symbol…"
              value={search}
              onChange={e => onSearch(e.target.value)}
              aria-label="Search a stock symbol"
          />
          <datalist id="global-symbols">
            {matches.map(m => <option key={m} value={m} />)}
          </datalist>
        </div>
      </header>
      {message && <div className="notice toast" role="status"><span>{message}</span><button className="icon-btn" onClick={() => setMessage('')} aria-label="Dismiss">×</button></div>}
      <main className="content">
        {page === 'overview' && (
            <Overview
                symbols={symbols}
                analysis={latestAnalysis}
                onOpenStock={openStock}
                onRunAnalysis={() => { void runAnalysis() }}
                busy={busy}
            />
        )}
        {page === 'portfolio' && <PortfolioPage portfolio={portfolio} onOpenStock={openStock} go={go} />}
        {page === 'research' && <Research symbols={symbols} symbol={symbol} onSelect={setResearchSymbol} analysis={latestAnalysis} review={portfolio.review} holdings={portfolio.holdings} />}
        {page === 'ai' && <AIIntelligence portfolio={portfolio} analysis={latestAnalysis} busy={busy} rows={rows} onRun={() => { void runAnalysis() }} onOpenStock={openStock} go={go} />}
        {page === 'watchlist' && <WatchlistPage symbols={symbols} onOpenStock={openStock} />}
        {page === 'history' && <HistoryPage portfolio={portfolio} history={history} selected={selectedHistory} historyLoading={historyLoading} collapsed={historyReportCollapsed} onToggle={() => setHistoryReportCollapsed(v => !v)} onOpen={id => { void openHistory(id) }} onOpenStock={openStock} />}
        {page === 'insights' && <InsightsPage symbols={symbols} rows={rows} analysis={latestAnalysis} history={history} onOpenStock={openStock} />}
        {page === 'settings' && <SettingsPage health={health} busy={busy} portfolio={portfolio} onImport={importFile} onRefresh={() => { void refresh() }} />}
        <footer className="disclaimer">MineVest · Find. Invest. Grow. <span>Research support only · No trading or guaranteed returns</span></footer>
      </main>
    </div>
    <nav className="bottom-nav" aria-label="Primary mobile">{NAV.map(n => <button key={n.id} className={page === n.id ? 'on' : ''} onClick={() => go(n.id)}><Icon name={n.id} size={20} /><span>{n.short}</span></button>)}</nav>
  </div>
}
