const chainId = 11155111n; // Sepolia example; align with the reviewed deployment configuration.
const chainHex = '0xaa36a7';
const picker = document.querySelector('#provider');
const status = document.querySelector('#status');
const connect = document.querySelector('#connect');
const retry = document.querySelector('#retry');
const switchChain = document.querySelector('#switch-chain');
const providers = new Map();
const placeholder = document.createElement('option');
placeholder.value = '';
placeholder.textContent = '사용 가능한 지갑 없음';
placeholder.disabled = true;
picker.append(placeholder);
picker.disabled = true;
connect.disabled = true;
let accessToken = null; // Session memory only. Never persist or print the token.
let generation = 0;
let active = null;
let activeAccount = null;
let activeHandlers = null;
let pendingConnection = null;
let pendingSwitch = null;
let busyRun = null;

function selectedProvider() { return providers.get(picker.value); }
function accountOf(accounts) { return typeof accounts?.[0] === 'string' ? accounts[0].toLowerCase() : null; }
function say(message, canRetry = false, canSwitch = false) {
  status.textContent = message;
  retry.hidden = !canRetry;
  switchChain.hidden = !canSwitch;
}
function ready() {
  const available = !!selectedProvider();
  picker.disabled = !providers.size;
  connect.disabled = !available || busyRun !== null;
  retry.disabled = !available || busyRun !== null;
  switchChain.disabled = !available || busyRun !== null;
}
function invalidate(message) {
  generation++;
  accessToken = null;
  activeAccount = null;
  pendingConnection = null;
  pendingSwitch = null;
  busyRun = null;
  say(message, !!selectedProvider());
  ready();
}
function addProvider(id, label, provider) {
  if (providers.has(id)) return;
  const duplicate = [...providers].find(([, known]) => known === provider);
  if (duplicate) {
    // Prefer the EIP-6963 announcement over an injected fallback for the same object.
    if (duplicate[0] !== 'injected' || id === 'injected') return;
    const wasSelected = picker.value === 'injected';
    providers.delete('injected');
    picker.querySelector('option[value="injected"]')?.remove();
    providers.set(id, provider);
    const option = document.createElement('option');
    option.value = id;
    option.textContent = label;
    picker.append(option);
    if (wasSelected) picker.value = id;
  } else {
    providers.set(id, provider);
    const option = document.createElement('option');
    option.value = id;
    option.textContent = label;
    picker.append(option);
    if (providers.size === 1) picker.value = id;
  }
  placeholder.remove();
  if (!active && busyRun === null) say('지갑을 선택하고 연결해 주세요.');
  ready();
}

window.addEventListener('eip6963:announceProvider', event => {
  const { info, provider } = event.detail || {};
  if (info?.uuid && provider?.request) addProvider(info.uuid, info.name || '지갑', provider);
});
window.dispatchEvent(new Event('eip6963:requestProvider'));
if (window.ethereum?.request) addProvider('injected', window.ethereum.isMetaMask ? 'MetaMask' : '연결된 지갑', window.ethereum);
if (!providers.size) say('지갑을 찾지 못했습니다. MetaMask가 설치된 브라우저 또는 지갑 앱의 브라우저에서 열어 주세요.');

function detach() {
  if (active && activeHandlers) {
    for (const [event, handler] of Object.entries(activeHandlers)) active.removeListener?.(event, handler);
  }
  active = null;
  activeHandlers = null;
  activeAccount = null;
}
function attach(provider) {
  if (active === provider) return;
  detach();
  active = provider;
  activeHandlers = {
    accountsChanged: accounts => {
      const account = accountOf(accounts);
      if (pendingConnection?.provider === provider && pendingConnection.run === generation) {
        const first = pendingConnection.announcedAccount;
        if (account && (!first || first === account)) {
          pendingConnection.announcedAccount = account;
          return;
        }
      } else if (account && activeAccount === account) {
        return;
      }
      invalidate('계정이 변경되었습니다. 다시 로그인해 주세요.');
    },
    chainChanged: value => {
      if (pendingSwitch?.provider === provider && pendingSwitch.run === generation && value === chainHex) return;
      invalidate('체인이 변경되었습니다. 다시 로그인해 주세요.');
    },
    disconnect: () => invalidate('지갑 연결이 끊겼습니다. 다시 연결해 주세요.')
  };
  for (const [event, handler] of Object.entries(activeHandlers)) provider.on?.(event, handler);
}

