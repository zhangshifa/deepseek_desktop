@echo off
REM DeepSeek 本地桌面客户端 - 一键启动
REM 首次运行会自动安装依赖（pywebview / WebView2 运行时）。
python -c "import webview" 2>nul || pip install -r requirements.txt
python app.py
pause
