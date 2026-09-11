# Repository working agreements

- Read existing files, branch and git status before editing; preserve team work.
- Primary student owner: Zavtoni Ion, Streaming & Simulator. Keep changes to
  schemas, generation, Kafka flow, simulator tests and component documentation
  identifiable; explain any integration changes to another component.
- Use synthetic data only. Never include real customer records or identifiers.
- Before a feature, state its branch, short plan and intended atomic commits.
  Inspect diffs, stage only related files and run relevant checks before commits.
- Use meaningful conventional commit messages. Do not create empty commits,
  fabricate activity or backdate work. Do not merge to main automatically.
- Keep implementation understandable and incremental. Respect the requested
  day's scope; do not start a later milestone without a user instruction.
- Maintain schemas, examples, units, run/validation instructions and handoffs.
- Do not expose scenario labels in raw event fields. scenarioRunId is optional
  traceability metadata and must not influence features, rules or risk scoring.
- EventV1 uses JSON, UTC event time with Z, integer bytes, integer money in minor
  units, and Kafka topic telecom.events.v1 with key entityType:entityId.
