package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

/**
 * Cola de impresión remota de recetas hacia el kiosco.
 *
 * Contrato del backend (APIRest/Usuario_mosi, rutas usuariosWeb/printJobs):
 *
 *   GET  usuariosWeb/printJobs?idMachine=N
 *        -> { success, codigo, mensaje, jobs: [ { id, idReceta, html, jobName, copies } ] }
 *        Devuelve SOLO los trabajos con estado 'pendiente' de esa cabina, ordenados por id.
 *
 *   POST usuariosWeb/printJobs/{id}/ack   body { status, detail }
 *        -> { success, codigo, mensaje }
 *        status = "printed" | "error". El backend no valida contra lista cerrada, pero la
 *        tabla documenta esos dos valores y el índice de la cola filtra por 'pendiente',
 *        así que cualquier otro valor saca el job de la cola igual.
 *
 * idMachine es el mismo valor que TelemedicineSettingsActivity.getCabina() manda en la URL
 * de la consulta, y el mismo que el navegador del médico tiene en $_SESSION["id_cabinaMedica"]
 * cuando ImprimeReceta.php encola. Es el eslabón que une las dos puntas.
 *
 * El trabajo llega como HTML, no como PDF: es el mismo HTML que el navegador del médico manda
 * a window.print(). La conversión a PDF ocurre aquí, en el kiosco (ver RecetaPdfRenderer),
 * porque IppPrinter solo sabe enviar application/pdf.
 */
data class PrintJobsResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null,
    @SerializedName("jobs") val jobs: List<PrintJobDto>? = null
)

data class PrintJobDto(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("idReceta") val idReceta: Int? = null,
    @SerializedName("html") val html: String? = null,
    @SerializedName("jobName") val jobName: String? = null,
    @SerializedName("copies") val copies: Int? = null
)

data class PrintJobAckRequest(
    @SerializedName("status") val status: String,
    @SerializedName("detail") val detail: String = ""
)

data class PrintJobAckResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null
)
