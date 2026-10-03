# OMEGAS HUB — contrato do agente (uma tela)

**Meta:** no GNV o motor se comporta como na gasolina. O app mede isso (equivalência MAP × injeção,
células de 0.02 bar, 30 pontos da Curva K), diz quão perto está, onde falta e qual é a única próxima
ação. Observa sozinho; só muda a ECU quando o dono toca.

**Specs (fonte da verdade):** `docs/spec/{ecu-link,mp48-frames,autocal,curve-k,map-k,equivalence,session,ux}.md`.
**Evidência:** `fixtures/` (origem e SHA em `fixtures/ORIGEM.md`). Evidência vence código antigo.

## Regras
1. Observar é automático; mudar é sempre o dono. Nada grava, zera, restaura ou aplica sozinho.
2. Todo botão é um toque. Proteção = foto antes + Desfazer depois (restaura a leitura anterior).
3. Toda gravação termina no readback da ECU. "Gravado" só depois dele e só se bater byte a byte.
4. Nenhuma falha derruba o app: toda exceção vira estado ✗ legível com próxima ação. Transporte ≠ ECU.
5. **Clean-room:** nada da Platina é copiado (arquivo, função, trecho, CSS). O CI reprova 12 linhas
   iguais seguidas e os nomes `v7 · prohub · Verde · Platina · learning · Predictor · MANUAL_AUTOMATCH` em código.
6. **Bytes sagrados:** o que vai para a ECU é idêntico ao provado nos fixtures. Byte novo = evidência + dono.
7. Dois níveis: frase humana primeiro; comando, bytes e readback em "Detalhes técnicos", sempre por último.
8. Multimídia fraca: 1280×720, Android 8+ (minSdk 26), WebView + JS puro, sem framework/bundler/fonte
   web/`backdrop-filter`, **zero timers** na UI (redesenho por revisão). Toque ≥ 76 px, texto crítico ≥ 24 px.
9. Texto do app em português; código curto e didático; `applicationId = com.omegas.hub`.
10. Validação física (classe 5) só com o dono no carro. Nunca escrever "validado" sem isso.

## Arquitetura
`:core` (Kotlin puro, testável em qualquer máquina): `usb` (porta abstrata) · `ecu` (quadros, fila) ·
`autocal` · `calibration` (curva, mapa) · `equivalence` (cérebro) · `session` · `state` (StateStore
imutável, `revision` monotônica) · `bridge` (contrato `Omegas.snapshot(rev)` / `Omegas.request(json)`).
`:app` (Android): USB real, serviço em primeiro plano, WebView, ponte. UI em `app/src/main/assets/ui/`.

## Como trabalhar
Branch `work/<assunto>` a partir de `main`; lotes grandes; Python/JS rodam localmente, Kotlin e lint no
CI; PR entra com `ci` verde no SHA. Cada PR diz: o que mudou · classe de prova (1 contrato · 2 sintético ·
3 replay real · 4 emulador · 5 físico) · o que ficou não provado. Orçamentos: Kotlin ≤ 10k linhas,
UI ≤ 5k, 2 métodos de ponte, 0 timers. Se a decisão muda o que o dono vê ou o que a ECU recebe: parar e perguntar.
