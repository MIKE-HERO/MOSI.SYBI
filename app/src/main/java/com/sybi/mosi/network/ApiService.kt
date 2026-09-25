package com.sybi.mosi.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
}