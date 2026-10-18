@echo off
rem ============================================================
rem  StoykoMessenger (S-messenger) - сборка APK одной кнопкой
rem  Положите в корень проекта (рядом с gradlew.bat) и запустите.
rem  Сам находит Android Studio, её JDK и Android SDK (нет SDK - ставит сам),
rem  собирает ОДИН release-APK со всеми архитектурами (armeabi-v7a, arm64-v8a,
rem  x86, x86_64) - защищённый R8, подписанный вашим ключом -
rem  и кладёт в папку APK только S-messenger-universal-release.apk.
rem ============================================================
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
cd /d "%~dp0"
title S-messenger: сборка APK

rem Собирается только release (debug и отдельные APK под архитектуры больше не нужны).
set "MODE=release"

if not exist "gradlew.bat" (
    echo [ОШИБКА] gradlew.bat не найден. Положите батник в корень проекта StoykoMessenger.
    goto :fail
)

if not exist "app\libs\libv2ray.aar" (
    echo [ОШИБКА] Нет файла app\libs\libv2ray.aar - ядро встроенного VPN.
    echo Положите присланный файл libv2ray.aar в папку app\libs и запустите снова.
    goto :fail
)

if not exist "app\libs\nova-core-gn.aar" (
    echo [ОШИБКА] Нет файла app\libs\nova-core-gn.aar - ядро WARP/MASQUE ^(Nova^).
    echo Распакуйте вторую часть архива ^(part2^) в папку проекта и запустите снова.
    goto :fail
)

rem Убрать файлы, удалённые из проекта: распаковка архива поверх папки старые файлы не стирает.
if exist "tools\obsolete_files.txt" (
    for /f "usebackq eol=# tokens=* delims=" %%F in ("tools\obsolete_files.txt") do (
        set "OBS=%%F"
        set "OBS=!OBS:/=\!"
        if exist "!OBS!" (
            del /f /q "!OBS!" >nul 2>&1
            echo Удалён устаревший файл: !OBS!
        )
    )
)

echo.
echo === 1/6 Поиск Android Studio ===
set "AS_DIR="
call :from_reg "HKLM\SOFTWARE\Android Studio" Path AS_DIR
if not defined AS_DIR call :from_reg "HKCU\SOFTWARE\Android Studio" Path AS_DIR
if not defined AS_DIR call :from_reg "HKLM\SOFTWARE\WOW6432Node\Android Studio" Path AS_DIR
if defined AS_DIR if not exist "!AS_DIR!\bin\studio64.exe" set "AS_DIR="

if not defined AS_DIR call :try_as "%ProgramFiles%\Android\Android Studio"
if not defined AS_DIR call :try_as "%LOCALAPPDATA%\Programs\Android Studio"
if not defined AS_DIR call :try_as "%ProgramFiles(x86)%\Android\Android Studio"
if not defined AS_DIR (
    for /d %%D in ("%LOCALAPPDATA%\JetBrains\Toolbox\apps\AndroidStudio\ch-0\*") do call :try_as "%%~D"
)
if not defined AS_DIR (
    for /d %%D in ("%LOCALAPPDATA%\Programs\Android Studio*") do call :try_as "%%~D"
)
if not defined AS_DIR (
    for %%L in (C D E F G) do (
        if not defined AS_DIR call :try_as "%%L:\Android\Android Studio"
        if not defined AS_DIR call :try_as "%%L:\Program Files\Android\Android Studio"
        if not defined AS_DIR call :try_as "%%L:\Android Studio"
    )
)

if defined AS_DIR (
    echo Найдена: !AS_DIR!
) else (
    echo Android Studio не найдена - попробую JDK из JAVA_HOME.
)

echo.
echo === 2/6 Поиск JDK ===
set "JDK="
if defined AS_DIR (
    if exist "!AS_DIR!\jbr\bin\java.exe" set "JDK=!AS_DIR!\jbr"
    if not defined JDK if exist "!AS_DIR!\jre\bin\java.exe" set "JDK=!AS_DIR!\jre"
)
if not defined JDK if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JDK=%JAVA_HOME%"
if not defined JDK (
    echo [ОШИБКА] JDK не найден. Установите Android Studio: https://developer.android.com/studio
    goto :fail
)
set "JAVA_HOME=%JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo JAVA_HOME = %JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /i "version"

