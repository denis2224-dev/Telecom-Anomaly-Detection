-- A finalized SMS feature remains pending until its Kafka send is acknowledged.
CREATE TABLE app.sms_kpi_delivery (
    window_id varchar(64) PRIMARY KEY REFERENCES app.feature_outbox (window_id),
    published_at timestamptz NOT NULL DEFAULT now()
);
REVOKE ALL ON app.sms_kpi_delivery FROM processing_app;
GRANT SELECT, INSERT ON app.sms_kpi_delivery TO processing_app;
