"""Gera o índice clean-room: hashes de janelas de 12 linhas normalizadas de todos os arquivos texto
de um checkout da Platina. Rode: python3 tools/clean_room/build_index.py <dir-platina> <saida>.

A regra de normalização vive em tools/clean_room/norm.py e é a mesma usada pelo teste.
"""
import sys, os, hashlib
sys.path.insert(0, os.path.dirname(__file__))
from norm import window_hashes, TEXT_SUFFIXES

def main(root, out):
    hashes = set()
    files = 0
    for dirpath, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ('.git', 'build', '.gradle', 'node_modules')]
        for name in names:
            if os.path.splitext(name)[1].lower() not in TEXT_SUFFIXES:
                continue
            path = os.path.join(dirpath, name)
            try:
                text = open(path, encoding='utf-8', errors='replace').read()
            except OSError:
                continue
            files += 1
            hashes.update(window_hashes(text))
    with open(out, 'wb') as fh:
        for h in sorted(hashes):
            fh.write(h)
    print(f'{files} arquivos, {len(hashes)} janelas, {os.path.getsize(out)} bytes -> {out}')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