echo.
echo === 3/6 Поиск Android SDK (если его нет - скачаю и установлю сам) ===
rem Поиск и установка SDK делаются PowerShell-частью в конце этого файла
rem (после строки-метки). Она ищет SDK в переменных среды, в настройках
rem Android Studio (other.xml), в реестре и на всех дисках. Если не нашла -
rem скачивает Android command-line tools с dl.google.com и ставит
rem platform-tools, platforms;android-35, build-tools;35.0.0, принимает лицензии.
set "SM_OUT=%TEMP%\smsdk_%RANDOM%%RANDOM%.txt"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$c=[IO.File]::ReadAllText('%~f0',[Text.Encoding]::UTF8); $i=$c.LastIndexOf('#PS'+'_BEGIN'); Invoke-Expression $c.Substring($i)"
if errorlevel 1 (
    echo.
    echo [ОШИБКА] Подготовка Android SDK не удалась - причина написана выше.
    echo Уже скачанное сохраняется: просто запустите батник ещё раз.
    goto :fail
)
set "SDK="
set "GHOME="
set "PLAT="
set "BTV="
set /a _n=0
for /f "usebackq delims=" %%L in ("%SM_OUT%") do (
    set /a _n+=1
    if !_n!==1 set "SDK=%%L"
    if !_n!==2 set "GHOME=%%L"
    if !_n!==3 set "PLAT=%%L"
    if !_n!==4 set "BTV=%%L"
)
del "%SM_OUT%" >nul 2>&1
if not defined SDK (
    echo [ОШИБКА] PowerShell не вернул путь к SDK.
    goto :fail
)
if "%GHOME%"=="-" set "GHOME="
if "%BTV%"=="-" set "BTV="
set "SDKPROPS="
if defined PLAT set "SDKPROPS=-Psm.compileSdk=%PLAT%"
if defined BTV set "SDKPROPS=%SDKPROPS% -Psm.buildTools=%BTV%"
if defined PLAT echo compileSdk = %PLAT%
if defined BTV echo build-tools = %BTV%
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"
echo ANDROID_HOME = %SDK%
if defined GHOME (
    set "GRADLE_USER_HOME=%GHOME%"
    echo GRADLE_USER_HOME = %GHOME%  ^(в пути профиля есть кириллица/пробелы - Gradle вынесен сюда^)
)

rem local.properties - Gradle берёт путь к SDK отсюда (прямые слэши, без экранирования)
set "SDK_FWD=%SDK:\=/%"
> local.properties echo sdk.dir=%SDK_FWD%

echo.
set "GFLAGS=--no-daemon"
rem Необязательный proxy.txt в корне проекта: одна строка host:port (HTTP-прокси,
rem например локальный VPN-клиент 127.0.0.1:10809). Через него пойдут все загрузки Gradle.
if exist "proxy.txt" (
    for /f "usebackq tokens=1,2 delims=: " %%a in ("proxy.txt") do (
        set "GFLAGS=--no-daemon -Dhttps.proxyHost=%%a -Dhttps.proxyPort=%%b -Dhttp.proxyHost=%%a -Dhttp.proxyPort=%%b"
        echo Прокси из proxy.txt: %%a:%%b
    )
)

echo === 4/6 Загрузка Gradle и зависимостей ===
set "SM_LOG=%TEMP%\smbuild_%RANDOM%%RANDOM%.log"
rem Сам Gradle (один раз, ~150 МБ) - до 3 попыток.
set /a GTRY=1
:gradle_dist_try
call gradlew.bat %GFLAGS% --version >nul 2>&1
if not errorlevel 1 goto :gradle_dist_ok
set /a GTRY+=1
if %GTRY% LEQ 3 (
    echo [*] Не удалось скачать Gradle - повтор %GTRY% из 3...
    timeout /t 5 /nobreak >nul
    goto :gradle_dist_try
)
echo [ОШИБКА] Не удаётся скачать Gradle с services.gradle.org. Нужен VPN или proxy.txt.
call gradlew.bat %GFLAGS% --version
goto :fail
:gradle_dist_ok

