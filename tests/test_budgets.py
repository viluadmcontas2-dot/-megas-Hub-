"""Orçamentos do prompt: Kotlin ≤ 10 000 linhas, UI ≤ 5 000 linhas, zero timers na UI."""
import re, unittest, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
TIMER = re.compile(r'\b(setTimeout|setInterval|requestAnimationFrame|requestIdleCallback)\s*\(')

def lines(paths):
    return sum(len(p.read_text(encoding='utf-8', errors='replace').splitlines()) for p in paths)

class BudgetsTest(unittest.TestCase):
    def test_kotlin(self):
        self.assertLessEqual(lines(ROOT.rglob('*.kt')), 10_000)

    def test_ui(self):
        ui = ROOT / 'app' / 'src' / 'main' / 'assets' / 'ui'
        files = [p for p in ui.rglob('*') if p.suffix in ('.html', '.css', '.js')]
        self.assertLessEqual(lines(files), 5_000)
        for p in files:
            self.assertIsNone(TIMER.search(p.read_text(encoding='utf-8')), f'timer na UI: {p.name}')
