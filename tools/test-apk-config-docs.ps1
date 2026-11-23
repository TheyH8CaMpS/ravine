$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$workflow = Get-Content -Raw (Join-Path $root '.github/workflows/build.yml')
$gradle = Get-Content -Raw (Join-Path $root 'source/app/build.gradle.kts')
$guide = Get-Content -Raw (Join-Path $root 'source/app/src/main/java/dev/veil/launcher/data/CourtsideGuide.kt')
$comment = [regex]::Match($guide, '(?s)/\*\*.*?\*/').Value

# Check names only. No local properties, APK, or credentials are needed.
$releaseFields = foreach ($field in [regex]::Matches($gradle, 'buildConfigField\("String", "(\w+)"')) {
    $name = $field.Groups[1].Value
    if ($workflow -cmatch "\b(?:secrets|vars)\.$name\b") { $name }
}
if (!$releaseFields) { throw 'No release BuildConfig fields found to check.' }
foreach ($name in $releaseFields) {
    if ($comment -cnotmatch "\b$name\b") { throw "The APK configuration comment omits $name." }
}
