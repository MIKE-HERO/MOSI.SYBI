# Instalacion inicial de MOSI en Android 7.1.2

Este instalador configura MOSI como **Device Owner**. Eso permite que las actualizaciones posteriores se instalen sin pedir confirmacion en pantalla.

## Requisitos

- El dispositivo debe estar recien restablecido y sin cuentas de Google.
- Activar `Opciones de desarrollador > Depuracion USB`.
- Conectar una sola maquina MOSI a la PC con un cable USB de datos.
- Aceptar la huella RSA que aparece en Android.

## Uso

1. Compilar el APK o copiarlo a la PC.
2. Arrastrar el archivo APK encima de `instalar-mosi.bat`, o ejecutar:

   ```bat
   tools\instalar-mosi.bat C:\ruta\MOSI.apk
   ```

Si no se proporciona una ruta, el instalador busca primero `app-release.apk` y despues `app-debug.apk` dentro del proyecto.

El instalador no restablece el dispositivo ni borra datos. Si Android rechaza la activacion por existir cuentas o una configuracion anterior, muestra el motivo y se detiene.

Durante la instalacion tambien intenta configurar `/dev/ttyS2` y `/dev/ttyS3` a 9600 baudios y enviar sus tramas de inicializacion. Esta parte requiere que la maquina tenga acceso `su`; si no lo tiene, el instalador muestra una advertencia pero conserva la instalacion de MOSI y la configuracion Device Owner.

> Importante: todas las actualizaciones deben conservar el mismo `applicationId` (`com.sybi.mosi`), la misma firma digital y un `versionCode` mayor.
