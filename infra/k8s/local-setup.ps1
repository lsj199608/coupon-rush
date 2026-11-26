# 로컬 Kubernetes 준비 (8단계): 네임스페이스 + Secret(postgres, 앱 4개) + ConfigMap postgres-init 을 만든다(이미 있으면 갱신). 여러 번 실행해도 된다.
# Secret 값을 바꾼 뒤에는 앱이 다시 읽도록 `kubectl rollout restart deploy -n coupon-rush` (Secret 은 기동 때만 환경변수로 들어감)
#   powershell -ExecutionPolicy Bypass -File infra/k8s/local-setup.ps1
#   그다음 kubectl apply -k infra/k8s/overlays/local
#
# 왜 매니페스트(YAML)가 아니라 스크립트인가
#   - Secret: 값이 infra/.env 에 있고 git 에 올리면 안 된다. .env 를 통째로 넣지 않고 서비스마다 필요한 키만 골라 Secret 을 나눈다
#     (compose 의 llm-service 가 필요한 값만 받는 것과 같은 원칙 — Postgres Pod 가 JWT·OAuth·LLM 키를 볼 이유가 없다)
#   - ConfigMap postgres-init: infra/postgres/ 파일을 compose 와 함께 쓴다. Kustomize 는 자기 폴더 밖 파일을 읽지 못해서
#     복사본을 두는 대신 여기서 원본으로 만든다
#   - Namespace: kustomization 에 넣으면 `kubectl delete -k` 가 네임스페이스와 PVC(데이터)까지 지운다
#
# ⚠ 이 파일은 BOM 있는 UTF-8 로 저장한다 (PowerShell 5.1 은 BOM 이 없으면 한글을 ANSI 로 읽어 깨진다 — 7단계 C3 메모)
$ErrorActionPreference = 'Stop'

$namespace = 'coupon-rush'
$infra = Split-Path -Parent $PSScriptRoot      # infra/
$envFile = Join-Path $infra '.env'
if (-not (Test-Path $envFile)) { throw "infra/.env 가 없습니다 (infra/.env.example 을 복사해 값을 채우세요)" }

# .env 읽기: KEY=VALUE 줄만, 주석·빈 줄 무시, 값 양끝 따옴표 제거
$values = @{}
foreach ($line in Get-Content -Encoding UTF8 $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
        $values[$Matches[1]] = $Matches[2].Trim('"').Trim("'")
    }
}

function Get-Required([string] $key) {
    if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key])) {
        throw "infra/.env 에 $key 를 설정하세요"
    }
    $values[$key]
}

# 없어도 앱이 뜨는 값 (비면 그 기능만 꺼짐 — README 환경변수 표). 비어 있으면 경고만
function Get-Optional([string] $key, [string] $whenEmpty) {
    if ($values.ContainsKey($key) -and -not [string]::IsNullOrWhiteSpace($values[$key])) { return $values[$key] }
    if ($whenEmpty) { Write-Warning "infra/.env 에 $key 가 없습니다 — $whenEmpty" }
    ''
}

# 고른 키만으로 Secret 을 만든다. 값은 명령줄 인자가 아니라 임시 파일로 넘긴다(프로세스 목록에 비밀값이 보이지 않게)
function Set-Secret([string] $name, [hashtable] $data) {
    $tmp = [IO.Path]::GetTempFileName()
    try {
        $lines = $data.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }
        [IO.File]::WriteAllLines($tmp, [string[]] $lines, (New-Object Text.UTF8Encoding($false)))
        kubectl create secret generic $name -n $namespace --from-env-file=$tmp --dry-run=client -o yaml | kubectl apply -f -
        if ($LASTEXITCODE -ne 0) { throw "Secret $name 생성 실패" }
    } finally {
        Remove-Item $tmp -Force
    }
}

