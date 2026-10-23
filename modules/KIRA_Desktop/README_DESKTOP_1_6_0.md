# KIRA Desktop 1.6.0 – erster lauffähiger Windows-Prototyp

## Start
Windows 11, Python 3.9+ inklusive Tkinter installieren. `desktop/START_KIRA_WINDOWS.bat` doppelklicken. Alternativ `py -3 desktop\kira_desktop.py` aus diesem Paketverzeichnis ausführen.

## Funktionen
- Native Windows-Desktopoberfläche (Tkinter), Matrix-inspirierter Terminalstil.
- Persistente Ereignisse in `%LOCALAPPDATA%\KiraDesktop\kira_events.sqlite3` mit dem originalen `EventStore` aus Core Bridge 1.5.0.
- Manuell zuschaltbarer localhost-Bridge-Server auf `127.0.0.1:8765`; Bearer-Token unter `%LOCALAPPDATA%\KiraDesktop\bridge_token.txt` (geheim halten).
- Nachrichten werden als Beobachtungen gespeichert; **kein KI-Chat, keine generativen Antworten, kein autonomes Lernen** verdrahtet.
- Quellarchive in `source/` unverändert als Grundlage enthalten.

## Android derzeit
Die Android-App aus `source/android_app/` ist weiterhin die ursprüngliche v0.9.8 mit Bridge-Client. Der Client ist noch nicht mit der Chatpipeline verbunden. Für USB-Test: `adb reverse tcp:8765 tcp:8765`, sofern Bridge läuft und derselbe Token auf dem Gerät sicher bereitgestellt wird. **Keine Bluetooth-Implementierung** in diesem Paket; dafür folgen ein Windows-RFCOMM-Server und ein Android-Client nach lokalem Desktop-Test. Keine Aussage über erfolgreiches Pairing oder Builds auf Windows/Android.

## EXE bauen (optional)
`desktop/BUILD_WINDOWS_EXE.bat` benötigt PyInstaller und Internetzugang zur Installation. Die EXE ist kein Installer; der Build ist hier nicht unter Windows getestet. Die komplette Android-Anwendung wird nicht automatisch mitgebaut. Windows-Installer folgt erst nach Funktionstest.

## Nächster Schritt
RFCOMM-Protokoll mit Geräte- und Anwendungsauthentifizierung; danach Android-Berechtigungen, Pairing-UI, Offline-Queue, Integrationstest. Bluetooth eignet sich für Events/Text, nicht als primärer Livevideo-Transport.
