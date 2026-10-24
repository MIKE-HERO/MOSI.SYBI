package com.sybi.mosi.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    // ── Buscar paciente ──
    @GET("usuariosWeb/buscar")
    suspend fun buscarPaciente(
        @Query("celular") celular: String? = null,
        @Query("curp") curp: String? = null,
        @Query("folio") folio: String? = null,          // ✅ coma añadida
        @Query("id_cliente") idCliente: String? = null  // ✅ nuevo parámetro
    ): Response<BuscarPacienteResponse>

    // ── Registrar medición ──
    @POST("usuariosWeb/registrarMedicion")
    suspend fun registrarMedicion(
        @Body body: MedicionesRequest
    ): Response<MedicionesResponse>

    // ── Consultar mediciones ──
    @GET("usuariosWeb/{id}/mediciones")
    suspend fun consultarMediciones(
        @Path("id") idUsuarioWeb: Int
    ): Response<MedicionesListResponse>

    // ✅ Obtener lista de clientes
    @GET("usuariosWeb/clientes")
    suspend fun obtenerClientes(): Response<ClientesResponse>

    // ✅ Obtener sucursales de un cliente
    @GET("usuariosWeb/sucursales")
    suspend fun obtenerSucursales(
        @Query("id_cliente") idCliente: String
    ): Response<SucursalesResponse>

    // ── Logos/razón social del cliente (encabezado del PDF) ──
    @GET("usuariosWeb/logosCliente")
    fun obtenerLogosCliente(
        @Query("id_cliente") idCliente: String
    ): Call<LogosClienteResponse>

    // ── Dirección de la sucursal y nombre del proyecto (pie de página del PDF) ──
    @GET("usuariosWeb/datosSucursal")
    fun obtenerDatosSucursal(
        @Query("id_sucursal") idSucursal: String
    ): Call<DatosSucursalResponse>

    // ── Subir/actualizar la imagen de un cliente en un slot ──
    // Contrato confirmado por backend (2026-10-24): multipart/form-data con los 3 campos como
    // partes del form (no query params). slot: "izquierdo" | "derecho" | "firma". archivo:
    // SVG/PNG/JPG, máx. 2 MB, validado por magic bytes (no por el Content-Type que mande el cliente).
    @Multipart
    @POST("usuariosWeb/logosCliente")
    suspend fun actualizarLogoCliente(
        @Part("id_cliente") idCliente: RequestBody,
        @Part("slot") slot: RequestBody,
        @Part archivo: MultipartBody.Part
    ): Response<ActualizarLogoResponse>
}