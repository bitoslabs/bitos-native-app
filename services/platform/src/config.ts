export interface ServiceConfig {
  readonly name: 'api' | 'indexer' | 'worker';
  readonly environment: 'local' | 'staging' | 'production';
  readonly port: number;
}

export function loadConfig(name: ServiceConfig['name']): ServiceConfig {
  const environment = parseEnvironment(process.env.BITOS_ENV);
  const port = parsePort(
    process.env.PORT,
    name === 'api' ? 8080 : name === 'indexer' ? 8081 : 8082
  );
  return { name, environment, port };
}

function parseEnvironment(raw: string | undefined): ServiceConfig['environment'] {
  if (raw === 'staging' || raw === 'production') return raw;
  return 'local';
}

function parsePort(raw: string | undefined, fallback: number): number {
  if (!raw) return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 1 || value > 65_535) {
    throw new Error('PORT must be an integer from 1 to 65535');
  }
  return value;
}
