from __future__ import annotations

import hashlib
import json
import os
import pathlib
import platform
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPO = 'better6666/jobpilot'
RELEASE = 'v1.0.30'
PACKAGES = {
    'windows-full': ('JobPilot-windows.zip', '8c4564c01dfddc99c12d3d8208c9cd2dc4166199dc0c40d58c8ca9c43d554769', 'bundled'),
    'windows-lite': ('JobPilot-1.0.30-windows-lite.zip', '1228be7ed7e2116fb525e5bb154f57bad72eca9fe2bbed63820ad225fc84a82f', 'msedge'),
    'macos': ('JobPilot-1.0.30.dmg', '32033b78853aac59354cf1dbbd7f8878576756efac7824ec09a704f323050e66', 'bundled'),
}
SOURCE = pathlib.Path(__file__).parent / 'ReleasePreviewSmoke.java'

def checked_json(port, path, data=None, headers=None):
    request = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=data, headers=headers or {})
    with urllib.request.urlopen(request, timeout=5) as reply:
        result = json.load(reply)
    if not result.get('success'):
        raise AssertionError(f'{path}: {result}')
    return result['data']

def verify_launcher(launcher, environment, work):
    with socket.socket() as port_socket:
        port_socket.bind(('127.0.0.1', 0))
        port = port_socket.getsockname()[1]
    output = work / 'launcher.log'
    with output.open('wb') as logfile:
        launched = subprocess.Popen([
            str(launcher), f'--server.port={port}', '--jobpilot.console=false', '--license.enabled=false',
            '--updates.manifest-url=http://127.0.0.1:1/updates.json',
        ], cwd=work, env=environment, stdout=logfile, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 75
            while time.monotonic() < deadline:
                if launched.poll() is not None:
                    raise RuntimeError(f'Official launcher stopped with {launched.returncode}')
                try:
                    health = checked_json(port, '/api/health')
                    if health['version'] != RELEASE[1:]:
                        raise AssertionError(health)
                    break
                except (OSError, urllib.error.URLError):
                    time.sleep(0.5)
            else:
                raise TimeoutError('Official launcher did not start')
            text = 'Synthetic resume for release preview only. Local test.'
            imported = checked_json(port, '/api/resumes/import?filename=synthetic.txt',
                                    text.encode('utf-8'), {'Content-Type': 'application/octet-stream'})
            assert text in imported['text'], imported
            print('PASS official launcher, v1.0.30 home API and synthetic resume import', flush=True)
        except BaseException:
            print(output.read_text(errors='replace')[-9000:], flush=True)
            raise
        finally:
            if launched.poll() is None:
                launched.terminate()
                try:
                    launched.wait(12)
                except subprocess.TimeoutExpired:
                    launched.kill()
                    launched.wait(10)

def main():
    mode = sys.argv[1]
    name, expected_sha, browser = PACKAGES[mode]
    work = pathlib.Path(os.environ['RUNNER_TEMP']) / f'jobpilot-official-preview-{mode}'
    work.mkdir(parents=True)
    archive = work / name
    os_name = platform.system()
    assert os_name == ('Darwin' if mode == 'macos' else 'Windows'), (mode, os_name)
    if mode == 'macos':
        assert platform.machine() == 'arm64', 'the public DMG is Apple Silicon'
    subprocess.run(['gh', 'release', 'download', RELEASE, '--repo', REPO, '--pattern', name, '--dir', str(work)],
                   check=True, timeout=150)
    with archive.open('rb') as inp:
        assert hashlib.file_digest(inp, 'sha256').hexdigest() == expected_sha, name
    print(f'PASS SHA-256 of the exact published {name}', flush=True)

    mount = work / 'dmg'
    mounted = False
    environment = os.environ.copy()
    environment.pop('GH_TOKEN', None)
    environment.pop('PSModulePath', None)
    test_home = work / 'profile'
    test_home.mkdir()
    environment['APPDATA'] = str(test_home)
    environment['JAVA_TOOL_OPTIONS'] = f'-Duser.home="{test_home}"'
    try:
        if mode == 'macos':
            mount.mkdir()
            subprocess.run(['hdiutil', 'attach', '-readonly', '-nobrowse', '-mountpoint', str(mount), str(archive)],
                           check=True, timeout=90)
            mounted = True
            official = mount / 'JobPilot.app' / 'Contents'
            launcher, app_files = official / 'MacOS/JobPilot', official / 'app'
        else:
            with zipfile.ZipFile(archive) as package:
                package.extractall(work)
            official = work / 'JobPilot'
            launcher, app_files = official / 'JobPilot.exe', official / 'app'
        jar = app_files / 'jobpilot-0.1.0.jar'
        assert jar.is_file() and launcher.is_file()
        if browser == 'bundled':
            bundled = app_files / 'browser'
            executable = (bundled / 'executable.txt').read_text().strip()
            assert (bundled / executable).is_file(), executable
        else:
            assert not (app_files / 'browser/executable.txt').is_file()
        verify_launcher(launcher, environment, work)

        dependencies = work / 'compile-deps'
        with zipfile.ZipFile(jar) as packaged_app:
            for entry in packaged_app.namelist():
                if entry.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
                    packaged_app.extract(entry, dependencies)
        compiled = work / 'probe'
        compiled.mkdir()
        cp = os.pathsep.join([str(dependencies / 'BOOT-INF/classes'), str(dependencies / 'BOOT-INF/lib' / '*')])
        jdk = pathlib.Path(os.environ['JAVA_HOME']) / 'bin'
        java = jdk / ('java.exe' if os_name == 'Windows' else 'java')
        javac = jdk / ('javac.exe' if os_name == 'Windows' else 'javac')
        subprocess.run([str(javac), '-encoding', 'UTF-8', '-cp', cp, '-d', str(compiled), str(SOURCE)], check=True, cwd=work)
        subprocess.run([
            str(java), f'-Dqa.browser.channel={browser}', f'-Djpackage.app-version={RELEASE[1:]}',
            f'-Dloader.path={compiled.as_posix()}', '-Dloader.main=com.jobpilot.ReleasePreviewSmoke',
            '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
        ], env=environment, cwd=work, check=True, timeout=150)
        print(f'PASS {mode} release delivery workbench end-to-end', flush=True)
    finally:
        if mounted:
            subprocess.run(['hdiutil', 'detach', str(mount)], check=True, timeout=35)

if __name__ == '__main__':
    main()
