"""Lê um log do Portmon (Sysinternals) e extrai transações serial: pedido escrito → bytes lidos.

Saída: JSONL com {sequence, atMs, port, request, response, statuses}. Escrito do zero para o Hub.
"""
import re, sys, json, gzip, collections

LINE = re.compile(r'^(\d+)\s+([\d.]+)\s+(.*?)\s*$')

def parse(path):
    pending = {}            # seq -> (kind, port, payload-info)
    txs = []
    cur = None              # transação em curso: dict
    t0 = None
    with open(path, 'r', encoding='latin-1', errors='replace') as fh:
        for raw in fh:
            m = LINE.match(raw.rstrip('\r\n'))
            if not m:
                continue
            seq, t, tail = m.groups()
            cols = re.split(r'\s{2,}', tail)
            if cols[0].endswith('.exe'):       # linha de pedido: exe, operação, porta, detalhe
                pending[seq] = (cols[1], cols[2], cols[3] if len(cols) > 3 else '')
                continue
            # linha de resultado: status, detalhe
            req = pending.pop(seq, None)
            if req is None:
                continue
            kind, port, info = req
            status = cols[0]
            rest = cols[1] if len(cols) > 1 else ''
            if kind == 'IRP_MJ_WRITE':
                data = hexbytes(info)
                if cur and cur['response'] == [] and cur['port'] == port:
                    cur['request'] += data     # escrita fragmentada
                else:
                    if cur: txs.append(cur)
                    cur = {'sequence': len(txs) + 1, 'at': float(0), 'port': port,
                           'request': data, 'response': [], 'statuses': []}
                cur['statuses'].append(f'W:{status}')
            elif kind == 'IRP_MJ_READ':
                data = hexbytes(rest)          # "Length n: xx xx" no resultado
                if cur is None or cur['port'] != port:
                    if cur: txs.append(cur)
                    cur = {'sequence': len(txs) + 1, 'at': 0.0, 'port': port,
                           'request': [], 'response': [], 'statuses': []}
                cur['response'] += data
                cur['statuses'].append(f'R:{status}:{len(data)}')
    if cur: txs.append(cur)
    return txs

def hexbytes(s):
    m = re.search(r'Length\s+\d+:\s*(.*)$', s)
    if not m:
        return []
    return [int(h, 16) for h in m.group(1).split() if re.fullmatch(r'[0-9A-Fa-f]{2}', h)]

def hx(bs): return ' '.join(f'{b:02X}' for b in bs)

if __name__ == '__main__':
    src, out = sys.argv[1], sys.argv[2]
    txs = parse(src)
    with gzip.open(out, 'wt') as fh:
        for tx in txs:
            fh.write(json.dumps({'sequence': tx['sequence'], 'port': tx['port'],
                                 'request': hx(tx['request']), 'response': hx(tx['response']),
                                 'statuses': tx['statuses']}) + '\n')
    cnt = collections.Counter(hx(tx['request']) for tx in txs)
    print('transactions', len(txs), 'distinct requests', len(cnt))
    for req, n in cnt.most_common(80):
        print(f'{n:7d}  {req}')
