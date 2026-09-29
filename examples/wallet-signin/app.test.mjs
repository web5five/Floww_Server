import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('./app.js', import.meta.url), 'utf8');
const message = 'Sign in to Floww\n한글';
const address = '0x' + '1'.repeat(40);

function element(kind = 'other') {
  const node = {
    value: '', hidden: false, disabled: false, textContent: '', handlers: new Map(), options: [],
    addEventListener(name, fn) { this.handlers.set(name, fn); },
    querySelector(selector) {
      const value = selector.match(/^option\[value="(.+)"\]$/)?.[1];
      return this.options.find(option => option.value === value);
    },
    fire(name, ...args) { return this.handlers.get(name)?.(...args); }
  };
  if (kind === 'select') {
    let selectedIndex = -1;
    Object.defineProperties(node, {
      value: {
        get() { return this.options[selectedIndex]?.value ?? ''; },
        set(value) { this.selectedIndex = this.options.findIndex(option => option.value === value); }
      },
      selectedIndex: {
        get() { return selectedIndex; },
        set(index) {
          selectedIndex = index;
          this.options.forEach((option, position) => { option.selected = position === index; });
        }
      }
    });
    node.append = function (option) {
      this.options.push(option);
      option.remove = () => {
        const index = this.options.indexOf(option);
        if (index < 0) return;
        this.options.splice(index, 1);
        if (selectedIndex === index) {
          this.selectedIndex = this.options.findIndex(item => !item.disabled);
        } else if (selectedIndex > index) {
          this.selectedIndex = selectedIndex - 1;
        }
      };
      // A disabled option is not auto-selected by this native-select stand-in.
      if (option.selected || (selectedIndex < 0 && !option.disabled)) {
        this.selectedIndex = this.options.length - 1;
      }
    };
  }
  return node;
}

function provider() {
  const listeners = new Map();
  return {
    isMetaMask: true,
    account: address,
    chain: '0xaa36a7',
    sign: async () => '0x' + '2'.repeat(130),
    permission: async function () { return [this.account]; },
    switch: async function () { this.chain = '0xaa36a7'; this.emit('chainChanged', this.chain); },
    on(name, fn) { listeners.set(name, fn); },
    removeListener(name, fn) { if (listeners.get(name) === fn) listeners.delete(name); },
    emit(name, value) { listeners.get(name)?.(value); },
    async request({ method, params }) {
      if (method === 'eth_requestAccounts') return this.permission();
      if (method === 'eth_accounts') return [this.account];
      if (method === 'eth_chainId') return this.chain;
      if (method === 'personal_sign') return this.sign(params);
      if (method === 'wallet_switchEthereumChain') return this.switch(params);
      throw Error(method);
    }
  };
}

function boot(wallet = provider(), { announceOnRequest, fetchImpl } = {}) {
  const nodes = Object.fromEntries(['#provider', '#status', '#connect', '#retry', '#switch-chain']
    .map(id => [id, element(id === '#provider' ? 'select' : 'other')]));
  const windowEvents = new Map();
  const calls = [];
  const context = {
    document: { querySelector: id => nodes[id], createElement: tag => element(tag) },
    window: {
      ethereum: wallet,
      addEventListener(name, fn) { windowEvents.set(name, fn); },
      dispatchEvent(event) { if (event.type === 'eip6963:requestProvider') announceOnRequest?.(windowEvents.get('eip6963:announceProvider')); }
    },
    Event: class { constructor(type) { this.type = type; } },
    TextEncoder,
    async fetch(path, options) {
      calls.push({ path, body: JSON.parse(options.body) });
      if (fetchImpl) return fetchImpl(path, options);
      return { ok: true, json: async () => path.endsWith('/nonce')
        ? { message, nonce: 'a'.repeat(48) }
        : { accessToken: 'synthetic-token', user: { userId: 'synthetic-user' } } };
    }
  };
  vm.createContext(context);
  vm.runInContext(source, context, { filename: 'app.js' });
  return { nodes, wallet, calls, windowEvents, context };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}
