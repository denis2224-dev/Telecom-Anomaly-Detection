# Trace Link Release Candidate Capacity Evaluation

Evaluation Date: **15 October 2026** (Trace Link Release Increment).  
Owner: Bradu Stanislav (Infrastructure, Observability & Release Engineering)

## 1. Executive Summary

This document establishes the verified capacity envelope for the Trace Link system, combining measurements from the 120s/900s/60s/300s coordinated profile, the 5-minute real-time interval runs, and multi-domain read-model scaling tests.

All durable boundaries operate within bounded limits. Under 2x burst load (8 windows/sec sustained for 60s), queue depth peaked at 4 windows and returned to baseline within 0.37 seconds upon phase completion. Zero data loss, zero duplicate episodes, and zero transaction deadlocks were observed.

## 2. Capacity Profile Specification

The verified profile coordinates across all five telecom domains:
- **VoLTE Voice:** 5-minute aggregation, call setup success rate, call drop rate, MOS histogram.
- **Intercity Transport:** 12 directional links, throughput, nominal vs effective capacity, packet loss, RTT, jitter.
- **International Roaming:** Inbound and outbound registration, data, and voice procedures across partner networks.
- **Mobile Data (LTE):** EPS bearer activation success, DNS resolution success and latency.
- **SMS Delivery:** Queue conservation, submission/delivery success, backlog tracking.

### Load Profile Parameters

| Phase | Duration | Offered Rate | Representative Equivalent Load |
| :--- | :--- | :--- | :--- |
| **Warmup** | 120 s | 2 windows / s | ~120,000 synthetic events / min |
| **Steady** | 900 s | 4 windows / s | ~240,000 synthetic events / min |
| **Burst** | 60 s | 8 windows / s | ~480,000 synthetic events / min |
| **Recovery** | 300 s | 2 windows / s | ~120,000 synthetic events / min |

## 3. End-to-End Latency Percentiles

Percentiles calculated over 4,584 total processed windows across the run:

| Pipeline Stage | p50 | p95 | p99 | Max |
| :--- | :--- | :--- | :--- | :--- |
| **Window Ingestion & Schema Validation** | 2.1 ms | 3.5 ms | 5.1 ms | 8.2 ms |
| **Baseline & Rule Evaluation** | 4.1 ms | 8.1 ms | 11.5 ms | 16.4 ms |
| **Transactional Outbox Commit** | 86.9 ms | 178.8 ms | 254.6 ms | 312.0 ms |
| **Outbox to Kafka Delivery** | 108.3 ms | 177.2 ms | 289.2 ms | 398.5 ms |
| **Incident Read Model Commit** | 34.2 ms | 68.4 ms | 112.0 ms | 145.2 ms |
| **REST API Latency (`/api/v2/metrics/series`)** | 18.5 ms | 34.2 ms | 58.7 ms | 98.4 ms |
| **Grafana SQL Query Execution (`reporting.*`)** | 12.4 ms | 26.8 ms | 48.1 ms | 72.3 ms |

## 4. Boundedness and Backlog Controls

1. **Queue Bounds:**
   - Detector queue depth: maximum 4 windows observed (limit: 1,000).
   - In-flight outbox records: maximum 6 rows pending broker ACK (limit: 500).
   - SSE buffer: 0 dropped events observed.

2. **Drainage Performance:**
   - Post-burst drainage: 0.3699s to return queues to zero.
   - Database connection acquisition wait: 0ms (pool max 10 for applications, max 5 for Grafana reader).

3. **Memory and Heap Limits:**
   - JVM max heap capped at 384 MB per service.
   - Peak processor heap utilization: 162.5 MB (42.3% of limit).
   - Peak incident-service heap utilization: 178.0 MB (46.4% of limit).

## 5. Distinction Between Platform Availability and Service Impairment

- Platform outages (HTTP 5xx, database disconnects, Kafka broker restarts) trigger infrastructure alerts (`TraceLinkDatasourceUnavailable`, `TraceLinkDatabasePoolExhaustion`).
- Monitored telecom impairments (link packet loss > 2%, VoLTE drop rate breach) produce versioned incident episodes (`SUPPORTED_HYPOTHESIS` or `UNDETERMINED`) with immutable evidence.
- An infrastructure failure does not forge or synthesize telecom incident resolutions.
