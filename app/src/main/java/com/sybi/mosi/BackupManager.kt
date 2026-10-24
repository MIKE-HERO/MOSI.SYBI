package com.sybi.mosi

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.database.PacienteDao
import com.sybi.mosi.database.Resultado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Respaldo local de pacientes y mediciones (tablas "pacientes"/"resultados"), para no perder el
 * historial si hay que desinstalar y reinstalar la app. Se guarda como .json en una carpeta
 * pública del almacenamiento (NO en getExternalFilesDir, que Android borra al desinstalar).
 *
 * Como cada kiosco se configura para UN solo cliente/sucursal y las tablas locales no tienen
 * columna idCliente por fila, esa asociación se guarda como metadato del archivo completo (el
 * cliente activo al momento de exportar). Al importar, se exige que coincida con el cliente
 * activo del dispositivo que importa, para no mezclar pacientes de otro cliente.
 */
object BackupManager {

    private const val TAG = "BackupManager"
    private const val CARPETA = "MOSI_Respaldo"
    private const val VERSION_FORMATO = 1

    data class RespaldoEnvelope(
        val version: Int = VERSION_FORMATO,
        val idCliente: String,
        val nombreCliente: String,
        val idSucursal: String,
        val nombreSucursal: String,
        val fechaExportacion: String,
        val totalPacientes: Int,
        val totalResultados: Int,
        val pacientes: List<Paciente>,
        val resultados: List<Resultado>
    )

    sealed class ResultadoImportacion {
        data class Exito(
            val pacientesImportados: Int,
            val pacientesOmitidos: Int,
            val resultadosImportados: Int,
            val resultadosOmitidos: Int
        ) : ResultadoImportacion()

        data class ClienteNoCoincide(
            val idClienteRespaldo: String,
            val nombreClienteRespaldo: String,
            val idClienteActual: String
        ) : ResultadoImportacion()

        data class Error(val mensaje: String) : ResultadoImportacion()
    }

    fun carpetaRespaldo(): File =
        File(Environment.getExternalStorageDirectory(), CARPETA).apply { mkdirs() }

    /** BLOQUEANTE (ya cambia a Dispatchers.IO internamente). Devuelve el archivo .json generado. */
    suspend fun exportar(context: Context): Result<File> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val pacientes = db.pacienteDao().obtenerTodosLosPacientes()
            val resultados = db.resultadoDao().obtenerTodosLosResultados()

            val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            val idCliente = devicePrefs.getString(TelemedicineSettingsActivity.PREF_CLIENTE, "") ?: ""
            val nombreCliente = devicePrefs.getString(TelemedicineSettingsActivity.PREF_CLIENTE_NOMBRE, "") ?: ""
            val idSucursal = devicePrefs.getString(TelemedicineSettingsActivity.PREF_SUCURSAL, "") ?: ""
            val nombreSucursal = devicePrefs.getString(TelemedicineSettingsActivity.PREF_SUCURSAL_NOMBRE, "") ?: ""

            if (idCliente.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Este kiosco no tiene un cliente configurado (Ajustes > Cliente). Configúralo antes de exportar.")
                )
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val envelope = RespaldoEnvelope(
                idCliente = idCliente,
                nombreCliente = nombreCliente,
                idSucursal = idSucursal,
                nombreSucursal = nombreSucursal,
                fechaExportacion = timeStamp,
                totalPacientes = pacientes.size,
                totalResultados = resultados.size,
                pacientes = pacientes,
                resultados = resultados
            )

            val archivo = File(carpetaRespaldo(), "respaldo_${idCliente}_$timeStamp.json")
            archivo.writeText(Gson().toJson(envelope))

