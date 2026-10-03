import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { useAuth } from '../auth';
import { ErrorNote } from '../components/ErrorNote';
import { formatWhen } from '../format';
import type { ApiKey } from '../types';

export function KeysPage() {
  const { session } = useAuth();
  const queryClient = useQueryClient();
  const keys = useQuery({ queryKey: ['keys'], queryFn: () => api<ApiKey[]>('/api/keys') });
  const [name, setName] = useState('');
  const [environment, setEnvironment] = useState(session?.environments[0]?.key ?? '');
  const [copied, setCopied] = useState(false);

  const create = useMutation({
    mutationFn: () => api<{ key: ApiKey; secret: string }>('/api/keys', { method: 'POST', body: { name, environment } }),
    onSuccess: () => {
      setName('');
      setCopied(false);
      void queryClient.invalidateQueries({ queryKey: ['keys'] });
    },
  });

  const revoke = useMutation({
    mutationFn: (id: string) => api<void>(`/api/keys/${id}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['keys'] }),
  });

  const onCreate = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };

  return (
    <>
      <header className="page-head">
        <h1>API keys</h1>
      </header>
      <p className="lede">
        An application uses a key to read the flags of one environment. Revoking a key closes its open connections
        straight away.
      </p>

      <form className="panel inline-form" onSubmit={onCreate}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="checkout-service" required />
        </label>
        <label>
          Environment
          <select value={environment} onChange={(e) => setEnvironment(e.target.value)}>
            {session?.environments.map((option) => (
              <option key={option.key} value={option.key}>
                {option.name}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" className="primary" disabled={create.isPending}>
          Create key
        </button>
      </form>
      <ErrorNote error={create.error ?? revoke.error ?? keys.error} />

      {create.data && (
        <div className="notice secret" role="status">
          <p>
            Copy the key for <strong>{create.data.key.name}</strong> now. It is not stored, so it cannot be shown again.
          </p>
          <code>{create.data.secret}</code>
          <button
            type="button"
            onClick={() => {
              void navigator.clipboard?.writeText(create.data.secret);
              setCopied(true);
            }}
          >
            {copied ? 'Copied' : 'Copy key'}
          </button>
        </div>
      )}

      {keys.data?.length === 0 && <p className="hint">No keys yet. Create one to connect an application.</p>}
      {keys.data && keys.data.length > 0 && (
        <table className="plain">
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Environment</th>
              <th scope="col">Key</th>
              <th scope="col">Last used</th>
              <th scope="col">Created</th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {keys.data.map((key) => (
              <tr key={key.id} data-revoked={key.revokedAt !== null}>
                <th scope="row">{key.name}</th>
                <td>{key.environment}</td>
                <td>
                  <code>{key.prefix}…</code>
                </td>
                <td>{key.lastUsedAt ? formatWhen(key.lastUsedAt) : 'Never'}</td>
                <td>
                  {formatWhen(key.createdAt)} by {key.createdBy}
                </td>
                <td className="actions">
                  {key.revokedAt ? (
                    `Revoked ${formatWhen(key.revokedAt)}`
                  ) : (
                    <button type="button" className="link danger" disabled={revoke.isPending} onClick={() => revoke.mutate(key.id)}>
                      Revoke
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
