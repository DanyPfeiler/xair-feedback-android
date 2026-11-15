package com.danypfeiler.xairfeedback.core

/** Parametros de cada nivel de agresividad. Los "frames" son cuadros de 50 ms. */
data class Profile(
    val promDb: Double, val narrowDb: Double, val minFrames: Int, val riseDb: Double,
    val sustainFrames: Int, val step: Double, val firstCut: Double, val maxCut: Double,
    val q: Double, val cooldown: Double, val familyDb: Double, val familyUpDb: Double,
    val longFrames: Int
) {
    companion object {
        val ALL = mapOf(
            "bajo" to Profile(15.0, 10.0, 8, 8.0, 40, 3.0, 3.0, 6.0, 8.0, 1.5, 15.0, 22.0, 200),
            "medio" to Profile(12.0, 8.0, 5, 6.0, 30, 3.0, 6.0, 9.0, 7.0, 1.2, 10.0, 16.0, 100),
            "alto" to Profile(9.0, 6.0, 3, 4.0, 24, 3.0, 6.0, 12.0, 5.0, 0.8, 6.0, 12.0, 80)
        )

        fun of(name: String): Profile = ALL[name] ?: ALL.getValue("medio")
    }
}

data class Config(
    val host: String = "192.168.1.100",
    val port: Int = 10024,
    val sources: List<String> = listOf("ch1"),   // que analizar (con varias se rota el analizador)
    val dest: String = "auto",                   // auto = donde se detecta | lr | bus1 | bus2
    val chBands: List<Int> = listOf(3, 4),       // bandas del EQ de canal que se pueden usar (1-4)
    val busBands: List<Int> = listOf(4, 5, 6),   // bandas del EQ de Main LR / bus (1-6)
    val profile: String = "medio",
    val apply: Boolean = false,                  // false = solo avisar
    val restoreOnStop: Boolean = true,
    val overflowLr: Boolean = true,              // sin bandas en el canal: seguir en Main LR
    val faderGuard: Boolean = true,              // ultimo recurso: bajar el volumen del microfono
    val faderMax: Double = 6.0,
    val releaseMin: Double = 0.0,                // soltar un corte si no vuelve en N minutos (0 = nunca)
    val minFreq: Double = 160.0,
    val maxFreq: Double = 12000.0,
    val minLevel: Double = -55.0,
    val dwell: Double = 1.0,                     // segundos por fuente cuando se rota
    val useSim: Boolean = false
)

/** Fila de la tabla de cortes activos. freq/binf nulos = bajada de volumen. */
data class CutRow(val where: String, val band: String, val freq: Double?, val depth: Double, val binf: Double?)
