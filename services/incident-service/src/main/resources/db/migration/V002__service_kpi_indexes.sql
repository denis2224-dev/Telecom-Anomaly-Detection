-- Supports latest-scope lookup and bounded history for one service/scope.
-- Keep every versioned row; the API selects one received projection per minute.
CREATE INDEX service_kpi_scope_history_idx
    ON app.service_kpi_windows (
        scope_id,
        service,
        window_start DESC,
        received_at DESC,
        window_id DESC
    );
