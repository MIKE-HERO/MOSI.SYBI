package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

/**
 * Dirección de la SUCURSAL (no del cliente pagante) y nombre del proyecto,
 * para el pie de página del PDF.
 *
 * GET usuariosWeb/datosSucursal?id_sucursal={idSucursal}
 *
 * "nombreProyecto" no es una columna propia de la sucursal: en este sistema
 * cada "cliente" (cat_Clientes.st_Nombre) representa en realidad un
 * proyecto/programa (ej. idCliente 85 = "Tarjeta Rosa"), no necesariamente
 * al pagante final. El "idCliente" que trae esta respuesta es el que debe
 * usarse para pedir los logos (GET usuariosWeb/logosCliente), no uno fijo.
 *
 * Si la sucursal no existe: success=true, codigo="200", sin objeto "sucursal".
 */
data class DatosSucursalResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null,
    @SerializedName("sucursal") val sucursal: DatosSucursalDto? = null
)

data class DatosSucursalDto(
    @SerializedName("idSucursal") val idSucursal: Int? = null,
    @SerializedName("nombreSucursal") val nombreSucursal: String? = null,
    @SerializedName("idCliente") val idCliente: Int? = null,
    @SerializedName("nombreProyecto") val nombreProyecto: String? = null,
    @SerializedName("domicilio") val domicilio: String? = null,
    @SerializedName("colonia") val colonia: String? = null,
    @SerializedName("codigoPostal") val codigoPostal: String? = null,
    @SerializedName("rfc") val rfc: String? = null,
    @SerializedName("telefono") val telefono: String? = null,
    @SerializedName("estado") val estado: String? = null,
    @SerializedName("estadoAbreviado") val estadoAbreviado: String? = null,
    @SerializedName("municipio") val municipio: String? = null
)
