@echo off
cd /d "%~dp0"
REM 虚拟环境放在 D: 盘（避免 C 盘空间不足）。若 D: 上没有则创建。
if exist "D:\ds_venv\Scripts\python.exe" (
  set VENV=D:\ds_venv
) else (
  if not exist "venv\Scripts\python.exe" (
    echo [首次运行] 创建虚拟环境并安装依赖（可能需几分钟）...
    "C:\Users\Admin\.workbuddy\binaries\python\versions\3.13.12\python.exe" -m venv venv
    venv\Scripts\pip.exe install -r requirements.txt
    set VENV=venv
  ) else (
    set VENV=venv
  )
)
call %VENV%\Scripts\activate.bat
python main.py
pause