            Result.success(archivo)
        } catch (e: Exception) {
            Log.e(TAG, "Error exportando respaldo: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** BLOQUEANTE (ya cambia a Dispatchers.IO internamente). */
    suspend fun importar(context: Context, archivo: File): ResultadoImportacion = withContext(Dispatchers.IO) {
        try {
            val json = archivo.readText()
            val envelope = try {
                Gson().fromJson(json, RespaldoEnvelope::class.java)
            } catch (e: Exception) {
                null
            } ?: return@withContext ResultadoImportacion.Error("El archivo no es un respaldo válido (JSON corrupto o con otro formato).")

            val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            val idClienteActual = devicePrefs.getString(TelemedicineSettingsActivity.PREF_CLIENTE, "") ?: ""

            if (idClienteActual.isBlank()) {
                return@withContext ResultadoImportacion.Error(
                    "Este kiosco no tiene un cliente configurado (Ajustes > Cliente). Configúralo antes de importar."
                )
            }
            if (envelope.idCliente != idClienteActual) {
                return@withContext ResultadoImportacion.ClienteNoCoincide(
                    envelope.idCliente, envelope.nombreCliente, idClienteActual
                )
            }

            val db = AppDatabase.getInstance(context)
            val pacienteDao = db.pacienteDao()
            val resultadoDao = db.resultadoDao()

            var pacientesImportados = 0
            var pacientesOmitidos = 0
            var resultadosImportados = 0
            var resultadosOmitidos = 0

            // id_local DEL RESPALDO -> id_local DESTINO en esta base de datos (los autogenerados
            // nunca coinciden entre instalaciones, así que hay que remapear antes de insertar
            // los resultados, que referencian a su paciente por id_local).
            val mapaIds = HashMap<Long, Long>()

            for (paciente in envelope.pacientes) {
                val existente = buscarCoincidencia(pacienteDao, paciente)
                val idDestino = if (existente != null) {
                    pacientesOmitidos++
                    existente.id_local
                } else {
                    val nuevoId = pacienteDao.insertarPaciente(paciente.copy(id_local = 0))
                    pacientesImportados++
                    nuevoId
                }
                mapaIds[paciente.id_local] = idDestino
            }

            // Mediciones ya existentes por paciente destino, para no duplicar si se reimporta
            // el mismo respaldo dos veces (se compara por fecha_medicion dentro del mismo paciente).
            val fechasPorPaciente = HashMap<Long, MutableSet<String>>()

            for (resultado in envelope.resultados) {
                val idDestino = mapaIds[resultado.id_local] ?: continue // resultado huérfano dentro del propio respaldo
                val fechasExistentes = fechasPorPaciente.getOrPut(idDestino) {
                    resultadoDao.obtenerResultadosPorIdLocal(idDestino).map { it.fecha_medicion }.toMutableSet()
                }
                if (resultado.fecha_medicion.isNotBlank() && fechasExistentes.contains(resultado.fecha_medicion)) {
                    resultadosOmitidos++
                    continue
                }
                resultadoDao.insertarResultado(resultado.copy(id_resultado = 0, id_local = idDestino))
                resultadosImportados++
                fechasExistentes.add(resultado.fecha_medicion)
            }

            ResultadoImportacion.Exito(pacientesImportados, pacientesOmitidos, resultadosImportados, resultadosOmitidos)
        } catch (e: Exception) {
            Log.e(TAG, "Error importando respaldo: ${e.message}", e)
            ResultadoImportacion.Error("No se pudo leer el archivo: ${e.message}")
        }
    }

    /** Mismo criterio de "misma persona" que ya usa la app en otros lados: tarjetaIc > CURP > teléfono. */
    private suspend fun buscarCoincidencia(dao: PacienteDao, paciente: Paciente): Paciente? {
        if (paciente.tarjetaIc.isNotBlank()) {
            dao.obtenerPacientePorTarjetaIc(paciente.tarjetaIc)?.let { return it }
        }
        if (paciente.curp.isNotBlank()) {
            dao.obtenerPacientePorCurp(paciente.curp)?.let { return it }
        }
        if (paciente.telefono.isNotBlank()) {
            dao.obtenerPacientePorTelefono(paciente.telefono)?.let { return it }
        }
        return null
    }
}
