# Chocolatey install script for MockServer CLI
#
# TODO(cli-release): Replace placeholder URLs and checksums once the CLI's
# GitHub Releases artifact naming is finalised.

$ErrorActionPreference = 'Stop'

$packageName = 'mockserver'
# TODO(cli-release): Replace with actual release version
$version = $env:ChocolateyPackageVersion

# Determine architecture
$is64bit = [System.Environment]::Is64BitOperatingSystem
$isArm = [System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture -eq [System.Runtime.InteropServices.Architecture]::Arm64

if ($isArm) {
    $arch = 'arm64'
} elseif ($is64bit) {
    $arch = 'x64'
} else {
    throw "MockServer CLI does not support 32-bit Windows"
}

# TODO(cli-release): Confirm binary naming convention
$url = "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-$version/mockserver-windows-$arch.exe"

# TODO(cli-release): Replace with actual checksums
$checksums = @{
    'x64'   = '${SHA256_WINDOWS_X64}'
    'arm64' = '${SHA256_WINDOWS_ARM64}'
}

$packageArgs = @{
    packageName    = $packageName
    fileFullPath   = Join-Path $env:ChocolateyInstall "lib\$packageName\tools\mockserver.exe"
    url64bit       = $url
    checksum64     = $checksums[$arch]
    checksumType64 = 'sha256'
}

# Download the binary directly (it's a standalone native executable, not an installer)
Get-ChocolateyWebFile @packageArgs

# Create a shim so `mockserver` is on PATH
Install-BinFile -Name 'mockserver' -Path $packageArgs.fileFullPath
