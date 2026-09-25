package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

data class MedicionesDto(
    // Altura / Peso
    @SerializedName("altura") val altura: String = "",
    @SerializedName("peso") val peso: String = "",
    @SerializedName("imc") val imc: String = "",

    // Composición corporal
    @SerializedName("grasa_corporal") val grasaCorporal: String = "",
    @SerializedName("grasa_corporal_kg") val grasaCorporalKg: String = "",
    @SerializedName("agua_corporal") val aguaCorporal: String = "",
    @SerializedName("agua_corporal_kg") val aguaCorporalKg: String = "",
    @SerializedName("masa_muscular") val masaMuscular: String = "",
    @SerializedName("masa_libre_grasa") val masaLibreGrasa: String = "",
    @SerializedName("proteina") val proteina: String = "",
    @SerializedName("minerales") val minerales: String = "",
    @SerializedName("metabolismo_basal") val metabolismoBasal: String = "",
    @SerializedName("grasa_visceral") val grasaVisceral: String = "",
    @SerializedName("peso_ideal") val pesoIdeal: String = "",
    @SerializedName("tipo_grasa") val tipoGrasa: String = "",

    // Presión arterial
    @SerializedName("sistolica") val sistolica: String = "",
    @SerializedName("diastolica") val diastolica: String = "",
    @SerializedName("pulso") val pulso: String = "",

    // Temperatura
    @SerializedName("temperatura") val temperatura: String = "",
    @SerializedName("temperatura_f") val temperaturaF: String = "",

    // Oxígeno
    @SerializedName("spo2") val spo2: String = "",
    @SerializedName("frecuencia_pulso") val frecuenciaPulso: String = "",
    @SerializedName("indice_perfusion") val indicePerfusion: String = "",

    // ECG
    @SerializedName("frecuencia_cardiaca") val frecuenciaCardiaca: String = "",
    @SerializedName("eje_p") val ejeP: String = "",
    @SerializedName("eje_qrs") val ejeQrs: String = "",
    @SerializedName("eje_t") val ejeT: String = "",
    @SerializedName("intervalo_pr") val intervaloPr: String = "",
    @SerializedName("duracion_qrs") val duracionQrs: String = "",
    @SerializedName("intervalo_qt") val intervaloQt: String = "",
    @SerializedName("qt_corregido") val qtCorregido: String = "",
    @SerializedName("onda_rv5") val ondaRv5: String = "",
    @SerializedName("onda_sv1") val ondaSv1: String = "",
    @SerializedName("resultado_ecg") val resultadoEcg: String = "",

    // Fecha
    @SerializedName("fecha_medicion") val fechaMedicion: String = ""
)