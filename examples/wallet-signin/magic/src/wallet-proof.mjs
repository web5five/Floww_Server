export const SEPOLIA_CHAIN_ID = 11155111;
const ADDRESS = /^0x[0-9a-fA-F]{40}$/;
const SIGNATURE = /^0x[0-9a-fA-F]{130}$/;
const NONCE = /^[a-f0-9]{48}$/;

export class ProofError extends Error {
  constructor(code) { super(code); this.code = code; }
}

export function utf8Hex(value) {
  return '0x' + Array.from(new TextEncoder().encode(value), byte => byte.toString(16).padStart(2, '0')).join('');
}

function assertIdentity(provider, address, chainId, assertCurrent) {
  return (async () => {
    assertCurrent();
    const accounts = await provider.request({ method: 'eth_accounts' });
    assertCurrent();
    if (!Array.isArray(accounts) || !ADDRESS.test(accounts[0] ?? '') ||
        accounts[0].toLowerCase() !== address.toLowerCase()) throw new ProofError('ACCOUNT_CHANGED');
    const rawChain = await provider.request({ method: 'eth_chainId' });
    assertCurrent();
    if (typeof rawChain !== 'string' || !/^0x[0-9a-fA-F]+$/.test(rawChain) ||
        BigInt(rawChain) !== BigInt(chainId)) throw new ProofError('WRONG_CHAIN');
  })();
}

function validOrigin(origin) {
  try {
    const url = new URL(origin);
    return url.origin === origin && !url.username && !url.password &&
      (url.protocol === 'https:' || (url.protocol === 'http:' &&
        ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)));
  } catch { return false; }
}

function validateChallenge(body, address, chainId, origin) {
  if (!body || typeof body !== 'object' || !NONCE.test(body.nonce ?? '') ||
      typeof body.message !== 'string' || typeof body.expiresAt !== 'string') throw new ProofError('BAD_CHALLENGE');
  const lines = body.message.split('\n');
  if (lines.length !== 11 || lines[0] !== `${origin} wants you to sign in with your Ethereum account:` ||
      !ADDRESS.test(lines[1] ?? '') || lines[1].toLowerCase() !== address.toLowerCase() ||
      lines[2] !== '' || lines[3] !== 'Sign in to Floww' || lines[4] !== '' ||
      lines[5] !== `URI: ${origin}` || lines[6] !== 'Version: 1' ||
      lines[7] !== `Chain ID: ${chainId}` || lines[8] !== `Nonce: ${body.nonce}` ||
      !lines[9]?.startsWith('Issued At: ') || Number.isNaN(Date.parse(lines[9].slice(11))) ||
      lines[10] !== `Expiration Time: ${body.expiresAt}` ||
      Number.isNaN(Date.parse(body.expiresAt)) || Date.parse(body.expiresAt) <= Date.now()) {
    throw new ProofError('BAD_CHALLENGE');
  }
  return body.message;
}

async function post(fetchFn, path, payload, assertCurrent) {
  assertCurrent();
  const response = await fetchFn(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin', cache: 'no-store', body: JSON.stringify(payload)
  });
  assertCurrent();
  if (!response || typeof response.ok !== 'boolean') throw new ProofError('BAD_RESPONSE');
  let body;
  try { body = await response.json(); } catch { throw new ProofError('BAD_RESPONSE'); }
  assertCurrent();
  if (!response.ok) throw new ProofError(typeof body?.reasonCode === 'string' ? body.reasonCode : 'SERVER_REJECTED');
  if (!body || typeof body !== 'object') throw new ProofError('BAD_RESPONSE');
  return body;
}

export async function signInWithMagicProvider({ provider, chainId, origin, fetchFn, assertCurrent, onStage,
  onAccountReady = () => {} }) {
  if (!provider?.request || !Number.isSafeInteger(chainId) || chainId <= 0 ||
      !validOrigin(origin) || typeof fetchFn !== 'function') throw new ProofError('INVALID_CONFIG');
  assertCurrent();
  onStage('지갑을 확인합니다.');
  const accounts = await provider.request({ method: 'eth_requestAccounts' });
  assertCurrent();
  const address = accounts?.[0];
  if (!ADDRESS.test(address ?? '')) throw new ProofError('NO_ACCOUNT');
  onAccountReady(provider, address);
  await assertIdentity(provider, address, chainId, assertCurrent);
  onStage('로그인 메시지를 준비합니다.');
  const challenge = await post(fetchFn, '/api/v1/auth/wallet/nonce', { address, chainId }, assertCurrent);
  const message = validateChallenge(challenge, address, chainId, origin);
  await assertIdentity(provider, address, chainId, assertCurrent);
  onStage('로그인 메시지에 서명해 주세요.');
  const signature = await provider.request({ method: 'personal_sign', params: [utf8Hex(message), address] });
  assertCurrent();
  if (!SIGNATURE.test(signature ?? '')) throw new ProofError('BAD_SIGNATURE');
  await assertIdentity(provider, address, chainId, assertCurrent);
  const result = await post(fetchFn, '/api/v1/auth/wallet/verify', { message, signature }, assertCurrent);
  await assertIdentity(provider, address, chainId, assertCurrent);
  if (typeof result.accessToken !== 'string' || !result.accessToken ||
      result.tokenType !== 'Bearer' || !Number.isSafeInteger(result.expiresIn) || result.expiresIn <= 0 ||
      typeof result.user?.userId !== 'string' || !result.user.userId ||
      !Array.isArray(result.user.wallets) ||
      !result.user.wallets.some(wallet => typeof wallet?.address === 'string' &&
        wallet.address.toLowerCase() === address.toLowerCase())) throw new ProofError('BAD_RESPONSE');
  return { accessToken: result.accessToken, userId: result.user.userId, address };
}
