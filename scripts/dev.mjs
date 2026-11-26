import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { parseEnv } from 'node:util';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const envFile = resolve(root, '.env');
const local = existsSync(envFile) ? parseEnv(readFileSync(envFile, 'utf8')) : {};
function port(key, fallback) {
  const value = process.env[key] ?? local[key] ?? String(fallback);
  if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 65535) {
    throw new Error(`Invalid ${key}: expected TCP port 1–65535`);
  }
  return String(Number(value));
}
const env = { ...process.env, CORE_HOST_PORT: port('CORE_HOST_PORT', 8080), REALTIME_HOST_PORT: port('REALTIME_HOST_PORT', 8081) };
const mode = process.argv[2];
if (mode !== 'admin' && mode !== 'learner') throw new Error('Expected admin or learner');
const admin = mode === 'admin';
const cwd = resolve(root, 'apps', admin ? 'creator-admin' : 'learner-web');
const require = createRequire(resolve(cwd, 'package.json'));
const pkg = admin ? '@angular/cli' : 'vite';
const entry = resolve(dirname(require.resolve(`${pkg}/package.json`)), admin ? 'bin/ng.js' : 'bin/vite.js');
const args = admin
  ? ['serve', '--host', '127.0.0.1', '--port', port('ADMIN_HOST_PORT', 4200)]
  : ['--host', '127.0.0.1', '--port', port('LEARNER_HOST_PORT', 5173), '--strictPort'];
const child = spawn(process.execPath, [entry, ...args], { cwd, env, stdio: 'inherit' });
child.on('error', error => { console.error(error); process.exitCode = 1; });
child.on('exit', (code, signal) => { process.exitCode = code ?? (signal ? 1 : 0); });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