rem Зависимости: до 3 попыток, но повторяем ТОЛЬКО при сетевой ошибке.
rem Ошибка в проекте - сразу показываем причину и останавливаемся.
set /a TRY=1
:fetch_try
echo.
echo --- Загрузка зависимостей: попытка %TRY% из 3
set "SM_GARGS=%GFLAGS% smPrefetch %SDKPROPS%"
call :gradle_run
if "!RUN_OK!"=="1" goto :fetch_ok
call :explain
if "!NET_ERR!"=="0" goto :fail
set /a TRY+=1
if %TRY% LEQ 3 (
    echo [*] Повтор через 5 секунд...
    timeout /t 5 /nobreak >nul
    goto :fetch_try
)
echo [ОШИБКА] За 3 попытки зависимости не скачались - см. причину выше.
goto :fail
:fetch_ok

echo.
echo === 5/6 Сборка APK (release) ===
rem Удаляем старые промежуточные файлы прошлых (в том числе неудачных) сборок:
rem испорченный кэш KSP/Room ломал сборку с "Expected start of the object ... EOF".
for %%D in ("build" "app\build" "core\build" "domain\build" ".gradle\configuration-cache") do if exist "%%~D" rmdir /s /q "%%~D" 2>nul
if exist "core\schemas" for /r "core\schemas" %%F in (*.json) do if %%~zF LSS 2 del /q "%%F" 2>nul
set "BFLAGS=--no-build-cache -Pksp.incremental=false"

rem Всё уже скачано - собираем без сети; если не хватило файла - ещё раз с сетью.
set "SM_GARGS=%GFLAGS% --offline %BFLAGS% :app:assembleRelease %SDKPROPS%"
call :gradle_run
if "!RUN_OK!"=="1" goto :gradle_ok
call :explain
if "!MEM_ERR!"=="1" goto :lowmem
if "!NET_ERR!"=="0" goto :fail
echo.
echo [*] Не хватило файлов - собираю ещё раз с сетью...
set "SM_GARGS=%GFLAGS% %BFLAGS% :app:assembleRelease %SDKPROPS%"
call :gradle_run
if "!RUN_OK!"=="1" goto :gradle_ok
call :explain
if "!MEM_ERR!"=="1" goto :lowmem
goto :fail

rem Не хватило памяти компьютера: один процесс Java (Kotlin внутри Gradle), один поток,
rem 3 ГБ. Уже собранное не удаляем - сборка продолжится с места падения.
:lowmem
echo.
echo [*] Экономный режим сборки (мало памяти)...
copy /y "gradle.properties" "gradle.properties.bak" >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p='gradle.properties'; $t=Get-Content -LiteralPath $p | Where-Object { $_ -notmatch '^(org\.gradle\.jvmargs|kotlin\.daemon\.jvmargs|org\.gradle\.parallel|kotlin\.compiler\.execution\.strategy|org\.gradle\.workers\.max)=' }; $t += 'org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -XX:+UseSerialGC -Dfile.encoding=UTF-8'; $t += 'kotlin.compiler.execution.strategy=in-process'; $t += 'org.gradle.parallel=false'; $t += 'org.gradle.workers.max=1'; Set-Content -LiteralPath $p -Value $t -Encoding ASCII"
set "SM_GARGS=%GFLAGS% %BFLAGS% --max-workers=1 :app:assembleRelease %SDKPROPS%"
call :gradle_run
copy /y "gradle.properties.bak" "gradle.properties" >nul
del /q "gradle.properties.bak" 2>nul
if "!RUN_OK!"=="1" goto :gradle_ok
call :explain
echo.
echo [!] Даже в экономном режиме не хватило памяти. Пришлите файл hs_err_pid*.log из папки проекта
echo     и увеличьте файл подкачки Windows (Система - Дополнительно - Быстродействие - Виртуальная память).
goto :fail

