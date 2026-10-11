CREATE INDEX trace_metric_series_idx ON app.trace_metric_window
    (dataset_id, domain, metric_id, scope_type, scope_id, bucket_start, revision DESC)
    WHERE finalized;
CREATE INDEX trace_metric_direction_idx ON app.trace_metric_window
    (dataset_id, scope_type, scope_id, direction, partner_id, procedure, bucket_start)
    WHERE finalized;
CREATE INDEX trace_city_active_idx ON app.trace_inventory_city
    (catalogue_version, active, city_id);
CREATE INDEX trace_link_city_a_idx ON app.trace_inventory_link
    (catalogue_version, endpoint_a_city_id, link_id);
CREATE INDEX trace_link_city_b_idx ON app.trace_inventory_link
    (catalogue_version, endpoint_b_city_id, link_id);
CREATE INDEX trace_partner_filter_idx ON app.trace_partner
    (catalogue_version, country_id, direction, partner_id);
