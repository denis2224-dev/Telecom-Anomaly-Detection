# Repository working agreements

- Check the current branch, status and existing files before editing.
- Primary student owner: Zavtoni Ion, Streaming & Simulator. Keep changes to
  schemas, generation, Kafka flow, simulator tests and component documentation
  identifiable; explain any integration changes to another component.
- Use synthetic data only. Do not add real customer records or identifiers.
- Before a feature, state its branch, short plan and intended atomic commits.
  Inspect diffs, stage only related files and run relevant checks before commits.
- Use clear conventional commit messages. Do not create empty or backdated commits.
  Do not merge to main automatically.
- Keep changes small and understandable. Stay within the requested milestone.
- Maintain schemas, examples, units, run/validation instructions and handoffs.
- Keep scenario labels out of raw events. `scenarioRunId` is only for tracing a
  simulator run and must not affect features, rules or risk scores.
- EventV1 uses JSON, UTC timestamps ending in `Z`, integer bytes, money in integer
  minor units, topic `telecom.events.v1` and key `entityType:entityId`.
