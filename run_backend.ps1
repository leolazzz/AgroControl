$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"
$ollamaHost = "http://127.0.0.1:11437"
$ollamaModel = "qwen3.5:0.8b"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Project environment is missing. Create .venv and install backend/requirements.txt first."
}

function Test-OllamaServer {
    try {
        Invoke-RestMethod -Uri "$ollamaHost/api/tags" -TimeoutSec 2 | Out-Null
        return $true
    }
    catch {
        return $false
    }
}

if (-not (Test-OllamaServer)) {
    $ollama = (Get-Command ollama -ErrorAction SilentlyContinue).Source
    if (-not $ollama) {
        throw "Ollama is missing. Install Ollama and pull $ollamaModel first."
    }

    $env:OLLAMA_HOST = "127.0.0.1:11437"
    $env:OLLAMA_VULKAN = "1"
    $env:OLLAMA_LLM_LIBRARY = "vulkan"
    Start-Process -FilePath $ollama -ArgumentList "serve" -WindowStyle Hidden | Out-Null

    for ($attempt = 0; $attempt -lt 30 -and -not (Test-OllamaServer); $attempt++) {
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-OllamaServer)) {
        throw "Ollama GPU server did not start on port 11437."
    }
}

$warmupBody = @{
    model = $ollamaModel
    prompt = ""
    stream = $false
    keep_alive = "10m"
    options = @{
        num_ctx = 2048
        num_predict = 1
        num_gpu = 999
    }
} | ConvertTo-Json -Depth 4

Invoke-RestMethod `
    -Uri "$ollamaHost/api/generate" `
    -Method Post `
    -ContentType "application/json" `
    -Body $warmupBody `
    -TimeoutSec 120 | Out-Null

$loadedModel = (Invoke-RestMethod -Uri "$ollamaHost/api/ps" -TimeoutSec 5).models |
    Where-Object { $_.model -eq $ollamaModel } |
    Select-Object -First 1
if (-not $loadedModel -or [long]$loadedModel.size_vram -le 0) {
    throw "Ollama started, but $ollamaModel was not loaded on GPU."
}

Write-Host "Ollama GPU ready: $ollamaModel on Vulkan ($([math]::Round($loadedModel.size_vram / 1MB)) MB VRAM)."

Set-Location -LiteralPath $projectRoot
& $python "backend/app.py" @args
exit $LASTEXITCODE
