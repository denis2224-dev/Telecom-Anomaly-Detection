import suite from '../../../../contracts/fixtures/detections/service-explanation-cases.json';
import type { components } from '../app/core/api/schema';
import { voiceIncidents } from './voice';
import { presentationFor } from './incident-presentation';
// Existing schema-validated processor trajectory; no richer runtime-only measurements.
const trajectory = suite.cases.find(item => item.id === 'sms-recovered')!;
export const smsWindows = trajectory.windows.map(item => item.feature) as components['schemas']['ServiceKpiWindow'][];
export const smsDetections = trajectory.detections as components['schemas']['ServiceDetection'][];
export const smsRange = { from: smsWindows[0].windowStart, to: smsWindows.at(-1)!.windowEnd };
const latest = smsDetections.at(-1)!;
export const smsIncidents: components['schemas']['Incident'][] = [{
  ...voiceIncidents[0], id: 'b28cadb5-dcfb-4a4e-bd80-6f4000000002', episodeId: latest.episodeId,
  service: 'SMS', scopeId: latest.scopeId, technicalState: latest.technicalState,
  severity: latest.severity, firstObservedAt: latest.firstObservedAt,
  lastObservedAt: latest.windowEnd, detectedAt: latest.detectedAt, updatedAt: latest.detectedAt,
  latestSequence: latest.sequence, latestDetection: latest,
  presentation: presentationFor('b28cadb5-dcfb-4a4e-bd80-6f4000000002', latest),
}];
