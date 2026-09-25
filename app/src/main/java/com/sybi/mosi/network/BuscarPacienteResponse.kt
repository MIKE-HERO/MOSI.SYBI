package com.sybi.mosi.network

import com.google.gson.annotations.SerializedName

data class BuscarPacienteResponse(
    @SerializedName("codigo") val codigo: String? = null,
    @SerializedName("mensaje") val mensaje: String? = null,
    @SerializedName("usuarios") val usuarios: List<UsuarioWeb>? = null
) {
    val encontrado: Boolean
        get() = codigo == "0" && !usuarios.isNullOrEmpty()

    val primerUsuario: UsuarioWeb?
        get() = usuarios?.firstOrNull()

    val idPaciente: Int?
        get() = primerUsuario?.idUsuarioWeb?.toIntOrNull()

    /**
     * Busca una coincidencia por nombre dentro de la lista de usuarios.
     * Útil cuando la búsqueda por folio devuelve múltiples resultados.
     */
    fun buscarCoincidencia(
        nombre: String? = null,
        apellidoPaterno: String? = null,
        apellidoMaterno: String? = null
    ): UsuarioWeb? {
        return usuarios?.firstOrNull { usuario ->
            val coincideNombre = nombre.isNullOrBlank() ||
                    usuario.nombre?.equals(nombre, ignoreCase = true) == true
            val coincideApPat = apellidoPaterno.isNullOrBlank() ||
                    usuario.apellidoPaterno?.equals(apellidoPaterno, ignoreCase = true) == true
            val coincideApMat = apellidoMaterno.isNullOrBlank() ||
                    usuario.apellidoMaterno?.equals(apellidoMaterno, ignoreCase = true) == true

            coincideNombre && coincideApPat && coincideApMat
        }
    }
}

data class UsuarioWeb(
    @SerializedName("idUsuarioWeb") val idUsuarioWeb: String? = null,
    @SerializedName("nombre") val nombre: String? = null,
    @SerializedName("apellidoPaterno") val apellidoPaterno: String? = null,
    @SerializedName("apellidoMaterno") val apellidoMaterno: String? = null,
    @SerializedName("direccion") val direccion: String? = null,
    @SerializedName("fechaRegistro") val fechaRegistro: String? = null,
    @SerializedName("correo") val correo: String? = null,
    @SerializedName("telefonoCasa") val telefonoCasa: String? = null,
    @SerializedName("celular") val celular: String? = null,
    @SerializedName("fechaNacimiento") val fechaNacimiento: String? = null,
    @SerializedName("curp") val curp: String? = null,
    @SerializedName("folio") val folio: String? = null,
    @SerializedName("idCliente") val idCliente: String? = null
)