async function until(predicate) {
  while (!predicate()) await new Promise(resolve => setImmediate(resolve));
}
function announce(windowEvents, uuid, wallet, name = 'MetaMask') {
  windowEvents.get('eip6963:announceProvider')({ detail: { info: { uuid, name }, provider: wallet } });
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

test('missing provider has a disabled placeholder and late announcement recovers', async () => {
  const { nodes, calls, windowEvents } = boot(null);
  assert.equal(nodes['#provider'].disabled, true);
  assert.equal(nodes['#provider'].options[0].disabled, true);
  assert.equal(nodes['#provider'].options[0].selected, true);
  assert.equal(nodes['#provider'].selectedIndex, 0);
  assert.equal(nodes['#provider'].options[nodes['#provider'].selectedIndex].textContent, '사용 가능한 지갑 없음');
  assert.equal(nodes['#connect'].disabled, true);
  assert.match(nodes['#status'].textContent, /MetaMask/);
  assert.equal(calls.length, 0);
  announce(windowEvents, 'late-wallet', provider());
  assert.equal(nodes['#provider'].disabled, false);
  assert.equal(nodes['#provider'].value, 'late-wallet');
  assert.equal(nodes['#provider'].selectedIndex, 0);
  assert.equal(nodes['#connect'].disabled, false);
  assert.equal(nodes['#provider'].options.length, 1);
  await nodes['#connect'].fire('click');
  assert.match(nodes['#status'].textContent, /synthetic-user/);
});

test('announced provider replaces matching injected fallback but keeps distinct wallets', () => {
  const injected = provider();
  const { nodes, windowEvents } = boot(injected);
  announce(windowEvents, 'metamask-announced', injected);
  assert.deepEqual(nodes['#provider'].options.map(option => option.value), ['metamask-announced']);
  assert.equal(nodes['#provider'].value, 'metamask-announced');
  announce(windowEvents, 'another-uuid', injected);
  assert.equal(nodes['#provider'].options.length, 1);
  announce(windowEvents, 'other-wallet', provider(), 'Other wallet');
  assert.equal(nodes['#provider'].options.length, 2);
  assert.equal(nodes['#provider'].value, 'metamask-announced');
});

test('synchronous EIP-6963 discovery prevents fallback duplicate', () => {
  const injected = provider();
  const { nodes } = boot(injected, { announceOnRequest: listener => listener({
    detail: { info: { uuid: 'announced', name: 'MetaMask' }, provider: injected }
  }) });
  assert.deepEqual(nodes['#provider'].options.map(option => option.value), ['announced']);
});

test('initial permission accountsChanged for returned account continues to one verify', async () => {
  const { nodes, wallet, calls, context } = boot();
  wallet.permission = async function () {
    this.emit('accountsChanged', [this.account]);
    return [this.account];
  };
  await nodes['#connect'].fire('click');
  assert.equal(calls.filter(call => call.path.endsWith('/nonce')).length, 1);
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 1);
  assert.equal(vm.runInContext('accessToken', context), 'synthetic-token');
  assert.equal(nodes['#connect'].disabled, false);
});

test('conflicting account announcement during permission fails before nonce and restores controls', async () => {
  const { nodes, wallet, calls } = boot();
  wallet.permission = async function () {
    this.emit('accountsChanged', ['0x' + '3'.repeat(40)]);
    return [this.account];
  };
  await nodes['#connect'].fire('click');
  assert.equal(calls.length, 0);
  assert.match(nodes['#status'].textContent, /계정/);
  assert.equal(nodes['#connect'].disabled, false);
});

test('pending permission blocks repeat clicks and reports wallet pending code without raw RPC text', async () => {
  const { nodes, wallet, calls } = boot();
  const permission = deferred();
  let requests = 0;
  wallet.permission = () => { requests++; return permission.promise; };
  const first = nodes['#connect'].fire('click');
  assert.equal(nodes['#connect'].disabled, true);
  await nodes['#connect'].fire('click');
  assert.equal(requests, 1);
  permission.reject({ code: -32002, message: 'wallet_requestPermissions -32002 raw text' });
  await first;
  assert.match(nodes['#status'].textContent, /이전 요청/);
  assert.doesNotMatch(nodes['#status'].textContent, /wallet_requestPermissions|32002/);
  assert.equal(nodes['#connect'].disabled, false);
  assert.equal(calls.length, 0);
});

test('permission cancellation restores controls and does not call the server', async () => {
  const { nodes, wallet, calls } = boot();
  wallet.permission = async () => { throw { code: 4001 }; };
  await nodes['#connect'].fire('click');
  assert.match(nodes['#status'].textContent, /취소/);
  assert.equal(nodes['#connect'].disabled, false);
  assert.equal(calls.length, 0);
});

test('real account switch while signing invalidates and late signature cannot verify', async () => {
  const { nodes, wallet, calls, context } = boot();
  const signature = deferred();
  wallet.sign = () => signature.promise;
  const pending = nodes['#connect'].fire('click');
  await until(() => calls.some(call => call.path.endsWith('/nonce')));
  await until(() => nodes['#status'].textContent.includes('서명'));
  wallet.account = '0x' + '3'.repeat(40);
  wallet.emit('accountsChanged', [wallet.account]);
  assert.equal(nodes['#connect'].disabled, false);
  signature.resolve('0x' + '2'.repeat(130));
  await pending;
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 0);
  assert.equal(vm.runInContext('accessToken', context), null);
});

