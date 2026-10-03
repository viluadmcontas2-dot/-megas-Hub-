"""Clean-room: nenhum arquivo autoral do Hub compartilha 12 linhas normalizadas seguidas com a Platina @ a125436.

O índice `tools/clean_room/platina-a125436.idx.gz` guarda os hashes (8 bytes) de todas as janelas de 12
linhas de todos os arquivos texto da Platina nesse SHA (gerado por tools/clean_room/build_index.py).
Regra de normalização: tools/clean_room/norm.py.
"""
import gzip, sys, unittest, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools' / 'clean_room'))
sys.path.insert(0, str(ROOT / 'tests'))
from norm import windows_with_hashes, HASH_BYTES  # noqa: E402
from _hub_files import authored_files  # noqa: E402

INDEX = ROOT / 'tools' / 'clean_room' / 'platina-a125436.idx.gz'

class CleanRoomTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raw = gzip.open(INDEX, 'rb').read()
        cls.index = {raw[i:i + HASH_BYTES] for i in range(0, len(raw), HASH_BYTES)}
        assert len(cls.index) > 50_000, 'índice da Platina incompleto'

    def test_sem_janelas_copiadas(self):
        hits = []
        for path in authored_files():
            if path.name == 'build_index.py' or path.name == 'norm.py':
                pass  # também são verificados: foram escritos do zero
            text = path.read_text(encoding='utf-8', errors='replace')
            for start, h, chunk in windows_with_hashes(text):
                if h in self.index:
                    hits.append(f'{path.relative_to(ROOT)} (linha normalizada {start}): {chunk[0][:60]}…')
                    break
        self.assertEqual(hits, [], 'Trechos iguais à Platina (12 linhas seguidas):\n' + '\n'.join(hits))
