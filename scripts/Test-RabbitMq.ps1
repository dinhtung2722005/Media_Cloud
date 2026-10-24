param(
    [string]$MavenRepository,
    [switch]$Jdk26Compatibility,
    [string]$RabbitMqImage = 'rabbitmq:4.3.6-management'
)

$ErrorActionPreference = 'Stop'
function Invoke-RabbitTestDocker {
    param([string[]]$DockerArguments)
    # Windows PowerShell treats Docker's progress on stderr as an error record.
    $ErrorActionPreference = 'Continue'
    $rabbitDockerOutput = & docker @DockerArguments 2>&1
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($rabbitDockerOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    }
}
$rabbitTestRoot = Split-Path -Parent $PSScriptRoot
$rabbitTestName = 'media-rabbitmq-it-' + [Guid]::NewGuid().ToString('N')
$rabbitTestVhost = 'media-integration-' + [Guid]::NewGuid().ToString('N')
$rabbitTestEnvFile = [IO.Path]::GetTempFileName()
$rabbitTestKeys = @('RABBITMQ_HOST', 'RABBITMQ_PORT', 'RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD', 'RABBITMQ_VHOST', 'RUN_RABBITMQ_IT')
$rabbitTestPrevious = @{}
foreach ($rabbitTestKey in $rabbitTestKeys) {
    $rabbitTestPrevious[$rabbitTestKey] = [Environment]::GetEnvironmentVariable($rabbitTestKey, 'Process')
}
$rabbitTestStarted = $false
$rabbitTestRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rabbitTestBytes = New-Object byte[] 32
    $rabbitTestRandom.GetBytes($rabbitTestBytes)
    $rabbitTestPassword = [Convert]::ToBase64String($rabbitTestBytes)
    [IO.File]::WriteAllLines($rabbitTestEnvFile, @(
        'RABBITMQ_DEFAULT_USER=media_test',
        ('RABBITMQ_DEFAULT_PASS=' + $rabbitTestPassword),
        ('RABBITMQ_DEFAULT_VHOST=' + $rabbitTestVhost)
    ), (New-Object Text.UTF8Encoding($false)))

    $rabbitTestDocker = Invoke-RabbitTestDocker @('info', '--format', '{{.ServerVersion}}')
    if ($rabbitTestDocker.ExitCode -ne 0) { throw "Docker daemon is not running: $($rabbitTestDocker.Output)" }
    Write-Host 'Starting an isolated RabbitMQ container...'
    $rabbitTestDocker = Invoke-RabbitTestDocker @('run', '--detach', '--name', $rabbitTestName,
        '--env-file', $rabbitTestEnvFile, '--publish', '127.0.0.1::5672', $RabbitMqImage)
    if ($rabbitTestDocker.ExitCode -ne 0) { throw "Cannot start the isolated RabbitMQ container: $($rabbitTestDocker.Output)" }
    $rabbitTestStarted = $true

    $rabbitTestReady = $false
    for ($rabbitTestAttempt = 0; $rabbitTestAttempt -lt 60; $rabbitTestAttempt++) {
        $rabbitTestDocker = Invoke-RabbitTestDocker @('exec', '--user', 'rabbitmq', $rabbitTestName,
            'rabbitmq-diagnostics', '-q', 'check_running')
        if ($rabbitTestDocker.ExitCode -eq 0) { $rabbitTestReady = $true; break }
        $rabbitTestState = Invoke-RabbitTestDocker @('inspect', '--format', '{{.State.Running}}', $rabbitTestName)
        if ($rabbitTestState.ExitCode -ne 0 -or $rabbitTestState.Output.Trim() -ne 'true') { break }
        Start-Sleep -Seconds 2
    }
    if (-not $rabbitTestReady) {
        $rabbitTestLogs = Invoke-RabbitTestDocker @('logs', '--tail', '40', $rabbitTestName)
        throw "RabbitMQ did not become ready: $($rabbitTestLogs.Output)"
    }
    Write-Host 'RabbitMQ is ready; running integration tests...'

    $rabbitTestDocker = Invoke-RabbitTestDocker @('port', $rabbitTestName, '5672/tcp')
    if ($rabbitTestDocker.ExitCode -ne 0) { throw 'Cannot determine the test AMQP port.' }
    $rabbitTestBinding = $rabbitTestDocker.Output
    $env:RABBITMQ_HOST = '127.0.0.1'
    $env:RABBITMQ_PORT = ($rabbitTestBinding.Trim() -split ':')[-1]
    $env:RABBITMQ_USERNAME = 'media_test'
    $env:RABBITMQ_PASSWORD = $rabbitTestPassword
    $env:RABBITMQ_VHOST = $rabbitTestVhost
    $env:RUN_RABBITMQ_IT = 'true'

    Push-Location -LiteralPath $rabbitTestRoot
    try {
        $rabbitTestArguments = @('clean', 'test', '-Dtest=RabbitMqIntegrationTests', '-Dmaven.compiler.useIncrementalCompilation=false')
        if ($MavenRepository) { $rabbitTestArguments += '-Dmaven.repo.local=' + $MavenRepository }
        if ($Jdk26Compatibility) {
            $rabbitTestArguments += '-DargLine=-Dnet.bytebuddy.experimental=true -XX:+EnableDynamicAgentLoading'
        }
        $rabbitTestMaven = Get-Command mvn -ErrorAction SilentlyContinue
        try {
            $ErrorActionPreference = 'Continue'
            if ($rabbitTestMaven) { $rabbitTestMavenOutput = & $rabbitTestMaven.Source @rabbitTestArguments 2>&1 }
            else { $rabbitTestMavenOutput = & (Join-Path $rabbitTestRoot 'mvnw.cmd') @rabbitTestArguments 2>&1 }
            $rabbitTestMavenExit = $LASTEXITCODE
            $rabbitTestMavenOutput | ForEach-Object { $_.ToString() }
        }
        finally { $ErrorActionPreference = 'Stop' }
        if ($rabbitTestMavenExit -ne 0) { throw 'RabbitMQ integration tests failed.' }
    }
    finally { Pop-Location }
}
finally {
    if ($rabbitTestStarted) { $null = Invoke-RabbitTestDocker @('rm', '--force', '--volumes', $rabbitTestName) }
    foreach ($rabbitTestKey in $rabbitTestKeys) {
        [Environment]::SetEnvironmentVariable($rabbitTestKey, $rabbitTestPrevious[$rabbitTestKey], 'Process')
    }
    $rabbitTestRandom.Dispose()
    Remove-Item -LiteralPath $rabbitTestEnvFile -ErrorAction SilentlyContinue
}