rem ------------------------------------------------------------
rem :gradle_run - Gradle с аргументами из SM_GARGS, вывод на экран и в журнал SM_LOG.
:gradle_run
set "RUN_OK=0"
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\sm_gradle.ps1"
if not errorlevel 1 set "RUN_OK=1"
exit /b 0

rem :explain - понятная причина ошибки из журнала; NET_ERR=1, если это сеть.
:explain
set "NET_ERR=0"
set "MEM_ERR=0"
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\sm_explain.ps1"
if errorlevel 3 (
    set "MEM_ERR=1"
) else (
    if errorlevel 2 set "NET_ERR=1"
)
exit /b 0


:gradle_ok
echo.
echo === 6/6 Копирование в папку APK ===
if exist "APK" rmdir /s /q "APK"
mkdir "APK"
set "SRC="
for %%F in ("app\build\outputs\apk\release\*universal*.apk" "app\build\outputs\apk\release\app-release.apk" "app\build\outputs\apk\release\*.apk") do if not defined SRC if exist "%%~F" set "SRC=%%~F"
if not defined SRC (
    echo [ОШИБКА] Готовый APK не найден в app\build\outputs\apk\release
    goto :fail
)
copy /y "!SRC!" "APK\S-messenger-universal-release.apk" >nul
if errorlevel 1 (
    echo [ОШИБКА] Не удалось скопировать APK.
    goto :fail
)
echo   APK\S-messenger-universal-release.apk

rem Проверка, что в APK есть библиотеки всех архитектур
where tar >nul 2>&1
if not errorlevel 1 (
    echo.
    echo Архитектуры в APK:
    for %%A in (armeabi-v7a arm64-v8a x86 x86_64) do (
        tar -tf "APK\S-messenger-universal-release.apk" 2>nul | findstr /b /c:"lib/%%A/" >nul && (echo   [OK] %%A) || (echo   [НЕТ] %%A)
    )
)

echo.
echo ============================================================
echo  Готово: APK\S-messenger-universal-release.apk
echo  Один файл на любой телефон. Его и update.json загружайте в релиз GitHub.
echo ============================================================
start "" explorer "%~dp0APK"
echo.
pause
exit /b 0

rem ---------------- подпрограммы ----------------
:try_as
if defined AS_DIR exit /b 0
if exist "%~1\bin\studio64.exe" set "AS_DIR=%~1"
exit /b 0

:from_reg
rem %1 = ключ, %2 = имя значения, %3 = переменная для результата
for /f "skip=2 tokens=2,*" %%A in ('reg query "%~1" /v %~2 2^>nul') do set "%~3=%%B"
exit /b 0

:fail
echo.
pause
exit /b 1

rem ============================================================
rem  Ниже - PowerShell-часть (поиск/установка Android SDK).
rem  cmd сюда не доходит: выше стоит exit /b.
rem ============================================================
#PS_BEGIN
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$out = $env:SM_OUT
$repo = 'https://dl.google.com/android/repository'
$platform = 'platforms;android-35'
$buildTools = 'build-tools;36.0.0'

function Test-Sdk([string]$p) {
    if ([string]::IsNullOrWhiteSpace($p)) { return $false }
    $p = $p.Trim().Trim('"')
    if (-not (Test-Path -LiteralPath $p -PathType Container)) { return $false }
    foreach ($d in 'platform-tools', 'platforms', 'build-tools', 'cmdline-tools') {
        if (Test-Path -LiteralPath (Join-Path $p $d)) { return $true }
    }
    return $false
}

function Test-Plain([string]$s) { return ($s -notmatch '[^\x21-\x7E\\]') -and -not $s.Contains(' ') }

function Find-SdkManager([string]$sdk) {
    $root = Join-Path $sdk 'cmdline-tools'
    if (-not (Test-Path -LiteralPath $root)) { return $null }
    $latest = Join-Path $root 'latest\bin\sdkmanager.bat'
    if (Test-Path -LiteralPath $latest) { return $latest }
    $f = Get-ChildItem -LiteralPath $root -Recurse -Filter 'sdkmanager.bat' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($f) { return $f.FullName }
    return $null
}

