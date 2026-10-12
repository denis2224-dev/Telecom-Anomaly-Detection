-- Reference catalogue registration only. Legacy geography and source authority stay unchanged.
CREATE TABLE app.trace_source_catalogue (
    catalogue_version text PRIMARY KEY,
    scope_namespace text NOT NULL CHECK (scope_namespace = 'trace-link-reference-v1'),
    topology_version text NOT NULL,
    activation_status text NOT NULL CHECK (activation_status = 'CONTRACT_ONLY'),
    synthetic boolean NOT NULL CHECK (synthetic),
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    geography jsonb NOT NULL CHECK (jsonb_typeof(geography) = 'object'),
    topology jsonb NOT NULL CHECK (jsonb_typeof(topology) = 'object'),
    contract_sha256 char(64) NOT NULL CHECK (contract_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE TABLE app.trace_source_city (
    catalogue_version text NOT NULL REFERENCES app.trace_source_catalogue(catalogue_version),
    reference_city_id text NOT NULL,
    contract_city_id text NOT NULL,
    display_name text NOT NULL,
    latitude numeric NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude numeric NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    demand_share numeric NOT NULL CHECK (demand_share BETWEEN 0 AND 1),
    active boolean NOT NULL,
    PRIMARY KEY (catalogue_version, reference_city_id),
    UNIQUE (catalogue_version, contract_city_id)
);
CREATE TABLE app.trace_source_link (
    catalogue_version text NOT NULL REFERENCES app.trace_source_catalogue(catalogue_version),
    link_id text NOT NULL,
    endpoint_a_reference_city_id text NOT NULL,
    endpoint_b_reference_city_id text NOT NULL,
    nominal_capacity_gbps numeric NOT NULL CHECK (nominal_capacity_gbps > 0),
    PRIMARY KEY (catalogue_version, link_id),
    FOREIGN KEY (catalogue_version, endpoint_a_reference_city_id) REFERENCES app.trace_source_city,
    FOREIGN KEY (catalogue_version, endpoint_b_reference_city_id) REFERENCES app.trace_source_city,
    CHECK (endpoint_a_reference_city_id <> endpoint_b_reference_city_id)
);
CREATE TABLE app.trace_source_link_direction (
    catalogue_version text NOT NULL,
    link_id text NOT NULL,
    direction_id text NOT NULL CHECK (direction_id IN ('a-to-b', 'b-to-a')),
    source_available boolean NOT NULL,
    PRIMARY KEY (catalogue_version, link_id, direction_id),
    FOREIGN KEY (catalogue_version, link_id) REFERENCES app.trace_source_link
);
REVOKE ALL ON app.trace_source_catalogue, app.trace_source_city, app.trace_source_link,
    app.trace_source_link_direction FROM processing_app;
GRANT SELECT, INSERT ON app.trace_source_catalogue, app.trace_source_city, app.trace_source_link,
    app.trace_source_link_direction TO processing_app;
