package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

data class ConsultaRequest(
    // ID del paciente en la API (id_UsuarioWeb)
    @SerializedName("id_UsuarioWeb")
    val idUsuarioWeb: Int,

    // Datos del dispositivo (opcionales pero útiles)
    @SerializedName("device_id")
    val deviceId: String? = null,

    @SerializedName("device_model")
    val deviceModel: String? = null,

    // Mediciones
    @SerializedName("mediciones")
    val mediciones: MedicionesDto
)