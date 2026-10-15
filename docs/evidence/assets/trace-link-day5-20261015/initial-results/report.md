# Trace Link Day 5 evaluation

Synthetic offline study; thresholds frozen from validation only.

| Mode | N | Precision | Recall | F1 | Episode TP/FP/FN |
|---|---:|---:|---:|---:|---|
| deterministic | 1680 | 1.0 | 0.9423076923076923 | 0.9702970297029703 | 65/0/0 |
| ml_shadow | 1680 | 0.31265508684863524 | 0.4846153846153846 | 0.38009049773755654 | unsupported |
| combined | 1680 | 1.0 | 0.9423076923076923 | 0.9702970297029703 | 65/0/0 |
| no_topology | 1680 | 1.0 | 0.9423076923076923 | 0.9702970297029703 | 65/0/0 |
| no_baseline | 1680 | 1.0 | 0.28846153846153844 | 0.44776119402985076 | 20/0/45 |

## Limitations
- paired scenario variants share seed; groups are not independent real-world trials
- normal daytime only; no night-time or continuous thirty-day coverage
- deterministic shipped fallback is STATIC; seasonal effectiveness is not established
- ML-only episode and cause metrics unsupported: no ML lifecycle or attribution policy
- cause-control benchmark is a frozen component oracle, not independent held-out causal truth
- no model promotion or production capacity claim; empirical ranks are not probabilities

Second-person runbook reproduction: PENDING. Failure-drill receipts are separate from classification results.
