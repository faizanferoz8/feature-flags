-- Every tenant-owned table carries tenant_id, and every reference between two of them
-- goes through a composite key that includes it. Foreign-key checks are not subject to
-- row-level security, so without the composite keys a tenant that guessed another
-- tenant's row id could attach its own rows to it.

CREATE TABLE tenants (
    id         uuid PRIMARY KEY,
    name       text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id),
    email         text NOT NULL,
    password_hash text NOT NULL,
    role          text NOT NULL CHECK (role IN ('ADMIN', 'EDITOR', 'VIEWER')),
    created_at    timestamptz NOT NULL DEFAULT now()
);
-- An email identifies one user across the whole service, which is what lets login work
-- before the tenant is known.
CREATE UNIQUE INDEX users_email_key ON users (lower(email));
CREATE INDEX users_tenant_idx ON users (tenant_id);

CREATE TABLE environments (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenants (id),
    key        text NOT NULL,
    name       text NOT NULL,
    sort_order int  NOT NULL,
    -- Goes up by one in the same transaction as any change that affects what SDKs in
    -- this environment should evaluate. Updating it also serialises those changes.
    revision   bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id)
);

CREATE TABLE flags (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id),
    key         text NOT NULL CHECK (key ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    name        text NOT NULL,
    description text NOT NULL DEFAULT '',
    salt        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id)
);

CREATE TABLE flag_configs (
    id                     uuid PRIMARY KEY,
    tenant_id              uuid NOT NULL,
    flag_id                uuid NOT NULL,
    environment_id         uuid NOT NULL,
    enabled                boolean NOT NULL DEFAULT false,
    rules                  jsonb NOT NULL DEFAULT '[]',
    fallthrough_percentage int NOT NULL DEFAULT 100 CHECK (fallthrough_percentage BETWEEN 0 AND 100),
    version                bigint NOT NULL DEFAULT 0,
    updated_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (flag_id, environment_id),
    FOREIGN KEY (tenant_id, flag_id) REFERENCES flags (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, environment_id) REFERENCES environments (tenant_id, id)
);
CREATE INDEX flag_configs_environment_idx ON flag_configs (environment_id);

CREATE TABLE api_keys (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL,
    environment_id uuid NOT NULL,
    name           text NOT NULL,
    -- Enough of the key to recognise it in a list. The key itself is never stored.
    prefix         text NOT NULL,
    key_hash       text NOT NULL UNIQUE,
    created_by     text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    last_used_at   timestamptz,
    revoked_at     timestamptz,
    FOREIGN KEY (tenant_id, environment_id) REFERENCES environments (tenant_id, id)
);
CREATE INDEX api_keys_tenant_idx ON api_keys (tenant_id);

CREATE TABLE audit_events (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id),
    actor_id    uuid,
    actor_email text NOT NULL,
    action      text NOT NULL,
    target      text NOT NULL,
    environment text,
    before      jsonb,
    after       jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_tenant_idx ON audit_events (tenant_id, id DESC);
