import test from 'node:test';
import assert from 'node:assert/strict';
import { createPreviewServer } from '../preview-server.mjs';

async function configFor(key) {
  const server = createPreviewServer({ key });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/magic-config.mjs`);
    assert.equal(response.status, 200);
    return response.text();
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
}

test('preview config exposes only a valid publishable key', async () => {
  assert.equal(await configFor('pk_synthetic_publishable_only'),
    'export const publishableKey = "pk_synthetic_publishable_only";\n');
  assert.equal(await configFor(''), 'export const publishableKey = "";\n');
});

test('secret-like or malformed preview configuration never reaches the response', async () => {
  const secret = 'sk_synthetic_secret_only';
  const malformed = 'pk_publishable\nexport const leaked = true';
  for (const value of [secret, malformed]) {
    const body = await configFor(value);
    assert.equal(body, 'export const publishableKey = "";\n');
    assert.ok(!body.includes(value));
  }
});
