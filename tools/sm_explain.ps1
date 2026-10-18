# Короткая понятная причина ошибки Gradle из журнала (для build_apk.bat).
# Код выхода: 2 - проблема сети (стоит повторить), 1 - ошибка проекта/настроек.
$t = @(Get-Content -LiteralPath $env:SM_LOG -ErrorAction SilentlyContinue)
Write-Host ''
Write-Host '================ ПРИЧИНА ОШИБКИ ================' -ForegroundColor Red
$w = $t | Select-String -SimpleMatch '* What went wrong:' | Select-Object -First 1
if ($w) {
    $from = $w.LineNumber
    for ($i = $from; $i -lt $t.Count -and $i -lt $from + 25; $i++) {
        if ($t[$i] -like '* Try:*' -or $t[$i] -like '* Exception is:*') { break }
        Write-Host $t[$i]
    }
}
$where = $t | Select-String -SimpleMatch '* Where:' | Select-Object -First 1
if ($where -and $where.LineNumber -lt $t.Count) { Write-Host ('Где: ' + $t[$where.LineNumber].Trim()) }
$e = @($t | Where-Object { $_ -match '^e: ' } | Select-Object -First 12)
if ($e.Count -gt 0) {
    Write-Host '--- Ошибки компиляции (файл:строка:столбец):'
    $e | ForEach-Object { Write-Host $_ }
}
if (-not $w -and $e.Count -eq 0) {
    Write-Host 'Последние строки журнала:'
    $t | Select-Object -Last 25 | ForEach-Object { Write-Host $_ }
}
$memPatterns = 'daemon disappeared','hs_err_pid','OutOfMemoryError','Java heap space','Metaspace','Could not reserve enough space','insufficient memory','GC overhead limit'
$mem = $t | Select-String -SimpleMatch -Pattern $memPatterns | Select-Object -First 2
if ($mem) {
    Write-Host '================================================' -ForegroundColor Red
    Write-Host ('Полный журнал: ' + $env:SM_LOG)
    Write-Host 'Не хватило ПАМЯТИ компьютера (Gradle/Java аварийно закрылся).' -ForegroundColor Yellow
    Write-Host 'Соберу ещё раз в экономном режиме: один поток, меньше памяти. Закройте браузер и другие тяжёлые программы.'
    exit 3
}
$netPatterns = 'Could not GET','Could not HEAD','Could not download','Could not resolve','handshake','timed out','Connection reset','Connection refused','UnknownHost','No such host','Remote host terminated','No cached version','PKIX path','Network is unreachable'
$net = $t | Select-String -SimpleMatch -Pattern $netPatterns | Select-Object -First 3
Write-Host '================================================' -ForegroundColor Red
Write-Host ('Полный журнал: ' + $env:SM_LOG)
if ($net) {
    Write-Host 'Это проблема СЕТИ (не скачались файлы):' -ForegroundColor Yellow
    $net | ForEach-Object { Write-Host ('  ' + $_.Line.Trim()) }
    Write-Host 'Включите VPN или создайте proxy.txt (одна строка host:port) и запустите снова.'
    exit 2
}
Write-Host 'Это ошибка проекта или настроек - пришлите текст выше разработчику.' -ForegroundColor Yellow
exit 1
