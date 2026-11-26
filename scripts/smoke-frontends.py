"""Start temporary local dev servers and verify their Core API proxy."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    # Use the launcher's .env resolution, with process environment taking precedence.
    port_json = subprocess.check_output([
        'node', '-e',
        "const fs=require('node:fs'), {parseEnv}=require('node:util');"
        "const e={...(fs.existsSync('.env')?parseEnv(fs.readFileSync('.env','utf8')):{}),...process.env};"
        "console.log(JSON.stringify([e.ADMIN_HOST_PORT||4200,e.LEARNER_HOST_PORT||5173]))",
    ], cwd=ROOT, text=True)
    jobs = []
    try:
        for name, port in zip(('admin', 'learner'), json.loads(port_json)):
            # Do not accidentally probe or stop an already-running user server.
            import socket
            with socket.socket() as sock:
                sock.settimeout(1)
                if sock.connect_ex(('127.0.0.1', int(port))) == 0:
                    raise RuntimeError(f'Port {port} is already in use')
            options = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
            job = subprocess.Popen(['node', 'scripts/dev.mjs', name], cwd=ROOT, **options)
            jobs.append(job)
            deadline = time.monotonic() + 120
            base = f'http://127.0.0.1:{port}'
            while time.monotonic() < deadline:
                if job.poll() is not None:
                    raise RuntimeError(f'{name} exited: {job.returncode}')
                try:
                    with urllib.request.urlopen(base + '/', timeout=5) as response:
                        assert response.status == 200
                    with urllib.request.urlopen(base + '/api/v1/status', timeout=5) as response:
                        assert json.load(response) == {'service': 'core-api', 'phase': '01'}
                    if name == 'admin':
                        with urllib.request.urlopen(base + '/realtime-health', timeout=5) as response:
                            assert json.load(response)['status'] == 'UP'
                    print(f'PASS {name} dev server and Core API proxy via {port}', flush=True)
                    break
                except (OSError, ValueError, urllib.error.URLError):
                    time.sleep(1)
            else:
                raise RuntimeError(f'{name} startup timeout')
    finally:
        for job in jobs:
            if job.poll() is None:
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(job.pid), '/T', '/F'], check=False, capture_output=True)
                else:
                    import signal
                    os.killpg(job.pid, signal.SIGTERM)
                job.wait(timeout=15)


if __name__ == '__main__':
    main()
