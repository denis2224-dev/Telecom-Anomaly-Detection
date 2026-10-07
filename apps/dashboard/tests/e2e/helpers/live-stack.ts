import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

export const root = resolve(__dirname, '../../../../..');
export function command(file: string, args: string[], input?: string) {
  try { return execFileSync(file, args, { cwd: root, input, encoding: 'utf8', timeout: 60000,
    stdio: ['pipe', 'pipe', 'pipe'] }).trim(); }
  catch { throw new Error(`Local live-test prerequisite failed (${file}); sensitive output omitted.`); }
}
export function admin(args: string[], input?: string) {
  return command('docker', ['compose', 'exec', '-T', 'keycloak', 'bash', '-ec', `
    config=$(mktemp)
    trap 'rm -f "$config"' EXIT
    /opt/keycloak/bin/kcadm.sh config credentials --config "$config" --server http://localhost:8080/auth \
      --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
    /opt/keycloak/bin/kcadm.sh "$@" --config "$config"
  `, '--', ...args], input);
}
export function sql(query: string) {
  return command('docker', ['compose', 'exec', '-T', 'postgres', 'bash', '-ec',
    'exec psql -X -q -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d incidents_db'], query);
}
