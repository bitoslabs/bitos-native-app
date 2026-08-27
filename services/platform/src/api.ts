import { createServer } from 'node:http';
import { loadConfig } from './config.ts';
import { createRequestHandler } from './http.ts';

const config = loadConfig('api');
const server = createServer(
  createRequestHandler({ service: config.name, environment: config.environment, status: 'ok' })
);

server.listen(config.port, '0.0.0.0', () => {
  process.stdout.write(`api listening on ${config.port}\n`);
});

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
