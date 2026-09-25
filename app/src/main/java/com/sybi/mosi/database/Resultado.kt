package com.sybi.mosi.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "resultados")
data class Resultado(
    @PrimaryKey(autoGenerate = true) val id_resultado: Long = 0,

    // Referencia al paciente local (0 si es invitado total)
    val id_local: Long = 0,

    // Identificador en el sistema del doctor (0 = no sincronizado)
    val id_usuario_web: Int = 0,

    // Altura/Peso
    val altura: String,
    val peso: String,
    val imc: String,

    // Composición Corporal
    val grasa_corporal: String,
    val grasa_corporal_kg: String,
    val agua_corporal: String,
    val agua_corporal_kg: String,
    val masa_muscular: String,
    val masa_libre_grasa: String,
    val proteina: String,
    val minerales: String,
    val metabolismo_basal: String,
    val grasa_visceral: String,
    val peso_ideal: String,
    val tipo_grasa: String,

    // Presión Arterial
    val sistolica: String,
    val diastolica: String,
    val pulso: String,

    // Temperatura
    val temperatura: String,
    val temperatura_f: String,

    // Oxígeno
    val spo2: String,
    val frecuencia_pulso: String,
    val indice_perfusion: String,

    // ECG
    val frecuencia_cardiaca: String,
    val eje_p: String,
    val eje_qrs: String,
    val eje_t: String,
    val intervalo_pr: String,
    val duracion_qrs: String,
    val intervalo_qt: String,
    val qt_corregido: String,
    val onda_rv5: String,
    val onda_sv1: String,
    val resultado_ecg: String,

    // Ruta al JPG guardado en filesDir/ecg/
    val ruta_ecg: String = "",

    // Fecha
    val fecha_medicion: String = ""
)