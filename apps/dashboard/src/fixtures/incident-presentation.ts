import type { components } from '../app/core/api/schema';

type Incident = components['schemas']['Incident'];
type Detection = components['schemas']['ServiceDetection'];

export function presentationFor(id: string, detection: Detection): Incident['presentation'] {
  const stale = detection.phase === 'UNKNOWN';
  return {
    impactState: stale ? 'STALE' : detection.phase === 'RECOVERY' ? 'RECOVERED' : 'CURRENT',
    currentImpact: stale ? null : detection.impact,
    retainedImpact: stale ? detection.impact : null,
    impactSourceDetectionId: detection.detectionId,
    impactWindowEnd: detection.windowEnd,
    probableCause: detection.probableCause,
    causeConfidence: detection.causeConfidence,
    evidenceHistoryPath: `/api/incidents/${id}/detections`,
  };
}
