# Builds Marble CamLink without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Output: build\marble-camlink.apk
# Each tool's exit code is checked below; 'Continue' stops Windows PowerShell from treating
# harmless stderr notes (javac deprecation notes, java -version) as fatal errors.
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt  = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$platform = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$androidJar = "$platform\android.jar"

# d8/apksigner need Java 11+
$jdk = @($env:JAVA_HOME) + (Get-ChildItem 'C:\Program Files\Microsoft','C:\Program Files\Eclipse Adoptium','C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName) |
  Where-Object { $_ -and (Test-Path "$_\bin\javac.exe") -and ((cmd /c "`"$_\bin\java.exe`" -version 2>&1" | Out-String) -notmatch 'version "1\.') } | Select-Object -First 1
if (-not $jdk) { throw 'Need a JDK 11 or newer (javac).' }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"
Write-Host "SDK $sdk | build-tools $(Split-Path $bt -Leaf) | $(Split-Path $platform -Leaf) | JDK $jdk"

Remove-Item build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build\classes, build\dex | Out-Null

& "$bt\aapt2.exe" link -o build\base.apk -I $androidJar --manifest AndroidManifest.xml `
    --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 1.0
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$src = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
$boot = "$androidJar;$bt\core-lambda-stubs.jar"   # lambda stubs: javac needs LambdaMetafactory
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath $boot -classpath $boot -d build\classes @src
if ($LASTEXITCODE) { throw 'javac failed' }

$classes = Get-ChildItem build\classes -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --lib $androidJar --min-api 26 --release --output build\dex @classes
if ($LASTEXITCODE) { throw 'd8 failed' }

Copy-Item build\dex\classes.dex build\classes.dex
Push-Location build
& "$bt\aapt.exe" add base.apk classes.dex | Out-Null
if ($LASTEXITCODE) { Pop-Location; throw 'aapt add failed' }
& "$bt\zipalign.exe" -f -p 4 base.apk aligned.apk
if ($LASTEXITCODE) { Pop-Location; throw 'zipalign failed' }
Pop-Location

$ks = Join-Path $PSScriptRoot 'camlink.keystore'
if (-not (Test-Path $ks)) {
  & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass camlink -keypass camlink -alias camlink `
      -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Marble CamLink' | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:camlink --key-pass pass:camlink --out build\marble-camlink.apk build\aligned.apk
if ($LASTEXITCODE) { throw 'apksigner failed' }
Write-Host "Built $(Resolve-Path build\marble-camlink.apk)"