function Get-File([string]$url, [string]$dest) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            & curl.exe -fL --retry 10 --retry-all-errors --retry-delay 5 -C - --connect-timeout 30 -o $dest $url
            if ($LASTEXITCODE -ne 0) { throw "curl exit code $LASTEXITCODE" }
        } else {
            Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
        }
    } finally { $ErrorActionPreference = $old }
}

function Install-CmdlineTools([string]$sdk) {
    $builds = @()
    try {
        $xml = (Invoke-WebRequest -Uri "$repo/repository2-3.xml" -UseBasicParsing -TimeoutSec 60).Content
        $builds += [regex]::Matches($xml, 'commandlinetools-win-(\d+)_latest\.zip') |
            ForEach-Object { [int64]$_.Groups[1].Value } | Sort-Object -Descending -Unique
    } catch { Write-Host "  (список версий недоступен, беру известные)" }
    $builds += 14742923, 13114758, 11076708
    $builds = $builds | Select-Object -Unique

    $ctRoot = Join-Path $sdk 'cmdline-tools'
    New-Item -ItemType Directory -Force -Path $ctRoot | Out-Null
    $zip = Join-Path $ctRoot 'download.zip'
    $ok = $false
    foreach ($b in $builds) {
        $url = "$repo/commandlinetools-win-${b}_latest.zip"
        Write-Host "Скачиваю $url"
        try { Get-File $url $zip; $ok = $true; break } catch { Write-Host "  не получилось: $_" }
    }
    if (-not $ok) { throw 'Не удалось скачать Android command-line tools: dl.google.com недоступен из вашей сети.' }

    Write-Host 'Распаковываю...'
    $tmp = Join-Path $ctRoot '_unpack'
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force }
    Expand-Archive -LiteralPath $zip -DestinationPath $tmp -Force
    $dest = Join-Path $ctRoot 'latest'
    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
    Move-Item -LiteralPath (Join-Path $tmp 'cmdline-tools') -Destination $dest
    Remove-Item -LiteralPath $tmp -Recurse -Force
    Remove-Item -LiteralPath $zip -Force
}

function Invoke-SdkManager([string]$sdkm, [string]$sdk, [string[]]$pkgs, [switch]$LicensesOnly) {
    # sdkmanager.bat - это cmd-скрипт: cmd режет аргументы по ';', поэтому
    # "platforms;android-35" обязательно передавать в кавычках через cmd /c.
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $yes = (1..80 | ForEach-Object { 'y' }) -join "`r`n"
        if ($LicensesOnly) {
            Write-Host 'Принимаю лицензии Android SDK...'
            $line = '"' + $sdkm + '" "--sdk_root=' + $sdk + '" --licenses'
            $yes | & cmd.exe /d /c "`"$line`"" | Out-Null
            return $true
        }
        $q = ($pkgs | ForEach-Object { '"' + $_ + '"' }) -join ' '
        $line = '"' + $sdkm + '" "--sdk_root=' + $sdk + '" ' + $q
        for ($try = 1; $try -le 3; $try++) {
            Write-Host ("sdkmanager ($try/3): " + ($pkgs -join ', '))
            $yes | & cmd.exe /d /c "`"$line`""
            if ($LASTEXITCODE -eq 0) { return $true }
            Write-Host "  не получилось (код $LASTEXITCODE)"
            Start-Sleep -Seconds 5
        }
        return $false
    } finally { $ErrorActionPreference = $old }
}

function Expand-Zip([string]$zip, [string]$dest) {
    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $tar = Join-Path $env:SystemRoot 'System32\tar.exe'
    if (Test-Path -LiteralPath $tar) {
        & $tar -xf $zip -C $dest
        if ($LASTEXITCODE -eq 0) { return }
    }
    Expand-Archive -LiteralPath $zip -DestinationPath $dest -Force
}

