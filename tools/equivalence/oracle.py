"""Oráculo do cérebro de equivalência (docs/spec/equivalence.md). Escrito do zero; o Kotlin tem paridade.

Entradas: quadros de telemetria (rpm, mapBar, petrolMs, gasMs, fuel, tMs), curva atual (eixo ms, K Q14),
bandas nativas (gasolina e gás: n, injMs, mapBar). Saídas: amostras, curvas próprias, 30 pontos, índice,
próxima ação, proposta de K.

Uso: python3 tools/equivalence/oracle.py replay fixtures/autocal/real/<sessao>.json.gz
     python3 tools/equivalence/oracle.py cases fixtures/equivalence/cases.json
"""
import gzip, json, math, sys
from dataclasses import dataclass, field

# ---- constantes do spec ------------------------------------------------------------------------
STABLE_FRAMES = 3; STABLE_WINDOW_MS = 1200; STABLE_RPM = 150; STABLE_MAP = 0.03; MIN_PETROL_MS = 1.0
CELL_BAR = 0.02; MAP_MIN = 0.10; MAP_MAX = 1.10; CELLS = int(round((MAP_MAX - MAP_MIN) / CELL_BAR))  # 50
CELL_KEEP = 30; BAND_MATURE_COUNT = 6; PRIOR_MIN_N = 3
DRIVE_RPM = 1000; DRIVE_TP_MS = 3.0
MIN_SAMPLES_MEASURED = 4; TOL_FLOOR = 0.04; LIGHT = 0.08; CONTEST_WORSE = 0.04; CONTEST_MIN = 0.05
OUTLIER_MIN_LOG = 0.05; MAD_K = 3.0; OUTLIER_PASSES = 3
LAMBDA = 0.3; IRLS_ITER = 6; TUKEY_C = 4.685
MAX_STEP_LN = math.log(1.15); MAX_ELASTICITY = 0.35; LOW_GUARD_MS = 3.5
K_MIN = 0.60; K_MAX = 0xFFFF / 16384.0; Q14 = 16384.0

def cell_of(map_bar):
    i = int(math.floor((map_bar - MAP_MIN) / CELL_BAR + 1e-9))
    return i if 0 <= i < CELLS else None

def cell_center(i): return MAP_MIN + (i + 0.5) * CELL_BAR

# ---- amostras estáveis -------------------------------------------------------------------------
@dataclass
class Sample: rpm: float; map_bar: float; petrol_ms: float; gas_ms: float; fuel: str; at_ms: int

class Stabilizer:
    """3 quadros seguidos, mesmo combustível, ≤1200 ms, rpm ≤150, MAP ≤0.03, gasolina ≥1 ms → média."""
    def __init__(self): self.buf = []
    def feed(self, f):
        if f['fuel'] not in ('GASOLINA', 'GNV') or f['petrolMs'] < MIN_PETROL_MS:
            self.buf = []; return None
        self.buf.append(f); self.buf = self.buf[-STABLE_FRAMES:]
        if len(self.buf) < STABLE_FRAMES: return None
        b = self.buf
        if any(x['fuel'] != b[0]['fuel'] for x in b) or b[-1]['tMs'] - b[0]['tMs'] > STABLE_WINDOW_MS: return None
        if max(x['rpm'] for x in b) - min(x['rpm'] for x in b) > STABLE_RPM: return None
        if max(x['mapBar'] for x in b) - min(x['mapBar'] for x in b) > STABLE_MAP + 1e-12: return None
        s = Sample(sum(x['rpm'] for x in b) / 3, sum(x['mapBar'] for x in b) / 3, sum(x['petrolMs'] for x in b) / 3,
                   sum(x['gasMs'] for x in b) / 3, b[0]['fuel'], b[-1]['tMs'])
        self.buf = []
        return s

