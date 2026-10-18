# Запуск Gradle с выводом на экран и в журнал (для build_apk.bat).
# Аргументы Gradle - в переменной SM_GARGS, путь к журналу - в SM_LOG.
$ErrorActionPreference = 'Continue'
$a = @($env:SM_GARGS -split ' ' | Where-Object { $_ -ne '' })
& .\gradlew.bat @a 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $env:SM_LOG
exit $LASTEXITCODE
