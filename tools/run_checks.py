"""Portão Python do CI: roda todos os tests/test_*.py com unittest e falha no primeiro erro.
Uso local: python3 -B tools/run_checks.py
"""
import sys, unittest, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]

def main():
    import os; os.chdir(ROOT)
    suite = unittest.defaultTestLoader.discover('tests', pattern='test_*.py', top_level_dir=str(ROOT))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1

if __name__ == '__main__':
    sys.exit(main())
