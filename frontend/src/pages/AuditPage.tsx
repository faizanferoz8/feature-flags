import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api';
import { ErrorNote } from '../components/ErrorNote';
import { describeAction, diffLines, formatWhen } from '../format';
import type { AuditPage as AuditPageData } from '../types';

const PAGE_SIZE = 25;

export function AuditPage() {
  const [page, setPage] = useState(0);
  const audit = useQuery({
    queryKey: ['audit', page],
    queryFn: () => api<AuditPageData>(`/api/audit?page=${page}&size=${PAGE_SIZE}`),
    placeholderData: keepPreviousData,
  });
  const pages = audit.data ? Math.max(1, Math.ceil(audit.data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <header className="page-head">
        <h1>Audit log</h1>
      </header>
      <p className="lede">Every change, who made it, and what it changed. Entries cannot be edited or removed.</p>
      <ErrorNote error={audit.error} />

      <ol className="audit">
        {audit.data?.items.map((entry) => {
          const changes = diffLines(entry.before, entry.after);
          return (
            <li key={entry.id}>
              <p>
                <strong>{entry.actor}</strong> {describeAction(entry.action)} <code>{entry.target}</code>
                {entry.environment && <> in {entry.environment}</>}
                <time dateTime={entry.at}>{formatWhen(entry.at)}</time>
              </p>
              {changes.length > 0 && (
                <dl>
                  {changes.map((change) => (
                    <div key={change.field}>
                      <dt>{change.field}</dt>
                      <dd>
                        <del>{change.from}</del>
                        <ins>{change.to}</ins>
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
            </li>
          );
        })}
      </ol>

      {pages > 1 && (
        <nav className="pager" aria-label="Pages">
          <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Newer
          </button>
          <span>
            Page {page + 1} of {pages}
          </span>
          <button type="button" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
            Older
          </button>
        </nav>
      )}
    </>
  );
}
