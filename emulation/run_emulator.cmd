@echo off
setlocal

if not exist ".venv\Scripts\python.exe" (
    echo Не найден Python из .venv.
    exit /b 1
)

".venv\Scripts\python.exe" hotbed_emulator.py %*
