# Integración MOSI: cabina y administrador

## Identidad real de Android

- Package name: `com.sybi.mosi`
- Device admin receiver: `com.sybi.mosi/.admin.MosiAdminReceiver`
- El servidor debe registrar y validar ese package name. No usar `com.sybiml.mosi` para esta APK.

## Endpoints requeridos

La app resuelve estas rutas sobre el origen configurado en Telemedicina (por defecto
`https://www.sybiml.com`):

- `POST /api/mosi_activar.php`
- `POST /api/mosi_telemetria.php`
- `POST /api/mosi_actualizacion_consultar.php`
- `POST /api/mosi_actualizacion_evento.php`

Después de activar, todas las llamadas administrativas y clínicas usan:

```http
Authorization: Bearer MLMOSI_TOKEN
```

El servidor debe aceptar ese Bearer también en la API clínica existente bajo
`/APIRest/Usuario_mosi/public/`. La llave global anterior fue retirada de la app.

## Respuesta mínima de activación

```json
{
  "success": true,
  "data": {
    "token": "MLMOSI_...",
    "intervalo_sincronizacion": 300,
    "rutas": {
      "telemetria": "/api/mosi_telemetria.php",
      "actualizacion_consultar": "/api/mosi_actualizacion_consultar.php",
      "actualizacion_evento": "/api/mosi_actualizacion_evento.php"
    }
  }
}
```

## Respuesta mínima de consulta de actualización

Cuando no haya actualización, `data.actualizacion` debe ser `null`. Cuando exista:

```json
{
  "success": true,
  "data": {
    "actualizacion": {
      "id_actualizacion": "123",
      "version_code": 27,
      "version_name": "1.0.22",
      "apk_url": "https://servidor/ruta/mosi.apk",
      "sha256": "64-caracteres-hex",
      "obligatoria": true
    }
  }
}
```

El APK debe tener `applicationId com.sybi.mosi`, un `versionCode` mayor y la misma firma que
la app instalada. La app descarga con Bearer, valida SHA-256, package y certificado antes de
abrir `PackageInstaller`.

## Instalación silenciosa

No basta con habilitar "Administrador del dispositivo". MOSI tiene que ser **Device Owner**.
En un equipo limpio, sin cuentas configuradas:

```text
adb shell dpm set-device-owner com.sybi.mosi/.admin.MosiAdminReceiver
```

En `Ajustes > Información del dispositivo` la app muestra si la instalación silenciosa está
disponible. Si no es Device Owner, Android abrirá el instalador para confirmación manual.

## Mediciones clínicas

Cada medición se guarda primero en Room. La app solo la marca sincronizada si
`registrarMedicion` responde `codigo="0"` y entrega `id`. Los fallos quedan en una cola local y
se reintentan con un `recordID` estable.

Falta definir en el servidor un endpoint de alta o vinculación de pacientes creados localmente.
Sin `id_usuario_web`, una medición no puede asociarse de forma segura al expediente remoto.
Ese endpoint debe ser idempotente y devolver el `id_usuario_web` definitivo.

## Prueba de aceptación

1. Instalar una APK firmada y convertirla en Device Owner.
2. Generar el código desde Inventario y activar la cabina.
3. Verificar telemetría y `device_owner=true` en el administrador.
4. Publicar una APK con mayor `versionCode`, misma firma y SHA-256 correcto.
5. Confirmar eventos `descarga` e `instalacion` en el administrador.
6. Tomar signos con un paciente que tenga `id_usuario_web`.
7. Confirmar `codigo=0`, ID remoto y estado sincronizado local.
8. Cortar internet, tomar otra medición, restaurar internet y verificar el reintento sin duplicado.