# ---- células -----------------------------------------------------------------------------------
class Cells:
    """Por combustível: últimos 30 ln(t) por célula de 0.02 bar (só quadros 'dirigindo')."""
    def __init__(self): self.ln = [[] for _ in range(CELLS)]
    def add(self, s):
        if s.rpm < DRIVE_RPM or s.petrol_ms < DRIVE_TP_MS: return False
        i = cell_of(s.map_bar)
        if i is None: return False
        self.ln[i].append(math.log(s.petrol_ms)); self.ln[i] = self.ln[i][-CELL_KEEP:]
        return True
    def count(self, i): return len(self.ln[i])
    def mean(self, i): return sum(self.ln[i]) / len(self.ln[i])
    def dispersion(self, i):
        n = len(self.ln[i])
        if n < 2: return 0.0
        m = self.mean(i); return math.sqrt(sum((v - m) ** 2 for v in self.ln[i]) / (n - 1))
    def total(self): return sum(len(c) for c in self.ln)

def interp(xs, ys, x, extrapolate=True):
    """Interpolação linear; nas pontas, constante (extrapolate) ou None. xs crescente."""
    if not xs: return None
    if x < xs[0] - 1e-12 or x > xs[-1] + 1e-12:
        if not extrapolate: return None
    if x <= xs[0]: return ys[0]
    if x >= xs[-1]: return ys[-1]
    for j in range(1, len(xs)):
        if x <= xs[j]:
            f = (x - xs[j - 1]) / (xs[j] - xs[j - 1]); return ys[j - 1] + f * (ys[j] - ys[j - 1])
    return ys[-1]

def pava(values, weights):
    """Regressão isotônica crescente ponderada (pool-adjacent-violators)."""
    blocks = [[v, w, 1] for v, w in zip(values, weights)]  # [média, peso, tamanho]
    out = []
    for b in blocks:
        out.append(b)
        while len(out) > 1 and out[-2][0] > out[-1][0]:
            a, c = out.pop(), out.pop()
            w = a[1] + c[1]
            out.append([(a[0] * a[1] + c[0] * c[1]) / w if w > 0 else (a[0] + c[0]) / 2, w, a[2] + c[2]])
    res = []
    for b in out: res += [b[0]] * b[2]
    return res

