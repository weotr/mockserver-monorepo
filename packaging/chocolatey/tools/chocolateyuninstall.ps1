# Chocolatey uninstall script for MockServer CLI

$ErrorActionPreference = 'Stop'

Uninstall-BinFile -Name 'mockserver'

$binaryPath = Join-Path $env:ChocolateyInstall "lib\mockserver\tools\mockserver.exe"
if (Test-Path $binaryPath) {
    Remove-Item $binaryPath -Force
}
