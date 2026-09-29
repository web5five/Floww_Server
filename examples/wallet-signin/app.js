const chainId = 11155111n; // Sepolia local example; replace after the deployment chain is agreed.
const picker = document.querySelector('#provider');
const status = document.querySelector('#status');
const connect = document.querySelector('#connect');
const retry = document.querySelector('#retry');
const providers = new Map();
let accessToken = null; // Session memory only. Never persist or print the token.
let generation = 0;
let active = null;
let activeHandlers = null;

function say(message, canRetry = false) {
  status.textContent = message;
  retry.hidden = !canRetry;
}
function invalidate(message) {
  generation++;
  accessToken = null;
  say(message, true);
}
function addProvider(id, label, provider) {
  if (providers.has(id)) return;
  providers.set(id, provider);
  const option = document.createElement('option');
  option.value = id;
  option.textContent = label;
  picker.append(option);
}

window.addEventListener('eip6963:announceProvider', event => {
  const { info, provider } = event.detail || {};
  if (info?.uuid && provider?.request) addProvider(info.uuid, info.name || '지갑', provider);
});
window.dispatchEvent(new Event('eip6963:requestProvider'));
if (window.ethereum?.request) addProvider('injected', window.ethereum.isMetaMask ? 'MetaMask' : '연결된 지갑', window.ethereum);
if (!providers.size) say('지갑을 찾지 못했습니다. 지갑 앱 또는 확장 프로그램에서 이 페이지를 열어 주세요.', true);

function attach(provider) {
  if (active === provider) return;
  if (active && activeHandlers) {
    for (const [event, handler] of Object.entries(activeHandlers)) active.removeListener?.(event, handler);
  }
  active = provider;
  activeHandlers = {
    accountsChanged: () => invalidate('계정이 변경되었습니다. 다시 로그인해 주세요.'),
    chainChanged: () => invalidate('체인이 변경되었습니다. 다시 로그인해 주세요.'),
    disconnect: () => invalidate('지갑 연결이 끊겼습니다. 다시 연결해 주세요.')
  };
  for (const [event, handler] of Object.entries(activeHandlers)) provider.on?.(event, handler);
}

function getProvider() { return providers.get(picker.value); }

function utf8Hex(message) {
  return '0x' + Array.from(new TextEncoder().encode(message), byte => byte.toString(16).padStart(2, '0')).join('');
}

async function api(path, payload) {
  const response = await fetch(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin', cache: 'no-store', body: JSON.stringify(payload)
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.reasonCode || body.code || `HTTP ${response.status}`);
  return body;
}

async function identityUnchanged(provider, address, run) {
  const accounts = await provider.request({ method: 'eth_accounts' });
  const currentChain = BigInt(await provider.request({ method: 'eth_chainId' }));
  if (run !== generation || provider !== active || getProvider() !== provider
      || accounts?.[0]?.toLowerCase() !== address.toLowerCase()) {
    throw new Error('계정 또는 지갑이 변경되었습니다. 다시 로그인해 주세요.');
  }
  if (currentChain !== chainId) throw new Error('Sepolia 체인으로 전환한 뒤 다시 시도해 주세요.');
}

async function loginWithProvider(provider) {
  if (!provider) { say('지갑을 선택해 주세요.', true); return; }
  attach(provider);
  accessToken = null;
  const run = ++generation;
  connect.disabled = true;
  retry.hidden = true;
  try {
    say('지갑 연결을 요청합니다.');
    const accounts = await provider.request({ method: 'eth_requestAccounts' });
    const address = accounts?.[0];
    if (!address) throw new Error('연결된 계정이 없습니다.');
    await identityUnchanged(provider, address, run);
    say('로그인 메시지를 준비합니다.');
    const challenge = await api('/api/v1/auth/wallet/nonce', { address, chainId: Number(chainId) });
    await identityUnchanged(provider, address, run);
    say('지갑에서 로그인 메시지를 확인하고 서명해 주세요.');
    const signature = await provider.request({ method: 'personal_sign', params: [utf8Hex(challenge.message), address] });
    await identityUnchanged(provider, address, run);
    const result = await api('/api/v1/auth/wallet/verify', { message: challenge.message, signature });
    await identityUnchanged(provider, address, run);
    accessToken = result.accessToken;
    say(`로그인했습니다. 사용자 ID: ${result.user.userId}`);
  } catch (error) {
    if (run !== generation) return;
    accessToken = null;
    const message = error?.code === 4001 ? '지갑 요청을 취소했습니다.'
      : error?.message?.includes('CHAIN_NOT_SUPPORTED')
        ? 'Sepolia 체인으로 전환한 뒤 다시 시도해 주세요.'
        : error?.message || '로그인에 실패했습니다.';
    say(message, true);
  } finally {
    if (run === generation) connect.disabled = false;
  }
}

// Application integration may call a protected API from this module with
// Authorization: Bearer ${accessToken}. The current server business filter
// still needs the common JWT integration before that path works end to end.
picker.addEventListener('change', () => invalidate('지갑 선택이 변경되었습니다. 다시 로그인해 주세요.'));
connect.addEventListener('click', () => loginWithProvider(getProvider()));
retry.addEventListener('click', () => loginWithProvider(getProvider()));
