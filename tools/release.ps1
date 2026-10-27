param(
    [string] $PythonCommand = 'python'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$version = ([xml](Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw)).project.version
$releaseDirectory = Join-Path $projectRoot ('dist/ofdh-' + $version)
if (Test-Path -LiteralPath $releaseDirectory) {
    throw '本版本的发行目录已存在；请先核对现有制品，修订候选应使用发布说明中的单步流程。'
}
Push-Location -LiteralPath $projectRoot
try {
    & mvn -B -ntp clean install
    if ($LASTEXITCODE -ne 0) { throw 'Maven 构建验证失败' }
    & mvn -B -ntp -f examples/java-sdk/pom.xml clean verify
    if ($LASTEXITCODE -ne 0) { throw '独立 SDK 接入验证失败' }
    & $PythonCommand tools/validate-design.py
    if ($LASTEXITCODE -ne 0) { throw '契约校验失败，请先安装 tools/requirements-validation.txt 中的依赖' }
    & $PythonCommand -m unittest discover -s tools/tests -p 'test_*.py'
    if ($LASTEXITCODE -ne 0) { throw '发行工具测试失败' }
    & $PythonCommand tools/check-publication.py
    if ($LASTEXITCODE -ne 0) { throw '公开文件检查失败' }
    & (Join-Path $PSScriptRoot 'audit-dependencies.ps1')
    if ($LASTEXITCODE -ne 0) { throw '依赖审计失败' }
    & $PythonCommand tools/collect-release.py
    if ($LASTEXITCODE -ne 0) { throw '发行制品收集失败' }
} finally {
    Pop-Location
}