def fit_curve(cells, prior_map, prior_ln):
    """Curva própria em ln T por célula: mistura própria+prior, isotônica em MAP, outliers rejeitados.
    Devolve (ln_t[50] ou None por célula, peso_proprio[50], dispersão[50])."""
    xs, raw, w, own_w, disp = [], [], [], [0.0] * CELLS, [0.0] * CELLS
    for i in range(CELLS):
        n = cells.count(i)
        p = interp(prior_map, prior_ln, cell_center(i), extrapolate=False) if prior_map else None
        if n == 0 and p is None: continue
        d = cells.dispersion(i) if n else 0.0
        g = min(n / BAND_MATURE_COUNT, 1.0) * (1 - min(d / 0.08, 1.0) * 0.5) if n else 0.0
        if p is None: g = 1.0
        own_w[i] = g; disp[i] = d
        v = (cells.mean(i) if n else 0.0) * g + (p if p is not None else 0.0) * (1 - g)
        xs.append(i); raw.append(v); w.append(0.05 + g)
    if not xs: return [None] * CELLS, own_w, disp
    keep = [True] * len(xs)
    fit = raw[:]
    for _ in range(OUTLIER_PASSES):
        sub_v = [raw[j] for j in range(len(xs)) if keep[j]]; sub_w = [w[j] for j in range(len(xs)) if keep[j]]
        fitted = pava(sub_v, sub_w)
        res = []; k = 0
        for j in range(len(xs)):
            if keep[j]: res.append(raw[j] - fitted[k]); k += 1
        if not res: break
        med = sorted(abs(r) for r in res)[len(res) // 2]
        thr = max(OUTLIER_MIN_LOG, MAD_K * med)
        changed = False; k = 0
        for j in range(len(xs)):
            if keep[j]:
                if abs(res[k]) > thr and own_w[xs[j]] < 1.0: keep[j] = False; changed = True
                k += 1
        if not changed: break
    kept_x = [xs[j] for j in range(len(xs)) if keep[j]]
    fitted = pava([raw[j] for j in range(len(xs)) if keep[j]], [w[j] for j in range(len(xs)) if keep[j]])
    out = [None] * CELLS
    for i in range(CELLS):
        if kept_x and kept_x[0] <= i <= kept_x[-1]:
            out[i] = interp([cell_center(c) for c in kept_x], fitted, cell_center(i))
    return out, own_w, disp

def inverse_map(ln_curve, ln_t):
    """MAP onde a curva (crescente) vale ln_t; None fora do domínio medido."""
    pts = [(cell_center(i), v) for i, v in enumerate(ln_curve) if v is not None]
    if len(pts) < 2 or ln_t < pts[0][1] or ln_t > pts[-1][1]: return None
    for j in range(1, len(pts)):
        if ln_t <= pts[j][1]:
            (x0, y0), (x1, y1) = pts[j - 1], pts[j]
            return x0 if y1 == y0 else x0 + (ln_t - y0) / (y1 - y0) * (x1 - x0)
    return pts[-1][0]

# ---- pontos, índice, próxima ação ---------------------------------------------------------------
def compute_points(axis_ms, k_raw, gas_curve, petrol_curve, gas_cells, gas_disp, prev_states=None, written=None):
    """prev_states/written: estados anteriores e índices gravados nesta sessão (para EM_PROVA/CONFIRMADO/CONTESTADO)."""
    pts = []
    total = max(gas_cells.total(), 1)
    for i, t in enumerate(axis_ms):
        k = k_raw[i] / Q14
        m = inverse_map(gas_curve, math.log(t)) if t > 0 else None
        p = {'index': i, 'axisMs': t, 'kCurrent': k, 'kTarget': None, 'mixture': None, 'tolerance': TOL_FLOOR,
             'mapBar': m, 'samples': 0, 'usage': 0.0, 'state': 'SEM_DADOS'}
        if m is not None:
            ci = cell_of(m)
            tgas = interp([cell_center(j) for j, v in enumerate(petrol_curve) if v is not None],
                          [v for v in petrol_curve if v is not None], m) if any(v is not None for v in petrol_curve) else None
            if ci is not None:
                lo, hi = max(0, ci - 1), min(CELLS - 1, ci + 1)
                p['samples'] = sum(gas_cells.count(j) for j in range(lo, hi + 1))
                p['usage'] = p['samples'] / total
                p['tolerance'] = max(TOL_FLOOR, 2 * gas_disp[ci])
            if tgas is not None and p['samples'] > 0:
                kt = k * t / math.exp(tgas)
                p['kTarget'] = kt; p['mixture'] = kt / k - 1
                if p['samples'] < MIN_SAMPLES_MEASURED: p['state'] = 'APRENDENDO'
                else:
                    mx = p['mixture']
                    p['state'] = 'EQUIVALENTE' if abs(mx) <= p['tolerance'] else ('POBRE' if mx > 0 else 'RICO')
        if written and i in written and p['state'] != 'SEM_DADOS':
            before = written[i]  # mixture no momento da gravação
            if p['samples'] < MIN_SAMPLES_MEASURED: p['state'] = 'EM_PROVA'
            elif abs(p['mixture']) <= p['tolerance']: p['state'] = 'CONFIRMADO'
            elif abs(p['mixture']) > abs(before) + CONTEST_WORSE and abs(p['mixture']) > CONTEST_MIN: p['state'] = 'CONTESTADO'
            else: p['state'] = 'EM_PROVA'
        pts.append(p)
    return pts

MEASURED = {'EQUIVALENTE', 'POBRE', 'RICO', 'CONFIRMADO', 'CONTESTADO', 'EM_PROVA'}

def compute_index(points, has_reference):
    if not has_reference: return None
    meas = [p for p in points if p['state'] in MEASURED]
    use = sum(p['usage'] for p in meas)
    if not meas or use <= 0: return {'value': None, 'coverage': 0.0, 'provisional': True}
    value = sum(p['usage'] for p in meas if p['state'] in ('EQUIVALENTE', 'CONFIRMADO')) / use
    cov = len(meas) / len(points)
    return {'value': value, 'coverage': cov, 'provisional': cov < 0.4}

def next_action(points, has_reference, mature_petrol_bands, operating):
    if operating: return {'kind': 'AGUARDE', 'text': 'Aguarde: gravando…'}
    if not has_reference:
        ok = mature_petrol_bands >= 10
        return {'kind': 'CONGELAR', 'text': 'Congelar referência' if ok else 'Rode em gasolina até a ECU completar as zonas', 'enabled': ok}
    c = [p for p in points if p['state'] == 'CONTESTADO']
    if c: return {'kind': 'DESFAZER', 'text': f"Desfazer a gravação do ponto {c[0]['index']}", 'point': c[0]['index']}
    off = [p for p in points if p['state'] in ('POBRE', 'RICO')]
    if off:
        worst = max(off, key=lambda p: abs(p['mixture']) * p['usage'])
        return {'kind': 'GRAVAR', 'text': f"Gravar proposta ({len(off)} pontos, maior ajuste {worst['mixture']*100:+.0f} %)"}
    prova = [p for p in points if p['state'] == 'EM_PROVA']
    if prova: return {'kind': 'RODAR', 'text': f"Rode em GNV perto de {prova[0]['axisMs']:.1f} ms para confirmar"}
    holes = [p for p in points if p['state'] in ('SEM_DADOS', 'APRENDENDO') and 3.0 <= p['axisMs'] <= 12.0]
    if holes: return {'kind': 'RODAR', 'text': f"Rode em GNV perto de {holes[0]['axisMs']:.1f} ms"}
    return {'kind': 'NADA', 'text': 'Equivalente. Nada a fazer.'}

# ---- proposta de K --------------------------------------------------------------------------------
def solve(A, b):
    """Eliminação de Gauss com pivô parcial (n ≤ 30)."""
    n = len(b); M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(M[r][c])); M[c], M[p] = M[p], M[c]
        for r in range(c + 1, n):
            f = M[r][c] / M[c][c]
            if f: 
                for k in range(c, n + 1): M[r][k] -= f * M[c][k]
    x = [0.0] * n
    for r in range(n - 1, -1, -1):
        x[r] = (M[r][n] - sum(M[r][k] * x[k] for k in range(r + 1, n))) / M[r][r]
    return x

