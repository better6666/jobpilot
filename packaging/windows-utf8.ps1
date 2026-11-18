param([Parameter(Mandatory = $true)][string]$Executable)
$ErrorActionPreference = 'Stop'
$exe = (Resolve-Path -LiteralPath $Executable).ProviderPath
$sdk = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin\*\x64\mt.exe'
$mt = Get-ChildItem -Path $sdk | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $mt) { throw 'Windows SDK mt.exe is required' }
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('jobpilot-manifest-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $original = $exe
    $exe = Join-Path $temporary 'JobPilot.exe'
    Copy-Item -LiteralPath $original -Destination $exe
    (Get-Item -LiteralPath $exe).IsReadOnly = $false
    $manifest = Join-Path $temporary 'launcher.manifest'
    & $mt -nologo "-inputresource:$exe;#1" "-out:$manifest"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read launcher manifest' }
    [xml]$document = [IO.File]::ReadAllText($manifest)
    $application = $document.SelectSingleNode("/*[local-name()='assembly']/*[local-name()='application' and namespace-uri()='urn:schemas-microsoft-com:asm.v3']")
    if ($null -eq $application) {
        $application = $document.CreateElement('application', 'urn:schemas-microsoft-com:asm.v3')
        $document.DocumentElement.AppendChild($application) | Out-Null
    }
    $settings = $application.SelectSingleNode("*[local-name()='windowsSettings']")
    if ($null -eq $settings) {
        $settings = $document.CreateElement('windowsSettings', 'urn:schemas-microsoft-com:asm.v3')
        $application.AppendChild($settings) | Out-Null
    }
    $codePage = $settings.SelectSingleNode("*[local-name()='activeCodePage']")
    if ($null -eq $codePage) {
        $codePage = $document.CreateElement('activeCodePage', 'http://schemas.microsoft.com/SMI/2019/WindowsSettings')
        $settings.AppendChild($codePage) | Out-Null
    }
    # Only this executable uses UTF-8; preserve the existing manifest and system locale.
    # https://learn.microsoft.com/windows/apps/design/globalizing/use-utf8-code-page
    $codePage.InnerText = 'UTF-8'
    $document.Save($manifest)
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        & $mt -nologo -manifest $manifest "-outputresource:$exe;#1"
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($LASTEXITCODE -ne 0) { throw 'Cannot update launcher manifest' }
    $verified = Join-Path $temporary 'verified.manifest'
    & $mt -nologo "-inputresource:$exe;#1" "-out:$verified"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify launcher manifest' }
    [xml]$actual = [IO.File]::ReadAllText($verified)
    if ($actual.SelectSingleNode("//*[local-name()='activeCodePage']").InnerText -ne 'UTF-8') {
        throw 'Launcher UTF-8 code page was not embedded'
    }
    (Get-Item -LiteralPath $original).IsReadOnly = $false
    Copy-Item -LiteralPath $exe -Destination $original -Force
    if ((Get-FileHash -LiteralPath $exe).Hash -ne (Get-FileHash -LiteralPath $original).Hash) {
        throw 'Launcher copy verification failed'
    }
    Write-Output 'Verified UTF-8 launcher manifest; original resources preserved'
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force
}
