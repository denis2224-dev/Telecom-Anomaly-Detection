# SMS rules and episode recovery handoff

Branch: `feat/sms-delay-queue-recovery-state`.

## Delivered

- `SmsDeliveryRule` evaluates the versioned delay thresholds and a separate backlog branch. Backlog remains evaluable with zero completed messages or no delay baseline when an authoritative, same-minute SMSC receipt matches the feature window.
- `RecoveryPolicy` applies two-breach opening and three-complete-window recovery. Voice episodes use it for UNKNOWN, gaps, gray zones and recurrence. UNKNOWN retains historical severity and impact with their source windows and event IDs.
- No SMS feature finalizer or Kafka episode publisher exists in this branch. The SMS rule is ready for Ion's finalized-window integration; model inference remains unavailable.

## Verification

On 25 September 2026, `python scripts/check-contracts.py` passed the observation, detection, voice and SMS parity checks. With JDK 21, `./mvnw -pl services/processor -am test` passed 58 streaming-support and 132 processor tests before the legacy-state regression was added. The final focused `SmsRuleTest,EpisodeStateTest,VoiceEpisodeTest` run passed 15 tests.

## Receiving checks

- Ion: confirm that finalized SMS features preserve distinct delivered-message counts and same-minute SMSC queue provenance.
- Denis: validate SMS evidence fields and historical UNKNOWN values against the receiver, including phase order and stable IDs.
- David: review the SMS probable-cause wording as a hypothesis and the independence of technical and analyst states.

No receiving-owner sign-off or live SMS delivery is claimed.
