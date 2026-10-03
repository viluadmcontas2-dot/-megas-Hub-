"""Normalização compartilhada do teste clean-room.

Linha normalizada = espaços colapsados, sem espaços nas pontas, minúscula. Linhas com menos de
3 caracteres alfanuméricos (chaves, parênteses, vírgulas) não contam. Janela = 12 linhas seguidas;
hash = 8 primeiros bytes do SHA-1 das 12 linhas unidas por '\n'.
"""
import hashlib, re

WINDOW = 12
HASH_BYTES = 8
TEXT_SUFFIXES = {'.kt', '.kts', '.py', '.js', '.cjs', '.mjs', '.css', '.html', '.md', '.json',
                 '.xml', '.yml', '.yaml', '.txt', '.csv', '.pro', '.properties', '.gradle', '.sh'}

_ws = re.compile(r'\s+')
_alnum = re.compile(r'[0-9a-zA-Z]')

def normalize_lines(text):
    out = []
    for line in text.splitlines():
        line = _ws.sub(' ', line.strip()).lower()
        if len(_alnum.findall(line)) < 3:
            continue
        out.append(line)
    return out

def window_hashes(text):
    lines = normalize_lines(text)
    for i in range(0, len(lines) - WINDOW + 1):
        yield hashlib.sha1('\n'.join(lines[i:i + WINDOW]).encode('utf-8')).digest()[:HASH_BYTES]

def windows_with_hashes(text):
    """Para o teste: (índice da primeira linha normalizada, hash, texto da janela)."""
    lines = normalize_lines(text)
    for i in range(0, len(lines) - WINDOW + 1):
        chunk = lines[i:i + WINDOW]
        yield i, hashlib.sha1('\n'.join(chunk).encode('utf-8')).digest()[:HASH_BYTES], chunk
