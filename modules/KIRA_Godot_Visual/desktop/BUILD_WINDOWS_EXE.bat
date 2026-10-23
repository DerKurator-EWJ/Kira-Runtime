@echo off
cd /d "%~dp0"
py -3 -m pip install pyinstaller
py -3 -m PyInstaller --noconfirm --onefile --windowed --name KiraDesktop --add-data "..\source\python_core\KIRA_UNIFIED_CORE_1_4\kira_core_bridge.py;." kira_desktop.py
echo ACHTUNG: EXE muss mit kompletter source-Struktur funktionieren; siehe README.
pause
