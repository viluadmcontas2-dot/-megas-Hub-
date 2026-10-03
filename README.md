# OMEGAS HUB

App Android (multimídia 1280×720) que observa a ECU de GNV e ajuda o dono a deixar o motor, no GNV,
igual ao que é na gasolina. Nasce limpo: specs em `docs/spec/`, evidência em `fixtures/`, regras em `AGENTS.md`.

Estado: **P0 — conhecimento e esqueleto**. Nada fala com a ECU ainda.

```
python3 -B tools/run_checks.py   # contratos (clean-room, nomes, fixtures, envelope, orçamentos)
node --test "tests/ui/**/*.test.js"             # UI
OMEGAS_CORE_ONLY=1 ./gradlew :core:test   # Kotlin puro, sem Android SDK
```