def propose(axis_ms, k_current, points):
    """Whittaker em ln K sobre u = ln t com IRLS Tukey; prior = K atual; restrições por projeção.
    Devolve (k_raw[30], origem[30], métricas)."""
    n = len(axis_ms); u = [math.log(t) for t in axis_ms]
    ln_cur = [math.log(max(k, 1e-6)) for k in k_current]
    # evidência: g = min(n,6)/6 espalhado por (0.25, 0.5, 0.25)/0.5 → ganho
    raw_g = [min(p['samples'], 6) / 6 if p['kTarget'] is not None else 0.0 for p in points]
    g = [min(1.0, (0.25 * raw_g[i - 1] if i > 0 else 0) + 0.5 * raw_g[i] + (0.25 * raw_g[i + 1] if i < n - 1 else 0)) / 0.5 * 0.5 for i in range(n)]
    target = [math.log(p['kTarget']) if p['kTarget'] is not None else ln_cur[i] for i, p in enumerate(points)]
    w_prior = [1.0 * (1 - g[i]) + 0.05 * g[i] for i in range(n)]
    h2 = sum((u[i + 1] - u[i]) ** 2 for i in range(n - 1)) / (n - 1)
    w_data = [g[i] for i in range(n)]
    x = ln_cur[:]
    for _ in range(IRLS_ITER):
        # resíduos robustos (Tukey) sobre os alvos
        res = [target[i] - x[i] for i in range(n)]
        scale = max(1e-6, sorted(abs(r) for r in res)[n // 2] * 1.4826)
        rob = [((1 - (r / (TUKEY_C * scale)) ** 2) ** 2 if abs(r) < TUKEY_C * scale else 0.0) for r in res]
        A = [[0.0] * n for _ in range(n)]; b = [0.0] * n
        for i in range(n):
            wd = w_data[i] * rob[i]
            A[i][i] += wd + w_prior[i]; b[i] += wd * target[i] + w_prior[i] * ln_cur[i]
        for i in range(1, n - 1):   # segundas diferenças normalizadas por h² médio
            hl, hr = u[i] - u[i - 1], u[i + 1] - u[i]
            row = {i - 1: 2 / (hl * (hl + hr)), i: -2 / (hl * hr), i + 1: 2 / (hr * (hl + hr))}
            for a, va in row.items():
                for c, vc in row.items(): A[a][c] += LAMBDA * h2 * h2 * va * vc
        x = solve(A, b)
    # restrições: faixa, guarda baixa, passo, elasticidade — projeção alternada
    lo = [max(math.log(K_MIN), ln_cur[i] if axis_ms[i] < LOW_GUARD_MS else -9) for i in range(n)]
    hi = [math.log(K_MAX)] * n
    for _ in range(50):
        moved = False
        for i in range(n):
            v = min(max(x[i], lo[i]), hi[i])
            if v != x[i]: x[i] = v; moved = True
        for i in range(n - 1):
            d = x[i + 1] - x[i]; lim = min(MAX_STEP_LN, MAX_ELASTICITY * (u[i + 1] - u[i]))
            if abs(d) > lim + 1e-12:
                over = (abs(d) - lim) / 2 * (1 if d > 0 else -1)
                x[i] += over; x[i + 1] -= over; moved = True
        if not moved: break
    k_raw = [min(0xFFFF, int(math.exp(v) * Q14)) for v in x]
    origin = []
    for i in range(n):
        d = abs(x[i] - ln_cur[i])
        origin.append('MEDIDO' if g[i] >= 0.5 else 'MISTO' if g[i] > 0 and d > 0.0025 else 'SUAVIZADO' if d > 0.0025 else 'MANTIDO')
    steps = [abs(x[i + 1] - x[i]) for i in range(2, 22)]
    elas = [abs(x[i + 1] - x[i]) / (u[i + 1] - u[i]) for i in range(2, 22)]
    second = [x[i + 1] - 2 * x[i] + x[i - 1] for i in range(3, 22)]
    metrics = {'maxNeighborStep': max(steps), 'maxElasticity': max(elas), 'roughness': math.sqrt(sum(s * s for s in second) / len(second)),
               'signChanges': sum(1 for a, b in zip(second, second[1:]) if a * b < 0)}
    return k_raw, origin, metrics

# ---- cérebro -------------------------------------------------------------------------------------
class Brain:
    def __init__(self):
        self.stab = Stabilizer(); self.petrol = Cells(); self.gas = Cells()
        self.axis_ms = None; self.k_raw = None; self.reference = None
        self.native = None   # último snapshot nativo: dict com bandas gasolina/gás
        self.written = {}    # índice → mixture no momento da gravação
    def feed_frame(self, f):
        s = self.stab.feed(f)
        if s: (self.petrol if s.fuel == 'GASOLINA' else self.gas).add(s)
        return s
    def feed_native(self, axis_ms, k_raw, petrol_bands, gas_bands):
        if self.k_raw is not None and list(k_raw) != list(self.k_raw): self.reset_gas_lane()   # CURVA_K_MUDOU
        self.axis_ms, self.k_raw, self.native = axis_ms, k_raw, {'petrol': petrol_bands, 'gas': gas_bands}
    def reset_gas_lane(self):
        self.gas = Cells()
    def freeze(self):
        if not self.native: return False
        self.reference = {'bands': self.native['petrol']}; return True
    def on_curve_written(self, indices, points):
        for i in indices: self.written[i] = points[i]['mixture'] if points[i]['mixture'] is not None else 0.0
        self.reset_gas_lane()
    def _prior(self, bands):
        pts = sorted((b['mapBar'], math.log(b['injMs'])) for b in bands if b['n'] >= PRIOR_MIN_N and b['injMs'] > 0)
        return [p[0] for p in pts], [p[1] for p in pts]
    def view(self, operating=False):
        if self.axis_ms is None: return {'index': None, 'points': [], 'nextAction': {'kind': 'AGUARDE', 'text': 'Lendo a ECU…'}}
        has_ref = self.reference is not None
        pm, pl = self._prior(self.reference['bands']) if has_ref else ([], [])
        gm, gl = self._prior(self.native['gas']) if self.native else ([], [])
        petrol_curve, _, _ = fit_curve(self.petrol, pm, pl)
        gas_curve, _, gdisp = fit_curve(self.gas, gm, gl)
        points = compute_points(self.axis_ms, self.k_raw, gas_curve, petrol_curve, self.gas, gdisp, written=self.written) if has_ref else \
                 [{'index': i, 'axisMs': t, 'kCurrent': self.k_raw[i] / Q14, 'kTarget': None, 'mixture': None, 'tolerance': TOL_FLOOR,
                   'mapBar': None, 'samples': 0, 'usage': 0.0, 'state': 'SEM_DADOS'} for i, t in enumerate(self.axis_ms)]
        mature = sum(1 for b in (self.native['petrol'] if self.native else []) if b['n'] >= 3)
        return {'index': compute_index(points, has_ref), 'points': points,
                'nextAction': next_action(points, has_ref, mature, operating),
                'petrolCurve': petrol_curve, 'gasCurve': gas_curve}

# ---- replay de sessão real (omegas-autocal-replay-v1) -------------------------------------------
def bands_from_snapshot(snap):
    f = {x['key']: x['rawValues'] for x in snap['fields']}
    def s16(v): return v - 65536 if v > 32767 else v
    petrol = [{'n': f['NUM_BUF_UPD_PETR'][i], 'injMs': f['PETR_INJ_TBUF'][i] / 512, 'mapBar': s16(f['MNFLD_PRESS_BUF'][i]) / 1024} for i in range(18)]
    gas = [{'n': f['NUM_BUF_UPD_GAS'][i], 'injMs': f['PETR_INJ_TBUF_GAS'][i] / 512, 'mapBar': s16(f['MNFLD_PRESS_BUF_GAS'][i]) / 1024} for i in range(18)]
    return [v / 512 for v in f['PETR_INJ_TBP']], f['MUL_ACT'], petrol, gas

def replay(path, freeze_at_seq=None):
    d = json.load(gzip.open(path, 'rt'))
    brain = Brain(); out = []
    snaps = sorted(d['snapshots'], key=lambda s: s['sequence'])
    si = 0
    for n, fr in enumerate(d['telemetry']):
        while si < len(snaps) and snaps[si]['sequence'] <= n:
            brain.feed_native(*bands_from_snapshot(snaps[si]))
            if snaps[si]['snapshotReason'] == 'ACTION_RESET_GAS': brain.reset_gas_lane()
            if brain.reference is None and (freeze_at_seq is None or snaps[si]['sequence'] >= freeze_at_seq): brain.freeze()
            si += 1
        brain.feed_frame({'rpm': fr['rpm'], 'mapBar': fr['load_bar'], 'petrolMs': fr['petrol_ms'] or 0.0,
                          'gasMs': fr['gas_ms_diagnostic'] or 0.0, 'fuel': fr['fuel'], 'tMs': fr['t']})
        if n % 200 == 199 or n == len(d['telemetry']) - 1:
            v = brain.view(); out.append((n, v['index'], v['nextAction']['text'], brain.petrol.total(), brain.gas.total()))
    return brain, out

if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'replay':
        brain, out = replay(sys.argv[2])
        for n, idx, act, np_, ng in out:
            print(f"frame {n:5d} gasolina {np_:4d} gás {ng:4d} índice {idx} → {act}")
        v = brain.view()
        for p in v['points']:
            if p['state'] != 'SEM_DADOS': print(f"  ponto {p['index']:2d} t={p['axisMs']:5.1f} K={p['kCurrent']:.3f} alvo={p['kTarget'] and round(p['kTarget'],3)} mix={p['mixture'] and round(p['mixture']*100,1)}% n={p['samples']} {p['state']}")
        if any(p['kTarget'] for p in v['points']):
            k_raw, origin, metrics = propose(brain.axis_ms, [k / Q14 for k in brain.k_raw], v['points'])
            print('proposta:', [f"{k/Q14:.3f}" for k in k_raw]); print('origem:', origin); print('métricas:', metrics)

# ---- fixtures de paridade (Kotlin reproduz estes números) --------------------------------------
CHECKPOINTS = (599, 1199, 1999)

def cases(out_path):
    import hashlib, os
    sessions = ['ref_2026-10-01_1719', 'automatch_2026-10-01_1301', 'gnv_only_2026-09-30_0931']
    result = {'schema': 'omegas-equivalence-parity-v1', 'checkpoints': list(CHECKPOINTS), 'sessions': []}
    for name in sessions:
        path = f'fixtures/autocal/real/{name}.json.gz'
        d = json.load(gzip.open(path, 'rt'))
        brain = Brain(); snaps = sorted(d['snapshots'], key=lambda s: s['sequence']); si = 0
        checks = []
        for n, fr in enumerate(d['telemetry']):
            while si < len(snaps) and snaps[si]['sequence'] <= n:
                brain.feed_native(*bands_from_snapshot(snaps[si]))
                if snaps[si]['snapshotReason'] == 'ACTION_RESET_GAS': brain.reset_gas_lane()
                if brain.reference is None: brain.freeze()
                si += 1
            brain.feed_frame({'rpm': fr['rpm'], 'mapBar': fr['load_bar'], 'petrolMs': fr['petrol_ms'] or 0.0,
                              'gasMs': fr['gas_ms_diagnostic'] or 0.0, 'fuel': fr['fuel'], 'tMs': fr['t']})
            if n in CHECKPOINTS or n == len(d['telemetry']) - 1:
                v = brain.view()
                c = {'frame': n, 'petrolSamples': brain.petrol.total(), 'gasSamples': brain.gas.total(), 'index': v['index'],
                     'nextAction': v['nextAction']['text'],
                     'points': [{'i': p['index'], 'kTarget': p['kTarget'], 'mixture': p['mixture'], 'tolerance': p['tolerance'],
                                 'mapBar': p['mapBar'], 'samples': p['samples'], 'usage': p['usage'], 'state': p['state']} for p in v['points']]}
                if any(p['kTarget'] for p in v['points']):
                    k_raw, origin, metrics = propose(brain.axis_ms, [k / Q14 for k in brain.k_raw], v['points'])
                    c['proposal'] = {'kRaw': k_raw, 'origin': origin, **metrics}
                checks.append(c)
        result['sessions'].append({'file': path, 'sha256': hashlib.sha256(open(path, 'rb').read()).hexdigest(), 'checks': checks})
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'w') as fh: json.dump(result, fh, indent=1, sort_keys=True)
    print('escrito', out_path, os.path.getsize(out_path), 'bytes')

if __name__ == '__main__' and sys.argv[1] == 'cases':
    cases(sys.argv[2])
