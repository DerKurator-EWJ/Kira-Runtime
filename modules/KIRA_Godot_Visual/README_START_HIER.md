# KIRA 1.7.0 – Godot Visual Prototype

**Ziel:** Kira in Godot als sichtbare 3D-Umgebung mit Matrix-Terminal und echter Ereignisverbindung zur bestehenden Core Bridge darstellen.

## Schnellstart (Windows 11)
1. ZIP komplett entpacken, z. B. `C:\KIRA\KIRA_Godot_Visual_1_7_0`.
2. Godot 4.2 oder neuer (Standard, nicht .NET) öffnen → **Importieren** → `world/project.godot` wählen.
3. **F6** drücken, um die aktuelle Szene zu starten, oder **F5** für das Projekt.
4. Bereits jetzt ist eine einfache 3D-Wohnung mit Terminal sichtbar. Ohne Bridge arbeitet nur die Darstellung.
5. Optional für echte Ereignisspeicherung: Python 3 installieren und `desktop/START_KIRA_WINDOWS.bat` starten. Im Desktop-Fenster **Bridge starten** anklicken.
6. Mit Windows+R `%LOCALAPPDATA%\KiraDesktop\bridge_token.txt` öffnen, Inhalt kopieren und in Godots Token-Feld eintragen.
7. **Bridge prüfen** klicken; dann Ereignisse senden oder Verlauf laden.

## Was echt funktioniert
- Godot 3D-Umgebung (prozedurale Grundgeometrie), Matrix-Terminal.
- HTTP-Verbindung zu der bestehenden lokalen Kira Core Bridge auf 127.0.0.1:8765.
- Schreiben und Lesen persistenter EPISODIC-Ereignisse im SQLite-Store der Desktop-App.
- Keine externen Cloud-Dienste erforderlich.

## Was noch nicht funktioniert
- Kein Avatar, keine autonome Weltgenerierung, keine Musik, keine Chat-KI-Antworten.
- Der Unified Core wird noch nicht für Chatentscheidungen aufgerufen.
- Keine Bluetooth-Verbindung; Android noch unverändert.
- Kein getesteter Windows-Export/Installer. Der Godot-Editor startet das Projekt.

## Sicherheit
Bridge lauscht nur auf 127.0.0.1 und erfordert Token. Token nicht veröffentlichen. Godot speichert den eingegebenen Token nicht.

## Projektstruktur
- `world/`: Godot-Projekt
- `desktop/`: vorhandene Kira Desktop 1.6.0 Starter-Oberfläche
- `source/`: unveränderter Core-Bridge-Quellbestand mit Android-App und Python-Core
