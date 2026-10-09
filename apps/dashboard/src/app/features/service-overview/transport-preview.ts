import { cities } from './dashboard-geography';

// Illustrative routes only: these are not catalogue-approved topology edges.
export const transportPreview = [
  { from: 'EDI', to: 'BAL', capacity: 10, utilization: 42, labelX: 90, labelY: 174 },
  { from: 'BAL', to: 'SOR', capacity: 4, utilization: 28, labelX: 250, labelY: 83 },
  { from: 'BAL', to: 'CHI', capacity: 10, utilization: 89, labelX: 267, labelY: 270 },
  { from: 'UNG', to: 'CHI', capacity: 7, utilization: 51, labelX: 118, labelY: 378 },
  { from: 'ORH', to: 'CHI', capacity: 4, utilization: 70, labelX: 450, labelY: 330 },
  { from: 'CHI', to: 'CAH', capacity: 10, utilization: 78, labelX: 258, labelY: 510 },
  { from: 'CAH', to: 'COM', capacity: 4, utilization: 62, labelX: 372, labelY: 637 },
].map(link => {
  const from = cities.find(city => city.id === link.from)!, to = cities.find(city => city.id === link.to)!;
  return { ...link, id: `${link.from}-${link.to}`, name: `${from.name} – ${to.name}`,
    x1: from.marker!.x * 6, y1: from.marker!.y * 7.4, x2: to.marker!.x * 6, y2: to.marker!.y * 7.4,
    traffic: (link.capacity * link.utilization / 100).toFixed(1),
    color: link.utilization >= 85 ? 'var(--danger)' : link.utilization >= 60 ? 'var(--warning)' : 'var(--success)' };
});
