import test from 'node:test';
import assert from 'node:assert/strict';
import { createMagicConnector } from './magic-connector.mjs';

const key = 'pk_synthetic_for_test_only';
const origin = 'http://127.0.0.1:4173';
const address = '0x' + '1'.repeat(40);
const alternate = '0x' + '2'.repeat(40);
const nonce = 'a'.repeat(48);
const expiresAt = new Date(Date.now() + 300000).toISOString();
const message = `${origin} wants you to sign in with your Ethereum account:\n${address}\n\nSign in to Floww\n\nURI: ${origin}\nVersion: 1\nChain ID: 11155111\nNonce: ${nonce}\nIssued At: 2026-09-29T00:00:00Z\nExpiration Time: ${expiresAt}`;
const signature = '0x' + '3'.repeat(130);

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function until(predicate) {
  for (let i = 0; i < 100; i++) {
    if (predicate()) return;
    await new Promise(resolve => setImmediate(resolve));
  }
  assert.fail('Expected pending step was not reached');
}
function fixture({ otp = async () => 'discarded-DID', loggedIn = true, chain = '0xaa36a7',
  sign = async () => signature, nonceBody = { nonce, message, expiresAt },
  verifyBody = { accessToken: 'synthetic-JWT', tokenType: 'Bearer', expiresIn: 1800,
    user: { userId: 'synthetic-user', wallets: [{ address }] } },
  fetchOverride } = {}) {
  const calls = { load: 0, magic: [], otp: [], logout: 0, rpc: [], http: [], states: [] };
  const listeners = new Map();
  const provider = {
    account: address,
    chain,
    on(name, fn) { listeners.set(name, fn); },
    removeListener(name, fn) { if (listeners.get(name) === fn) listeners.delete(name); },
    emit(name, value) { listeners.get(name)?.(value); },
    async request(args) {
      calls.rpc.push(args);
      if (args.method === 'eth_requestAccounts' || args.method === 'eth_accounts') return [this.account];
      if (args.method === 'eth_chainId') return this.chain;
      if (args.method === 'personal_sign') return sign(args.params);
      throw new Error('unexpected provider method');
    }
  };
  class FakeMagic {
    constructor(publishableKey, options) {
      calls.magic.push({ publishableKey, options });
      this.rpcProvider = provider;
      this.auth = { loginWithEmailOTP: async input => { calls.otp.push(input); return otp(input); } };
      this.user = { isLoggedIn: async () => loggedIn, logout: async () => { calls.logout++; } };
    }
  }
  const fetchFn = fetchOverride ?? (async (path, options) => {
    calls.http.push({ path, options, payload: JSON.parse(options.body) });
    return { ok: true, json: async () => path.endsWith('/nonce') ? nonceBody : verifyBody };
  });
  const create = overrides => createMagicConnector({ publishableKey: key, origin,
    loadMagic: async () => { calls.load++; return { Magic: FakeMagic }; },
    fetchFn, onState: state => calls.states.push(state), ...overrides });
  return { calls, provider, create };
}

test('missing publishable key stops before SDK, OTP, wallet and API', async () => {
  const { calls, create } = fixture();
  const connector = create({ publishableKey: '' });
  await assert.rejects(connector.connect('person@example.test'), { code: 'MAGIC_UNAVAILABLE' });
  assert.equal(calls.load, 0);
  assert.equal(calls.otp.length, 0);
  assert.equal(calls.rpc.length, 0);
  assert.equal(calls.http.length, 0);
  assert.equal(connector.getAccessToken(), null);
});

test('OTP cancellation or failure never requests nonce and can retry', async () => {
  let fail = true;
  const { calls, create } = fixture({ otp: async () => { if (fail) throw new Error('cancelled'); } });
  const connector = create();
  await assert.rejects(connector.connect('person@example.test'), { code: 'MAGIC_OTP_FAILED' });
  assert.equal(calls.http.length, 0);
  assert.equal(connector.getAccessToken(), null);
  fail = false;
  await connector.connect('person@example.test');
  assert.equal(calls.http.length, 2);
});

test('Magic session must be authenticated after OTP', async () => {
  const { calls, create } = fixture({ loggedIn: false });
  await assert.rejects(create().connect('person@example.test'), { code: 'MAGIC_OTP_FAILED' });
  assert.equal(calls.http.length, 0);
});

test('Sepolia signs exact server SIWE UTF-8 bytes and verifies through fixed routes', async () => {
  const { calls, create } = fixture();
  const connector = create();
  const result = await connector.connect('person@example.test');
  assert.deepEqual(calls.magic[0], { publishableKey: key, options: { network: 'sepolia', deferPreload: true } });
  assert.deepEqual(calls.otp[0], { email: 'person@example.test' });
  assert.deepEqual(calls.http.map(call => call.path), ['/api/v1/auth/wallet/nonce', '/api/v1/auth/wallet/verify']);
  assert.deepEqual(calls.http[0].payload, { address, chainId: 11155111 });
  assert.deepEqual(calls.http[1].payload, { message, signature });
  assert.deepEqual(calls.rpc.find(call => call.method === 'personal_sign').params,
    ['0x' + Buffer.from(message, 'utf8').toString('hex'), address]);
  assert.equal(calls.http[1].options.credentials, 'same-origin');
  assert.equal(calls.http[1].options.cache, 'no-store');
  assert.deepEqual(result, { userId: 'synthetic-user', address });
  assert.equal(connector.getAccessToken(), 'synthetic-JWT');
  assert.ok(!JSON.stringify(calls.states).includes('synthetic-JWT'));
  assert.ok(!JSON.stringify(calls.http).includes('person@example.test'));
});

