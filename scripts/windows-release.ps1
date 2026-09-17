# PowerShell 5.1 compatible: access release paths directly, never recurse through junctions.
function Export-MctierWindowsRelease {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$ReleaseDirectory,
        [Parameter(Mandatory = $true)][string]$OutputDirectory,
        [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version
    )
    $executable = Join-Path $ReleaseDirectory 'mctier.exe'
    $installerName = "MCTier_${Version}_x64-setup.exe"
    $installer = Join-Path $ReleaseDirectory "bundle\nsis\$installerName"
    # Validate the entire set before copying anything. An older version's installer is not a fallback.
    foreach ($path in @($executable, $installer)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) {
            throw "Missing Windows build artifact: $path"
        }
    }
    $actualVersion = (Get-Item -LiteralPath $executable).VersionInfo.ProductVersion
    if ($actualVersion -ne $Version) {
        throw "Windows executable version mismatch: expected $Version, actual '$actualVersion'."
    }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $artifacts = @(
        @{ Source = $executable; Destination = (Join-Path $OutputDirectory 'MCTier.exe') },
        @{ Source = $installer; Destination = (Join-Path $OutputDirectory $installerName) }
    )
    foreach ($artifact in $artifacts) {
        Copy-Item -LiteralPath $artifact.Source -Destination $artifact.Destination -Force -ErrorAction Stop
        if ((Get-FileHash -LiteralPath $artifact.Source -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $artifact.Destination -Algorithm SHA256).Hash) {
            throw "Windows artifact copy checksum mismatch: $($artifact.Destination)"
        }
    }
    return $artifacts.Destination
}
