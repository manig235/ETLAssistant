#!/usr/bin/env python3
"""Exercise the device-build publication gate without network or credentials."""
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / '.github/scripts/ios-runtime-proof.py'

class ProofTests(unittest.TestCase):
    def execute(self, state, conclusions, allow=False, changed=False, wait=0):
        run = {'workflow_id': 1, 'event': 'workflow_dispatch', 'head_repository': {'full_name': 'owner/repo'}, 'head_sha': 'a' * 40, 'status': state}
        jobs = {'jobs': [{'name': name, 'conclusion': conclusion} for name, conclusion in zip(['javascript-tool-probe', 'codex-code-mode-delegate'], conclusions)]}
        def output(args, **kwargs):
            if args[:3] == ('git', 'show', 'a' * 40 + ':.github/scripts/ios-codex-probe.sh'):
                return (ROOT / '.github/scripts/ios-codex-probe.sh').read_text()
            route = args[-1]
            if route.endswith('/jobs?per_page=100'): return json.dumps(jobs)
            if '/workflows/' in route: return json.dumps({'id': 1})
            return json.dumps(run)
        def invoke(args, **kwargs):
            if changed and args[:2] == ['git', 'diff']: raise subprocess.CalledProcessError(1, args)
            return subprocess.CompletedProcess(args, 0)
        with tempfile.TemporaryDirectory() as temp:
            result = Path(temp) / 'output'
            env = {'PROOF_RUN_ID': '123', 'GITHUB_REPOSITORY': 'owner/repo', 'GITHUB_OUTPUT': str(result), 'PROOF_ALLOW_PENDING': '1' if allow else '', 'PROOF_WAIT_SECONDS': str(wait)}
            with patch.dict(os.environ, env), patch('subprocess.check_output', side_effect=output), patch('subprocess.run', side_effect=invoke):
                old = Path.cwd()
                os.chdir(ROOT)
                try: runpy.run_path(str(SCRIPT), run_name='__main__')
                finally: os.chdir(old)
            return result.read_text()
    def test_pending_can_only_start_compilation(self):
        self.assertIn('pending=true', self.execute('in_progress', ['success', None], allow=True))
    def test_pending_cannot_publish(self):
        with self.assertRaises(SystemExit): self.execute('in_progress', ['success', None])
    def test_failed_proof_cannot_compile_or_publish(self):
        for allow in [False, True]:
            with self.assertRaises(SystemExit): self.execute('completed', ['success', 'failure'], allow=allow)
    def test_both_successful_jobs_verify(self):
        self.assertIn('verified=true', self.execute('completed', ['success', 'success']))
    def test_different_runtime_cannot_reuse_proof(self):
        with self.assertRaises(subprocess.CalledProcessError): self.execute('in_progress', ['success', None], allow=True, changed=True)
    def test_finished_run_without_both_jobs_fails(self):
        with self.assertRaises(SystemExit): self.execute('completed', ['success'], allow=True)

if __name__ == '__main__': unittest.main()
