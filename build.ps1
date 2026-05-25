$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDir = Join-Path $root "build\classes"
$jarPath = Join-Path $root "ExtraEditor.jar"
$stageDir = Join-Path $root "build\stage"
$dexOutDir = Join-Path $root "build\dex"
$sdkRoot = "D:\Android\Sdk"
$d8Path = Join-Path $sdkRoot "build-tools\35.0.0\d8.bat"
$androidJar = Join-Path $sdkRoot "platforms\android-35\android.jar"
$mindustryData = Join-Path $env:APPDATA "Mindustry\be_builds"
$clientJar = Get-ChildItem -Path $mindustryData -Filter "client-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if($null -eq $clientJar){
    throw "No Mindustry client jar found in $mindustryData"
}

if(Test-Path $buildDir){
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root "src") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
javac --release 8 -encoding UTF-8 -classpath $clientJar.FullName -d $buildDir $sources
if($LASTEXITCODE -ne 0){
    throw "javac failed with exit code $LASTEXITCODE"
}

# Android dex generation for Java mods on mobile.
if(-not (Test-Path $d8Path)){
    throw "d8 not found at $d8Path"
}
if(-not (Test-Path $androidJar)){
    throw "android.jar not found at $androidJar"
}
if(Test-Path $dexOutDir){
    Remove-Item -LiteralPath $dexOutDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $dexOutDir | Out-Null

# Use same classpath base as compilation; include android platform stubs for d8.
$allClassFiles = Get-ChildItem -Path $buildDir -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& $d8Path --release --min-api 21 --classpath $clientJar.FullName --lib $androidJar --output $dexOutDir $allClassFiles
if($LASTEXITCODE -ne 0){
    throw "d8 failed with exit code $LASTEXITCODE"
}
if(-not (Test-Path (Join-Path $dexOutDir "classes.dex"))){
    throw "classes.dex was not generated"
}

if(Test-Path (Join-Path $root "extraeditor")){
    Remove-Item -LiteralPath (Join-Path $root "extraeditor") -Recurse -Force
}

if(Test-Path $jarPath){
    Remove-Item -LiteralPath $jarPath -Force
}
if(Test-Path $stageDir){
    Remove-Item -LiteralPath $stageDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stageDir | Out-Null
Push-Location $root
try{
    # include mod.json and optional icon.png at root, then compiled classes and sprites if present
    $args = @("cf", $jarPath, "mod.json")
    if(Test-Path (Join-Path $root "icon.png")){
        $args += "icon.png"
    }
    $args += "-C"; $args += $buildDir; $args += "extraeditor"
    $args += "-C"; $args += $dexOutDir; $args += "classes.dex"
    if(Test-Path (Join-Path $root "sprites")){
        $args += "sprites"
    }
    & jar @args
}finally{
    Pop-Location
}

Write-Host "Built $jarPath"
