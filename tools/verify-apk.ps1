param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [Parameter(Mandatory=$true)][string]$BuildTools
)
$ErrorActionPreference = 'Stop'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$toolRoot = (Resolve-Path -LiteralPath $BuildTools).Path
& (Join-Path $toolRoot 'apksigner.bat') verify --verbose $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
& (Join-Path $toolRoot 'zipalign.exe') -c -P 16 4 $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK ZIP alignment verification failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    foreach ($entry in $archive.Entries | Where-Object { $_.FullName -match '^lib/.+\.so$' }) {
        $stream = $entry.Open()
        $memory = [System.IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
        if ($bytes[0] -ne 127 -or $bytes[1] -ne 69 -or $bytes[2] -ne 76 -or $bytes[3] -ne 70 -or $bytes[5] -ne 1) { throw "Unsupported ELF: $($entry.FullName)" }
        if ($bytes[4] -eq 2) {
            $offset = [BitConverter]::ToUInt64($bytes,32); $entrySize = [BitConverter]::ToUInt16($bytes,54); $count = [BitConverter]::ToUInt16($bytes,56); $alignOffset = 48
        } else {
            $offset = [BitConverter]::ToUInt32($bytes,28); $entrySize = [BitConverter]::ToUInt16($bytes,42); $count = [BitConverter]::ToUInt16($bytes,44); $alignOffset = 28
        }
        $alignments = @()
        for ($index = 0; $index -lt $count; $index++) {
            $header = [int]($offset + $index * $entrySize)
            if ([BitConverter]::ToUInt32($bytes,$header) -eq 1) {
                $alignment = if ($bytes[4] -eq 2) { [BitConverter]::ToUInt64($bytes,$header+$alignOffset) } else { [BitConverter]::ToUInt32($bytes,$header+$alignOffset) }
                $alignments += $alignment
                if ($alignment -lt 16384) { throw "ELF LOAD alignment below 16 KB: $($entry.FullName)" }
            }
        }
        Write-Output "$($entry.FullName): LOAD alignments $($alignments -join ', ')"
    }
} finally { $archive.Dispose() }
Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
