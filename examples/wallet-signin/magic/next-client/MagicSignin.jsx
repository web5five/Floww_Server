'use client';

import { useEffect, useRef, useState } from 'react';

// Copy this component with the Magic package into the frontend build context.
// The backend must expose only the two wallet auth routes at this page's origin.
export function MagicSignin() {
  const connector = useRef(null);
  const mounted = useRef(true);
  const [email, setEmail] = useState('');
  const [status, setStatus] = useState('이메일 주소를 입력해 주세요.');
  const [address, setAddress] = useState('');
  const [busy, setBusy] = useState(false);
  const key = process.env.NEXT_PUBLIC_MAGIC_PUBLISHABLE_KEY ?? '';
  const available = /^pk_[A-Za-z0-9_-]{8,}$/.test(key);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; void connector.current?.disconnect(); };
  }, []);

  async function signIn(event) {
    event.preventDefault();
    if (!available || busy) return;
    setBusy(true);
    try {
      const { createMagicConnector } = await import('../src/magic-connector.mjs');
      if (!mounted.current) return;
      connector.current ??= createMagicConnector({
        publishableKey: key,
        network: { rpcUrl: 'https://ethereum-sepolia-rpc.publicnode.com', chainId: 11155111 },
        chainId: 11155111,
        origin: window.location.origin,
        onState: state => {
          if (mounted.current) {
            setStatus(state.message ?? '');
            if (state.status !== 'signed-in') setAddress('');
          }
        }
      });
      const result = await connector.current.connect(email.trim());
      if (mounted.current) setAddress(result.address);
      // getAccessToken() is available only in connector memory for approved API calls.
    } catch {
      if (mounted.current) setAddress('');
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function signOut() {
    if (busy) return;
    setBusy(true);
    try { await connector.current?.disconnect(); }
    catch { if (mounted.current) setStatus('로그아웃을 완료하지 못했습니다. 새로고침 후 다시 시도해 주세요.'); }
    finally { if (mounted.current) { setAddress(''); setBusy(false); } }
  }

  function restart() {
    if (!busy) return;
    void connector.current?.disconnect().catch(() => {});
    window.location.reload();
  }

  return <section aria-label="Magic 지갑 로그인">
    <form onSubmit={signIn}>
      <label htmlFor="magic-email">이메일 주소</label>
      <input id="magic-email" type="email" autoComplete="email" required value={email}
        onChange={event => setEmail(event.target.value)} />
      <button type="submit" disabled={!available || busy}>Magic으로 로그인</button>
    </form>
    {busy && <button type="button" onClick={restart}>인증 취소하고 새로고침</button>}
    <p role="status">{available ? status : 'Magic 앱 설정이 필요합니다.'}</p>
    {address && <div><p>연결된 지갑: {address.slice(0, 8)}…{address.slice(-6)}</p>
      <button type="button" disabled={busy} onClick={signOut}>로그아웃</button></div>}
  </section>;
}
