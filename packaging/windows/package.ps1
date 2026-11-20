[CmdletBinding(DefaultParameterSetName = 'PreparedOcr')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'PreparedOcr')][string]$OcrRoot,
    [Parameter(Mandatory = $true, ParameterSetName = 'Installer')][string]$OcrInstaller,
    [Parameter(Mandatory = $true, ParameterSetName = 'Installer')][string]$TessdataRoot,
    [Parameter(ParameterSetName = 'Installer')][string]$SevenZip = '7z.exe',
    [string]$Destination,
    [ValidateSet('msi', 'exe', 'app-image')][string]$PackageType = 'msi',
    [switch]$MavenOffline
)

$ErrorActionPreference = 'Stop'

function Get-MsiProductVersion([string]$Path) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $view = $record = $null
    try {
        $database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($Path, 0))
        $query = 'SELECT `Value` FROM `Property` WHERE `Property` = ''ProductVersion'''
        $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @($query))
        $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
        $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
        if ($null -eq $record) { throw "Missing ProductVersion in $Path" }
        return $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
    } finally {
        foreach ($object in @($record, $view, $database, $installer)) {
            if ($null -ne $object) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($object) }
        }
    }
}

$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if ($env:OS -ne 'Windows_NT' -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
    throw 'Build on a Windows x64 target with a native JDK 21.'
}
$icon = Join-Path $project 'packaging\icons\expense-assistant.ico'
if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) {
    throw "Application icon is missing: $icon"
}
[IO.File]::OpenRead($icon).Dispose()
if (-not $env:JAVA_HOME) {
    throw 'Set JAVA_HOME to a Windows x64 JDK 21.'
}
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
$jdkVersion = & $jpackage --version
if ($LASTEXITCODE -ne 0 -or $jdkVersion -notmatch '^21(\.|$)') {
    throw 'JDK 21 jpackage is required.'
}
if ($PackageType -ne 'app-image') {
    Get-Command candle.exe, light.exe -ErrorAction Stop | Out-Null
    $resources = Join-Path $PSScriptRoot 'resources'
    $overrides = Join-Path $resources 'overrides.wxi'
    if (-not (Test-Path -LiteralPath $overrides -PathType Leaf)) {
        throw "Installer override is missing: $overrides"
    }
    [IO.File]::OpenRead($overrides).Dispose()
}
if ($PSCmdlet.ParameterSetName -eq 'Installer') {
    $OcrInstaller = (Resolve-Path -LiteralPath $OcrInstaller).Path
    $TessdataRoot = (Resolve-Path -LiteralPath $TessdataRoot).Path
} else {
    $OcrRoot = (Resolve-Path -LiteralPath $OcrRoot).Path
}
if (-not $Destination) {
    $Destination = Join-Path $project 'release\windows-x64'
}
if (-not (Test-Path -LiteralPath $Destination -PathType Container)) {
    New-Item -ItemType Directory -Path $Destination | Out-Null
}
$Destination = (Resolve-Path -LiteralPath $Destination).Path
$scratch = Join-Path $project ('target\package-windows.' + [guid]::NewGuid())
$input = Join-Path $scratch 'input'
New-Item -ItemType Directory -Path $input -Force | Out-Null

