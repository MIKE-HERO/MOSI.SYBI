package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

data class ClientesResponse(
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null,
    @SerializedName("clientes") val clientes: List<Cliente>? = null
)

data class Cliente(
    @SerializedName("idCliente") val idCliente: String? = null,
    @SerializedName("nombreCliente") val nombreCliente: String? = null
)