import { signInWithMagicProvider, SEPOLIA_CHAIN_ID, ProofError } from './wallet-proof.mjs';

export class MagicConnectorError extends Error {
  constructor(code, message) { super(message); this.name = 'MagicConnectorError'; this.code = code; }
}

const validKey = value => typeof value === 'string' && /^pk_[A-Za-z0-9_-]{8,}$/.test(value);
const validEmail = value => typeof value === 'string' && value.length <= 254 &&
  /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);

function checkedNetwork(network, chainId) {
  if (!Number.isSafeInteger(chainId) || chainId <= 0) throw new MagicConnectorError('INVALID_CONFIG', '체인 설정을 확인해 주세요.');
  if (network === 'sepolia' && chainId === SEPOLIA_CHAIN_ID) return network;
  if (network && typeof network === 'object' && network.chainId === chainId &&
      typeof network.rpcUrl === 'string') {
    try {
      const url = new URL(network.rpcUrl);
      if (url.protocol === 'https:' && !url.username && !url.password && !url.hash) return network;
    } catch { /* invalid network */ }
  }
  throw new MagicConnectorError('INVALID_CONFIG', 'Magic 네트워크와 서버 체인을 일치시켜 주세요.');
}

function safeError(error, otpComplete) {
  if (error instanceof MagicConnectorError) return error;
  const code = error instanceof ProofError ? error.code : otpComplete ? 'WALLET_SIGNIN_FAILED' : 'MAGIC_OTP_FAILED';
  const messages = {
    WRONG_CHAIN: 'Magic 지갑의 체인이 설정된 체인과 다릅니다. 네트워크 설정을 확인해 주세요.',
    ACCOUNT_CHANGED: '지갑 계정이 변경되었습니다. 다시 로그인해 주세요.',
    BAD_CHALLENGE: '로그인 메시지가 설정된 출처·주소·체인과 일치하지 않습니다.',
    BAD_RESPONSE: '서버 로그인 응답을 확인할 수 없습니다.',
    BAD_SIGNATURE: '지갑 서명이 올바르지 않습니다.',
    NO_ACCOUNT: '연결된 지갑 계정이 없습니다.'
  };
  return new MagicConnectorError(code, messages[code] ??
    (otpComplete ? '지갑 로그인을 완료하지 못했습니다. 다시 시도해 주세요.' :
      '이메일 인증을 완료하지 못했습니다. 다시 시도해 주세요.'));
}