test('chain switch while signing invalidates and late signature cannot verify', async () => {
  const { nodes, wallet, calls, context } = boot();
  const signature = deferred();
  wallet.sign = () => signature.promise;
  const pending = nodes['#connect'].fire('click');
  await until(() => nodes['#status'].textContent.includes('서명'));
  wallet.chain = '0x1';
  wallet.emit('chainChanged', wallet.chain);
  signature.resolve('0x' + '2'.repeat(130));
  await pending;
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 0);
  assert.equal(vm.runInContext('accessToken', context), null);
  assert.equal(nodes['#connect'].disabled, false);
});

test('chain change during verify discards delayed success and restores controls', async () => {
  const verify = deferred();
  const { nodes, wallet, calls, context } = boot(provider(), {
    fetchImpl: async path => path.endsWith('/nonce')
      ? { ok: true, json: async () => ({ message }) }
      : verify.promise
  });
  const pending = nodes['#connect'].fire('click');
  await until(() => calls.some(call => call.path.endsWith('/verify')));
  wallet.chain = '0x1';
  wallet.emit('chainChanged', '0x1');
  assert.equal(nodes['#connect'].disabled, false);
  verify.resolve({ ok: true, json: async () => ({ accessToken: 'late-token', user: { userId: 'late-user' } }) });
  await pending;
  assert.equal(vm.runInContext('accessToken', context), null);
  assert.doesNotMatch(nodes['#status'].textContent, /late-user/);
});

test('account switch during verify discards delayed success', async () => {
  const verify = deferred();
  const { nodes, wallet, calls, context } = boot(provider(), {
    fetchImpl: async path => path.endsWith('/nonce')
      ? { ok: true, json: async () => ({ message }) }
      : verify.promise
  });
  const pending = nodes['#connect'].fire('click');
  await until(() => calls.some(call => call.path.endsWith('/verify')));
  wallet.account = '0x' + '3'.repeat(40);
  wallet.emit('accountsChanged', [wallet.account]);
  verify.resolve({ ok: true, json: async () => ({ accessToken: 'late-token', user: { userId: 'late-user' } }) });
  await pending;
  assert.equal(vm.runInContext('accessToken', context), null);
  assert.equal(nodes['#connect'].disabled, false);
});

test('Sepolia switch is user triggered and restarts login for same provider and account', async () => {
  const { nodes, wallet, calls } = boot();
  wallet.chain = '0x1';
  let requested;
  wallet.switch = async function (params) {
    requested = params;
    this.chain = '0xaa36a7';
    this.emit('chainChanged', this.chain);
  };
  await nodes['#connect'].fire('click');
  assert.equal(calls.length, 0);
  assert.equal(nodes['#switch-chain'].hidden, false);
  await nodes['#switch-chain'].fire('click');
  assert.deepEqual(JSON.parse(JSON.stringify(requested)), [{ chainId: '0xaa36a7' }]);
  assert.equal(calls.filter(call => call.path.endsWith('/verify')).length, 1);
  assert.match(nodes['#status'].textContent, /synthetic-user/);
});

test('rejected Sepolia switch stays recoverable and never starts nonce', async () => {
  const { nodes, wallet, calls } = boot();
  wallet.chain = '0x1';
  wallet.switch = async () => { throw { code: 4001 }; };
  await nodes['#connect'].fire('click');
  await nodes['#switch-chain'].fire('click');
  assert.match(nodes['#status'].textContent, /취소/);
  assert.equal(nodes['#switch-chain'].hidden, false);
  assert.equal(nodes['#connect'].disabled, false);
  assert.equal(calls.length, 0);
});

test('account change while Sepolia switch is pending cannot restart login', async () => {
  const { nodes, wallet, calls } = boot();
  wallet.chain = '0x1';
  const switchResult = deferred();
  wallet.switch = () => switchResult.promise;
  await nodes['#connect'].fire('click');
  const pending = nodes['#switch-chain'].fire('click');
  await until(() => nodes['#status'].textContent.includes('전환을'));
  wallet.account = '0x' + '3'.repeat(40);
  wallet.emit('accountsChanged', [wallet.account]);
  switchResult.resolve();
  await pending;
  assert.equal(calls.length, 0);
  assert.equal(nodes['#connect'].disabled, false);
});
