package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

data class ConsultaResponse(
    @SerializedName("session_id")
    val sessionId: String,

    @SerializedName("url_consulta")
    val urlConsulta: String? = null,

    @SerializedName("exito")
    val exito: Boolean = true,

    @SerializedName("mensaje")
    val mensaje: String? = null
)