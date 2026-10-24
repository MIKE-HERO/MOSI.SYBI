package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

/**
 * Logos y razón social del cliente (subrogado) para el encabezado/pie de página
 * del PDF de resultados. Endpoint nuevo, independiente de usuariosWeb/buscar.
 *
 * GET usuariosWeb/logosCliente?id_cliente={idCliente}
 *
 * Si el cliente no tiene logos registrados, el servidor responde
 * success=true, codigo="200" y SIN el objeto "logos".
 */
data class LogosClienteResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null,
    @SerializedName("logos") val logos: LogoClienteDto? = null
)

data class LogoClienteDto(
    @SerializedName("idCliente") val idCliente: Int? = null,
    @SerializedName("razonSocial") val razonSocial: String? = null,
    // Dirección del NEGOCIO/cliente (no la del paciente).
    @SerializedName("direccion") val direccion: String? = null,
    @SerializedName("codigoPostal") val codigoPostal: String? = null,
    @SerializedName("estado") val estado: String? = null,
    @SerializedName("rfc") val rfc: String? = null,
    // URLs https directas a la imagen (no base64). Cualquiera puede venir null.
    @SerializedName("logoIzquierdo") val logoIzquierdo: String? = null,
    @SerializedName("logoDerecho") val logoDerecho: String? = null,
    @SerializedName("firma") val firma: String? = null,
    @SerializedName("nombreFirma") val nombreFirma: String? = null,
    @SerializedName("puesto") val puesto: String? = null
)

/**
 * POST usuariosWeb/logosCliente (multipart/form-data): sube/actualiza la imagen de un cliente
 * en un slot ("izquierdo" | "derecho" | "firma"). En éxito, el servidor devuelve el mismo shape
 * que el GET (con las URLs ya actualizadas) más el slot guardado, para refrescar sin otra llamada.
 */
data class ActualizarLogoResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null,
    @SerializedName("slot") val slot: String? = null,
    @SerializedName("logos") val logos: LogoClienteDto? = null
)
