'use strict';
// Independent primitive arithmetic. The external pack is only a compatibility check.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const VOICE = [.5, 1, 2, 3, 5, 8, 15, 30, 60];
const SMS = [1, 2, 5, 10, 20, 40, 80, 160, 320];
const bad = () => { throw new Error('INVALID_INPUT'); };
const need = x => { if (!x) bad(); };
const number = x => { need(typeof x === 'number' && Number.isFinite(x) && x >= 0); return x; };
const count = x => { number(x); need(Number.isSafeInteger(x)); return x; };
const positive = x => { number(x); need(x > 0); return x; };
const text = x => { need(typeof x === 'string' && x.trim().length > 0); return x; };
const keys = (x, expected) => need(x && !Array.isArray(x) && Object.keys(x).sort().join('|') === expected.split(' ').sort().join('|'));
const timestamp = x => {
  need(typeof x === 'string' && !x.startsWith('0000') && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(x));
  const t = Date.parse(x); need(Number.isFinite(t));
  const canonical = x.replace(/(?:\.(\d{1,3}))?Z$/, (_, fraction) => '.' + (fraction || '').padEnd(3, '0') + 'Z');
  need(new Date(t).toISOString() === canonical); return t;
};
const aligned = x => { const t = timestamp(x); need(t % 300000 === 0); return t; };
function clean(x) {
  if (typeof x === 'number') need(Number.isFinite(x));
  if (Array.isArray(x)) x.forEach(clean);
  else if (x && typeof x === 'object') for (const [k, v] of Object.entries(x)) {
    need(['expectedRows', 'expectedSeconds'].includes(k) || !/(truth|expected|future|scenario|label|outcome)/i.test(k)); clean(v);
  }
}
function sum(xs) { return xs.reduce((a, b) => a + b, 0); }
function safeSum(xs) { return count(sum(xs)); }
function rate(n, d) { return d === 0 ? null : 100 * n / d; }
function p95(counts, edges) {
  const total = safeSum(counts); if (!total) return null;
  let running = 0;
  for (let i = 0; i < counts.length; i++) {
    if (counts[i] > 0 && running + counts[i] >= .95 * total)
      return (i ? edges[i - 1] : 0) + (edges[i] - (i ? edges[i - 1] : 0)) * (.95 * total - running) / counts[i];
    running += counts[i];
  }
  bad();
}
function bins(xs, length) { need(Array.isArray(xs) && xs.length === length); xs.forEach(count); return xs; }
const result = (primitives, values, quality = 'VALID', nullReason = null, coverage = 1, snapshotAt = null) =>
  ({primitives, values, quality, nullReason, coverage, snapshotAt});
