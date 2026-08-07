# AI Stock App — Auto Maintainer
# Runs on schedule: checks for changes, commits, pushes, emails owner

$GH = "C:/Users/shant/Downloads/gh_portable/bin/gh.exe"
$PROJECT = "C:/Users/shant/AndroidStudioProjects/MyApplication3"
$EMAIL_TO = "shantiniranjan1@gmail.com"
$LOG = "$PROJECT/backups/maintainer_log.txt"

function Write-Log($msg) {
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "$ts | $msg" | Tee-Object -Append -FilePath $LOG
}

Write-Log "=== Maintainer started ==="

Set-Location $PROJECT

# Check git status
$status = git status --porcelain 2>&1
$hasChanges = $status -ne $null -and $status.Trim().Length -gt 0

if ($hasChanges) {
    Write-Log "Changes detected: $($status.Split("`n").Count) files"
    
    # Stage all changes
    git add . 2>&1 | ForEach-Object { Write-Log "git add: $_" }
    
    # Commit with timestamp
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm"
    git commit -m "Auto-maintainer: $ts — changes detected and committed" 2>&1 | ForEach-Object { Write-Log "git commit: $_" }
    
    # Push
    & $GH auth setup-git 2>&1 | Out-Null
    git push origin main 2>&1 | ForEach-Object { Write-Log "git push: $_" }
    
    Write-Log "Push complete — sending email"
    
    # Send email via Gmail SMTP
    try {
        $smtpServer = "smtp.gmail.com"
        $smtpPort = 587
        $cred = Get-Credential -Message "Enter Gmail credentials for sending maintainer email" -UserName $EMAIL_TO
        
        $body = @"
AI Stock App — Auto Maintainer Report
======================================
Time: $ts
Changes found and pushed to GitHub.

Files changed:
$status

GitHub Repo: https://github.com/Niranjan2023kumar/ai-stock-app

-- Auto Maintainer
"@
        Send-MailMessage -To $EMAIL_TO -From $EMAIL_TO -Subject "AI Stock App: Changes pushed to GitHub" `
            -Body $body -SmtpServer $smtpServer -Port $smtpPort -UseSsl -Credential $cred
        Write-Log "Email sent to $EMAIL_TO"
    } catch {
        Write-Log "Email failed: $_"
    }
} else {
    Write-Log "No changes — nothing to commit"
}

Write-Log "=== Maintainer done ==="
