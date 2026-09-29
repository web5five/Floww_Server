import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('./app.js', import.meta.url), 'utf8');
const message = 'Sign in to Floww\n한글';
const address = '0x' + '1'.repeat(40);

function element() {
  return {
    value: '', hidden: false, disabled: false, textContent: '', handlers: new Map(), options: [],
    addEventListener(name, fn) { this.handlers.set(name, fn); },
    append(option) { this.options.push(option); if (!this.value) this.value = option.value; },
    fire(name) { return this.handlers.get(name)?.(); }
  };
}

function provider() {
  const listeners = new Map();
  return {
    isMetaMask: true,
    account: address,
    chain: '0xaa36a7',
    sign: async () => '0x' + '2'.repeat(130),
    on(name, fn) { listeners.set(name, fn); },
    removeListener(name, fn) { if (listeners.get(name) === fn) listeners.delete(name); },
    emit(name) { listeners.get(name)?.(); },
    async request({ method, params }) {
      if (method === 'eth_requestAccounts' || method === 'eth_accounts') return [this.account];
      if (method === 'eth_chainId') return this.chain;
      if (method === 'personal_sign') return this.sign(params);
      throw Error(method);
    }
  };
}

function boot(wallet = provider()) {
  const nodes = Object.fromEntries(['#provider', '#status', '#connect', '#retry'].map(id => [id, element()]));
  const windowEvents = new Map();
  const calls = [];
  const context = {
    document: { querySelector: id => nodes[id], createElement: () => element() },
    window: {
      ethereum: wallet,
      addEventListener(name, fn) { windowEvents.set(name, fn); },
      dispatchEvent() { }
    },
    Event: class { constructor(type) { this.type = type; } },
    TextEncoder,
    async fetch(path, options) {
      calls.push({ path, body: JSON.parse(options.body) });
      return { ok: true, json: async () => path.endsWith('/nonce')
        ? { message, nonce: 'a'.repeat(48) }
        : { accessToken: 'synthetic-token', user: { userId: 'synthetic-user' } } };
    }
  };
  vm.createContext(context);
  vm.runInContext(source, context, { filename: 'app.js' });
  return { nodes, wallet, calls, windowEvents, context };
}

test('MetaMask signs exact UTF-8 bytes as RPC hex and verifies once', async () => {
  const { nodes, wallet, calls } = boot();
  let signed;
  wallet.sign = async params => { signed = params; return '0x' + '2'.repeat(130); };
  await nodes['#connect'].fire('click');
  assert.equal(signed[0], '0x' + Buffer.from(message, 'utf8').toString('hex'));
  assert.equal(signed[1], address);
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 1);
  assert.match(nodes['#status'].textContent, /synthetic-user/);
  assert.doesNotMatch(nodes['#status'].textContent, /synthetic-token/);
});

test('account switch while signing aborts verify; retry signs again', async () => {
  const { nodes, wallet, calls } = boot();
  let finish;
  wallet.sign = () => new Promise(resolve => { finish = resolve; });
  const pending = nodes['#connect'].fire('click');
  while (!finish) await new Promise(resolve => setImmediate(resolve));
  wallet.emit('accountsChanged');
  finish('0x' + '2'.repeat(130));
  await pending;
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 0);
  assert.equal(nodes['#retry'].hidden, false);
  wallet.sign = async () => '0x' + '2'.repeat(130);
  await nodes['#retry'].fire('click');
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 1);
});

test('wallet rejection and wrong chain do not submit verification', async () => {
  const { nodes, wallet, calls } = boot();
  wallet.sign = async () => { throw { code: 4001 }; };
  await nodes['#connect'].fire('click');
  assert.match(nodes['#status'].textContent, /취소/);
  wallet.chain = '0x1';
  await nodes['#retry'].fire('click');
  assert.match(nodes['#status'].textContent, /Sepolia/);
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 0);
});

test('provider selection change invalidates pending signature', async () => {
  const { nodes, wallet, calls, windowEvents } = boot();
  const other = provider();
  windowEvents.get('eip6963:announceProvider')({ detail: { info: { uuid: 'magic-later', name: 'Other wallet' }, provider: other } });
  let finish;
  wallet.sign = () => new Promise(resolve => { finish = resolve; });
  const pending = nodes['#connect'].fire('click');
  while (!finish) await new Promise(resolve => setImmediate(resolve));
  nodes['#provider'].value = 'magic-later';
  nodes['#provider'].fire('change');
  finish('0x' + '2'.repeat(130));
  await pending;
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 0);
  await nodes['#retry'].fire('click');
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 1);
  wallet.emit('disconnect');
  assert.match(nodes['#status'].textContent, /synthetic-user/);
});

test('stale rejected request cannot clear a newer successful session', async () => {
  const { nodes, wallet, context } = boot();
  let rejectOld;
  let first = true;
  wallet.sign = () => first ? new Promise((resolve, reject) => { rejectOld = reject; first = false; })
    : Promise.resolve('0x' + '2'.repeat(130));
  const oldRun = nodes['#connect'].fire('click');
  while (!rejectOld) await new Promise(resolve => setImmediate(resolve));
  wallet.emit('accountsChanged');
  await nodes['#retry'].fire('click');
  assert.equal(vm.runInContext('accessToken', context), 'synthetic-token');
  rejectOld({ code: 4001 });
  await oldRun;
  assert.equal(vm.runInContext('accessToken', context), 'synthetic-token');
  assert.match(nodes['#status'].textContent, /synthetic-user/);
});
