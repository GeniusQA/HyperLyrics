@echo off
chcp 65001 >nul
rem 项目统一构建入口：正式包（release 签名+混淆），日志与产物输出到项目目录内，APK 复制到桌面
set "JAVA_HOME=D:\AndroidTools\jdk"
set "PROJECT=%~dp0"
set "DESKTOP=C:\Users\Administrator\Desktop"
cd /d "%PROJECT%"
if not exist "build\logs" mkdir "build\logs"
call gradlew.bat :app:assembleRelease --console=plain > "build\logs\release_build.log" 2>&1
echo EXIT_CODE=%ERRORLEVEL% >> "build\logs\release_build.log"

rem 打包产物统一放桌面
if exist "app\build\outputs\apk\release" (
    copy /Y "app\build\outputs\apk\release\*.apk" "%DESKTOP%\" >nul 2>&1
)

rem 清理 outputs\apk（debug/release 文件夹），保持输出目录干净
if exist "app\build\outputs\apk" rd /s /q "app\build\outputs\apk"

echo 构建完成，正式包已复制到桌面（详细日志见 build\logs\release_build.log）
pause