# Скачивает платформу Android напрямую ZIP-архивом с докачкой после обрывов
# (без sdkmanager) и кладёт в <SDK>\platforms\android-<api>.
function Install-Platform([string]$sdk, [int]$api) {
    $dir = Join-Path $sdk 'platforms'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    foreach ($rev in '02', '03', '01') {
        $url = "$repo/platform-${api}_r$rev.zip"
        $zip = Join-Path $dir "platform-${api}_r$rev.zip"
        Write-Host "Скачиваю Android API $api : $url"
        try {
            Get-File $url $zip
        } catch {
            Write-Host "  не получилось: $_"
            if ((Test-Path -LiteralPath $zip) -and (Get-Item -LiteralPath $zip).Length -lt 1MB) {
                Remove-Item -LiteralPath $zip -Force
            }
            continue
        }
        Write-Host 'Распаковываю...'
        $tmp = Join-Path $dir "_unpack$api"
        try {
            Expand-Zip $zip $tmp
        } catch {
            Write-Host "  архив повреждён ($_), качаю заново"
            Remove-Item -LiteralPath $zip -Force
            continue
        }
        $top = Get-ChildItem -LiteralPath $tmp -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'android.jar') } |
            Select-Object -First 1
        if (-not $top) {
            Remove-Item -LiteralPath $tmp -Recurse -Force
            Write-Host '  в архиве нет android.jar'
            continue
        }
        $dest = Join-Path $dir "android-$api"
        if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
        Move-Item -LiteralPath $top.FullName -Destination $dest
        Remove-Item -LiteralPath $tmp -Recurse -Force
        Remove-Item -LiteralPath $zip -Force
        Write-Host "Установлено: $dest"
        return $true
    }
    return $false
}

