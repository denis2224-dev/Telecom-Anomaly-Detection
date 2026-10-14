CREATE TABLE app.trace_dataset (
    dataset_id VARCHAR(128) PRIMARY KEY,
    generator_version VARCHAR(128) NOT NULL,
    seed BIGINT NOT NULL,
    synthetic BOOLEAN NOT NULL,
    history_from TIMESTAMPTZ NOT NULL,
    history_to_exclusive TIMESTAMPTZ NOT NULL,
    source_resolution_seconds INTEGER NOT NULL CHECK (source_resolution_seconds = 300),
    timezone VARCHAR(64) NOT NULL CHECK (length(trim(timezone)) > 0),
    manifest_sha256 CHAR(64) NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (history_from < history_to_exclusive)
);

CREATE TABLE app.trace_catalogue (
    catalogue_version VARCHAR(128) PRIMARY KEY,
    dataset_id VARCHAR(128) NOT NULL REFERENCES app.trace_dataset(dataset_id),
    topology_version VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE UNIQUE INDEX trace_one_active_catalogue_idx ON app.trace_catalogue (active) WHERE active;

CREATE TABLE app.trace_inventory_city (
    catalogue_version VARCHAR(128) NOT NULL REFERENCES app.trace_catalogue(catalogue_version),
    city_id VARCHAR(32) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    latitude NUMERIC(9,6) NOT NULL,
    longitude NUMERIC(9,6) NOT NULL,
    demand_share NUMERIC(8,6) NOT NULL CHECK (demand_share >= 0 AND demand_share <= 1),
    active BOOLEAN NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    synthetic BOOLEAN NOT NULL,
    PRIMARY KEY (catalogue_version, city_id),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE TABLE app.trace_inventory_link (
    catalogue_version VARCHAR(128) NOT NULL REFERENCES app.trace_catalogue(catalogue_version),
    link_id VARCHAR(96) NOT NULL,
    endpoint_a_city_id VARCHAR(32) NOT NULL,
    endpoint_b_city_id VARCHAR(32) NOT NULL,
    link_type VARCHAR(32) NOT NULL,
    nominal_capacity_gbps NUMERIC(18,6) NOT NULL CHECK (nominal_capacity_gbps > 0),
    active BOOLEAN NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    synthetic BOOLEAN NOT NULL,
    PRIMARY KEY (catalogue_version, link_id),
    FOREIGN KEY (catalogue_version, endpoint_a_city_id) REFERENCES app.trace_inventory_city(catalogue_version, city_id),
    FOREIGN KEY (catalogue_version, endpoint_b_city_id) REFERENCES app.trace_inventory_city(catalogue_version, city_id),
    CHECK (endpoint_a_city_id <> endpoint_b_city_id),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE TABLE app.trace_link_direction (
    catalogue_version VARCHAR(128) NOT NULL,
    link_id VARCHAR(96) NOT NULL,
    direction_id VARCHAR(16) NOT NULL CHECK (direction_id IN ('a-to-b','b-to-a')),
    source_interface VARCHAR(96),
    destination_interface VARCHAR(96),
    effective_capacity_gbps NUMERIC(18,6) NOT NULL CHECK (effective_capacity_gbps > 0),
    capacity_valid_from TIMESTAMPTZ NOT NULL,
    capacity_valid_to TIMESTAMPTZ,
    measurement_source VARCHAR(128) NOT NULL,
    PRIMARY KEY (catalogue_version, link_id, direction_id, capacity_valid_from),
    FOREIGN KEY (catalogue_version, link_id) REFERENCES app.trace_inventory_link(catalogue_version, link_id),
    CHECK (capacity_valid_to IS NULL OR capacity_valid_to > capacity_valid_from)
);

CREATE TABLE app.trace_partner (
    catalogue_version VARCHAR(128) NOT NULL REFERENCES app.trace_catalogue(catalogue_version),
    partner_id VARCHAR(96) NOT NULL,
    country_id VARCHAR(8) NOT NULL,
    direction VARCHAR(3) NOT NULL CHECK (direction IN ('in','out')),
    active BOOLEAN NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    synthetic BOOLEAN NOT NULL,
    PRIMARY KEY (catalogue_version, partner_id, direction),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE TABLE app.trace_metric_window (
    dataset_id VARCHAR(128) NOT NULL REFERENCES app.trace_dataset(dataset_id),
    domain VARCHAR(32) NOT NULL CHECK (domain IN ('volte','transport','roaming','sms','mobile-data')),
    metric_id VARCHAR(96) NOT NULL,
    scope_type VARCHAR(32) NOT NULL CHECK (scope_type IN ('CITY','LINK_DIRECTION','PARTNER_DIRECTION')),
    scope_id VARCHAR(96) NOT NULL,
    direction VARCHAR(16) NOT NULL DEFAULT '',
    partner_id VARCHAR(96) NOT NULL DEFAULT '',
    procedure VARCHAR(96) NOT NULL DEFAULT '',
    bucket_start TIMESTAMPTZ NOT NULL,
    bucket_end TIMESTAMPTZ NOT NULL,
    source_resolution_seconds INTEGER NOT NULL CHECK (source_resolution_seconds = 300),
    aggregation_type VARCHAR(24) NOT NULL CHECK (aggregation_type IN ('COUNT_SUM','RATIO_OF_SUMS','WEIGHTED_MEAN','TIME_WEIGHTED_MEAN','HISTOGRAM_P95','SNAPSHOT_LAST','STATE_TIMELINE')),
    numerator NUMERIC(22,6),
    denominator NUMERIC(22,6),
    sum_value NUMERIC(22,6),
    sample_count BIGINT,
    histogram_counts JSONB,
    histogram_edges JSONB,
    snapshot_value NUMERIC(22,6),
    min_value NUMERIC(22,6),
    max_value NUMERIC(22,6),
    coverage NUMERIC(7,6) NOT NULL CHECK (coverage >= 0 AND coverage <= 1),
    quality VARCHAR(16) NOT NULL CHECK (quality IN ('VALID','PARTIAL','MISSING','STALE')),
    unit VARCHAR(32) NOT NULL,
    policy_version VARCHAR(160) NOT NULL,
    topology_version VARCHAR(160) NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 0),
    finalized BOOLEAN NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    PRIMARY KEY (dataset_id, domain, metric_id, scope_type, scope_id, direction, partner_id, procedure, bucket_start, policy_version, topology_version, revision),
    CHECK (bucket_end = bucket_start + INTERVAL '5 minutes'),
    CHECK (mod(EXTRACT(EPOCH FROM bucket_start)::BIGINT, 300) = 0),
    CHECK (numerator IS NULL OR numerator >= 0),
    CHECK (denominator IS NULL OR denominator >= 0),
    CHECK (sample_count IS NULL OR sample_count >= 0),
    CHECK (histogram_counts IS NULL OR jsonb_typeof(histogram_counts) = 'array'),
    CHECK (histogram_edges IS NULL OR jsonb_typeof(histogram_edges) = 'array')
);

REVOKE ALL ON app.trace_dataset, app.trace_catalogue, app.trace_inventory_city,
    app.trace_inventory_link, app.trace_link_direction, app.trace_partner,
    app.trace_metric_window FROM incidents_app;
GRANT SELECT, INSERT ON app.trace_dataset, app.trace_inventory_city,
    app.trace_inventory_link, app.trace_link_direction, app.trace_partner,
    app.trace_metric_window TO incidents_app;
GRANT SELECT, INSERT, UPDATE (active, valid_to) ON app.trace_catalogue TO incidents_app;
