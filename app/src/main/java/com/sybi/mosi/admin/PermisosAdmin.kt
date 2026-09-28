package com.sybi.mosi.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.util.Log

/**
 * Concesión automática de permisos cuando la app es propietaria del dispositivo.
 *
 * Alcance real de un administrador: puede conceder los permisos de ejecución (cámara, micrófono,
 * ubicación, almacenamiento...) y fijar la política para que futuros permisos se acepten solos.
 * NO puede activar el modo OTG/anfitrión del puerto USB ni saltarse el permiso USB por
 * dispositivo: eso lo decide el firmware del equipo y el propio sistema.
 */
object PermisosAdmin {

    private const val TAG = "MosiAdmin"

    data class Estado(val esPropietario: Boolean, val concedidos: Int, val total: Int, val pendientes: List<String>)

    fun componente(context: Context) = ComponentName(context, MosiAdminReceiver::class.java)

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun esPropietario(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /** Permisos peligrosos (de ejecución) que declara la app. */
    fun permisosPeligrosos(context: Context): List<String> {
        val pm = context.packageManager
        val info: PackageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions.orEmpty().filter { permiso ->
            runCatching {
                pm.getPermissionInfo(permiso, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE ==
                        PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
        }
    }

    /**
     * Si la app es propietaria del dispositivo, concede todos sus permisos peligrosos y activa la
     * concesión automática para los siguientes. Si no lo es, no hace nada.
     */
    fun aplicar(context: Context): Estado {
        val propietario = esPropietario(context)
        val permisos = permisosPeligrosos(context)
        if (propietario && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val manager = dpm(context)
            val admin = componente(context)
            runCatching { manager.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT) }
                .onFailure { Log.e(TAG, "No se pudo fijar la política de permisos: ${it.message}") }
            permisos.forEach { permiso ->
                val ok = runCatching {
                    manager.setPermissionGrantState(
                        admin, context.packageName, permiso, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                }.getOrDefault(false)
                if (!ok) Log.w(TAG, "No se pudo conceder $permiso")
            }
        }
        return estado(context)
    }

    fun estado(context: Context): Estado {
        val permisos = permisosPeligrosos(context)
        val pendientes = permisos.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        return Estado(esPropietario(context), permisos.size - pendientes.size, permisos.size, pendientes)
    }

    /**
     * Renuncia al modo de propietario del dispositivo. Un propietario no se puede quitar con
     * "adb" en una app de producción, así que esta salida evita tener que restablecer el equipo.
     */
    @Suppress("DEPRECATION")
    fun renunciar(context: Context): Boolean {
        if (!esPropietario(context)) return false
        return runCatching { dpm(context).clearDeviceOwnerApp(context.packageName) }.isSuccess
    }
}
