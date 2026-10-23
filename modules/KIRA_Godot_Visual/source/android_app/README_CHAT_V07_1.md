# Equinox v0.7.1 — Matrix Chat / Local Learning Surface

v0.7.1 adds a first text conversation surface directly to the unified Android app.

## Product surface

The bottom dock now contains **CHAT**. CHAT opens a full-size text conversation layer over HYPNO while keeping the product dock available.

- conversation history above;
- multiline text input below;
- SEND action and keyboard send action;
- user messages render on the right;
- Equinox messages render on the left;
- chat history persists locally across launches;
- the view can be closed without clearing state.

## Display modes

The rendering style is independent from the stored conversation and can be changed after the fact:

- **MATRIX** — black/green monospace terminal/chat presentation;
- **CLEAN** — conventional dark chat bubbles;
- **RAW** — message IDs, reply kind and diagnostic metadata.

The mode can be cycled from the chat header or selected explicitly under **Settings -> MIND -> CHAT DISPLAY**.

## Honest bootstrap behavior

This release intentionally does not fake a free-language model on-device.

When no Forge BASE or useful local association exists, Equinox answers:

> Noch keine stabile Antwort. Ich habe die Eingabe als neue lokale Spur gespeichert.

The local bootstrap then learns simple structural traces:

- normalized message surface;
- role (USER / EQUINOX);
- mentioned tokens;
- temporal `chat_next` links;
- repeated exact inputs;
- associative overlap with earlier user messages.

If a mounted Forge BASE contains an exact matching text surface, the chat reports a BASE hit, but it does not invent a natural-language fact that the kernel did not explicitly resolve.

## Shared MIND space

Chat learning is not a second database model. Every submitted message is compiled into the same local numeric MIND triple space using the same deterministic IDs as Equinox Language.

The visible raw text remains in the local chat-history file for presentation, while MIND receives numeric relations such as:

- `chat_role`
- `chat_surface`
- `chat_mentions`
- `chat_next`

Chat activity is also projected to HYPNO through the existing MIND WorkingSet snapshot.

## External oven

The v0.7 external Forge/Hugging-Face oven is unchanged. A mounted `knowledge.eqkernel.json` remains a read-only BASE layer; CHAT and the local language layer can operate with BASE offline or mounted.

## Build note

No Android SDK/Gradle installation is available in the assembly environment, so this release does not claim an APK build. Pure Kotlin host tests cover the language core and the new chat reply/learning logic; delimiter/source checks cover the integrated Activity source.
