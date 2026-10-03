import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { ErrorNote } from '../components/ErrorNote';
import type { Session } from '../types';

interface TokenResponse {
  token: string;
  session: Session;
}

export function AuthPage({ mode }: { mode: 'login' | 'signup' }) {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [organization, setOrganization] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const signup = mode === 'signup';

  const submit = useMutation({
    mutationFn: () =>
      api<TokenResponse>(signup ? '/api/auth/signup' : '/api/auth/login', {
        method: 'POST',
        body: signup ? { organization, email, password } : { email, password },
      }),
    onSuccess: ({ token, session }) => {
      signIn(token, session);
      navigate('/flags', { replace: true });
    },
  });

  const fields = submit.error instanceof ApiError ? submit.error.fields : {};
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    submit.mutate();
  };

  return (
    <div className="auth">
      <div className="auth-art" aria-hidden="true">
        {Array.from({ length: 120 }, (_, i) => (
          <span key={i} data-on={(i * 37) % 100 < 34} />
        ))}
      </div>
      <form className="auth-form" onSubmit={onSubmit}>
        <h1>{signup ? 'Create your organization' : 'Sign in'}</h1>
        <p className="lede">
          {signup
            ? 'You get development, staging and production environments, and become the first administrator.'
            : 'Release to one percent of users first, then the rest.'}
        </p>
        {signup && (
          <label>
            Organization
            <input value={organization} onChange={(e) => setOrganization(e.target.value)} required autoFocus />
            {fields.organization && <span className="field-error">Organization {fields.organization}</span>}
          </label>
        )}
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus={!signup} />
          {fields.email && <span className="field-error">Email {fields.email}</span>}
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoComplete={signup ? 'new-password' : 'current-password'}
          />
          {fields.password && <span className="field-error">Password {fields.password}</span>}
        </label>
        {Object.keys(fields).length === 0 && <ErrorNote error={submit.error} />}
        <button type="submit" className="primary" disabled={submit.isPending}>
          {signup ? 'Create organization' : 'Sign in'}
        </button>
        <p className="switch-mode">
          {signup ? (
            <>
              Already have an account? <Link to="/login">Sign in</Link>
            </>
          ) : (
            <>
              New here? <Link to="/signup">Create an organization</Link>
            </>
          )}
        </p>
      </form>
    </div>
  );
}
