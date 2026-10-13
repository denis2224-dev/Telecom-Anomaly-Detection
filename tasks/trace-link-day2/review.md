# Independent integration review

Reviewers: Agent A (baseline implementation, reviewing coordinator/cause boundaries), Agent B (rules implementation, reviewing coordinator publication/interfaces), Agent C (cause implementation, reviewing rule composition). Coordinator reviewed the combined diff and fixes using the code-review-and-quality skill.

Required findings and resolutions:

| Finding | Resolution / regression |
|---|---|
| Raw context could bypass registry with missing MAD, zero learned coverage or wrong versions | Baseline boundary validates configured policy, lookup provenance, declared peers, coverage and optional evidence baseline; configured static zero retained. Python/Java input regressions. |
| Capacity snapshot could precede its claimed event window | Day 2 requires snapshot timestamp within `[from,to)`. Capacity input regression observed RED. Day 1 contract remains unchanged. |
| Candidate identity tuple used ambiguous delimiter concatenation | Cause dedup uses structural JSON tuple; two distinct matching causes remain ambiguous. Shared cause fixture. |
| Mapped service result could refer to forged candidate values with a reused evidence ID | Exact independently validated service evidence and baseline must match candidate; mixed valid/forged candidates remain UNKNOWN. Transport/service regression. |
| Attached dependency metadata could disagree with selected authoritative relation | Non-null attachment must match relation ID, dependency target scope, topology and validity; raw null candidates remain valid join inputs. Positive and three negative cause fixtures. |
| Cause output vocabulary was open | Domain/namespace/revision and bounded cause reasons tightened; Python negative schema tests. |
| Service result identity omitted mapped partner/procedure | `serviceScope` is explicit and participates in lifecycle identity; two same-link service trajectories cannot share state. |
| Result accepted arbitrary anomaly kinds or mismatched roaming procedure | Closed known kinds and domain/procedure validation, with boundary regressions. |
| Published source SHA could describe an uncommitted implementation | Manifest records exact source hashes; final publication identifies implementation commit. |
| Stale Surefire XML could inflate fresh test count | Only reports newer than run start count; expected Day 2 suites are mandatory. Test exports also require fresh mtimes. |
| Rounded cause actual could move an exact loss/RTT support boundary | Primitive counter products determine support; four independent rounded-actual fixtures and focused Java regression. |
| Absent excluded rows were classified as missing in fit diagnostics | Counts derive from canonical expected UTC grid; Python RED/GREEN and Java regression. Complete reference artifact remains byte-identical. |
| Concurrent EPS/DNS recovery was only implicit in separate state instances | Interleaved real-rule trajectories prove neither procedure can recover or mutate the other. |
| Seasonal lookup with null Day 1 evidence baseline emitted no signed deviation | New result envelope emits original-unit actual-minus-center or null, including mapped service results; Java/Python regressions. |

No registration, Spring components, database migrations, public API modifications or model artifact changes were added. Components use explicit input time and caller-owned offline state. JSON/schema duplication is deliberate to preserve the unchanged Day 1 contract while enforcing additional Day 2 semantics. Historical artifact loading is an offline batch operation; live performance and state retention require Day 3 design.

Final acceptance requires the recorded full reproduction and final focused regression checks to pass. See the published report and manifest for actual results and skips.
