"""Prova de classe 3 do spec mp48-frames §1 e ecu-link §2 sobre o corpus real do ProgBase (Lognovo).

- Toda resposta não vazia começa pelo eco do pedido.
- Status é 0x53 (ACK) ou 0xCA (erro, payload 01 08 | 01 10).
- LEN confere e checksum = soma(status, LEN, payload) mod 256.
- Checksum de todo pedido = soma dos bytes anteriores mod 256.
- Fatos usados pelos specs: 48 08 50 → CA 01 10; 29 64 01 8E → CA 01 10; telemetria LEN 0x22; curva LEN 0x3C.
"""
import gzip, json, unittest, pathlib, collections

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORPUS = ROOT / 'fixtures' / 'portmon' / 'progbase-lognovo-transactions.jsonl.gz'

def hexlist(s):
    return [int(x, 16) for x in s.split()] if s else []

class LognovoEnvelopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.txs = [json.loads(l) for l in gzip.open(CORPUS, 'rt')]

    def test_tamanho_do_corpus(self):
        self.assertEqual(len(self.txs), 39520)

    def test_checksum_dos_pedidos(self):
        bad = 0
        for t in self.txs:
            req = hexlist(t['request'])
            if len(req) < 2 or not t['response']:
                continue  # sonda de 1 byte "00" sem checksum; escritas sem resposta (cabo removido no fim)
            if sum(req[:-1]) % 256 != req[-1]:
                bad += 1
        self.assertEqual(bad, 0)

    def test_envelope_das_respostas(self):
        counts = collections.Counter()
        for t in self.txs:
            req, resp = hexlist(t['request']), hexlist(t['response'])
            if not resp or len(req) < 2:
                counts['vazia'] += 1; continue
            if resp[:len(req)] != req:
                counts['sem eco'] += 1; continue
            body = resp[len(req):]
            if len(body) < 3:
                counts['truncada'] += 1; continue
            status, ln, payload, ck = body[0], body[1], body[2:-1], body[-1]
            self.assertIn(status, (0x53, 0xCA), t['request'])
            self.assertEqual(len(payload), ln, t['request'])
            self.assertEqual((status + ln + sum(payload)) % 256, ck, t['request'])
            if status == 0xCA:
                self.assertIn(payload, ([0x01, 0x08], [0x01, 0x10], [0x10]), t['request'])
            counts['ok'] += 1
        self.assertGreater(counts['ok'], 39000)
        self.assertLessEqual(counts['vazia'] + counts['sem eco'] + counts['truncada'], 10)

    def first(self, request):
        return next(hexlist(t['response']) for t in self.txs if t['request'] == request and t['response'])

    def test_fatos_dos_specs(self):
        self.assertEqual(self.first('48 08 50')[3:], [0xCA, 0x01, 0x10, 0xDB])
        self.assertEqual(self.first('29 64 01 8E')[4:7], [0xCA, 0x01, 0x10])
        tele = self.first('48 01 49')
        self.assertEqual(tele[3:5], [0x53, 0x22]); self.assertEqual(len(tele), 40)
        curve = self.first('29 61 01 8B')
        self.assertEqual(curve[4:6], [0x53, 0x3C]); self.assertEqual(len(curve), 67)
        axis = self.first('29 4B 01 75')[6:-1]
        ms = [(axis[i] | axis[i + 1] << 8) / 512 for i in range(0, 60, 2)]
        self.assertEqual(ms[:3], [0.5, 1.0, 1.5]); self.assertEqual(ms[19], 10.0); self.assertEqual(ms[27:], [18.0, 20.0, 22.0])
        thd = self.first('29 4C 01 76')[6:-1]
        bar = [round((thd[i] | thd[i + 1] << 8) / 1024, 3) for i in range(0, 36, 2)]
        self.assertEqual(bar[0], 0.15); self.assertEqual(bar[-1], 1.1); self.assertEqual(len(bar), 18)
        self.assertEqual(self.first('09 74 01 7E')[6], 3)
        self.assertEqual(self.first('29 72 01 9C')[6:16], [1, 3, 3, 1, 3, 3, 1, 3, 3, 1])

    def test_fluxo_mapa_k(self):
        reqs = [t['request'] for t in self.txs]
        on, off = '35 03 00 86 2C 51 10 4B', '35 03 00 86 24 51 10 43'
        i = reqs.index(on)
        j = reqs.index(off, i)
        writes = [r for r in reqs[i:j] if r.startswith('14 54 00')]
        self.assertEqual(len(writes), 144)
        self.assertEqual(writes[0], '14 54 00 00 00 64 CC'); self.assertEqual(writes[-1], '14 54 00 0B 0B 64 E2')
        for t in self.txs:
            if t['request'].startswith('14 54 00'):
                self.assertTrue(t['response'].endswith('53 00 53'), t['request'])
