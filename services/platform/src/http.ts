import type { IncomingMessage, ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';

export interface HealthSnapshot {
  readonly service: string;
  readonly environment: string;
  readonly status: 'ok';
}

export interface RouteResponse {
  readonly status: 200 | 404;
  readonly body: unknown;
}

export function resolveRequest(
  method: string | undefined,
  url: string | undefined,
  health: HealthSnapshot
): RouteResponse {
  if (method === 'GET' && url === '/health') {
    return { status: 200, body: health };
  }
  if (method === 'GET' && url === '/v1/capabilities') {
    return {
      status: 200,
      body: {
        apiVersion: 1,
        canonicalState: 'nostr',
        media: 'blossom',
        writeFeatures: []
      }
    };
  }
  return { status: 404, body: { error: { code: 'not_found' } } };
}

export function createRequestHandler(health: HealthSnapshot) {
  return function handle(request: IncomingMessage, response: ServerResponse): void {
    const requestId = request.headers['x-request-id']?.toString().slice(0, 128) ?? randomUUID();
    response.setHeader('content-type', 'application/json; charset=utf-8');
    response.setHeader('x-request-id', requestId);
    response.setHeader('cache-control', 'no-store');

    const resolved = resolveRequest(request.method, request.url, health);
    response.writeHead(resolved.status).end(JSON.stringify(resolved.body));
  };
}