Push-Location -LiteralPath $project
try {
    if ($PSCmdlet.ParameterSetName -eq 'Installer') {
        $components = Get-Content -Raw -Encoding UTF8 -LiteralPath 'packaging/components.json' | ConvertFrom-Json
        $nativeLock = $components.platforms.'windows-x64'.ocr
        $hash = (Get-FileHash -LiteralPath $OcrInstaller -Algorithm SHA256).Hash
        if ($hash -ne $nativeLock.upstreamInstallerSha256) { throw 'Unexpected upstream OCR installer checksum.' }
        $extracted = Join-Path $scratch 'extracted'
        $OcrRoot = Join-Path $scratch 'provisioned'
        $binaryInputs = @($nativeLock.files.PSObject.Properties.Name | Where-Object { $_ -like 'bin/*' })
        $members = @($binaryInputs | ForEach-Object { $_.Substring(4) }) + 'doc/LICENSE'
        & $SevenZip 'x' '-y' "-o$extracted" $OcrInstaller @members
        if ($LASTEXITCODE -ne 0) { throw 'Could not extract the pinned OCR installer.' }
        foreach ($file in $binaryInputs) {
            $target = Join-Path $OcrRoot $file
            if (-not (Test-Path -LiteralPath (Split-Path -Parent $target))) {
                New-Item -ItemType Directory -Path (Split-Path -Parent $target) | Out-Null
            }
            Copy-Item -LiteralPath (Join-Path $extracted ($file.Substring(4))) -Destination $target
        }
        $licenses = Join-Path $OcrRoot 'licenses'
        New-Item -ItemType Directory -Path $licenses | Out-Null
        Copy-Item -LiteralPath (Join-Path $extracted 'doc\LICENSE') -Destination (Join-Path $licenses 'Tesseract-Apache-2.0.txt')
        $languageDestination = Join-Path $OcrRoot 'share\tessdata'
        New-Item -ItemType Directory -Path (Join-Path $languageDestination 'configs') -Force | Out-Null
        foreach ($file in @('chi_sim.traineddata', 'eng.traineddata', 'configs\tsv')) {
            Copy-Item -LiteralPath (Join-Path $TessdataRoot $file) -Destination (Join-Path $languageDestination $file)
        }
    }
    [xml]$pom = Get-Content -Raw -Encoding UTF8 -LiteralPath 'pom.xml'
    $version = [string]$pom.project.version
    $nativeVersion = & $java packaging/ReleaseVersion.java packaging/release-versions.properties $version
    if ($LASTEXITCODE -ne 0 -or $nativeVersion -isnot [string] -or $nativeVersion -notmatch '^\d+\.\d+\.\d+$') {
        throw 'Application version failed release ledger validation.'
    }
    $jar = "expense-assistant-$version.jar"
    $mavenArgs = @('-B', '-ntp')
    if ($MavenOffline) { $mavenArgs += '-o' }
    $mavenArgs += @('verify', 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
        '-DincludeScope=runtime', "-DoutputDirectory=$input")
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification or dependency staging failed.' }
    Copy-Item -LiteralPath (Join-Path 'target' $jar) -Destination (Join-Path $input $jar)
    & $java --class-path "$input\*" packaging/StageRelease.java packaging/components.json windows-x64 $input $OcrRoot $jar
    if ($LASTEXITCODE -ne 0) { throw 'Release inputs failed version or checksum verification.' }
    $packageOutput = $Destination
    if ($PackageType -ne 'app-image') { $packageOutput = Join-Path $scratch 'out' }
    $jpackageArgs = @('--type', $PackageType, '--input', $input, '--dest', $packageOutput,
        '--name', 'ExpenseAssistant', '--app-version', $nativeVersion,
        '--main-jar', $jar, '--main-class', 'expenseassistant.DesktopLauncher', '--add-modules',
        'java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs', '--icon', $icon)
    if ($PackageType -ne 'app-image') {
        $jpackageArgs += @('--win-menu', '--win-shortcut', '--win-per-user-install', '--resource-dir', $resources)
    }
    & $jpackage @jpackageArgs
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
    if ($PackageType -ne 'app-image') {
        $candidate = Join-Path $packageOutput "ExpenseAssistant-$nativeVersion.$PackageType"
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf) -or (Get-Item -LiteralPath $candidate).Length -eq 0) {
            throw 'Missing installer in the current build output.'
        }
        $productVersion = if ($PackageType -eq 'msi') { Get-MsiProductVersion $candidate }
            else { [Diagnostics.FileVersionInfo]::GetVersionInfo($candidate).ProductVersion }
        if ($productVersion -ne $nativeVersion) { throw "Unexpected installer ProductVersion: $productVersion" }
        $output = Join-Path $Destination "ExpenseAssistant-$version.$PackageType"
        Move-Item -LiteralPath $candidate -Destination $output -Force
        Write-Output $output
    }
} finally {
    Pop-Location
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
