#!/usr/bin/env pwsh
# =====================================================================
# FlashDeal 一键压测脚本
#
# 用法：
#   pwsh bench/run-bench.ps1                     # 默认参数
#   pwsh bench/run-bench.ps1 -Threads 400 -Requests 40000
#
# 脚本做四件事：
#   1. 启动 Spring Boot（H2 文件库，端口 8080）
#   2. 创建商品 + 活动（库存按 -Stock 参数）
#   3. 调用自研压测工具打真实 HTTP 接口
#   4. 采集 /api/metrics 的缓存命中率、队列水位并打印
#
# 全程不依赖 Docker / Redis / MySQL。
# =====================================================================
param(
    [int]$Threads = 200,
    [int]$Requests = 20000,
    [int]$Warmup = 2000,
    [int]$Stock = 5000,
    [string]$StockMode = "db-cas",
    [int]$Port = 8080,
    [string]$Scenario = "seckill",
    [switch]$SkipBuild,
    [switch]$KeepServerRunning
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$base = "http://127.0.0.1:$Port"

function Write-Step($msg) {
    Write-Host ""
    Write-Host "==== $msg ====" -ForegroundColor Cyan
}

Write-Step "1/5 构建后端（跳过测试，测试由 mvn verify 单独负责）"
if (-not $SkipBuild) {
    Push-Location $root
    mvn -B -q clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "构建失败" }
    Pop-Location
}

$jar = Join-Path $root "target\flashdeal.jar"
if (-not (Test-Path $jar)) { throw "找不到 $jar，请先构建" }

Write-Step "2/5 启动应用（库存策略=$StockMode 端口=$Port）"
$logFile = Join-Path $root "bench\app-bench.log"
$env:FLASHDEAL_STOCK_MODE = $StockMode
$proc = Start-Process -FilePath $java `
    -ArgumentList @("-Xms512m", "-Xmx2g", "-Dfile.encoding=UTF-8",
                    "-Dserver.port=$Port", "-jar", $jar) `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError (Join-Path $root "bench\app-bench.err.log") `
    -PassThru -NoNewWindow

Write-Host "等待应用就绪..."
$ready = $false
for ($i = 1; $i -le 60; $i++) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest -Uri "$base/api/metrics/summary" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $ready = $true; Write-Host "就绪（第 $i 次探测）"; break }
    } catch { }
}
if (-not $ready) {
    Get-Content $logFile -Tail 40
    throw "应用启动失败"
}

try {
    Write-Step "3/5 准备压测数据（商品 + 活动 + 库存预热）"
    $productBody = @{ name = "压测商品-$(Get-Date -Format 'HHmmss')"; description = "bench"; priceCents = 19900 } | ConvertTo-Json
    $product = Invoke-RestMethod -Uri "$base/api/admin/products" -Method Post -ContentType "application/json" -Body $productBody
    $productId = $product.data.id
    Write-Host "商品 ID = $productId"

    $start = (Get-Date).AddMinutes(-1).ToString("yyyy-MM-dd HH:mm:ss")
    $end = (Get-Date).AddHours(2).ToString("yyyy-MM-dd HH:mm:ss")
    $activityBody = @{
        productId = $productId
        title = "压测活动-$(Get-Date -Format 'HHmmss')"
        seckillPriceCents = 9900
        totalStock = $Stock
        startTime = $start
        endTime = $end
    } | ConvertTo-Json
    $activity = Invoke-RestMethod -Uri "$base/api/admin/activities" -Method Post -ContentType "application/json" -Body $activityBody
    $activityId = $activity.data.id
    Write-Host "活动 ID = $activityId  库存 = $Stock"

    Invoke-RestMethod -Uri "$base/api/metrics/reset" -Method Post | Out-Null
    Write-Host "已复位指标基线"

    Write-Step "4/5 开始压测（线程=$Threads 请求=$Requests 预热=$Warmup 场景=$Scenario）"
    Push-Location $root
    & $java -cp "target/classes" com.flashdeal.bench.LoadTestHarness `
        --url $base --scenario $Scenario --activity-id $activityId --stock $Stock `
        --threads $Threads --requests $Requests --warmup $Warmup
    Pop-Location

    Write-Step "5/5 采集服务端指标（缓存命中率 / 队列水位 / 延迟分位）"
    $metrics = Invoke-RestMethod -Uri "$base/api/metrics"
    $m = $metrics.data
    Write-Host ""
    Write-Host "--- 服务端聚合 ---"
    Write-Host ("总请求数     : {0}" -f $m.aggregate.totalCount)
    Write-Host ("成功率       : {0}" -f $m.aggregate.successRate)
    Write-Host ("1s QPS       : {0}" -f $m.aggregate.qps1s)
    Write-Host ("峰值 QPS     : {0}" -f $m.aggregate.peakQps1s)
    Write-Host ""
    Write-Host "--- 缓存 ---"
    foreach ($c in $m.caches) {
        Write-Host ("{0}: 命中={1} 未命中={2} 命中率={3} 条目={4}/{5} 淘汰={6}" -f `
            $c.name, $c.hitCount, $c.missCount, $c.hitRate, $c.size, $c.capacity, $c.evictionCount)
    }
    Write-Host ("L2({0}): 命中={1} 未命中={2} 命中率={3}" -f `
        $m.l2.backend, $m.l2.hitCount, $m.l2.missCount, $m.l2.hitRate)
    Write-Host ("综合命中率(含L2): {0}" -f $m.extra.cacheOverallHitRate)
    Write-Host ""
    Write-Host "--- 队列 ---"
    foreach ($q in $m.queues) {
        Write-Host ("{0}: 容量={1} 发布={2} 消费={3} 积压={4} 峰值积压={5} 平均批量={6} 死信={7}" -f `
            $q.topic, $q.capacity, $q.publishedCount, $q.consumedCount, $q.backlog, `
            $q.peakBacklog, $q.avgBatchSize, $q.deadLetterCount)
    }
    Write-Host ""
    Write-Host "--- 端点延迟分位 ---"
    foreach ($e in $m.endpoints) {
        if ($e.latency.samples -gt 0) {
            Write-Host ("{0}: n={1} P50={2}ms P95={3}ms P99={4}ms 成功率={5}" -f `
                $e.endpoint, $e.totalCount, $e.latency.p50, $e.latency.p95, $e.latency.p99, $e.successRate)
        }
    }
    Write-Host ""
    Write-Host "--- 业务计数器 ---"
    $m.extra.business.PSObject.Properties | ForEach-Object { Write-Host ("{0} = {1}" -f $_.Name, $_.Value) }
}
finally {
    if (-not $KeepServerRunning) {
        Write-Host ""
        Write-Host "停止应用（PID=$($proc.Id)）..."
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host "应用保持运行（PID=$($proc.Id)），日志: $logFile"
    }
}
