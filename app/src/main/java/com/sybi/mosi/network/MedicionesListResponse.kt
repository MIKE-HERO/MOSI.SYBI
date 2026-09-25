package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

/**
 * Respuesta del endpoint GET /usuariosWeb/{id}/mediciones
 *
 * Nota: los campos tienen nombres DIFERENTES a los del POST.
 * Aquí se mapean a los nombres que devuelve el GET.
 */
data class MedicionesListResponse(
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null,
    @SerializedName("mediciones") val mediciones: List<MedicionItem>? = null
) {
    val exito: Boolean
        get() = codigo == "0" && !mediciones.isNullOrEmpty()
}

data class MedicionItem(
    // Identificadores
    @SerializedName("id") val id: String? = null,
    @SerializedName("recordID") val recordID: String? = null,
    @SerializedName("memberID") val memberID: String? = null,
    @SerializedName("id_UsuarioWeb") val idUsuarioWeb: String? = null,
    @SerializedName("id_VendingMachine") val idVendingMachine: String? = null,

    // Dispositivo
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("device_model") val deviceModel: String? = null,

    // Antropometría
    @SerializedName("height") val height: String? = null,
    @SerializedName("weight") val weight: String? = null,
    @SerializedName("bmi") val bmi: String? = null,

    // Presión
    @SerializedName("pressure_systolic") val pressureSystolic: String? = null,
    @SerializedName("pressure_diastolic") val pressureDiastolic: String? = null,
    @SerializedName("pressure_pulse_rate") val pressurePulseRate: String? = null,

    // Oxígeno
    @SerializedName("spo2") val spo2: String? = null,
    @SerializedName("pulse_rate") val pulseRate: String? = null,

    // Temperatura
    @SerializedName("temper") val temper: String? = null,

    // Composición corporal
    @SerializedName("fatType") val fatType: String? = null,
    @SerializedName("bodyAge") val bodyAge: String? = null,
    @SerializedName("bodyScore") val bodyScore: String? = null,
    @SerializedName("metabolism") val metabolism: String? = null,
    @SerializedName("whr") val whr: String? = null,
    @SerializedName("fat") val fat: String? = null,
    @SerializedName("notFat") val notFat: String? = null,
    @SerializedName("fatRate") val fatRate: String? = null,
    @SerializedName("water") val water: String? = null,
    @SerializedName("waterRate") val waterRate: String? = null,
    @SerializedName("visceralFat") val visceralFat: String? = null,
    @SerializedName("idealWeight") val idealWeight: String? = null,
    @SerializedName("muscle") val muscle: String? = null,
    @SerializedName("mineral") val mineral: String? = null,
    @SerializedName("protein") val protein: String? = null,

    // ECG
    @SerializedName("HeartRate") val heartRate: String? = null,
    @SerializedName("PAxis") val pAxis: String? = null,
    @SerializedName("QRSAxis") val qrsAxis: String? = null,
    @SerializedName("TAxis") val tAxis: String? = null,
    @SerializedName("PRInterval") val prInterval: String? = null,
    @SerializedName("QRSDuration") val qrsDuration: String? = null,
    @SerializedName("QTD") val qtd: String? = null,
    @SerializedName("QTC") val qtc: String? = null,
    @SerializedName("RV5") val rv5: String? = null,
    @SerializedName("SV1") val sv1: String? = null,
    @SerializedName("ResCode") val resCode: String? = null,
    @SerializedName("ECGImg") val ecgImg: String? = null,

    // Fecha y otros
    @SerializedName("fechaRegistro") val fechaRegistro: String? = null,
    @SerializedName("folio") val folio: String? = null
)