# 1. 네임스페이스 (없을 때만)
#    --ignore-not-found: 없으면 오류 대신 빈 출력 (PowerShell 5.1 은 Stop 설정에서 네이티브 명령의 stderr 를 오류로 바꿔 스크립트가 멈춘다)
if (-not (kubectl get namespace $namespace --ignore-not-found -o name)) {
    kubectl create namespace $namespace
    if ($LASTEXITCODE -ne 0) { throw '네임스페이스 생성 실패' }
}

# 2. Secret postgres — Postgres Pod 와 초기화 스크립트(20-web-chat-db.sh)가 읽는 값만
Set-Secret 'postgres' @{
    POSTGRES_DB          = Get-Required 'POSTGRES_DB'
    POSTGRES_USER        = Get-Required 'POSTGRES_USER'
    POSTGRES_PASSWORD    = Get-Required 'POSTGRES_PASSWORD'
    WEB_CHAT_DB_USER     = $(if ($values['WEB_CHAT_DB_USER']) { $values['WEB_CHAT_DB_USER'] } else { 'web_chat' })
    WEB_CHAT_DB_PASSWORD = Get-Required 'WEB_CHAT_DB_PASSWORD'
}

# 3. 앱 Secret (8-3) — 앱마다 그 앱이 읽는 비밀값만. 비밀이 아닌 설정은 base/kustomization.yaml 의 configMapGenerator
$internalToken = Get-Optional 'INTERNAL_API_TOKEN' '챗봇의 "내 쿠폰" 조회(내부 API)가 꺼집니다'
$mcpToken      = Get-Optional 'MCP_SERVICE_TOKEN' '챗봇이 mcp-server 도구를 못 씁니다'

Set-Secret 'coupon-api' @{
    POSTGRES_DB          = Get-Required 'POSTGRES_DB'
    POSTGRES_USER        = Get-Required 'POSTGRES_USER'
    POSTGRES_PASSWORD    = Get-Required 'POSTGRES_PASSWORD'
    JWT_SECRET           = Get-Required 'JWT_SECRET'
    KAKAO_CLIENT_ID      = Get-Required 'KAKAO_CLIENT_ID'
    KAKAO_CLIENT_SECRET  = Get-Required 'KAKAO_CLIENT_SECRET'
    NAVER_CLIENT_ID      = Get-Required 'NAVER_CLIENT_ID'
    NAVER_CLIENT_SECRET  = Get-Required 'NAVER_CLIENT_SECRET'
    GOOGLE_CLIENT_ID     = Get-Required 'GOOGLE_CLIENT_ID'
    GOOGLE_CLIENT_SECRET = Get-Required 'GOOGLE_CLIENT_SECRET'
    INTERNAL_API_TOKEN   = $internalToken
}
Set-Secret 'llm-service' @{
    MCP_SERVICE_TOKEN = $mcpToken
    LLM_API_KEY       = Get-Optional 'LLM_API_KEY' ''   # 없으면 fake 제공자 (기본)
}
Set-Secret 'mcp-server' @{
    MCP_SERVICE_TOKEN  = $mcpToken
    INTERNAL_API_TOKEN = $internalToken
}
Set-Secret 'web-chat-service' @{
    WEB_CHAT_DB_USER     = $(if ($values['WEB_CHAT_DB_USER']) { $values['WEB_CHAT_DB_USER'] } else { 'web_chat' })
    WEB_CHAT_DB_PASSWORD = Get-Required 'WEB_CHAT_DB_PASSWORD'
}

# 4. ConfigMap postgres-init — infra/postgres/ 의 init.sql, 20-web-chat-db.sh 그대로 (빈 볼륨 첫 기동 때만 실행됨)
$initDir = Join-Path $infra 'postgres'   # 변수로: --from-file=(식) 은 PowerShell 이 인자 두 개로 나눈다
kubectl create configmap postgres-init -n $namespace "--from-file=$initDir" --dry-run=client -o yaml | kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw 'ConfigMap postgres-init 생성 실패' }

Write-Host "준비 완료 (네임스페이스 $namespace). 다음: kubectl apply -k infra/k8s/overlays/local"
