import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveRequest } from '../src/http.ts';

test('health response is bounded and does not expose configuration', () => {
  const response = resolveRequest('GET', '/health', {
    service: 'api',
    environment: 'local',
    status: 'ok'
  });
  assert.equal(response.status, 200);
  assert.deepEqual(response.body, { service: 'api', environment: 'local', status: 'ok' });
});

test('unknown routes expose a stable error code', () => {
  const response = resolveRequest('POST', '/unknown', {
    service: 'api',
    environment: 'local',
    status: 'ok'
  });
  assert.equal(response.status, 404);
  assert.deepEqual(response.body, { error: { code: 'not_found' } });
});
