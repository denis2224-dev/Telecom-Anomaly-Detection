# TraceLink Documentation Portal

Welcome to the technical documentation for **TraceLink**, an anomaly detection and service assurance platform developed as an engineering internship project at Orange Systems Moldova by students from the Technical University of Moldova (UTM).

TraceLink detects, analyzes, correlates, and presents telecom service degradations using strictly synthetic, simulated network observations.

---

## Documentation Navigation

```text
docs/
├── architecture/       # System design, dataflow, and domain specifications
│   ├── README.md       # Architecture overview and component topologies
│   ├── owner-map.md    # Engineering ownership and team responsibilities
│   └── ...             # Detailed contract and projection specifications
├── runbooks/           # Operational, development, and deployment runbooks
│   ├── local-dev.md    # Primary developer setup guide (Docker, DB, Keycloak)
│   ├── streaming.md    # Guide to running and monitoring Kafka event streams
│   └── ...             # Specialist deployment and verification guides
├── tasks/              # Curated feature walkthroughs and user guides
│   ├── README.md       # Directory of end-to-end task demonstrations
│   └── ...             # Feature-specific guides (Login, KPI, Evidence)
├── user-guide/         # End-user analyst documentation
│   └── trace-link-analyst-guide.md  # Incident investigation user manual
├── ux/                 # UI branding, design system, and accessibility audits
├── evidence/           # Historical milestone submissions and verification records
│   ├── README.md       # Complete milestone index and verification catalog
│   └── assets/         # Pinned execution logs, reports, and screenshots
└── VERIFICATION.md     # Consolidated test execution and CI validation guide
```

---

## Core System Modules

| Module | Location | Primary Responsibilities | Technologies |
|---|---|---|---|
| **Event Generator** | [`services/event-generator`](../services/event-generator) | Deterministic synthetic observation generation, scenario playback, and health probes. | Java 21, Spring Boot 3, Kafka |
| **Streaming Support** | [`services/streaming-support`](../services/streaming-support) | Shared observation validation library, schema enforcement, and Kafka contracts. | Java 21, NetworkNT JSON Schema |
| **Processor** | [`services/processor`](../services/processor) | 60-second window aggregation, KPI computation, rule evaluation, ML inference client, and outbox episode delivery. | Java 21, Spring Data JPA, PostgreSQL, Kafka |
| **Incident Service** | [`services/incident-service`](../services/incident-service) | Protected REST & SSE APIs, incident life cycle, evidence persistence, and Keycloak session management. | Java 21, Spring Boot 4, Flyway, PostgreSQL |
| **ML Inference Service** | [`services/ml-service`](../services/ml-service) | Private HTTP model serving, Isolation Forest anomaly scoring, and offline candidate training. | Python 3.13, FastAPI, Scikit-learn |
| **Operations Dashboard** | [`apps/dashboard`](../apps/dashboard) | Protected web UI, real-time SSE streaming, interactive charts, and evidence inspection. | Angular 21, TypeScript, Vitest, Playwright |

---

## Quick Reference Guides

1. **New Developer Setup:** Read [`docs/runbooks/local-dev.md`](runbooks/local-dev.md) for step-by-step instructions on starting Docker containers, Keycloak authentication, and running the stack locally.
2. **Architecture & Topology:** Read [`docs/architecture/README.md`](architecture/README.md) for end-to-end data pipelines, detection rules, and reliability mechanisms.
3. **Running Tests:** Read [`docs/VERIFICATION.md`](VERIFICATION.md) for instructions on executing contracts, Python suites, Maven builds, and Angular unit tests.
4. **Milestone Evidence:** Read [`docs/evidence/README.md`](evidence/README.md) for the audit catalog indexing all historical deliverables and verification proofs.
