-- Tenant isolation, enforced by Postgres instead of by every query remembering a WHERE.
--
-- Two roles are involved. The owner runs these migrations and owns the tables. The
-- application connects as ${appUser}, which owns nothing, so the policies below apply to
-- everything it does. Each transaction announces its tenant with
--     set_config('app.tenant_id', '<uuid>', true)
-- and from then on sees and writes only that tenant's rows. With no tenant announced,
-- current_tenant() is NULL, no row satisfies "tenant_id = NULL", and the application
-- sees nothing at all: forgetting to set the tenant fails closed.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${appUser}') THEN
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', '${appUser}', '${appPassword}');
    END IF;
END
$$;

CREATE FUNCTION current_tenant() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = current_tenant())
    WITH CHECK (id = current_tenant());

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY ['users', 'environments', 'flags', 'flag_configs', 'api_keys', 'audit_events']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        -- USING filters what a statement can see; WITH CHECK rejects a row it tries to
        -- write for anyone else.
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant())',
            t);
    END LOOP;
END
$$;

-- Row-level security is deliberately not FORCEd: the owner bypasses it, which is what
-- lets the two functions below look across tenants. They are the only cross-tenant
-- reads in the system, and both answer a question that has to be asked before the
-- tenant is known: "whose email is this?" and "whose API key is this?".

CREATE FUNCTION auth_find_user(p_email text)
    RETURNS TABLE (id uuid, tenant_id uuid, email text, password_hash text, role text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
    AS $$
        SELECT u.id, u.tenant_id, u.email, u.password_hash, u.role
        FROM users u
        WHERE lower(u.email) = lower(p_email)
    $$;

CREATE FUNCTION auth_find_api_key(p_key_hash text)
    RETURNS TABLE (id uuid, tenant_id uuid, environment_id uuid, environment_key text)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public, pg_temp
    AS $$
    BEGIN
        -- At most one write a minute per key, however busy the SDK is.
        UPDATE api_keys k
        SET last_used_at = now()
        WHERE k.key_hash = p_key_hash
          AND k.revoked_at IS NULL
          AND (k.last_used_at IS NULL OR k.last_used_at < now() - interval '1 minute');

        RETURN QUERY
            SELECT k.id, k.tenant_id, k.environment_id, e.key
            FROM api_keys k
            JOIN environments e ON e.id = k.environment_id
            WHERE k.key_hash = p_key_hash
              AND k.revoked_at IS NULL;
    END
    $$;

REVOKE ALL ON FUNCTION auth_find_user(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth_find_api_key(text) FROM PUBLIC;

DO $$
BEGIN
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth_find_user(text) TO %I', '${appUser}');
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth_find_api_key(text) TO %I', '${appUser}');
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON tenants, users, environments, flags, flag_configs, api_keys TO %I',
        '${appUser}');
    -- The audit log is append-only for the application: it can add and read events, and
    -- has no privilege to change or remove one.
    EXECUTE format('GRANT SELECT, INSERT ON audit_events TO %I', '${appUser}');
END
$$;
