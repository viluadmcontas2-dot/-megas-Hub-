"""Enumera os arquivos do Hub que contam como código/texto autoral (fora de fixtures e binários)."""
import os, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
SKIP_DIRS = {'.git', '.gradle', 'build', 'node_modules', 'fixtures', '__pycache__', '.idea'}
SKIP_FILES = {'gradlew', 'gradlew.bat'}
TEXT_SUFFIXES = {'.kt', '.kts', '.py', '.js', '.cjs', '.mjs', '.css', '.html', '.md', '.json',
                 '.xml', '.yml', '.yaml', '.txt', '.pro', '.properties'}

def authored_files():
    for dirpath, dirs, names in os.walk(ROOT):
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
        for name in sorted(names):
            if name in SKIP_FILES or pathlib.Path(name).suffix.lower() not in TEXT_SUFFIXES:
                continue
            path = pathlib.Path(dirpath) / name
            if 'gradle/wrapper' in path.as_posix():
                continue
            yield path

def code_files():
    """Só código (não documentação): onde nomes proibidos têm tolerância zero."""
    for p in authored_files():
        if p.suffix.lower() in {'.kt', '.kts', '.py', '.js', '.cjs', '.mjs', '.css', '.html', '.xml', '.yml', '.yaml', '.pro', '.properties', '.json'}:
            yield p
