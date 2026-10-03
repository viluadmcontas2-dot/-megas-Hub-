package com.omegas.hub.calibration

import com.omegas.hub.ecu.Exchange
import com.omegas.hub.ecu.Mp48Frames

/** Mapa K 12×12 (docs/spec/map-k.md). `rows[r][c]` em %, 100 = neutro; linha técnica 0x0C à parte. */
data class MapK(val rows: Array<IntArray>, val technicalRow: IntArray) {
    companion object {
        const val SIZE = 12
        const val TECHNICAL_ROW = 0x0C
        const val MIN_SAFE_K = 100
        val RPM_AXIS = intArrayOf(850, 1350, 1850, 2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000, 6500)
        val MS_AXIS = doubleArrayOf(2.0, 2.5, 3.0, 3.5, 4.5, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0)
    }
}

data class Cell(val row: Int, val col: Int, val value: Int)

/** Lê e grava o Mapa K com modo de inserção em volta e readback por linha. */
class MapKWriter(private val ex: Exchange) {

    fun read(): MapK = Trace(ex).let { t ->
        val rows = Array(MapK.SIZE) { r -> row(t, r) }
        MapK(rows, row(t, MapK.TECHNICAL_ROW))
    }

    private fun row(t: Trace, r: Int): IntArray =
        t.ack(Mp48Frames.readMapRow(r), "ler linha $r").let { p -> require(p.size >= MapK.SIZE) { "Linha $r curta." }; IntArray(MapK.SIZE) { p[it].toInt() and 0xFF } }

    /** 1..16 células. Fluxo: foto das linhas tocadas → modo on → escritas → modo off (sempre) → readback. */
    fun write(cells: List<Cell>): Written {
        var photo: Map<Int, IntArray>? = null
        val outcome = Trace(ex).run({ "Gravado: ${cells.size} célula(s) confirmadas pela ECU." }) {
            require(cells.size in 1..16) { "Grave de 1 a 16 células por vez." }
            require(cells.all { it.row in 0 until MapK.SIZE && it.col in 0 until MapK.SIZE }) { "Célula fora do mapa." }
            require(cells.all { it.value in MapK.MIN_SAFE_K..255 }) { "K abaixo de 100 não é seguro." }
            val touched = cells.map { it.row }.distinct().sorted()
            val before = touched.associateWith { row(this, it) }; photo = before
            ack(Mp48Frames.insertionMode(true), "modo de inserção ligado")
            try {
                for (c in cells) ack(Mp48Frames.writeMapCell(c.row, c.col, c.value), "gravar [${c.row},${c.col}]")
            } finally {
                ack(Mp48Frames.insertionMode(false), "modo de inserção desligado")
            }
            for (r in touched) {
                val after = row(this, r)
                for (c in 0 until MapK.SIZE) {
                    val expected = cells.lastOrNull { it.row == r && it.col == c }?.value ?: before.getValue(r)[c]
                    if (after[c] != expected) throw Mismatch("A ECU não confirmou a célula [$r,$c] (esperado $expected, lido ${after[c]}).")
                }
            }
        }
        return Written(outcome, photo)
    }

    data class Written(val outcome: Outcome, val photo: Map<Int, IntArray>?)
}
