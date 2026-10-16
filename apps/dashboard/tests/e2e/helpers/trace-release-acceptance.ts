import assert from 'node:assert/strict';

export function requireArchive(dataset: any, manifest: any, checksum: string): void {
  assert.ok(dataset, 'The pinned persisted archive is not registered.');
  assert.equal(dataset.datasetId, manifest.dataset_id);
  assert.equal(dataset.archiveReady, true, 'The full archive projection must be complete.');
  assert.equal(dataset.synthetic, true);
  assert.equal(dataset.manifestSha256, checksum, 'Archive manifest checksum differs.');
  assert.equal(Date.parse(dataset.from), Date.parse(manifest.from));
  assert.equal(Date.parse(dataset.to), Date.parse(manifest.to_exclusive));
  assert.ok(dataset.catalogueVersion, 'Recorded inventory version is required.');
}

/** Read the server's RFC 4180 export, including JSON arrays and multiline reasons. */
export function csvRecords(csv: string): Record<string,string>[] {
  const rows: string[][] = []; let row: string[] = [], cell = '', quoted = false, closed = false;
  for (let index = 0; index < csv.length; index++) {
    const char = csv[index];
    if (quoted) {
      if (char !== '"') cell += char;
      else if (csv[index + 1] === '"') {cell += '"'; index++;}
      else {quoted = false; closed = true;}
    } else if (char === ',' || char === '\r' || char === '\n') {
      row.push(cell); cell = ''; closed = false;
      if (char !== ',') {
        if (char === '\r' && csv[index + 1] === '\n') index++;
        rows.push(row); row = [];
      }
    } else if (char === '"' && cell === '' && !closed) quoted = true;
    else {assert.ok(!closed && char !== '"', 'Malformed CSV quoting.'); cell += char;}
  }
  assert.equal(quoted, false, 'Unterminated CSV field.');
  if (row.length || cell || closed) rows.push([...row, cell]);
  const header = rows.shift(); assert.ok(header?.length, 'CSV header is required.');
  assert.equal(new Set(header).size, header.length, 'Duplicate CSV headers.');
  return rows.map(values => {
    assert.equal(values.length, header.length, 'CSV row has an incorrect field count.');
    return Object.fromEntries(header.map((name, index) => [name, values[index]]));
  });
}

export function requireExactExport(series: any, csv: string): void {
  const rows = csvRecords(csv);
  assert.equal(rows.length, series.points.length, 'CSV bucket count differs from JSON.');
  const metadata = ['datasetId','domain','metric','scopeType','scopeId','resolution','unit','aggregation','policyVersion','topologyVersion','sourceResolutionSeconds','synthetic'];
  const times = ['from','to','snapshotAt'];
  const numbers = ['value','coverage','numerator','denominator','sumValue','sampleCount','expectedSlots','receivedSlots','sourceCoverage','sourceAgeSeconds'];
  for (const [index, point] of series.points.entries()) {
    const row = rows[index];
    for (const key of metadata) assert.equal(row[key], series[key] == null ? '' : String(series[key]), key);
    for (const key of ['requestedFrom','requestedTo']) assert.equal(Date.parse(row[key]), Date.parse(series[key]), key);
    for (const key of times) assert.equal(row[key] ? Date.parse(row[key]) : null, point[key] == null ? null : Date.parse(point[key]), key);
    for (const key of numbers) {
      assert.ok(Object.hasOwn(row, key), `Missing CSV field: ${key}`);
      assert.equal(row[key] === '' ? null : Number(row[key]), point[key] ?? null, key);
    }
    for (const key of ['quality','nullReason','qualityReason','sourceQuality']) assert.equal(row[key], point[key] ?? '', key);
    for (const key of ['histogramCounts','histogramEdges','provenance']) assert.deepEqual(JSON.parse(row[key]), point[key] ?? null, key);
  }
}
