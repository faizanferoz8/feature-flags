import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { ErrorNote } from '../components/ErrorNote';
import type { Role, User } from '../types';

const ROLES: { value: Role; label: string; can: string }[] = [
  { value: 'VIEWER', label: 'Viewer', can: 'Can see flags and the audit log' },
  { value: 'EDITOR', label: 'Editor', can: 'Can also create and change flags' },
  { value: 'ADMIN', label: 'Admin', can: 'Can also manage members and API keys' },
];

export function MembersPage() {
  const { session } = useAuth();
  const queryClient = useQueryClient();
  const members = useQuery({ queryKey: ['members'], queryFn: () => api<User[]>('/api/members') });
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('EDITOR');
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['members'] });

  const add = useMutation({
    mutationFn: () => api<User>('/api/members', { method: 'POST', body: { email, password, role } }),
    onSuccess: () => {
      setEmail('');
      setPassword('');
      void refresh();
    },
  });
  const changeRole = useMutation({
    mutationFn: (change: { id: string; role: Role }) =>
      api<User>(`/api/members/${change.id}/role`, { method: 'PUT', body: { role: change.role } }),
    onSettled: refresh,
  });
  const remove = useMutation({
    mutationFn: (id: string) => api<void>(`/api/members/${id}`, { method: 'DELETE' }),
    onSettled: refresh,
  });

  const fields = add.error instanceof ApiError ? add.error.fields : {};
  const onAdd = (event: FormEvent) => {
    event.preventDefault();
    add.mutate();
  };

  return (
    <>
      <header className="page-head">
        <h1>Members</h1>
      </header>

      <form className="panel inline-form" onSubmit={onAdd}>
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          {fields.email && <span className="field-error">Email {fields.email}</span>}
        </label>
        <label>
          Temporary password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoComplete="new-password" />
          {fields.password && <span className="field-error">Password {fields.password}</span>}
        </label>
        <label>
          Role
          <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
            {ROLES.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <span className="hint">{ROLES.find((option) => option.value === role)?.can}</span>
        </label>
        <button type="submit" className="primary" disabled={add.isPending}>
          Add member
        </button>
      </form>
      {Object.keys(fields).length === 0 && <ErrorNote error={add.error} />}
      <ErrorNote error={changeRole.error ?? remove.error ?? members.error} />

      <table className="plain">
        <thead>
          <tr>
            <th scope="col">Email</th>
            <th scope="col">Role</th>
            <th scope="col" />
          </tr>
        </thead>
        <tbody>
          {members.data?.map((member) => (
            <tr key={member.id}>
              <th scope="row">
                {member.email}
                {member.id === session?.user.id && <span className="you"> (you)</span>}
              </th>
              <td>
                <select
                  aria-label={`Role of ${member.email}`}
                  value={member.role}
                  disabled={changeRole.isPending}
                  onChange={(e) => changeRole.mutate({ id: member.id, role: e.target.value as Role })}
                >
                  {ROLES.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </td>
              <td className="actions">
                <button type="button" className="link danger" disabled={remove.isPending} onClick={() => remove.mutate(member.id)}>
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
