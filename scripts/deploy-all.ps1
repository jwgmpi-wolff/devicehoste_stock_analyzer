[CmdletBinding()]
param(
    [switch]$SkipAndroidInstall
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & .\gradlew.bat testDebugUnitTest assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Android validation failed." }

    & dotnet run --project .\windows\StockMovementAnalyzer.Verification\StockMovementAnalyzer.Verification.csproj -c Release
    if ($LASTEXITCODE -ne 0) { throw "Windows parity verification failed." }

    foreach ($runtime in 'win-arm64', 'win-x64') {
        $publishPath = ".\windows\StockMovementAnalyzer.Windows\publish\$runtime"
        & dotnet publish .\windows\StockMovementAnalyzer.Windows\StockMovementAnalyzer.Windows.csproj `
            -c Release -r $runtime --self-contained true `
            -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true `
            -p:IncludeNativeLibrariesForSelfExtract=true -o $publishPath
        if ($LASTEXITCODE -ne 0) { throw "Windows $runtime publish failed." }

        $architecture = $runtime.Replace('win-', '')
        $publishedExecutable = Join-Path $publishPath 'StockMovementAnalyzer.Windows.exe'
        Copy-Item $publishedExecutable ".\releases\StockMovementAnalyzer-Windows-$architecture.exe" -Force
        Copy-Item $publishedExecutable ".\releases\StockMovementAnalyzer-Setup-$architecture.exe" -Force
    }

    Copy-Item .\app\build\outputs\apk\debug\app-debug.apk .\releases\StockMovementAnalyzer-debug.apk -Force

    Get-Process -Name 'StockMovementAnalyzer.Windows' -ErrorAction SilentlyContinue | Stop-Process -Force
    $currentArchitecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
    $setupPath = Resolve-Path ".\releases\StockMovementAnalyzer-Setup-$currentArchitecture.exe"
    $setupProcess = Start-Process $setupPath -ArgumentList '/install', '/silent' -PassThru -Wait
    if ($setupProcess.ExitCode -ne 0) { throw "Windows installation failed with exit code $($setupProcess.ExitCode)." }

    if (-not $SkipAndroidInstall) {
        $adb = Get-Command adb -ErrorAction Stop
        $devices = & $adb.Source devices | Select-String "`tdevice$" | ForEach-Object {
            ($_ -split "`t")[0].Trim()
        }
        if ($devices.Count -eq 0) {
            Write-Warning "No authorized Android devices are attached; skipped Android installation."
        }
        else {
            foreach ($device in $devices) {
                & $adb.Source -s $device install -r .\app\build\outputs\apk\debug\app-debug.apk
                if ($LASTEXITCODE -ne 0) { throw "Android in-place installation failed for device $device." }
            }
        }
    }

    $installedApp = Join-Path $env:LOCALAPPDATA 'Programs\StockMovementAnalyzer\StockMovementAnalyzer.Windows.exe'
    Start-Process $installedApp

    Write-Host 'Validated, published, and redeployed Windows and Android without clearing user data.' -ForegroundColor Green
}
finally {
    Pop-Location
}
