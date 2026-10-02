#!/usr/bin/env python3
"""Read-only local evidence collector; optional supported private scenario command."""
import argparse
import datetime as dt
import json
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def docker(*args, stdin=None):
    result = subprocess.run(["docker", "compose", *args], cwd=ROOT, input=stdin,
                            text=True, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout.strip()


def query(database, sql):
    user = "telecom_admin"
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if line.startswith("POSTGRES_USER="):
            user = line.split("=", 1)[1].strip() or user
    return json.loads(docker("exec", "-T", "postgres", "psql", "-U", user,
                             "-d", database, "-At", "-c", sql))


def instant(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(dt.timezone.utc).isoformat()


def history():
    result = query("processing_db", """
        SELECT row_to_json(h) FROM app.historical_bootstrap h WHERE bootstrap_id='initial-demo-v1'
    """)
    bounds = f"window_start >= '{instant(result['history_start'])}' AND window_start < '{instant(result['history_end'])}'"
    for name, table, database in (("features", "feature_outbox", "processing_db"),
                                  ("read_model", "service_kpi_windows", "incidents_db")):
        result[name] = query(database, f"""
            SELECT coalesce(json_agg(t),'[]') FROM (
              SELECT scope_id,count(*) AS windows,count(DISTINCT window_start) AS distinct_minutes,
                min(window_start) AS first,max(window_start) AS last
              FROM app.{table} WHERE {bounds} GROUP BY scope_id ORDER BY scope_id
            ) t
        """)
    result["raw"] = query("processing_db", f"""
        SELECT json_build_object('receipts',count(*),'initialization_receipts',count(*) FILTER
          (WHERE kafka_topic='synthetic-history-bootstrap'),'unique_events',count(DISTINCT event_id),
          'unique_natural_keys',count(DISTINCT (source_id,scope_id,kind,window_start)))
        FROM app.observation_receipt WHERE {bounds}
    """)
    result["bootstrap_rejections"] = query("processing_db", """
        SELECT coalesce(json_agg(t),'[]') FROM (
          SELECT reason_code,count(*) AS records FROM app.rejection_outbox
          WHERE kafka_topic='synthetic-history-bootstrap' GROUP BY reason_code
        ) t
    """)
    return result


def live(start, end):
    bounds = f"window_start >= '{instant(start)}' AND window_start < '{instant(end)}'"
    result = {"from": instant(start), "to": instant(end)}
    result["minutes"] = query("processing_db", f"""
        SELECT coalesce(json_agg(t),'[]') FROM (
          SELECT b.scope_id,b.window_start,b.accepted_input_count,b.finalized,
            (SELECT count(*) FROM app.observation_receipt r WHERE r.scope_id=b.scope_id AND r.window_start=b.window_start) AS receipts,
            (SELECT json_agg(json_build_object('source',r.source_id,'id',r.event_id,'kind',r.kind,
                'received_at',r.received_at,'lag_sec',extract(epoch FROM r.received_at-r.window_end)) ORDER BY r.source_id)
              FROM app.observation_receipt r WHERE r.scope_id=b.scope_id AND r.window_start=b.window_start) AS records,
            (SELECT json_agg(json_build_object('id',f.window_id,'quality',f.payload->>'quality','kpis',f.payload->'kpis'))
              FROM app.feature_outbox f WHERE f.scope_id=b.scope_id AND f.window_start=b.window_start) AS features
          FROM app.interval_bucket b WHERE {bounds} ORDER BY b.window_start,b.scope_id
        ) t
    """)
    result["read_model"] = query("incidents_db", f"""
        SELECT coalesce(json_agg(t),'[]') FROM (
          SELECT scope_id,window_start,window_id,quality FROM app.service_kpi_windows
          WHERE {bounds} ORDER BY window_start,scope_id
        ) t
    """)
    result["duplicate_natural_keys"] = query("processing_db", f"""
        SELECT count(*) FROM (SELECT 1 FROM app.observation_receipt WHERE {bounds}
          GROUP BY source_id,scope_id,kind,window_start HAVING count(*)>1) t
    """)
    result["rejections_since"] = query("processing_db", f"""
        SELECT coalesce(json_agg(t),'[]') FROM (SELECT reason_code,count(*) AS records
          FROM app.rejection_outbox WHERE created_at>='{instant(start)}'
          GROUP BY reason_code ORDER BY reason_code) t
    """)
    result["detections"] = query("processing_db", f"""
        SELECT coalesce(json_agg(t),'[]') FROM (
          SELECT id,payload FROM app.voice_delivery WHERE topic='telecom.detections.v2'
            AND (payload->>'windowStart')::timestamptz>='{instant(start)}'
            AND (payload->>'windowStart')::timestamptz<'{instant(end)}'
          ORDER BY created_at,id
        ) t
    """)
    return result


def start_scenario(kind, scope, start):
    when = dt.datetime.fromisoformat(instant(start))
    run_id = str(uuid.uuid4())
    body = {"scenarioType": kind, "scopeId": scope, "seed": 42,
            "scheduledStartAt": when.isoformat(), "scheduledEndAt": (when + dt.timedelta(minutes=8)).isoformat()}
    response = docker("exec", "-T", "event-generator", "curl", "--fail-with-body", "--silent",
                      "-X", "PUT", f"http://localhost:8081/internal/scenario-runs/{run_id}",
                      "-H", "Content-Type: application/json", "--data-binary", "@-", stdin=json.dumps(body))
    return json.loads(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("history", "live", "start-scenario"))
    parser.add_argument("--from", dest="start")
    parser.add_argument("--to", dest="end")
    parser.add_argument("--type", default="VOLTE_IMS_OVERLOAD")
    parser.add_argument("--scope", default="VOLTE-MD-CENTRAL")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.mode == "history":
        result = history()
    elif args.mode == "live":
        if not args.start or not args.end:
            parser.error("live requires --from and --to")
        result = live(args.start, args.end)
    else:
        if not args.start:
            parser.error("start-scenario requires aligned --from UTC start")
        result = start_scenario(args.type, args.scope, args.start)
    text = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
        print(f"Evidence saved to {args.output}")
    else:
        print(text)


if __name__ == "__main__":
    main()