function utf8Hex(message) {
  return '0x' + Array.from(new TextEncoder().encode(message), byte => byte.toString(16).padStart(2, '0')).join('');
}
async function api(path, payload) {
  const response = await fetch(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin', cache: 'no-store', body: JSON.stringify(payload)
  });
  const body = await response.json();
  if (!response.ok) throw Object.assign(new Error('요청을 완료하지 못했습니다.'), { code: body.reasonCode || body.code });
  return body;
}
function errorMessage(error) {
  if (error?.code === 4001) return '지갑 요청을 취소했습니다. 다시 시도할 수 있습니다.';
  if (error?.code === -32002) return '지갑에서 이전 요청을 처리 중입니다. 지갑 창에서 완료하거나 취소한 뒤 다시 시도해 주세요.';
  if (error?.code === 4100) return '지갑 접근이 거부되었습니다. 지갑에서 권한을 확인한 뒤 다시 시도해 주세요.';
  if (error?.code === 4902) return '지갑에 Sepolia가 없습니다. 지갑 설정에서 네트워크를 확인해 주세요.';
  if (error?.code === 'WRONG_CHAIN' || error?.code === 'CHAIN_NOT_SUPPORTED') return 'Sepolia로 전환한 뒤 다시 로그인해 주세요.';
  if (error?.code === 'ACCOUNT_CHANGED') return '계정 또는 지갑이 변경되었습니다. 다시 로그인해 주세요.';
  if (error?.code === 'NO_ACCOUNT') return '연결된 계정이 없습니다. 지갑에서 계정을 선택해 주세요.';
  return '로그인에 실패했습니다. 지갑 상태를 확인하고 다시 시도해 주세요.';
}
function failure(code) { return Object.assign(new Error(code), { code }); }
async function identityUnchanged(provider, address, run) {
  const accounts = await provider.request({ method: 'eth_accounts' });
  const currentChain = BigInt(await provider.request({ method: 'eth_chainId' }));
  if (run !== generation || provider !== active || selectedProvider() !== provider
      || accountOf(accounts) !== address.toLowerCase()) throw failure('ACCOUNT_CHANGED');
  if (currentChain !== chainId) throw failure('WRONG_CHAIN');
}

async function loginWithProvider(provider) {
  if (!provider) { say('MetaMask가 설치된 브라우저 또는 지갑 앱의 브라우저에서 열어 주세요.'); ready(); return; }
  if (busyRun !== null) return;
  attach(provider);
  accessToken = null;
  activeAccount = null;
  const run = ++generation;
  busyRun = run;
  pendingConnection = { provider, run, announcedAccount: null };
  ready();
  say('지갑 연결을 요청합니다.');
  try {
    const accounts = await provider.request({ method: 'eth_requestAccounts' });
    const address = accounts?.[0];
    if (run !== generation) return;
    if (!address) throw failure('NO_ACCOUNT');
    if (pendingConnection?.announcedAccount && pendingConnection.announcedAccount !== address.toLowerCase()) throw failure('ACCOUNT_CHANGED');
    pendingConnection = null;
    activeAccount = address.toLowerCase();
    await identityUnchanged(provider, address, run);
    if (run !== generation) return;
    say('로그인 메시지를 준비합니다.');
    const challenge = await api('/api/v1/auth/wallet/nonce', { address, chainId: Number(chainId) });
    await identityUnchanged(provider, address, run);
    if (run !== generation) return;
    say('지갑에서 로그인 메시지를 확인하고 서명해 주세요.');
    const signature = await provider.request({ method: 'personal_sign', params: [utf8Hex(challenge.message), address] });
    await identityUnchanged(provider, address, run);
    if (run !== generation) return;
    const result = await api('/api/v1/auth/wallet/verify', { message: challenge.message, signature });
    await identityUnchanged(provider, address, run);
    if (run !== generation) return;
    accessToken = result.accessToken;
    say(`로그인했습니다. 사용자 ID: ${result.user.userId}`);
  } catch (error) {
    if (run !== generation) return;
    accessToken = null;
    const canSwitch = error?.code === 'WRONG_CHAIN' || error?.code === 'CHAIN_NOT_SUPPORTED';
    say(errorMessage(error), true, canSwitch);
  } finally {
    if (run === generation) {
      pendingConnection = null;
      busyRun = null;
      ready();
    }
  }
}

async function switchToSepolia() {
  const provider = selectedProvider();
  if (!provider || busyRun !== null) return;
  attach(provider);
  accessToken = null;
  const run = ++generation;
  busyRun = run;
  ready();
  say('지갑에서 Sepolia 전환을 확인해 주세요.');
  try {
    const before = accountOf(await provider.request({ method: 'eth_accounts' }));
    if (!before || run !== generation || selectedProvider() !== provider) throw failure('ACCOUNT_CHANGED');
    activeAccount = before;
    pendingSwitch = { provider, run };
    await provider.request({ method: 'wallet_switchEthereumChain', params: [{ chainId: chainHex }] });
    if (run !== generation || selectedProvider() !== provider || active !== provider) return;
    const [accounts, currentChain] = await Promise.all([
      provider.request({ method: 'eth_accounts' }), provider.request({ method: 'eth_chainId' })
    ]);
    if (run !== generation || selectedProvider() !== provider || active !== provider || accountOf(accounts) !== before) throw failure('ACCOUNT_CHANGED');
    if (BigInt(currentChain) !== chainId) throw failure('WRONG_CHAIN');
    pendingSwitch = null;
    busyRun = null;
    return loginWithProvider(provider);
  } catch (error) {
    if (run !== generation) return;
    say(errorMessage(error), true, error?.code !== 'ACCOUNT_CHANGED');
  } finally {
    if (run === generation) {
      pendingSwitch = null;
      busyRun = null;
      ready();
    }
  }
}

// Application integration may use accessToken in memory for an approved session.
// Existing business routes still need common JWT filter integration.
picker.addEventListener('change', () => {
  detach();
  invalidate('지갑 선택이 변경되었습니다. 다시 로그인해 주세요.');
});
connect.addEventListener('click', () => loginWithProvider(selectedProvider()));
retry.addEventListener('click', () => loginWithProvider(selectedProvider()));
switchChain.addEventListener('click', switchToSepolia);
