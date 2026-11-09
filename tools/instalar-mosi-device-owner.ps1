[CmdletBinding()]
param(
    [string]$ApkPath = "",
    [switch]$NoInstalar
)

$ErrorActionPreference = "Stop"
$packageName = "com.sybi.mosi"
$adminComponent = "$packageName/.admin.MosiAdminReceiver"
$projectRoot = Split-Path -Parent $PSScriptRoot

function Stop-WithMessage([string]$Message) {
    Write-Host ""
    Write-Host "ERROR: $Message" -ForegroundColor Red
    Write-Host ""
    Write-Host "No se restablecio ni se borro ningun dato del equipo."
    exit 1
}

function Find-Adb {
    $fromPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($fromPath) {
        return $fromPath.Source
    }

    $candidates = @()
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return $candidate
        }
    }

    return $null
}

function Invoke-Adb([string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $script:adb @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        Stop-WithMessage "ADB fallo al ejecutar: adb $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return [PSCustomObject]@{
        ExitCode = $exitCode
        Output = ($output -join [Environment]::NewLine).Trim()
    }
}

Write-Host ""
Write-Host "INSTALADOR MOSI - ANDROID 7.1.2" -ForegroundColor Cyan
Write-Host "Instala MOSI y lo configura para actualizaciones automaticas sin confirmacion."
Write-Host ""

$script:adb = Find-Adb
if (-not $script:adb) {
    Stop-WithMessage "No se encontro adb.exe. Instala Android SDK Platform-Tools o abre este instalador desde una PC con Android Studio."
}

Write-Host "1/6 Comprobando conexion USB..."
& $script:adb start-server | Out-Null
$deviceLines = & $script:adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\S" }
$authorized = @($deviceLines | Where-Object { $_ -match "\sdevice$" })
$unauthorized = @($deviceLines | Where-Object { $_ -match "\sunauthorized$" })

if ($unauthorized.Count -gt 0) {
    Stop-WithMessage "El dispositivo no autorizo la depuracion USB. Acepta la huella RSA en la pantalla de Android y vuelve a ejecutar el instalador."
}
if ($authorized.Count -eq 0) {
    Stop-WithMessage "No se encontro ningun dispositivo. Activa Depuracion USB y conecta la maquina con un cable de datos."
}
if ($authorized.Count -gt 1) {
    Stop-WithMessage "Hay mas de un dispositivo conectado. Deja conectada solamente la maquina MOSI."
}

$androidVersion = (Invoke-Adb @("shell", "getprop", "ro.build.version.release")).Output
$androidSdk = (Invoke-Adb @("shell", "getprop", "ro.build.version.sdk")).Output
Write-Host "    Android $androidVersion (API $androidSdk) detectado."

if (-not $NoInstalar) {
    if (-not $ApkPath) {
        $apkCandidates = @(
            (Join-Path $projectRoot "app\build\outputs\apk\release\app-release.apk"),
            (Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk")
        )
        $ApkPath = $apkCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    }

    if (-not $ApkPath) {
        Stop-WithMessage "No se encontro un APK. Compila la app o ejecuta: instalar-mosi.bat C:\ruta\MOSI.apk"
    }

    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction SilentlyContinue).Path
    if (-not $resolvedApk -or -not (Test-Path -LiteralPath $resolvedApk -PathType Leaf)) {
        Stop-WithMessage "No existe el APK indicado: $ApkPath"
    }

    Write-Host "2/6 Instalando APK..."
    Write-Host "    $resolvedApk"
    $install = Invoke-Adb @("install", "-r", $resolvedApk) -AllowFailure
    if ($install.ExitCode -ne 0 -or $install.Output -notmatch "Success") {
        Stop-WithMessage "Android no pudo instalar el APK.`n$($install.Output)`nSi ya habia otra version, verifica que ambas tengan la misma firma digital."
    }
} else {
    Write-Host "2/6 Se omitio la instalacion del APK."
}

Write-Host "3/6 Verificando la aplicacion..."
$packageCheck = Invoke-Adb @("shell", "pm", "path", $packageName) -AllowFailure
if ($packageCheck.ExitCode -ne 0 -or $packageCheck.Output -notmatch "package:") {
    Stop-WithMessage "MOSI no esta instalado con el paquete esperado: $packageName"
}

Write-Host "4/6 Activando MOSI como propietario del dispositivo..."
$ownerResult = Invoke-Adb @("shell", "dpm", "set-device-owner", $adminComponent) -AllowFailure
if ($ownerResult.ExitCode -ne 0 -or $ownerResult.Output -notmatch "(?i)(success|active admin|already.*owner)") {
    Stop-WithMessage "Android rechazo la activacion como Device Owner.`n$($ownerResult.Output)`nEl equipo debe estar recien restablecido, sin cuentas de Google ni otro administrador. En ese caso hay que restablecerlo manualmente y ejecutar este instalador antes de configurarlo."
}

Write-Host "5/6 Configurando puertos ttyS2 y ttyS3 a 9600 baudios..."
$serialInitCommand = "su -c 'stty -F /dev/ttyS2 9600; printf `"\002\000\014\001\000\041\000\001\000\000\000\000\000\000\000\003\261\323`" > /dev/ttyS2; stty -F /dev/ttyS3 9600; printf `"\002\000\014\001\000\041\000\013\000\000\000\000\000\000\000\003\021\255`" > /dev/ttyS3'"
$serialInit = Invoke-Adb @("shell", $serialInitCommand) -AllowFailure
if ($serialInit.ExitCode -ne 0) {
    Write-Host "    ADVERTENCIA: no se pudieron inicializar los puertos seriales." -ForegroundColor Yellow
    Write-Host "    Se necesita acceso root (su). MOSI y Device Owner si quedaron configurados."
    if ($serialInit.Output) { Write-Host "    $($serialInit.Output)" }
} else {
    Write-Host "    Puertos seriales inicializados correctamente."
}

Write-Host "6/6 Confirmando configuracion..."
$policy = Invoke-Adb @("shell", "dumpsys", "device_policy") -AllowFailure
if ($policy.Output -notmatch [regex]::Escape($packageName)) {
    Stop-WithMessage "El comando termino, pero MOSI no aparece en la politica del dispositivo.`n$($policy.Output)"
}

Invoke-Adb @("shell", "monkey", "-p", $packageName, "-c", "android.intent.category.LAUNCHER", "1") -AllowFailure | Out-Null

Write-Host ""
Write-Host "LISTO: MOSI quedo instalado y configurado como Device Owner." -ForegroundColor Green
Write-Host "Las siguientes actualizaciones podran instalarse sin mostrar la pantalla de confirmacion."
Write-Host "Puedes desconectar el cable USB."
Write-Host ""

