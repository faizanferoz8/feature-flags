import type { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth';

export function Layout({ children }: { children: ReactNode }) {
  const { session, signOut, can } = useAuth();
  if (!session) return null;

  return (
    <div className="shell">
      <aside className="sidebar">
        <p className="org">{session.organization}</p>
        <nav aria-label="Main">
          <NavLink to="/flags">Flags</NavLink>
          {can('ADMIN') && <NavLink to="/keys">API keys</NavLink>}
          <NavLink to="/audit">Audit log</NavLink>
          {can('ADMIN') && <NavLink to="/members">Members</NavLink>}
        </nav>
        <div className="whoami">
          <span title={session.user.email}>{session.user.email}</span>
          <span className="role">{session.user.role.toLowerCase()}</span>
          <button type="button" className="link" onClick={signOut}>
            Sign out
          </button>
        </div>
      </aside>
      <main className="content">{children}</main>
    </div>
  );
}
