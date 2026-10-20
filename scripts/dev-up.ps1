param([switch]$SourceMinio)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path -LiteralPath '.env')) {
    throw 'Create .env from .env.example and set local service passwords first.'
}
Get-Command docker -ErrorAction Stop | Out-Null
& docker info --format 'Docker {{.ServerVersion}} / {{.OSType}}'
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop with the Linux engine first.' }

$settings = @{}
foreach ($line in Get-Content -LiteralPath '.env') {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith("'") -and $value.EndsWith("'")) -or ($value.StartsWith('"') -and $value.EndsWith('"')))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $settings[$key] = $value
    }
}
foreach ($key in @('DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY', 'QDRANT_API_KEY')) {
    if ([string]::IsNullOrWhiteSpace($settings[$key]) -or $settings[$key].StartsWith('change-')) {
        throw "Set a non-example value for $key in .env."
    }
}
$appUser = if ($settings['MYSQL_APP_USER']) { $settings['MYSQL_APP_USER'] } else { 'dovideo' }
if (-not (Test-Path -LiteralPath 'mysql/data/mysql') -and $settings['DB_USERNAME'] -ne $appUser) {
    throw 'For a fresh database, DB_USERNAME and MYSQL_APP_USER must match.'
}

$composeOptions = @('compose', '--env-file', '.env', '-f', 'docker-compose.yml')
if ($SourceMinio) { $composeOptions += @('-f', 'docker-compose.source-minio.yml') }
function Invoke-ProjectCompose {
    & docker @composeOptions @args
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed; inspect the error above.' }
}
Invoke-ProjectCompose config --quiet
if ($SourceMinio) { Invoke-ProjectCompose build minio }

# The upstream RocketMQ container runs as uid 3000; initialize only its named volumes.
Invoke-ProjectCompose run --rm --no-deps --user root --entrypoint sh rmqbroker -c 'mkdir -p /home/rocketmq/store /home/rocketmq/logs && chown -R 3000:3000 /home/rocketmq/store /home/rocketmq/logs'
Invoke-ProjectCompose up --wait --wait-timeout 120 --no-build

function Wait-LocalHttp($url, $headers) {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "Local service did not become healthy: $url"
}
Wait-LocalHttp 'http://127.0.0.1:9000/minio/health/live' @{}
Wait-LocalHttp 'http://127.0.0.1:6333/healthz' @{ 'api-key' = $settings['QDRANT_API_KEY'] }

$registered = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $cluster = & docker @composeOptions exec -T rmqbroker sh -c '$ROCKETMQ_HOME/bin/mqadmin clusterList -n rmqnamesrv:9876' 2>&1
    if ($LASTEXITCODE -eq 0 -and ($cluster -match 'broker-a')) { $registered = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $registered) { throw 'RocketMQ broker did not register with nameserver.' }
foreach ($key in @('ROCKETMQ_ANALYSIS_TOPIC', 'ROCKETMQ_ANALYSIS_DEAD_TOPIC')) {
    $topic = $settings[$key]
    if (-not $topic) { $topic = if ($key -eq 'ROCKETMQ_ANALYSIS_TOPIC') { 'video-analysis-topic' } else { 'video-analysis-dead-topic' } }
    if ($topic -notmatch '^[A-Za-z0-9_-]+$') { throw "Invalid topic name in $key." }
    Invoke-ProjectCompose exec -T rmqbroker sh -c ('$ROCKETMQ_HOME/bin/mqadmin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t ' + $topic)
}
Invoke-ProjectCompose ps
Write-Host 'Local infrastructure is ready. AI/OCR tools and the backend are checked separately.'
