import { publishableKey } from './magic-config.mjs';

const form = document.querySelector('#signin');
const email = document.querySelector('#email');
const button = document.querySelector('#connect');
const status = document.querySelector('#status');
const session = document.querySelector('#session');
const address = document.querySelector('#address');
const disconnect = document.querySelector('#disconnect');
let busy = false;
let connector = null;

if (!/^pk_[A-Za-z0-9_-]{8,}$/.test(publishableKey)) {
  status.textContent = 'Magic 앱 설정이 필요합니다.';
  button.disabled = true;
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || button.disabled) return;
  busy = true;
  button.disabled = true;
  try {
    const { createMagicConnector } = await import('./dist/magic-connector.js');
    connector ??= createMagicConnector({ publishableKey, onState: state => {
      status.textContent = state.message ?? '';
      if (state.status !== 'signed-in') session.hidden = true;
    } });
    const signedIn = await connector.connect(email.value.trim());
    address.textContent = signedIn.address.slice(0, 8) + '…' + signedIn.address.slice(-6);
    session.hidden = false;
  } catch {
    session.hidden = true;
  } finally {
    busy = false;
    button.disabled = !/^pk_[A-Za-z0-9_-]{8,}$/.test(publishableKey);
  }
});

disconnect.addEventListener('click', async () => {
  if (busy) return;
  busy = true;
  disconnect.disabled = true;
  try { await connector?.disconnect(); }
  catch { status.textContent = 'Magic 로그아웃을 완료하지 못했습니다. 새로고침 후 다시 시도해 주세요.'; }
  finally { busy = false; disconnect.disabled = false; session.hidden = true; }
});
