import { loadConfig } from './config.ts';

const config = loadConfig('worker');
process.stdout.write(`${config.name} scaffold ready for sandboxed media jobs\n`);

const keepAlive = setInterval(() => undefined, 60_000);
for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    clearInterval(keepAlive);
    process.exit(0);
  });
}