test('wrong chain, substituted address and substituted origin stop before signing', async () => {
  const wrongChain = fixture({ chain: '0x1' });
  await assert.rejects(wrongChain.create().connect('person@example.test'), { code: 'WRONG_CHAIN' });
  assert.equal(wrongChain.calls.http.length, 0);
  const wrongAddress = fixture({ nonceBody: { nonce, message: message.replace(address, alternate), expiresAt } });
  await assert.rejects(wrongAddress.create().connect('person@example.test'), { code: 'BAD_CHALLENGE' });
  assert.equal(wrongAddress.calls.rpc.some(call => call.method === 'personal_sign'), false);
  const wrongOrigin = fixture({ nonceBody: { nonce, message: message.replaceAll(origin, 'https://evil.example'), expiresAt } });
  await assert.rejects(wrongOrigin.create().connect('person@example.test'), { code: 'BAD_CHALLENGE' });
  assert.equal(wrongOrigin.calls.rpc.some(call => call.method === 'personal_sign'), false);
  const wrongSignedChain = fixture({ nonceBody: { nonce, message: message.replace('Chain ID: 11155111', 'Chain ID: 1'), expiresAt } });
  await assert.rejects(wrongSignedChain.create().connect('person@example.test'), { code: 'BAD_CHALLENGE' });
  assert.equal(wrongSignedChain.calls.rpc.some(call => call.method === 'personal_sign'), false);
});

test('configured chain and Magic network must agree', async () => {
  const { create, calls } = fixture();
  assert.throws(() => create({ chainId: 1 }), { code: 'INVALID_CONFIG' });
  assert.equal(calls.load, 0);
});

test('account switch during signature invalidates pending operation before verify', async () => {
  const pendingSign = deferred();
  const { create, calls, provider } = fixture({ sign: () => pendingSign.promise });
  const connector = create();
  const login = connector.connect('person@example.test');
  await until(() => calls.rpc.some(call => call.method === 'personal_sign'));
  provider.account = alternate;
  provider.emit('accountsChanged', [alternate]);
  pendingSign.resolve(signature);
  await assert.rejects(login, { code: 'STALE' });
  assert.equal(calls.http.length, 1);
  assert.equal(connector.getAccessToken(), null);
  assert.equal(calls.logout, 1);
});

test('chain change during signature invalidates pending operation before verify', async () => {
  const pendingSign = deferred();
  const { create, calls, provider } = fixture({ sign: () => pendingSign.promise });
  const connector = create();
  const login = connector.connect('person@example.test');
  await until(() => calls.rpc.some(call => call.method === 'personal_sign'));
  provider.chain = '0x1';
  provider.emit('chainChanged', '0x1');
  pendingSign.resolve(signature);
  await assert.rejects(login, { code: 'STALE' });
  assert.equal(calls.http.length, 1);
  assert.equal(connector.getAccessToken(), null);
});

test('disconnect during OTP or verify discards late completion and logs out once', async () => {
  const pendingOtp = deferred();
  const first = fixture({ otp: () => pendingOtp.promise });
  const one = first.create();
  const loginOne = one.connect('person@example.test');
  await until(() => first.calls.otp.length === 1);
  await one.disconnect();
  pendingOtp.resolve('discarded-DID');
  await assert.rejects(loginOne, { code: 'STALE' });
  assert.equal(first.calls.http.length, 0);
  assert.equal(first.calls.logout, 1);

  const pendingVerify = deferred();
  const second = fixture({ fetchOverride: async (path, options) => {
    second.calls.http.push({ path, options });
    return { ok: true, json: async () => path.endsWith('/nonce') ? { nonce, message, expiresAt } : pendingVerify.promise };
  } });
  const two = second.create();
  const loginTwo = two.connect('person@example.test');
  await until(() => second.calls.http.length === 2);
  await two.disconnect();
  pendingVerify.resolve({ accessToken: 'stale-JWT', tokenType: 'Bearer', expiresIn: 1800,
    user: { userId: 'synthetic-user', wallets: [{ address }] } });
  await assert.rejects(loginTwo, { code: 'STALE' });
  assert.equal(two.getAccessToken(), null);
  assert.equal(second.calls.logout, 1);
});

test('malformed server challenge or verify response cannot install a token', async () => {
  const badChallenge = fixture({ nonceBody: { nonce, message: '', expiresAt } });
  await assert.rejects(badChallenge.create().connect('person@example.test'), { code: 'BAD_CHALLENGE' });
  assert.equal(badChallenge.calls.http.length, 1);
  const badVerify = fixture({ verifyBody: { accessToken: 'pretend-JWT', tokenType: 'Bearer', expiresIn: 1800,
    user: { userId: 'synthetic-user', wallets: [{ address: alternate }] } } });
  const connector = badVerify.create();
  await assert.rejects(connector.connect('person@example.test'), { code: 'BAD_RESPONSE' });
  assert.equal(connector.getAccessToken(), null);
});

test('parallel clicks and repeated connect do not create duplicate OTP or sessions', async () => {
  const { create, calls } = fixture();
  const connector = create();
  const [first, second] = await Promise.all([connector.connect('person@example.test'), connector.connect('person@example.test')]);
  const third = await connector.connect('person@example.test');
  assert.deepEqual(first, second);
  assert.deepEqual(second, third);
  assert.equal(calls.otp.length, 1);
  assert.equal(calls.http.length, 2);
  await connector.disconnect();
  assert.equal(connector.getAccessToken(), null);
  assert.equal(calls.logout, 1);
});
