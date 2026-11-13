package com.sybi.mosi

import android.util.Log
import java.io.DataOutputStream

/**
 * Optimizador de recursos del sistema mediante privilegios root (su) para quioscos médicos (Rockchip RK3288, Android 7.1.2).
 *
 * Dedica todos los recursos de CPU, memoria y prioridad de procesos a la videollamada de telemedicina
 * ejecutada en Firefox Custom Tabs (org.mozilla.firefox).
 */
object TelemedicineOptimizer {
    private const val TAG = "TelemedicineOptimizer"

    /**
     * Aplica optimizaciones de alto rendimiento al iniciar la teleconsulta:
     * 1. Cambia el gobernador de CPU a 'performance' en todos los núcleos del RK3288.
     * 2. Libera caché de memoria (drop_caches = 3) para dar RAM libre a WebRTC / Firefox.
     * 3. Eleva la prioridad (niceness) y ajusta OOM score del navegador Firefox y de la app Mosi.
     */
    fun optimizarParaTelemedicina() {
        Log.d(TAG, "🚀 [OPTIMIZADOR] Aplicando optimizaciones de sistema para telemedicina...")
        val commands = mutableListOf<String>()

        // 1. Fijar gobernador de CPU a 'performance' en todos los núcleos disponibles (RK3288 tiene 4 núcleos: cpu0 a cpu3)
        commands.add("echo performance > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        commands.add("echo performance > /sys/devices/system/cpu/cpu1/cpufreq/scaling_governor")
        commands.add("echo performance > /sys/devices/system/cpu/cpu2/cpufreq/scaling_governor")
        commands.add("echo performance > /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor")
        // Alternativa comodín si la ruta varía
        commands.add("for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo performance > \$g; done")

        // 2. Liberar memoria caché y pagecache
        commands.add("sync")
        commands.add("echo 3 > /proc/sys/vm/drop_caches")

        // 3. Priorizar procesos (renice a máxima prioridad -15 para Firefox y Mosi)
        commands.add("for pid in \$(pgrep org.mozilla.firefox); do renice -n -15 -p \$pid; echo -1000 > /proc/\$pid/oom_score_adj; done")
        commands.add("for pid in \$(pgrep com.sybi.mosi); do renice -n -10 -p \$pid; done")

        ejecutarComandosRoot(commands)
    }

    /**
     * Restaura el estado normal del sistema al finalizar la teleconsulta:
     * 1. Restaura el gobernador de CPU a 'interactive'.
     */
    fun restaurarEstadoNormal() {
        Log.d(TAG, "🔄 [OPTIMIZADOR] Restaurando estado normal del sistema...")
        val commands = mutableListOf<String>()

        // Restaurar gobernador de CPU a 'interactive'
        commands.add("echo interactive > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        commands.add("echo interactive > /sys/devices/system/cpu/cpu1/cpufreq/scaling_governor")
        commands.add("echo interactive > /sys/devices/system/cpu/cpu2/cpufreq/scaling_governor")
        commands.add("echo interactive > /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor")
        commands.add("for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo interactive > \$g; done")

        ejecutarComandosRoot(commands)
    }

    private fun ejecutarComandosRoot(commands: List<String>) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = DataOutputStream(process.outputStream)
            for (cmd in commands) {
                outputStream.writeBytes("$cmd\n")
                outputStream.flush()
            }
            outputStream.writeBytes("exit\n")
            outputStream.flush()
            val exitCode = process.waitFor()
            Log.d(TAG, "✅ Comandos root ejecutados con exitCode: $exitCode")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ No se pudieron ejecutar comandos root (¿dispositivo sin root o permiso denegado?): ${e.message}")
        }
    }
}
