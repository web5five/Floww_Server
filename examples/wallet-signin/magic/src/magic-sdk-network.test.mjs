import test from 'node:test';
import assert from 'node:assert/strict';
import { Magic } from 'magic-sdk';

const publishableKey = 'pk_synthetic_for_test_only';

function encodedNetwork(network) {
  const sdk = new Magic(publishableKey, { network, deferPreload: true });
  const options = JSON.parse(Buffer.from(sdk.parameters, 'base64').toString('utf8'));
  assert.equal(options.API_KEY, publishableKey);
  assert.equal(typeof sdk.rpcProvider.request, 'function');
  return options.ETH_NETWORK;
}

test('pinned Magic SDK carries Sepolia alias into its iframe configuration', () => {
  assert.equal(encodedNetwork('sepolia'), 'sepolia');
});

test('pinned Magic SDK carries the exact custom Sepolia RPC and chain ID', () => {
  const network = { rpcUrl: 'https://ethereum-sepolia-rpc.publicnode.com', chainId: 11155111 };
  assert.deepEqual(encodedNetwork(network), network);
});