function calculate(operation, input, window) {
  clean(input); clean(window); need(input && typeof input === 'object');
  const from = timestamp(window.from), to = timestamp(window.to); need(from < to);
  keys(window, 'from to');
  const inputKeys = {ratio: 'partitions', histogram: 'histograms outputUnit', utilization: 'segments expectedSeconds', queue: 'openingQueue submitted delivered terminalNegative closingQueue durationSeconds', city_summary: 'rows expectedRows', link_snapshot: 'linkId cityId at dataFrom dataTo maxAgeSeconds rows', roaming_summary: 'country direction cityShare maxAgeSeconds rows'};
  need(Object.hasOwn(inputKeys, operation)); keys(input, inputKeys[operation]);
  if (operation === 'ratio') {
    need(Array.isArray(input.partitions));
    input.partitions.forEach(p => { keys(p, 'numerator denominator'); need(count(p.numerator) <= count(p.denominator)); });
    const numerator = safeSum(input.partitions.map(p => p.numerator)), denominator = safeSum(input.partitions.map(p => p.denominator));
    return result({numerator, denominator}, {value: rate(numerator, denominator)}, denominator ? 'VALID' : 'INSUFFICIENT_DATA', denominator ? null : 'ZERO_DENOMINATOR');
  }
  if (operation === 'histogram') {
    need(Array.isArray(input.histograms) && input.histograms.length);
    const first = input.histograms[0]; need(Array.isArray(first.edges) && first.edges.length);
    need(['SECONDS', 'MILLISECONDS'].includes(first.unit) && ['SECONDS', 'MILLISECONDS'].includes(input.outputUnit));
    text(first.schemaVersion);
    first.edges.forEach((e, i) => need(positive(e) && (!i || e > first.edges[i - 1])));
    const counts = first.edges.map(() => 0);
    for (const h of input.histograms) {
      keys(h, 'counts edges unit schemaVersion');
      need(h.unit === first.unit && h.schemaVersion === first.schemaVersion && JSON.stringify(h.edges) === JSON.stringify(first.edges));
      bins(h.counts, counts.length).forEach((n, i) => { counts[i] = count(counts[i] + n); });
    }
    const sampleCount = safeSum(counts), value = p95(counts, first.edges);
    const scale = first.unit === input.outputUnit ? 1 : first.unit === 'SECONDS' ? 1000 : .001;
    return result({counts, edges: first.edges, sampleCount}, {p95: value === null ? null : value * scale}, sampleCount ? 'VALID' : 'INSUFFICIENT_DATA', sampleCount ? null : 'EMPTY_HISTOGRAM');
  }
  if (operation === 'utilization') {
    positive(input.expectedSeconds); need(Array.isArray(input.segments));
    let validSeconds = 0, trafficTime = 0, utilizationTime = 0, peak = 0, stale = false;
    for (const s of input.segments) {
      keys(s, 'trafficGbps effectiveCapacityGbps validSeconds quality');
      number(s.trafficGbps); positive(s.validSeconds); need(['VALID', 'MISSING', 'STALE'].includes(s.quality));
      if (s.effectiveCapacityGbps !== null) { positive(s.effectiveCapacityGbps); need(s.trafficGbps <= s.effectiveCapacityGbps); }
      stale ||= s.quality === 'STALE';
      if (s.quality === 'VALID' && s.effectiveCapacityGbps !== null && s.validSeconds > 0) {
        const u = 100 * s.trafficGbps / s.effectiveCapacityGbps;
        validSeconds += s.validSeconds; trafficTime += s.trafficGbps * s.validSeconds; utilizationTime += u * s.validSeconds; peak = Math.max(peak, u);
      }
    }
    const coverage = validSeconds / input.expectedSeconds; need(coverage <= 1);
    const primitives = {validSeconds, trafficTime, utilizationTime};
    const values = {meanPct: validSeconds ? utilizationTime / validSeconds : null, peakPct: validSeconds ? peak : null, trafficMeanGbps: validSeconds ? trafficTime / validSeconds : null, transferredGbEstimate: validSeconds ? trafficTime : null};
    return result(primitives, values, !validSeconds ? (stale ? 'STALE' : 'INSUFFICIENT_DATA') : coverage < 1 ? 'PARTIAL' : 'VALID', !validSeconds ? (stale ? 'STALE_SOURCE' : 'MISSING_CAPACITY') : coverage < 1 ? 'PARTIAL_COVERAGE' : null, coverage);
  }
  if (operation === 'queue') {
    const fields = ['openingQueue', 'submitted', 'delivered', 'terminalNegative', 'closingQueue']; fields.forEach(k => count(input[k])); positive(input.durationSeconds);
    need(input.closingQueue === input.openingQueue + input.submitted - input.delivered - input.terminalNegative);
    const primitives = Object.fromEntries([...fields, 'durationSeconds'].map(k => [k, input[k]]));
    const growth = input.closingQueue - input.openingQueue, finalized = count(input.delivered + input.terminalNegative);
    return result(primitives, {growth, growthPerSecond: growth / input.durationSeconds, finalizedSuccessPct: rate(input.delivered, finalized)}, finalized ? 'VALID' : 'INSUFFICIENT_DATA', finalized ? null : 'ZERO_DENOMINATOR');
  }
  if (operation === 'city_summary') {
    need(Array.isArray(input.rows)); positive(input.expectedRows); count(input.expectedRows); need(input.rows.length <= input.expectedRows);
    const sums = ['attempts', 'success', 'drops', 'holding', 'setupSum', 'smsSubmitted', 'smsDelivered', 'smsExpired', 'dataAttempts', 'dataSuccess', 'dnsAttempts', 'dnsSuccess', 'hoAttempts', 'hoSuccess'];
    const continuous = new Set(['holding', 'setupSum']);
    const primitives = Object.fromEntries(sums.map(k => [k, 0]));
    Object.assign(primitives, {voiceCounts: VOICE.map(() => 0), smsCounts: SMS.map(() => 0), throughputSum: 0, rowCount: input.rows.length, slotCount: 0, queue: 0, oldest: 0});
    const seen = new Set(), slots = new Set(), latest = new Map(); let last = null;
    for (const r of input.rows) {
      keys(r, 'cityId ts attempts success drops holding setupSum smsSubmitted smsDelivered smsExpired smsQueue smsOldest dataAttempts dataSuccess dnsAttempts dnsSuccess throughput hoAttempts hoSuccess voiceCounts smsCounts');
      text(r.cityId); const ts = aligned(r.ts); need(ts >= from && ts < to);
      const key = JSON.stringify([r.cityId, ts]); need(!seen.has(key)); seen.add(key); slots.add(ts);
      for (const k of sums) { (continuous.has(k) ? number : count)(r[k]); primitives[k] += r[k]; if (!continuous.has(k)) count(primitives[k]); }
      count(r.smsQueue); number(r.smsOldest); number(r.throughput); primitives.throughputSum += r.throughput;
      need(r.success <= r.attempts && r.drops <= r.success && r.dataSuccess <= r.dataAttempts && r.dnsSuccess <= r.dnsAttempts && r.hoSuccess <= r.hoAttempts);
      bins(r.voiceCounts, 9); bins(r.smsCounts, 9); need(safeSum(r.voiceCounts) === r.success && safeSum(r.smsCounts) === r.smsDelivered);
      r.voiceCounts.forEach((n, i) => { primitives.voiceCounts[i] = count(primitives.voiceCounts[i] + n); });
      r.smsCounts.forEach((n, i) => { primitives.smsCounts[i] = count(primitives.smsCounts[i] + n); });
      if (!latest.has(r.cityId) || ts > timestamp(latest.get(r.cityId).ts)) latest.set(r.cityId, r);
      if (last === null || ts > timestamp(last)) last = r.ts;
    }
    primitives.slotCount = slots.size; primitives.queue = safeSum([...latest.values()].map(r => r.smsQueue)); primitives.oldest = Math.max(0, ...[...latest.values()].map(r => r.smsOldest));
    const p = primitives;
    const values = {voiceSR: rate(p.success, p.attempts), dropRate: rate(p.drops, p.success), erlang: p.slotCount ? p.holding / (p.slotCount * 300) : null, setupMeanSeconds: p.success ? p.setupSum / p.success : null, setupP95Seconds: p95(p.voiceCounts, VOICE), smsP95Ms: p95(p.smsCounts, SMS) === null ? null : p95(p.smsCounts, SMS) * 1000, smsSR: rate(p.smsDelivered, p.smsDelivered + p.smsExpired), dataSR: rate(p.dataSuccess, p.dataAttempts), dnsSR: rate(p.dnsSuccess, p.dnsAttempts), hoSR: rate(p.hoSuccess, p.hoAttempts), throughputMbps: p.rowCount ? p.throughputSum / p.rowCount : null};
    const coverage = p.rowCount / input.expectedRows;
    if (new Set([...latest.values()].map(r => timestamp(r.ts))).size > 1) {
      p.queue = null; p.oldest = null;
      return result(p, values, 'PARTIAL', 'SNAPSHOT_MISALIGNED', coverage, null);
    }
    return result(p, values, !p.rowCount ? 'MISSING' : coverage < 1 ? 'PARTIAL' : 'VALID', !p.rowCount ? 'NO_OBSERVATIONS' : coverage < 1 ? 'PARTIAL_COVERAGE' : null, coverage, last);
  }
  if (operation === 'link_snapshot') {
    need(Array.isArray(input.rows)); number(input.maxAgeSeconds); text(input.linkId); text(input.cityId);
    const dataFrom = timestamp(input.dataFrom), dataTo = timestamp(input.dataTo); need(dataFrom < dataTo);
    const at = input.at === null ? to : timestamp(input.at); let selected = null;
    const seen = new Set();
    for (const r of input.rows) {
      keys(r, 'linkId a b ts trafficGbps effectiveCapacityGbps');
      const ts = aligned(r.ts); number(r.trafficGbps); if (r.effectiveCapacityGbps !== null) { positive(r.effectiveCapacityGbps); need(r.trafficGbps <= r.effectiveCapacityGbps); }
      ['linkId', 'a', 'b'].forEach(k => text(r[k])); need(r.a !== r.b);
      const key = JSON.stringify([r.linkId, ts]); need(!seen.has(key)); seen.add(key);
      if (r.linkId === input.linkId && (input.cityId === 'all' || [r.a, r.b].includes(input.cityId)) && ts >= dataFrom && ts < dataTo && (input.at === null ? ts >= from && ts < to : ts <= at) && (!selected || ts > timestamp(selected.ts))) selected = r;
    }
    const empty = reason => result({trafficGbps: null, effectiveCapacityGbps: null}, {utilizationPct: null, ageSeconds: null}, 'MISSING', reason, 0);
    if (at < dataFrom || (input.at === null ? from < dataFrom || to > dataTo : at >= dataTo)) return empty('OUTSIDE_DATASET');
    if (!selected) return empty('NO_OBSERVATIONS');
    const primitives = {trafficGbps: selected.trafficGbps, effectiveCapacityGbps: selected.effectiveCapacityGbps};
    const ageSeconds = (at - timestamp(selected.ts)) / 1000;
    if (ageSeconds > input.maxAgeSeconds) return result(primitives, {utilizationPct: null, ageSeconds}, 'STALE', 'STALE_SOURCE', 0, selected.ts);
    if (selected.effectiveCapacityGbps === null) return result(primitives, {utilizationPct: null, ageSeconds}, 'INSUFFICIENT_DATA', 'MISSING_CAPACITY', 0, selected.ts);
    return result(primitives, {utilizationPct: 100 * selected.trafficGbps / selected.effectiveCapacityGbps, ageSeconds}, 'VALID', null, 1, selected.ts);
  }
  if (operation === 'roaming_summary') {
    need(Array.isArray(input.rows) && (input.country === 'all' || /^[A-Z]{2}$/.test(text(input.country))) && ['out', 'in', 'both'].includes(input.direction)); positive(input.cityShare); need(input.cityShare <= 1); number(input.maxAgeSeconds);
    const fields = ['regAtt', 'regOk', 'dataAtt', 'dataOk', 'voiceAtt', 'voiceOk', 'smsAtt', 'smsOk'];
    const primitives = Object.fromEntries(fields.map(k => [k, 0])); primitives.users = 0;
    const latest = new Map(), seen = new Set(); let last = null;
    for (const r of input.rows) {
      keys(r, 'countryId partnerId direction ts users regAtt regOk dataAtt dataOk voiceAtt voiceOk smsAtt smsOk');
      const ts = aligned(r.ts); need(/^[A-Z]{2}$/.test(text(r.countryId)) && ['out', 'in'].includes(r.direction)); text(r.partnerId); count(r.users); fields.forEach(k => count(r[k]));
      for (const prefix of ['reg', 'data', 'voice', 'sms']) need(r[prefix + 'Ok'] <= r[prefix + 'Att']);
      const scope = JSON.stringify([r.countryId, r.partnerId, r.direction]), key = scope + ts; need(!seen.has(key)); seen.add(key);
      if (ts < from || ts >= to || (input.country !== 'all' && input.country !== r.countryId) || (input.direction !== 'both' && input.direction !== r.direction)) continue;
      fields.forEach(k => { primitives[k] = count(primitives[k] + r[k]); });
      if (!latest.has(scope) || ts > timestamp(latest.get(scope).ts)) latest.set(scope, r);
      if (last === null || ts > timestamp(last)) last = r.ts;
    }
    const rows = [...latest.values()]; primitives.users = safeSum(rows.map(r => r.users));
    const values = {users: rows.length ? safeSum(rows.map(r => Math.round(r.users * input.cityShare))) : null, regSR: rate(primitives.regOk, primitives.regAtt), dataSR: rate(primitives.dataOk, primitives.dataAtt), voiceSR: rate(primitives.voiceOk, primitives.voiceAtt), smsSR: rate(primitives.smsOk, primitives.smsAtt)};
    const stale = rows.some(r => (to - timestamp(r.ts)) / 1000 > input.maxAgeSeconds); if (stale) values.users = null;
    if (!stale && new Set(rows.map(r => timestamp(r.ts))).size > 1) {
      primitives.users = null; values.users = null;
      return result(primitives, values, 'PARTIAL', 'SNAPSHOT_MISALIGNED', 0, null);
    }
    return result(primitives, values, !rows.length ? 'MISSING' : stale ? 'STALE' : 'VALID', !rows.length ? 'NO_OBSERVATIONS' : stale ? 'STALE_SOURCE' : null, !rows.length || stale ? 0 : 1, last);
  }
  bad();
}
function execute(operation, input, window) {
  try { const r = calculate(operation, input, window); cleanFinite(r); return r; }
  catch (e) { if (e.message === 'INVALID_INPUT' || e instanceof TypeError) return {error: 'INVALID_INPUT'}; throw e; }
}
function cleanFinite(x) {
  if (typeof x === 'number') need(Number.isFinite(x));
  else if (x && typeof x === 'object') Object.values(x).forEach(cleanFinite);
}
function verifyReference(E, root) {
  let checks = 0;
  const equal = (a, b) => { need(a === b || (typeof a === 'number' && typeof b === 'number' && Math.abs(a - b) <= 1e-9)); checks++; };
  const window = {from: new Date(E.END - 2 * E.STEP).toISOString().replace('.000Z', 'Z'), to: new Date(E.END).toISOString().replace('.000Z', 'Z')};
  const iso = i => new Date(E.START + i * E.STEP).toISOString().replace('.000Z', 'Z');
  for (const city of ['chi', 'all']) {
    const cities = E.CITIES.filter(c => city === 'all' || c.id === city), rows = [];
    for (const c of cities) for (const i of [E.N - 2, E.N - 1]) {
      const r = E.getRow('cities', c.id, i);
      const fields = ['attempts', 'success', 'drops', 'holding', 'setupSum', 'smsSubmitted', 'smsDelivered', 'smsExpired', 'smsQueue', 'smsOldest', 'dataAttempts', 'dataSuccess', 'dnsAttempts', 'dnsSuccess', 'throughput', 'hoAttempts', 'hoSuccess'];
      rows.push({cityId: c.id, ts: iso(i), ...Object.fromEntries(fields.map(k => [k, r[k]])), voiceCounts: E.voiceBins.map((_, b) => r['vh' + b]), smsCounts: E.smsBins.map((_, b) => r['sh' + b])});
    }
    const a = execute('city_summary', {rows, expectedRows: rows.length}, window), b = E.citySummary(city, E.END - 2 * E.STEP, E.END); need(!a.error);
    for (const k of ['attempts', 'success', 'drops', 'holding', 'setupSum', 'smsSubmitted', 'smsDelivered', 'smsExpired', 'dataAttempts', 'dataSuccess', 'dnsAttempts', 'dnsSuccess', 'hoAttempts', 'hoSuccess']) equal(a.primitives[k], b[k]);
    for (const k of ['voiceSR', 'dropRate', 'erlang', 'smsSR', 'dataSR', 'dnsSR', 'hoSR']) equal(a.values[k], b[k]);
    equal(a.values.setupMeanSeconds, b.setupMean); equal(a.values.setupP95Seconds, b.setupP95); equal(a.values.smsP95Ms, b.smsP95 * 1000); equal(a.values.throughputMbps, b.throughput); equal(a.primitives.queue, b.smsQueue); equal(a.primitives.oldest, b.smsOldest);
  }
  for (const l of [E.LINKS[0], E.LINKS[6]]) {
    const raw = E.getRow('links', l.id, E.N - 1), at = iso(E.N - 1);
    const a = execute('link_snapshot', {linkId: l.id, cityId: l.b, at, dataFrom: iso(0), dataTo: window.to, maxAgeSeconds: 600, rows: [{linkId: l.id, a: l.a, b: l.b, ts: at, trafficGbps: raw.traffic, effectiveCapacityGbps: raw.capacity}]}, window);
    const b = E.linkSnapshot(l.id, E.END - E.STEP); need(!a.error); equal(a.values.utilizationPct, b.util); equal(a.primitives.trafficGbps, b.traffic); equal(a.primitives.effectiveCapacityGbps, b.capacity);
  }
  const rows = [];
  for (const country of E.COUNTRIES) for (const direction of ['out', 'in']) for (const i of [E.N - 2, E.N - 1]) {
    const r = E.getRow('roaming', country.id + '-' + direction, i), fields = ['users', 'regAtt', 'regOk', 'dataAtt', 'dataOk', 'voiceAtt', 'voiceOk', 'smsAtt', 'smsOk'];
    rows.push({countryId: country.id, partnerId: country.partner, direction, ts: iso(i), ...Object.fromEntries(fields.map(k => [k, r[k]]))});
  }
  for (const [city, direction, country] of [['all', 'out', 'all'], ['bal', 'both', 'all'], ['chi', 'in', 'RO']]) {
    const share = city === 'all' ? 1 : E.CITIES.find(c => c.id === city).share;
    const a = execute('roaming_summary', {country, direction, cityShare: share, maxAgeSeconds: 600, rows}, window), b = E.roamingSummary(city, E.END - 2 * E.STEP, E.END, direction, country); need(!a.error);
    equal(a.values.users, b.users); for (const k of ['regSR', 'dataSR', 'voiceSR', 'smsSR']) equal(a.values[k], b[k]);
  }
  // The generator serializes non-integers with toFixed(6). Compare sampled CSV
  // primitives to that known quantization bound, not the cross-calculator bound.
  const parseCsv = line => {
    const cells = []; let cell = '', quoted = false;
    for (let i = 0; i < line.length; i++) {
      const c = line[i];
      if (c === '"') { if (quoted && line[i + 1] === '"') { cell += '"'; i++; } else quoted = !quoted; }
      else if (c === ',' && !quoted) { cells.push(cell); cell = ''; }
      else cell += c;
    }
    cells.push(cell); return cells;
  };
  let sampledRows = 0, primitiveChecks = 0, maximumPrimitiveError = 0, maximumUtilizationError = 0, maximumUtilizationBound = 0;
  const mappings = [
    ['city_kpi_5m.csv', 'cities', 'city_id', {voice_attempts: 'attempts', voice_success: 'success', voice_drops: 'drops', voice_holding_seconds: 'holding', voice_baseline_erlang: 'baseline', voice_setup_elapsed_seconds: 'setupSum', sms_submitted: 'smsSubmitted', sms_delivered: 'smsDelivered', sms_expired: 'smsExpired', sms_queue: 'smsQueue', sms_oldest_seconds: 'smsOldest', data_attempts: 'dataAttempts', data_success: 'dataSuccess', dns_attempts: 'dnsAttempts', dns_success: 'dnsSuccess', user_throughput_mbps: 'throughput', ho_attempts: 'hoAttempts', ho_success: 'hoSuccess'}],
    ['link_kpi_5m.csv', 'links', 'link_id', {traffic_gbps: 'traffic', effective_capacity_gbps: 'capacity', loss_pct: 'loss', rtt_ms: 'rtt', jitter_ms: 'jitter', interface_errors: 'errors', queue_drops: 'drops'}],
    ['roaming_kpi_5m.csv', 'roaming', 'country_id', {active_users: 'users', reg_attempts: 'regAtt', reg_success: 'regOk', data_attempts: 'dataAtt', data_success: 'dataOk', voice_attempts: 'voiceAtt', voice_success: 'voiceOk', sms_attempts: 'smsAtt', sms_success: 'smsOk', rtt_ms: 'rtt'}],
  ];
  const epsilon = value => Number.isInteger(value) ? 0 : 5e-7 + 2 * Number.EPSILON * Math.max(1, Math.abs(value));
  for (const [file, kind, idField, mapping] of mappings) {
    const lines = fs.readFileSync(path.join(root, 'data', file), 'utf8').trim().split(/\r?\n/), header = parseCsv(lines.shift());
    for (const position of [0, Math.floor(lines.length / 2), lines.length - 1]) {
      const csv = Object.fromEntries(parseCsv(lines[position]).map((value, i) => [header[i], value]));
      const id = kind === 'roaming' ? csv.country_id + '-' + csv.direction : csv[idField];
      const index = (Date.parse(csv.ts) - E.START) / E.STEP, raw = E.getRow(kind, id, index); need(raw); sampledRows++;
      for (const [column, field] of Object.entries(mapping)) {
        const error = Math.abs(Number(csv[column]) - raw[field]); need(error <= epsilon(raw[field]));
        maximumPrimitiveError = Math.max(maximumPrimitiveError, error); primitiveChecks++;
      }
      if (kind === 'cities') for (const [column, prefix, edges] of [['voice_setup_hist', 'vh', E.voiceBins], ['sms_delay_hist', 'sh', E.smsBins]]) {
        const bins = csv[column].slice(1, -1).split(',').map(Number); need(bins.length === edges.length);
        bins.forEach((n, i) => { need(n === raw[prefix + i]); primitiveChecks++; });
      }
      if (kind === 'links') {
        const tc = Number(csv.traffic_gbps), cc = Number(csv.effective_capacity_gbps), tr = raw.traffic, cr = raw.capacity;
        const error = Math.abs(100 * tc / cc - 100 * tr / cr);
        const bound = 100 * (epsilon(tr) / cr + Math.abs(tc) * epsilon(cr) / (cc * cr)) + 2 * Number.EPSILON * 100;
        need(error <= bound); maximumUtilizationError = Math.max(maximumUtilizationError, error); maximumUtilizationBound = Math.max(maximumUtilizationBound, bound);
      }
    }
  }
  const report = {referenceCompatibility: 'PASS', checks, operations: ['city_summary', 'link_snapshot', 'roaming_summary'], adaptations: ['period snapshots exclude to; point at inclusive; outside coverage never clamped', 'mean utilization distinct from reference peak utilization', 'SQL city estimates round each partner/direction before sum', 'roaming procedure primitives remain raw; reference city weighting cancels in rates', 'SMS histogram seconds converted to milliseconds; frozen V2 nearest-rank unchanged'], csvQuantization: {status: 'PASS', sampledRows, primitiveChecks, decimalPlaces: 6, primitiveBound: '0.5e-6 plus floating representation allowance; integer primitives exact', maximumPrimitiveError, maximumUtilizationError, maximumUtilizationBound, utilizationBoundFormula: '100*(trafficError/capacityRaw + abs(trafficCsv)*capacityError/(capacityCsv*capacityRaw)) plus floating representation allowance', limitation: 'Nine deterministic sample rows; not a full CSV dataset import or completeness check'}};
  console.log(JSON.stringify(report)); return report;
}
function main() {
  const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, xs) => i % 2 ? a : [...a, [v, xs[i + 1]]], []));
  need(args['--fixtures'] && args['--output']);
  const bytes = fs.readFileSync(args['--fixtures']), suite = JSON.parse(bytes), results = {}, contexts = {};
  need(suite.suiteVersion === 'trace-link-arithmetic-v1' && Array.isArray(suite.cases));
  const reference = args['--reference-root'] ? require(path.join(path.resolve(args['--reference-root']), 'source', 'engine.js')) : null;
  if (reference) {
    const report = verifyReference(reference, path.resolve(args['--reference-root']));
    if (args['--reference-report']) {
      fs.mkdirSync(path.dirname(path.resolve(args['--reference-report'])), {recursive: true});
      fs.writeFileSync(args['--reference-report'], JSON.stringify(report, null, 2) + '\n');
    }
  }
  for (const c of suite.cases) {
    need(typeof c.id === 'string' && !Object.hasOwn(results, c.id));
    const r = execute(c.operation, c.input, c.window); results[c.id] = r;
    contexts[c.id] = {scope: c.scope, window: c.window, policyVersion: c.policyVersion, units: c.units};
    need(Boolean(r.error) === Boolean(c.expectedError));
    if (reference && !r.error && c.operation === 'ratio') {
      const value = reference.ratio(r.primitives.numerator, r.primitives.denominator);
      need(value === r.values.value || Math.abs(value - r.values.value) <= 1e-9);
    }
    if (reference && !r.error && c.operation === 'histogram') {
      const h = c.input.histograms[0], value = reference.quantile(r.primitives.counts, r.primitives.edges, .95);
      const scaled = value === null ? null : value * (h.unit === c.input.outputUnit ? 1 : h.unit === 'SECONDS' ? 1000 : .001);
      need(scaled === r.values.p95 || Math.abs(scaled - r.values.p95) <= 1e-9);
    }
  }
  fs.mkdirSync(path.dirname(path.resolve(args['--output'])), {recursive: true});
  fs.writeFileSync(args['--output'], JSON.stringify({fixtureSha256: crypto.createHash('sha256').update(bytes).digest('hex'), contexts, results}, null, 2) + '\n');
  console.log(`PASS: JavaScript exported ${Object.keys(results).length} cases${reference ? '; reference ratio/histogram compatibility checked' : ''}`);
}
if (require.main === module) main();
module.exports = {calculate: execute};
