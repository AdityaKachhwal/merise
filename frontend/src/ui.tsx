import type { ReactNode } from 'react'

export const NAV = [
  { id: 'overview', label: 'Overview', short: 'Overview' },
  { id: 'portfolio', label: 'My Portfolio', short: 'Portfolio' },
  { id: 'research', label: 'Stock Research', short: 'Research' },
  { id: 'ai', label: 'AI Intelligence', short: 'AI' },
  { id: 'watchlist', label: 'Watchlist', short: 'Watchlist' },
  { id: 'history', label: 'History', short: 'History' },
  { id: 'insights', label: 'Market Insights', short: 'Insights' },
  { id: 'settings', label: 'Settings', short: 'Settings' },
] as const
export type PageId = typeof NAV[number]['id']

const ICONS: Record<string, ReactNode> = {
  overview: <><rect x="3" y="3" width="7" height="9" /><rect x="14" y="3" width="7" height="5" /><rect x="14" y="12" width="7" height="9" /><rect x="3" y="16" width="7" height="5" /></>,
  portfolio: <><rect x="2" y="7" width="20" height="14" rx="2" /><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16" /></>,
  research: <><path d="M3 3v18h18" /><path d="m19 9-5 5-4-4-3 3" /></>,
  ai: <><path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z" /><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z" /></>,
  watchlist: <path d="m12 2 3.1 6.3 6.9 1-5 4.9 1.2 6.8L12 17.8 5.8 21l1.2-6.8-5-4.9 6.9-1z" />,
  history: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  insights: <><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" /></>,
  settings: <><path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0" /><circle cx="16" cy="6" r="2" /><circle cx="10" cy="12" r="2" /><circle cx="18" cy="18" r="2" /></>,
}

export function Icon({ name, size = 18 }: { name: string; size?: number }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{ICONS[name]}</svg>
}

export function Panel({ title, subtitle, actions, children, className = '' }: { title?: ReactNode; subtitle?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return <section className={`panel ${className}`}>
    {(title || actions) && <div className="panel-head"><div>{title && <h2>{title}</h2>}{subtitle && <p className="sub">{subtitle}</p>}</div>{actions && <div className="panel-actions">{actions}</div>}</div>}
    <div className="panel-body">{children}</div>
  </section>
}

export function Stat({ label, value, sub, tone = '', big = false }: { label: string; value: ReactNode; sub?: ReactNode; tone?: string; big?: boolean }) {
  return <div className={`stat ${big ? 'big' : ''}`}><span className="stat-label">{label}</span><strong className={`stat-value ${tone}`}>{value}</strong>{sub && <span className="stat-sub">{sub}</span>}</div>
}

export function Empty({ icon = '○', title, children }: { icon?: string; title: string; children?: ReactNode }) {
  return <div className="empty"><div className="empty-icon">{icon}</div><b>{title}</b>{children && <div className="empty-body">{children}</div>}</div>
}

// Shown wherever the backend does not provide the data. Never filled with invented values.
export function Soon({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="soon"><span className="soon-tag">Not available yet</span><b>{title}</b>{children && <p>{children}</p>}</div>
}

export function Segmented<T extends string>({ options, value, onChange, small = false }: { options: readonly T[]; value: T; onChange: (v: T) => void; small?: boolean }) {
  return <div className={`seg ${small ? 'small' : ''}`} role="tablist">{options.map(o =>
    <button key={o} type="button" role="tab" aria-selected={o === value} className={o === value ? 'on' : ''} onClick={() => onChange(o)}>{o}</button>)}</div>
}

export function ActionBadge({ action }: { action?: string }) {
  const a = (action || '').toUpperCase()
  const cls = a === 'HOLD' ? 'good' : a === 'REVIEW' ? 'warn' : a === 'EXIT' ? 'bad' : ''
  return <span className={`badge ${cls}`}>{a || 'NO ACTION'}</span>
}
