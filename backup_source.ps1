# Source snapshot ritual (P0 #7, ROADMAP.md) — respects the owner's NO-GIT rule.
# Zips all source (no build junk) to backups\src_yyyy-MM-dd_HHmm.zip, keeps last 15.
# Run after every successful build:  powershell -ExecutionPolicy Bypass -File backup_source.ps1
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$backupDir = Join-Path $root "backups"
if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Force $backupDir | Out-Null }
$stamp = Get-Date -Format "yyyy-MM-dd_HHmm"
$dest = Join-Path $backupDir "src_$stamp.zip"
$staging = Join-Path $env:TEMP "mya3_snap_$stamp"
New-Item -ItemType Directory -Force $staging | Out-Null
# Copy source trees only — exclude build outputs, caches, backups itself
$exclude = @("build", ".gradle", ".kotlin", ".idea", "backups")
Get-ChildItem $root -Force | Where-Object {
    $_.Name -notin $exclude -and $_.Name -notlike "*.zip" -and $_.Name -ne "combined-cacerts.jks"
} | ForEach-Object {
    if ($_.PSIsContainer) {
        robocopy $_.FullName (Join-Path $staging $_.Name) /E /XD build .gradle .kotlin .idea /XF *.apk *.jks /NFL /NDL /NJH /NJS /NP | Out-Null
    } else {
        Copy-Item $_.FullName $staging
    }
}
Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $dest -Force
Remove-Item -Recurse -Force $staging
# Keep only the newest 15 snapshots
Get-ChildItem $backupDir -Filter "src_*.zip" | Sort-Object Name -Descending | Select-Object -Skip 15 | Remove-Item -Force
Write-Output "Snapshot: $dest ($([math]::Round((Get-Item $dest).Length/1MB,1)) MB)"
