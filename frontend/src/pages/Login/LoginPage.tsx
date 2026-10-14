import React, { useState } from 'react';
import { useAuth } from '../../context/AuthContext';
export function LoginPage() {
  const { login, notice, transitioning } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setPending(true); setError('');
    try { await login(username, password); }
    catch { setError('Sign in failed. Check your credentials or contact the demo operator.'); }
    finally { setPassword(''); setPending(false); }
  };
  return <main className="min-h-screen bg-slate-950 text-slate-100 grid place-items-center p-6">
    <form className="max-w-sm w-full space-y-4" onSubmit={submit}>
      <h1 className="text-2xl font-semibold">Sign in to KaiPay</h1>
      <p className="text-sm text-slate-400">Simulated payments only. Use your individually provisioned demo account.</p>
      {notice && <p role="status">{notice}</p>}{error && <p role="alert">{error}</p>}
      <label className="block">Username<input className="block w-full bg-slate-800 p-2 rounded" name="username" autoComplete="username" required value={username} onChange={(e) => setUsername(e.target.value)} /></label>
      <label className="block">Password<input className="block w-full bg-slate-800 p-2 rounded" name="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} /></label>
      <button className="bg-emerald-700 rounded px-4 py-2" disabled={pending || transitioning}>{pending ? 'Signing in...' : 'Sign in'}</button>
    </form>
  </main>;
}
