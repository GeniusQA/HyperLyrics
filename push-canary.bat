@echo off
REM 一键发布 canary：把本地 main 推送到远程，并在 GitHub 触发 canary 构建。
REM 用法：双击本文件，或命令行执行 push-canary.bat
REM 注意：需要本地已登录 gh CLI（gh auth status），且已配置好 git remote "mine"。

echo [1/2] Pushing local main to remote (mine)...
git push mine main
if %errorlevel% neq 0 (
    echo Push failed, abort.
    pause
    exit /b 1
)

echo [2/2] Triggering canary workflow on GitHub...
gh workflow run -R QuanTum2088/HyperLyrics android-release.yml -f channel=canary
if %errorlevel% neq 0 (
    echo Workflow trigger failed.
    pause
    exit /b 1
)

echo Done. Canary build triggered. Watch progress in GitHub Actions.
pause
