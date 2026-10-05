-- PROPOSAL ONLY: review and reserve a Flyway version on the shared integration SHA.
-- These are additive incident-database projections, not processing authority.
CREATE TABLE app.geo_catalogue_versions (
    catalogue_version VARCHAR(80) PRIMARY KEY,
    topology_version VARCHAR(160) NOT NULL,
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    activation_status VARCHAR(16) NOT NULL CHECK (activation_status IN ('CONTRACT_ONLY', 'ACTIVE')),
    effective_from TIMESTAMPTZ,
    synthetic BOOLEAN NOT NULL DEFAULT TRUE CHECK (synthetic),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (catalogue_version, topology_version),
    CHECK ((activation_status = 'ACTIVE') = (effective_from IS NOT NULL))
);

CREATE TABLE app.geo_cities (
    catalogue_version VARCHAR(80) NOT NULL REFERENCES app.geo_catalogue_versions,
    city_id CHAR(3) NOT NULL CHECK (city_id ~ '^[A-Z]{3}$'),
    display_name VARCHAR(120) NOT NULL CHECK (length(trim(display_name)) > 0),
    PRIMARY KEY (catalogue_version, city_id)
);

-- Country, city, containment and dependency metadata share one versioned node set.
-- The importer must additionally reject cycles, cross-city parents and wrong depth.
CREATE TABLE app.geo_nodes (
    catalogue_version VARCHAR(80) NOT NULL REFERENCES app.geo_catalogue_versions,
    node_id VARCHAR(96) NOT NULL,
    parent_node_id VARCHAR(96),
    city_id CHAR(3),
    node_type VARCHAR(16) NOT NULL CHECK (node_type IN
        ('COUNTRY', 'CITY', 'AGGREGATION', 'SITE', 'CELL', 'IMS', 'SMSC', 'TRANSPORT')),
    PRIMARY KEY (catalogue_version, node_id),
    FOREIGN KEY (catalogue_version, parent_node_id)
        REFERENCES app.geo_nodes (catalogue_version, node_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (catalogue_version, city_id)
        REFERENCES app.geo_cities (catalogue_version, city_id),
    CHECK (parent_node_id IS NULL OR parent_node_id <> node_id)
);

CREATE TABLE app.geo_scope_bindings (
    catalogue_version VARCHAR(80) NOT NULL,
    scope_id VARCHAR(96) NOT NULL,
    service VARCHAR(8) NOT NULL CHECK (service IN ('VOLTE', 'SMS')),
    city_id CHAR(3),
    legacy BOOLEAN NOT NULL,
    service_source_id VARCHAR(96) NOT NULL,
    footprint_node_id VARCHAR(96),
    PRIMARY KEY (catalogue_version, scope_id),
    UNIQUE (catalogue_version, city_id, service),
    FOREIGN KEY (catalogue_version) REFERENCES app.geo_catalogue_versions,
    FOREIGN KEY (catalogue_version, city_id)
        REFERENCES app.geo_cities (catalogue_version, city_id),
    FOREIGN KEY (catalogue_version, footprint_node_id)
        REFERENCES app.geo_nodes (catalogue_version, node_id),
    CHECK (legacy = (city_id IS NULL)),
    CHECK ((legacy AND footprint_node_id IS NULL) OR
           (NOT legacy AND footprint_node_id IS NOT NULL))
);

-- Roles are service dependency edges, never containment or traffic partitions.
CREATE TABLE app.geo_scope_roles (
    catalogue_version VARCHAR(80) NOT NULL,
    scope_id VARCHAR(96) NOT NULL,
    role VARCHAR(24) NOT NULL CHECK (role IN
        ('VOLTE_IMS', 'VOLTE_TRANSPORT', 'SMS_SMSC', 'SMS_TRANSPORT')),
    node_id VARCHAR(96) NOT NULL,
    source_id VARCHAR(96) NOT NULL,
    PRIMARY KEY (catalogue_version, scope_id, role),
    FOREIGN KEY (catalogue_version, scope_id)
        REFERENCES app.geo_scope_bindings (catalogue_version, scope_id),
    FOREIGN KEY (catalogue_version, node_id)
        REFERENCES app.geo_nodes (catalogue_version, node_id)
);

-- PR #48 asserts no inter-city links; an initial import leaves this table empty.
-- Future map edges require explicit synthetic catalogue evidence.
CREATE TABLE app.geo_configured_links (
    catalogue_version VARCHAR(80) NOT NULL,
    link_id VARCHAR(96) NOT NULL,
    from_city_id CHAR(3) NOT NULL,
    to_city_id CHAR(3) NOT NULL,
    synthetic BOOLEAN NOT NULL DEFAULT TRUE CHECK (synthetic),
    PRIMARY KEY (catalogue_version, link_id),
    FOREIGN KEY (catalogue_version, from_city_id)
        REFERENCES app.geo_cities (catalogue_version, city_id),
    FOREIGN KEY (catalogue_version, to_city_id)
        REFERENCES app.geo_cities (catalogue_version, city_id),
    CHECK (from_city_id <> to_city_id)
);

-- A consumer imports the signed catalogue atomically and validates exact authority
-- correspondence, unique reporters, required roles, leaf footprint and all ten pairs.
CREATE TABLE app.scope_window_coverage (
    coverage_id CHAR(64) PRIMARY KEY CHECK (coverage_id ~ '^[0-9a-f]{64}$'),
    window_id CHAR(64) NOT NULL CHECK (window_id ~ '^[0-9a-f]{64}$'),
    scope_id VARCHAR(96) NOT NULL,
    service VARCHAR(8) NOT NULL CHECK (service IN ('VOLTE', 'SMS')),
    catalogue_version VARCHAR(80) NOT NULL,
    topology_version VARCHAR(160) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    expected_source_ids JSONB NOT NULL CHECK (jsonb_typeof(expected_source_ids) = 'array'),
    received_source_ids JSONB NOT NULL CHECK (jsonb_typeof(received_source_ids) = 'array'),
    usable_source_ids JSONB NOT NULL CHECK (jsonb_typeof(usable_source_ids) = 'array'),
    source_issues JSONB NOT NULL CHECK (jsonb_typeof(source_issues) = 'array'),
    payload_sha256 CHAR(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (scope_id, service, window_start, topology_version, catalogue_version),
    FOREIGN KEY (catalogue_version, topology_version)
        REFERENCES app.geo_catalogue_versions (catalogue_version, topology_version),
    FOREIGN KEY (catalogue_version, scope_id)
        REFERENCES app.geo_scope_bindings (catalogue_version, scope_id),
    CHECK (window_end = window_start + INTERVAL '1 minute'),
    CHECK (EXTRACT(SECOND FROM window_start) = 0)
);

CREATE INDEX scope_window_coverage_lookup_idx
    ON app.scope_window_coverage (service, scope_id, window_start DESC);

-- Do not create/apply this migration until catalogue-version import, duplicate-body
-- hashing, source-set checks, exact windowId calculation, old rows and DB grants are
-- tested. The existing incident Flyway tests currently expect V001-V002 only.
