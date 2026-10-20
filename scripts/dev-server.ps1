param([string]$JavaHome=$env:JAVA_HOME)

$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $repo
if(!(Test-Path -LiteralPath '.env')){throw 'Create .env from .env.example and start the local infrastructure first.'}
if(!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))){throw 'Pass -JavaHome with a JDK 21 or newer installation.'}
$compiler=& (Join-Path $JavaHome 'bin/javac.exe') -version 2>&1
if(($compiler-join' ') -notmatch 'javac\s+(\d+)' -or [int]$Matches[1]-lt21){throw 'JDK 21 or newer is required; the system default Java will not be changed.'}
foreach($line in Get-Content -LiteralPath '.env') {
    if($line-match'^([A-Z][A-Z0-9_]*)=(.*)$') {
        $name=$Matches[1];$value=$Matches[2].Trim()
        if($value.Length-ge2 -and (($value.StartsWith("'") -and $value.EndsWith("'")) -or ($value.StartsWith('"') -and $value.EndsWith('"')))){$value=$value.Substring(1,$value.Length-2)}
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
}
$env:JAVA_HOME=$JavaHome
$env:Path=(Join-Path $JavaHome 'bin')+';'+$env:Path
if([string]::IsNullOrWhiteSpace($env:SILICONFLOW_API_KEY)) {
    # The inherited model client requires a nonempty key to initialize. Keep all
    # its unconfigured model URLs local, so this fallback cannot call a provider.
    $env:SILICONFLOW_API_KEY='local-no-model-service'
    $env:SILICONFLOW_BASE_URL='http://127.0.0.1:1/unconfigured'
    $env:ASR_URL='http://127.0.0.1:1/unconfigured/audio/transcriptions'
    Write-Host 'Original AI services are unconfigured. Local subtitle editing and FFmpeg export remain available.'
}
Set-Location -LiteralPath (Join-Path $repo 'server')
& .\mvnw.cmd -B -ntp spring-boot:run
exit $LASTEXITCODE
