package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

data class MedicionesResponse(
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null,
    @SerializedName("id") val id: Int? = null
)