# Kira v0.9.0 – Routing Surface / Media Reset

- `FEATURE -> MEDIA` can now clear the selected image/video with `KEIN MEDIUM`.
- Stage background color is independent from media and persists across restarts.
- IN, FEATURE and SETTINGS quick panels use one coherent black-glass backing plate.
- SETTINGS quick panel is also the active Core-mode switcher: WRITE / VIDEO / MUSIC / CODE / MATH.
- Primary Settings pages were shortened and grouped around actual output switches and tuning.
- MASTER remains only the 3x3 connection matrix; it does not create separate memories.
- Video/FILM rendering consumes the VIDEO master patch; Orchestra consumes the AUDIO patch; Kira text generation consumes the TEXT patch.
- Low-latency camera packets are routed through the AUDIO patch before reaching the audio engine.
- Video output and Audio output now activate their bootstrap generators even with empty learned memory.
