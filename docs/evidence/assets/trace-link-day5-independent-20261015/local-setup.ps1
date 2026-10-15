
$day5Project='trace-link-day5-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
$day5Local=Join-Path (Get-Location) ('tmp/'+$day5Project)
New-Item -ItemType Directory -Path $day5Local -ErrorAction Stop | Out-Null
$day5Env=Join-Path $day5Local 'local.env'
$day5Override=Join-Path $day5Local 'ports.yaml'
$day5Text=[IO.File]::ReadAllText((Join-Path (Get-Location) '.env.example'))
$day5Text=[regex]::Replace($day5Text,'(?m)^([A-Z_]*(?:PASSWORD|SECRET))=\r?$',{
    param($match) $match.Groups[1].Value+'='+[guid]::NewGuid().ToString('N')
})
$day5Text+="`nTRACE_LINK_ENABLED=true`nML_TRACE_LINK_SHADOW_ENABLED=false`n"
[IO.File]::WriteAllText($day5Env,$day5Text,(New-Object Text.UTF8Encoding($false)))
[IO.File]::WriteAllText($day5Override,"services:`n  postgres:`n    ports: !override []`n  kafka:`n    ports: !override []`n",(New-Object Text.UTF8Encoding($false)))
$day5Compose=@('--project-name',$day5Project,'--env-file',$day5Env,'-f','compose.yaml','-f',$day5Override)
docker compose @day5Compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration failed' }
docker compose @day5Compose up -d --wait --wait-timeout 180 postgres kafka
if ($LASTEXITCODE -ne 0) { throw 'Disposable dependencies failed' }
docker compose @day5Compose up --no-deps --exit-code-from kafka-init kafka-init
if ($LASTEXITCODE -ne 0) { throw 'Topic initialization failed' }
docker compose @day5Compose up -d --no-deps --build --wait --wait-timeout 180 processor
if ($LASTEXITCODE -ne 0) { throw 'Disposable processor failed' }
docker compose @day5Compose ps