/** Optional client connector. No SDK import, OTP, or API call without a publishable key. */
export function createMagicConnector({ publishableKey, chainId = SEPOLIA_CHAIN_ID, network = 'sepolia',
  origin = globalThis.location?.origin, fetchFn = globalThis.fetch,
  loadMagic = () => import('magic-sdk'), onState = () => {},
  now = () => globalThis.performance.now() } = {}) {
  const checked = checkedNetwork(network, chainId);
  let generation = 0;
  let token = null;
  let result = null;
  let sessionExpiresAt = null;
  let activeMagic = null;
  let authenticated = false;
  let activeProvider = null;
  let listeners = null;
  let pending = null;
  let pendingRun = null;
  const logoutPromises = new WeakMap();
  let logoutBarrier = Promise.resolve();
  let logoutFailed = false;

  const detach = () => {
    if (activeProvider && listeners) {
      for (const [event, handler] of Object.entries(listeners)) activeProvider.removeListener?.(event, handler);
    }
    activeProvider = null;
    listeners = null;
  };
  const logoutOnce = async instance => {
    if (!instance) return;
    let work = logoutPromises.get(instance);
    if (!work) {
      work = Promise.resolve().then(() => instance.user.logout());
      logoutPromises.set(instance, work);
    }
    return work;
  };
  const queueLogout = instance => {
    const work = logoutOnce(instance);
    logoutBarrier = Promise.allSettled([logoutBarrier, work]).then(outcomes => {
      if (outcomes.some(outcome => outcome.status === 'rejected')) logoutFailed = true;
    });
    return work;
  };
  const clear = () => { token = null; result = null; sessionExpiresAt = null;
    detach(); activeMagic = null; authenticated = false; };
  const invalidate = reason => {
    generation++;
    const instance = activeMagic;
    const shouldLogout = authenticated;
    clear();
    onState({ status: 'disconnected', message: reason });
    if (shouldLogout) void queueLogout(instance).catch(() => {});
  };
  const getAccessToken = () => {
    if (!token) return null;
    const currentTime = now();
    if (!Number.isFinite(sessionExpiresAt) || !Number.isFinite(currentTime) || currentTime >= sessionExpiresAt) {
      invalidate('로그인 시간이 만료되었습니다. 다시 로그인해 주세요.');
      return null;
    }
    return token;
  };
  const attach = provider => {
    activeProvider = provider;
    listeners = {
      accountsChanged: () => invalidate('지갑 계정이 변경되었습니다. 다시 로그인해 주세요.'),
      chainChanged: () => invalidate('지갑 체인이 변경되었습니다. 다시 로그인해 주세요.'),
      disconnect: () => invalidate('지갑 연결이 끊겼습니다. 다시 로그인해 주세요.')
    };
    for (const [event, handler] of Object.entries(listeners)) provider.on?.(event, handler);
  };

  function connect(email) {
    if (!validKey(publishableKey)) {
      const error = new MagicConnectorError('MAGIC_UNAVAILABLE', 'Magic 앱 키를 설정해 주세요.');
      onState({ status: 'unavailable', code: error.code, message: error.message });
      return Promise.reject(error);
    }
    if (!validEmail(email)) return Promise.reject(new MagicConnectorError('INVALID_EMAIL', '올바른 이메일을 입력해 주세요.'));
    if (pending) {
      if (pendingRun === generation) return pending;
      return Promise.reject(new MagicConnectorError('PENDING_SETTLEMENT',
        '이전 이메일 인증 요청이 아직 끝나지 않았습니다. 새로고침 후 다시 시도해 주세요.'));
    }
    if (result && getAccessToken()) return Promise.resolve(result);

    const run = ++generation;
    clear();
    const current = () => {
      if (run !== generation) throw new MagicConnectorError('STALE', '로그인 요청이 취소되거나 변경되었습니다.');
    };
    const work = async () => {
      let instance;
      let otpComplete = false;
      let verifyStartedAt = null;
      let stage = 'email-otp';
      try {
        await logoutBarrier;
        current();
        if (logoutFailed) throw new MagicConnectorError('LOGOUT_FAILED',
          'Magic 로그아웃을 완료하지 못했습니다. 새로고침 후 다시 시도해 주세요.');
        onState({ status: 'pending', message: '이메일 인증을 시작합니다.' });
        const sdk = await loadMagic();
        current();
        if (typeof sdk?.Magic !== 'function') throw new MagicConnectorError('MAGIC_UNAVAILABLE', 'Magic SDK를 불러오지 못했습니다.');
        instance = new sdk.Magic(publishableKey, { network: checked, deferPreload: true });
        activeMagic = instance;
        await instance.auth.loginWithEmailOTP({ email }); // Never send or store its DID result.
        otpComplete = await instance.user.isLoggedIn();
        if (!otpComplete) throw new MagicConnectorError('MAGIC_OTP_FAILED', '이메일 인증을 완료하지 못했습니다.');
        current();
        authenticated = true;
        const provider = instance.rpcProvider;
        if (!provider?.request) throw new MagicConnectorError('MAGIC_UNAVAILABLE', 'Magic 지갑을 사용할 수 없습니다.');
        // Attach after the first account request so an initial account announcement is not treated as a switch.
        let attached = false;
        const proof = await signInWithMagicProvider({ provider, chainId, origin, fetchFn,
          assertCurrent: () => {
            current();
            if (activeMagic !== instance || (attached && activeProvider !== provider)) {
              throw new MagicConnectorError('STALE', '로그인 요청이 취소되거나 변경되었습니다.');
            }
          },
          onStage: (nextStage, message) => {
            stage = nextStage;
            onState({ status: 'pending', stage, message });
          },
          onAccountReady: () => { attach(provider); attached = true; },
          onVerifyStart: () => { verifyStartedAt = now(); } });
        current();
        const lifetimeMs = proof.expiresIn * 1000;
        const deadline = verifyStartedAt + lifetimeMs;
        if (!Number.isFinite(verifyStartedAt) || !Number.isSafeInteger(lifetimeMs) ||
            !Number.isFinite(deadline) || now() >= deadline) {
          throw new MagicConnectorError('SESSION_EXPIRED', '로그인 시간이 만료되었습니다. 다시 로그인해 주세요.');
        }
        token = proof.accessToken;
        sessionExpiresAt = deadline;
        result = Object.freeze({ userId: proof.userId, address: proof.address });
        onState({ status: 'signed-in', userId: proof.userId, address: proof.address, message: '로그인했습니다.' });
        return result;
      } catch (error) {
        if (otpComplete) { try { await queueLogout(instance); } catch { /* local token remains cleared */ } }
        if (activeMagic === instance) clear();
        if (run !== generation) throw new MagicConnectorError('STALE', '로그인 요청이 취소되거나 변경되었습니다.');
        const safe = safeError(error, otpComplete);
        onState({ status: 'error', stage, code: safe.code, message: safe.message });
        throw safe;
      }
    };
    const promise = work().finally(() => {
      if (pending === promise) { pending = null; pendingRun = null; }
    });
    pending = promise;
    pendingRun = run;
    return pending;
  }

  async function disconnect() {
    const instance = activeMagic;
    const shouldLogout = authenticated;
    generation++;
    clear();
    onState({ status: 'disconnected', message: '로그아웃했습니다.' });
    if (shouldLogout) {
      try { await queueLogout(instance); }
      catch { throw new MagicConnectorError('LOGOUT_FAILED', 'Magic 로그아웃을 완료하지 못했습니다.'); }
    }
  }

  return Object.freeze({ connect, disconnect, getAccessToken });
}
