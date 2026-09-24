-- A processor-created expected window has no accepted observations until ingestion
-- inserts a real receipt. Only successful ingestion increments this count.
ALTER TABLE app.interval_bucket
    DROP CONSTRAINT interval_bucket_accepted_input_count_check;
ALTER TABLE app.interval_bucket
    ADD CONSTRAINT interval_bucket_accepted_input_count_check
    CHECK (accepted_input_count >= 0);
