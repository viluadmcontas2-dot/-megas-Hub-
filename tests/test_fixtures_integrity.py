"""Cada fixture listado em fixtures/ORIGEM.md existe e tem o SHA-256 declarado; nenhum fixture fora da lista."""
import hashlib, re, unittest, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
ORIGEM = ROOT / 'fixtures' / 'ORIGEM.md'
ROW = re.compile(r'^\|\s*`([^`]+)`\s*\|[^|]*\|[^|]*\|\s*`([0-9a-f]{64})`\s*\|', re.M)

class FixturesIntegrityTest(unittest.TestCase):
    def test_sha256_bate(self):
        declared = dict(ROW.findall(ORIGEM.read_text(encoding='utf-8')))
        self.assertGreaterEqual(len(declared), 10)
        for rel, sha in declared.items():
            path = ROOT / 'fixtures' / rel
            self.assertTrue(path.is_file(), f'falta {rel}')
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), sha, f'SHA diferente: {rel}')
        present = {p.relative_to(ROOT / 'fixtures').as_posix() for p in (ROOT / 'fixtures').rglob('*') if p.is_file() and p.name != 'ORIGEM.md'}
        self.assertEqual(present - set(declared), set(), 'fixtures sem origem declarada')
