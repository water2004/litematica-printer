"""Run the existing production fixtures in CI and fail on missing evidence or assertions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = json.loads((ROOT / '.github/mapped-ci-inputs.json').read_text(encoding='utf-8'))
parser = argparse.ArgumentParser()
parser.add_argument('minecraft')
parser.add_argument('suite', choices=['functional', 'audit'])
args = parser.parse_args()
mc = args.minecraft
inputs = MANIFEST[mc]
output = ROOT / '.ci/results' / mc
output.mkdir(parents=True, exist_ok=True)

def download(row, directory):
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / row['name']
    for attempt in range(4):
        try:
            if not destination.exists() or hashlib.sha256(destination.read_bytes()).hexdigest() != row['sha256']:
                request = urllib.request.Request(row['url'], headers={'User-Agent': 'Litematica-production-CI'})
                with urllib.request.urlopen(request, timeout=120) as response:
                    destination.write_bytes(response.read())
            assert hashlib.sha256(destination.read_bytes()).hexdigest() == row['sha256'], row['name']
            return destination
        except Exception:
            if attempt == 3:
                raise
            destination.unlink(missing_ok=True)
            time.sleep(2 * (attempt + 1))

def prepare(scenario, kind, mode='full', miner=None, servux=False):
    directory = ROOT / '.ci/dependencies' / mc / scenario
    directory.mkdir(parents=True, exist_ok=True)
    for stale in directory.glob('*.jar'):
        stale.unlink()
    for row in inputs['dependencies']:
        download(row, directory)
    if kind == 'smoke':
        download(inputs['legacy'] if mode == 'legacy' else inputs['quick'], directory)
        if mode != 'nochain':
            download(inputs['chain'], directory)
    else:
        network = ROOT / '.ci/network/build/libs' / ('network-chaos-audit-' + mc + '.jar')
        shutil.copy2(network, directory / network.name)
        if kind == 'stress':
            download(inputs['quick'], directory)
            server = directory / 'server'
            server.mkdir(exist_ok=True)
            for stale in server.glob('*.jar'):
                stale.unlink()
            for path in directory.glob('*.jar'):
                if path.name.startswith(('fabric-api-', 'network-chaos-', 'quickshulker-')):
                    shutil.copy2(path, server / path.name)
            if servux:
                download(inputs['servux'], server)
        else:
            download(inputs['miners'][miner], directory)
    hashes = [{'name': p.name, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in directory.glob('*.jar')]
    (output / scenario).mkdir(parents=True, exist_ok=True)
    (output / scenario / 'input-hashes.json').write_text(json.dumps(hashes, indent=2), encoding='utf-8')

def command(kind, scenario, mode='full', miner='none', servux=False, task='runMappedClientCI'):
    wrapper = os.environ.get('MAPPED_CI_GRADLE', 'gradlew.bat' if os.name == 'nt' else './gradlew')
    return [wrapper, '-Ptargets=' + mc, *(['-Ploader_version=' + os.environ['MAPPED_CI_LOADER']] if 'MAPPED_CI_LOADER' in os.environ else []), *(['-PenableAudit=true'] if kind != 'smoke' else []), '-I', '.github/scripts/mapped-ci.gradle',
            ':' + mc + ':' + task, '-PciKind=' + kind, '-PciMode=' + mode,
            '-PciScenario=' + scenario, '-PciMiner=' + miner, '-PciServux=' + str(servux).lower(),
            '--no-daemon', '--max-workers=2', '--console=plain', '--stacktrace',
            '-Dorg.gradle.jvmargs=-Xmx1200M -Dfile.encoding=UTF-8']

def run(kind, scenario, mode='full', miner='none', servux=False):
    prepare(scenario, kind, mode, miner, servux)
    directory = output / scenario
    server = None
    server_log = None
    try:
        if kind == 'stress':
            bridge = directory / 'bridge'
            bridge.mkdir(exist_ok=True)
            for path in bridge.glob('*'):
                if path.is_file():
                    path.unlink()
            server_log = (directory / 'server-console.log').open('w', encoding='utf-8')
            server = subprocess.Popen(command(kind, scenario, servux=servux, task='runMappedServerCI'),
                                      cwd=ROOT, stdout=server_log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 360
            while 'Done (' not in (directory / 'server-console.log').read_text(encoding='utf-8', errors='replace'):
                if server.poll() is not None or time.monotonic() > deadline:
                    if server.poll() is None:
                        server.terminate()
                    raise RuntimeError('Dedicated production server did not start: ' + scenario)
                time.sleep(1)
        with (directory / 'console.log').open('w', encoding='utf-8') as log:
            completed = subprocess.run(command(kind, scenario, mode, miner, servux), cwd=ROOT,
                                       stdout=log, stderr=subprocess.STDOUT, timeout=600)
        if completed.returncode:
            print((directory / 'console.log').read_text(encoding='utf-8', errors='replace')[-12000:])
            raise AssertionError('Production client process failed: ' + scenario)
        if kind == 'smoke':
            result = (directory / 'printer-smoke-result.txt').read_text(encoding='utf-8').splitlines()
            expected = 16 if mode == 'nochain' else (19 if mc == '1.21.10' else 18)
            assert result[0] == 'PASS' and len(result) - 1 == expected, (scenario, result)
            assert sum(line.startswith('ice-water-floor-') for line in result) == 5
            assert sum(line.startswith('fill-height-filter-') for line in result) == 3
            if mode != 'nochain':
                assert 'chainvein-production-reflection-and-mining' in result
        else:
            result = json.loads((directory / 'result.json').read_text(encoding='utf-8'))
            assert result['status'] == 'PASS', result.get('error', result)
            if kind == 'stress':
                assert result['clientBusy'] is False and result['materialConservationDelta'] == 0
                if servux:
                    assert result['server']['wrong'] == 0
        print('PASS', mc, scenario, flush=True)
    finally:
        if server is not None:
            (directory / 'bridge/stop').write_text('stop', encoding='utf-8')
            try:
                server.wait(timeout=40)
            except subprocess.TimeoutExpired:
                server.terminate()
                server.wait(timeout=20)
            server_log.close()

failures = []
if args.suite == 'functional':
    # Missing public Printer or companion files must fail independently of builds.
    for key in ['printerRelease', 'chain', 'quick']:
        download(inputs[key], ROOT / '.ci/published' / mc)
    scenarios = [('smoke', mode, mode, 'none', False) for mode in ['full', 'legacy', 'nochain']]
else:
    scenarios = [('bedrock', 'bedrock-' + miner, 'full', miner, False) for miner in ['bedrockminer', 'blockminer']]
    scenarios += [('stress', 'loss-servux-off', 'full', 'none', False),
                  ('stress', 'loss-servux-on', 'full', 'none', True)]
for kind, scenario, mode, miner, servux in scenarios:
    try:
        run(kind, scenario, mode, miner, servux)
    except Exception as error:
        failures.append({'scenario': scenario, 'error': str(error)})
        print('FAIL', mc, scenario, error, flush=True)
(output / (args.suite + '-summary.json')).write_text(json.dumps({'minecraft': mc,
    'status': 'FAIL' if failures else 'PASS', 'failures': failures}, indent=2), encoding='utf-8')
raise SystemExit(1 if failures else 0)
