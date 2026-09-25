package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

data class SucursalesResponse(
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null,
    @SerializedName("sucursales") val sucursales: List<Sucursal>? = null
)

data class Sucursal(
    @SerializedName("idSucursal") val idSucursal: String? = null,
    @SerializedName("nombreSucursal") val nombreSucursal: String? = null,
    @SerializedName("idCliente") val idCliente: String? = null
)