CREATE SCHEMA IF NOT EXISTS bitos;

CREATE TABLE IF NOT EXISTS bitos.schema_migrations (
    version bigint PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now(),
    description text NOT NULL
);

COMMENT ON SCHEMA bitos IS 'Rebuildable BitOS projections and operational state; never private keys.';