try {
    # ---- 1. Кандидаты на путь к SDK ----
    $cand = New-Object System.Collections.Generic.List[string]
    $cand.Add($env:ANDROID_HOME)
    $cand.Add($env:ANDROID_SDK_ROOT)

    # Путь, который сохранила сама Android Studio (любая версия)
    foreach ($r in @("$env:APPDATA\Google", "$env:LOCALAPPDATA\Google")) {
        if (-not (Test-Path -LiteralPath $r)) { continue }
        Get-ChildItem -LiteralPath $r -Directory -Filter 'AndroidStudio*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object {
                $f = Join-Path $_.FullName 'options\other.xml'
                if (Test-Path -LiteralPath $f) {
                    $m = [regex]::Match((Get-Content -LiteralPath $f -Raw), 'android\.sdk\.path"\s+value="([^"]+)"')
                    if ($m.Success) {
                        $p = $m.Groups[1].Value.Replace('$USER_HOME$', $env:USERPROFILE).Replace('/', '\')
                        $cand.Add($p)
                    }
                }
            }
    }

    foreach ($k in 'HKLM:\SOFTWARE\Android SDK Tools', 'HKLM:\SOFTWARE\WOW6432Node\Android SDK Tools', 'HKCU:\SOFTWARE\Android SDK Tools') {
        try { $cand.Add((Get-ItemProperty -LiteralPath $k -ErrorAction Stop).Path) } catch {}
    }

    $cand.Add("$env:LOCALAPPDATA\Android\Sdk")
    $cand.Add("$env:USERPROFILE\AppData\Local\Android\Sdk")
    $cand.Add("$env:ProgramFiles\Android\Sdk")
    $cand.Add("${env:ProgramFiles(x86)}\Android\android-sdk")
    Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue | ForEach-Object {
        foreach ($sub in 'Android\Sdk', 'Android\android-sdk', 'Android\sdk', 'AndroidSDK', 'Android-SDK', 'SDK\Android') {
            $cand.Add((Join-Path $_.Root $sub))
        }
    }

    $sdk = $null
    foreach ($c in $cand) {
        if (Test-Sdk $c) { $sdk = [IO.Path]::GetFullPath($c.Trim().Trim('"')); break }
    }

    # ---- 2. Нет SDK - ставим ----
    if ($sdk) {
        Write-Host "Найден SDK: $sdk"
    } else {
        $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        if (-not (Test-Plain $sdk)) { $sdk = Join-Path $env:SystemDrive 'Android\Sdk' }
        Write-Host "Android SDK не найден. Устанавливаю в $sdk"
        Write-Host '(нужен интернет, ~600 МБ, это делается один раз; Android Studio потом тоже будет его использовать)'
        New-Item -ItemType Directory -Force -Path $sdk | Out-Null
    }

    # ---- 3. Платформа и build-tools: сначала берём то, что уже установлено ----
    function Get-Platforms {
        $d = Join-Path $sdk 'platforms'
        if (-not (Test-Path -LiteralPath $d)) { return @() }
        Get-ChildItem -LiteralPath $d -Directory | ForEach-Object {
            $m = [regex]::Match($_.Name, '^android-(\d+)$')
            if ($m.Success -and (Test-Path -LiteralPath (Join-Path $_.FullName 'android.jar'))) { [int]$m.Groups[1].Value }
        } | Sort-Object -Descending
    }
    function Get-BuildTools {
        $d = Join-Path $sdk 'build-tools'
        if (-not (Test-Path -LiteralPath $d)) { return @() }
        Get-ChildItem -LiteralPath $d -Directory | Where-Object {
            $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt2.exe'))
        } | ForEach-Object { [version]$_.Name } | Sort-Object -Descending
    }

    $plat = Get-Platforms | Where-Object { $_ -ge 35 } | Select-Object -First 1
    $bt = Get-BuildTools | Where-Object { $_.Major -ge 35 } | Select-Object -First 1
    if ($plat) { Write-Host "Платформа уже есть: android-$plat" }
    if ($bt) { Write-Host "Build-tools уже есть: $bt" }

    # Платформа: качаем ZIP напрямую (докачка при обрывах)
    if (-not $plat) {
        if (Install-Platform $sdk 35) { $plat = 35 }
    }

    # Лицензии и недостающие build-tools / platform-tools - через sdkmanager, если он есть
    $pkgs = @()
    if (-not $bt) { $pkgs += $buildTools }
    if (-not (Test-Path -LiteralPath (Join-Path $sdk 'platform-tools'))) { $pkgs += 'platform-tools' }
    if (-not $plat) { $pkgs += $platform }
    $needLic = -not (Test-Path -LiteralPath (Join-Path $sdk 'licenses\android-sdk-license'))

    if ($pkgs.Count -gt 0 -or $needLic) {
        $sdkm = Find-SdkManager $sdk
        if (-not $sdkm) {
            Install-CmdlineTools $sdk
            $sdkm = Find-SdkManager $sdk
        }
        if ($sdkm) {
            if ($needLic) { [void](Invoke-SdkManager $sdkm $sdk @() -LicensesOnly) }
            if ($pkgs.Count -gt 0) { [void](Invoke-SdkManager $sdkm $sdk $pkgs) }
        }
    }

    if (-not (Test-Path -LiteralPath (Join-Path $sdk 'licenses\android-sdk-license'))) {
        throw 'Лицензии Android SDK не приняты. Откройте Android Studio - More Actions - SDK Manager и нажмите Apply.'
    }
    if (-not $plat) { $plat = Get-Platforms | Where-Object { $_ -ge 35 } | Select-Object -First 1 }
    if (-not $bt) { $bt = Get-BuildTools | Where-Object { $_.Major -ge 35 } | Select-Object -First 1 }
    if (-not $plat) {
        throw ('Платформа Android API 35 не скачалась (dl.google.com обрывает связь). ' +
               'Запустите батник ещё раз или поставьте её в Android Studio: ' +
               'More Actions - SDK Manager - SDK Platforms - Android 15 (API 35) или новее - Apply.')
    }

    # ---- 4. Кириллица/пробелы в профиле ломают Gradle - выносим его кэш ----
    $gh = '-'
    if (-not (Test-Plain $env:USERPROFILE)) { $gh = Join-Path $env:SystemDrive 'Android\gradle-home' }

    $btOut = if ($bt) { $bt.ToString() } else { '-' }
    [IO.File]::WriteAllText($out, "$sdk`r`n$gh`r`n$plat`r`n$btOut`r`n", (New-Object Text.UTF8Encoding $false))
    exit 0
} catch {
    Write-Host ''
    Write-Host "[ОШИБКА] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
