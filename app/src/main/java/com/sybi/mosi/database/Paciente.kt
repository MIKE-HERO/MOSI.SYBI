package com.sybi.mosi.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pacientes")
data class Paciente(
    // ===== IDENTIFICADORES =====
    @PrimaryKey(autoGenerate = true) val id_local: Long = 0,
    val id_usuario_web: Int? = null,
    val folio: String = "",

    // ===== DATOS PERSONALES =====
    val nombre: String,
    val apellido_paterno: String,
    val apellido_materno: String,
    val fecha_nacimiento: String,
    val genero: String = "O",
    val curp: String = "",

    // ===== CONTACTO =====
    val telefono: String,
    val correo: String = "",

    // ===== DIRECCIÓN =====
    val direccion: String = "",

    // ===== CONTROL LOCAL =====
    val foto: String? = null,
    val sincronizado: Boolean = false,
    val fecha_registro: String = "",              // Fecha local (cuando se registró en el quiosco)
    val fecha_registro_global: String = ""        // Fecha en que se dio de alta en el sistema del doctor
) {
    fun calcularEdad(): Int {
        return try {
            val partes = fecha_nacimiento.split("/")
            val dia = partes[0].toInt()
            val mes = partes[1].toInt()
            val anio = partes[2].toInt()

            val calendario = java.util.Calendar.getInstance()
            val anioActual = calendario.get(java.util.Calendar.YEAR)
            val mesActual = calendario.get(java.util.Calendar.MONTH) + 1
            val diaActual = calendario.get(java.util.Calendar.DAY_OF_MONTH)

            var edad = anioActual - anio
            if (mesActual < mes || (mesActual == mes && diaActual < dia)) {
                edad--
            }
            edad
        } catch (e: Exception) {
            0
        }
    }
}