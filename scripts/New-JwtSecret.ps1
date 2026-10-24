$jwtSecretBytes = New-Object byte[] 32
$jwtSecretGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $jwtSecretGenerator.GetBytes($jwtSecretBytes)
    [Convert]::ToBase64String($jwtSecretBytes)
}
finally {
    $jwtSecretGenerator.Dispose()
}
