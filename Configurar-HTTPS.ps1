param(
    [Parameter(Mandatory = $true)]
    [string]$Ip,
    [switch]$Renovar
)

$ErrorActionPreference = 'Stop'
if ($Ip -notmatch '^(?:0|[1-9][0-9]{0,2})(?:\.(?:0|[1-9][0-9]{0,2})){3}$') {
    throw 'Escribe una IPv4 privada, por ejemplo 192.168.1.50.'
}
$partes = $Ip.Split('.') | ForEach-Object { [int]$_ }
if (($partes | Where-Object { $_ -gt 255 }) -or !(
    $partes[0] -eq 10 -or
    ($partes[0] -eq 172 -and $partes[1] -in (16..31)) -or
    ($partes[0] -eq 192 -and $partes[1] -eq 168)
)) {
    throw 'Usa la IP privada que muestra el ESP32.'
}

$openssl = (Get-Command openssl.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (!$openssl) {
    $git = Get-Command git.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($git) {
        $gitFolder = Split-Path (Split-Path $git.Source -Parent) -Parent
        $openssl = Join-Path $gitFolder 'mingw64/bin/openssl.exe'
    }
}
if (!$openssl -or !(Test-Path -LiteralPath $openssl)) {
    throw 'Se requiere OpenSSL. En Windows puedes instalar Git for Windows y volver a ejecutar este script.'
}

$firmware = Join-Path $PSScriptRoot 'firmware/DetectorEsp32'
$headerPath = Join-Path $firmware 'detector_tls_secrets.h'
$accessFile = Join-Path $firmware 'detector_access_key.txt'
$raw = Join-Path $PSScriptRoot 'app/src/main/res/raw'
$appCert = Join-Path $raw 'detector_cert.pem'
if (!$Renovar -and ((Test-Path -LiteralPath $headerPath) -or (Test-Path -LiteralPath $appCert) -or (Test-Path -LiteralPath $accessFile))) {
    throw 'Ya existe una configuración. Usa -Renovar para cambiar IP, certificado y clave de acceso.'
}

$temp = [IO.Path]::Combine([IO.Path]::GetTempPath(), 'detector-tls-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $pem = Join-Path $temp 'cert.pem'
    $key = Join-Path $temp 'key.pem'
    & $openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -sha256 -noenc `
        -days 365 -subj '/CN=Detector de Gas' -addext "subjectAltName=IP:$Ip" `
        -addext 'basicConstraints=critical,CA:FALSE' -addext 'keyUsage=critical,digitalSignature' `
        -addext 'extendedKeyUsage=serverAuth' -keyout $key -out $pem
    if ($LASTEXITCODE -ne 0) { throw 'OpenSSL no pudo generar el certificado.' }
    $access = (& $openssl rand -hex 16).Trim()
    if ($LASTEXITCODE -ne 0 -or $access -notmatch '^[0-9a-f]{32}$') {
        throw 'OpenSSL no pudo generar la clave de acceso.'
    }
    $certText = (Get-Content -Raw -LiteralPath $pem).Trim()
    $privateText = (Get-Content -Raw -LiteralPath $key).Trim()
    $header = @"
#pragma once

static constexpr char DETECTOR_IP[] = "$Ip";
static constexpr char DETECTOR_AUTHORIZATION[] = "Bearer $access";

static constexpr char DETECTOR_CERT[] = R"PEM($certText
)PEM";

static constexpr char DETECTOR_PRIVATE_KEY[] = R"PEM($privateText
)PEM";
"@
    New-Item -ItemType Directory -Force -Path $raw | Out-Null
    Copy-Item -LiteralPath $pem -Destination $appCert -Force
    Set-Content -LiteralPath $headerPath -Value $header -Encoding ascii
    Set-Content -LiteralPath $accessFile -Value $access -Encoding ascii
    Write-Host 'Certificado instalado en el proyecto Android. Vuelve a cargar el ESP32 y recompila el APK.'
    Write-Host 'Copia la clave desde firmware/DetectorEsp32/detector_access_key.txt al campo de la app.'
} finally {
    foreach ($file in @('cert.pem', 'key.pem')) {
        $path = Join-Path $temp $file
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    Remove-Item -LiteralPath $temp
}
