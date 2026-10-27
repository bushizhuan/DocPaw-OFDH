param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$bomPath = Join-Path $projectRoot 'target\bom.json'
if (-not (Test-Path -LiteralPath $bomPath -PathType Leaf)) {
    throw '请先运行 mvn verify 生成依赖清单。'
}
$bom = Get-Content -LiteralPath $bomPath -Raw | ConvertFrom-Json
$queries = @($bom.components | Where-Object { $_.group -ne 'org.openofdh' } | ForEach-Object {
    @{ package = @{ name = ($_.group + ':' + $_.name); ecosystem = 'Maven' }; version = $_.version }
})
$response = Invoke-RestMethod -Method Post -Uri 'https://api.osv.dev/v1/querybatch' `
    -ContentType 'application/json' -Body (@{ queries = $queries } | ConvertTo-Json -Depth 5)
if ($response.results.Count -ne $queries.Count) { throw '漏洞公告查询响应不完整' }
$findings = @()
for ($index = 0; $index -lt $queries.Count; $index++) {
    if ($response.results[$index].vulns) {
        $findings += @{
            component = $queries[$index].package.name
            version = $queries[$index].version
            advisories = @($response.results[$index].vulns | ForEach-Object { $_.id })
        }
    }
}
$result = @{
    queriedAt = (Get-Date).ToUniversalTime().ToString('o')
    source = 'https://api.osv.dev/v1/querybatch'
    componentCount = $queries.Count
    components = $queries
    findings = $findings
}
$json = $result | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $projectRoot 'target\dependency-audit.json'), $json, [Text.UTF8Encoding]::new($false))
Write-Output $json
if ($findings.Count -gt 0) { exit 2 }
