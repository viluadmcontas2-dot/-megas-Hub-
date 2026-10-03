"""Nomes proibidos em código do Hub (zero ocorrências): v7, prohub, Verde, Platina, learning, Predictor, MANUAL_AUTOMATCH.

Documentação (docs/, README, AGENTS) pode citar a Platina como fonte; código não.
"""
import re, sys, unittest, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tests'))
from _hub_files import code_files  # noqa: E402

FORBIDDEN = re.compile(r'(?<![A-Za-z0-9])(v7|prohub|verde|platina|learning|predictor|manual_automatch)(?![A-Za-z0-9])', re.IGNORECASE)
# Exceções: este próprio teste e o nome do índice clean-room (que identifica a fonte do hash).
ALLOWED = {'tests/test_forbidden_names.py', 'tools/clean_room/build_index.py', 'tools/clean_room/norm.py', 'tests/test_clean_room.py'}

class ForbiddenNamesTest(unittest.TestCase):
    def test_zero_ocorrencias(self):
        hits = []
        for path in code_files():
            rel = path.relative_to(ROOT).as_posix()
            if rel in ALLOWED:
                continue
            for n, line in enumerate(path.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
                m = FORBIDDEN.search(line)
                if m:
                    hits.append(f'{rel}:{n}: {m.group(0)}')
        self.assertEqual(hits, [], 'Nomes proibidos em código:\n' + '\n'.join(hits))
