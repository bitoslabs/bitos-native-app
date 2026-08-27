import { loadConfig } from './config.ts';

const config = loadConfig('indexer');
process.stdout.write(`${config.name} scaffold ready for verified relay ingestion\n`);

const keepAlive = setInterval(() => undefined, 60_000);
for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    clearInterval(keepAlive);
    process.exit(0);
  });
}
