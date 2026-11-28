param([Parameter(Mandatory=$true)][string]$Installer)
$ErrorActionPreference = 'Stop'
$desktopRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$installRoot = [IO.Path]::GetFullPath((Join-Path $desktopRoot 'build/installer-smoke/YoungMini'))
if (-not $installRoot.StartsWith((Join-Path $desktopRoot 'build') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test install path escaped workspace' }
$installerPath = (Resolve-Path -LiteralPath $Installer).Path
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runName = 'kr.snu.youngmini.desktop'
$uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\4addcb63-16d5-5f4d-821e-cf53b344332a'
$installKey = 'HKCU:\Software\4addcb63-16d5-5f4d-821e-cf53b344332a'
$shortcuts = @((Join-Path ([Environment]::GetFolderPath('Desktop')) '영민이.lnk'), (Join-Path ([Environment]::GetFolderPath('Programs')) '영민이.lnk'))
function Read-Startup { (Get-ItemProperty -LiteralPath $runKey -Name $runName -ErrorAction SilentlyContinue).$runName }
if ((Test-Path -LiteralPath $installRoot) -or (Test-Path -LiteralPath $uninstallKey) -or (Test-Path -LiteralPath $installKey) -or (Read-Startup)) { throw 'Existing app or test installation found; refusing to modify it' }
foreach ($shortcut in $shortcuts) { if (Test-Path -LiteralPath $shortcut) { throw 'Existing app shortcut found; refusing to replace it' } }
function Install-Fixture([string[]]$Options) {
    $process = Start-Process -FilePath $installerPath -ArgumentList (@('/S') + $Options + @("/D=$installRoot")) -WindowStyle Hidden -PassThru
    if (-not $process.WaitForExit(120000)) { throw 'Installer timed out' }
    Write-Output "Installer exit=$($process.ExitCode); executable=$(Test-Path -LiteralPath (Join-Path $installRoot 'YoungMini.exe')); startup=$([bool](Read-Startup)); uninstall=$(Test-Path -LiteralPath $uninstallKey)"
    if ($process.ExitCode -ne 0) { throw "Installer exited $($process.ExitCode)" }
    if (-not (Test-Path -LiteralPath (Join-Path $installRoot 'YoungMini.exe'))) { throw 'Installed executable missing' }
}
$uninstaller = Join-Path $installRoot 'Uninstall YoungMini.exe'
try {
    Install-Fixture @()
    if ((Read-Startup) -ne ('"' + $installRoot + '\YoungMini.exe" --startup')) { throw 'Fresh install did not register the expected startup command' }
    if (-not (Test-Path -LiteralPath $uninstallKey)) { throw 'Windows uninstall registration missing' }
    foreach ($shortcut in $shortcuts) { if (-not (Test-Path -LiteralPath $shortcut)) { throw 'App shortcut missing' } }
    Write-Output 'PASS: fresh per-user install, startup command, shortcuts and uninstall registration'
    Install-Fixture @('/AUTOSTART=0')
    if (Read-Startup) { throw 'Explicit startup opt-out was ignored' }
    Install-Fixture @()
    if (Read-Startup) { throw 'Reinstall re-enabled an opted-out startup item' }
    Write-Output 'PASS: startup opt-out and silent reinstall preserve the choice'
} finally {
    if (Test-Path -LiteralPath $uninstaller) {
        $process = Start-Process -FilePath $uninstaller -ArgumentList @('/S', "_?=$installRoot") -WindowStyle Hidden -PassThru
        if (-not $process.WaitForExit(120000)) { throw 'Uninstaller timed out' }
        if ($process.ExitCode -ne 0) { throw "Uninstaller exited $($process.ExitCode)" }
    }
}
if ((Read-Startup) -or (Test-Path -LiteralPath $uninstallKey)) { throw 'Uninstall left registration behind' }
foreach ($shortcut in $shortcuts) { if (Test-Path -LiteralPath $shortcut) { throw 'Uninstall left a shortcut behind' } }
Write-Output 'PASS: uninstall removes startup, shortcuts and Windows uninstall registration'
if (Test-Path -LiteralPath $uninstaller) { Remove-Item -LiteralPath $uninstaller }
if ((Test-Path -LiteralPath $installRoot) -and @(Get-ChildItem -LiteralPath $installRoot -Force).Count -eq 0) { Remove-Item -LiteralPath $installRoot }
