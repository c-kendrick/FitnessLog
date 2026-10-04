$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$target = Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.jar'
$expected = (Invoke-RestMethod -Uri 'https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256').Trim().ToLowerInvariant()
if ($expected -notmatch '^[0-9a-f]{64}$') { throw 'Gradle returned an invalid wrapper checksum.' }
if (!(Test-Path $target) -or (Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    $temp = "$target.download"
    Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $temp -UseBasicParsing
    if ((Get-FileHash $temp -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        Remove-Item $temp -Force
        throw 'Wrapper checksum mismatch. No unverified JAR was installed.'
    }
    Move-Item $temp $target -Force
}
$distributionHash = (Invoke-RestMethod -Uri 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256').Trim().ToLowerInvariant()
if ($distributionHash -notmatch '^[0-9a-f]{64}$') { throw 'Gradle returned an invalid distribution checksum.' }
$properties = Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.properties'
$lines = Get-Content $properties | Where-Object { $_ -notmatch '^distributionSha256Sum=' }
$lines += "distributionSha256Sum=$distributionHash"
Set-Content -Path $properties -Value $lines -Encoding ascii
Write-Host 'Gradle wrapper verified. Open this FitnessLogSync folder in Android Studio.'
