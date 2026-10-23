@echo off
cd /d "%~dp0"
py -3 kira_desktop.py
if errorlevel 1 (echo Python 3 und Tkinter erforderlich. & pause)
