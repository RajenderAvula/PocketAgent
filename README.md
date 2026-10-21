# 🤖 PocketAgent — Hybrid Offline & Online Android AI Agent

PocketAgent is a hands-free, privacy-first personal Android agent built with Kotlin and Jetpack Compose. It operates seamlessly both online and in Airplane Mode, accepting commands via voice dictation, text chat, an always-on wake word ("Hey Agent"), or a draggable floating overlay bubble over any app.

## Features
- **3-Tier Hybrid Brain**: Instant regex/reflexes + Gemini Flash Cloud API + On-device MediaPipe Gemma 2B.
- **Smart-Polling UI Macros**: Automates complex tasks across apps (e.g., WhatsApp, YouTube) with 250ms polling.
- **Live Screen Recorder**: Records user interactions into custom voice routines stored in Room.
- **Floating Overlay Bubble**: Push-to-Talk mic, screen reader (Eye), emergency STOP, and draggable UI.
- **Screen Context Reader**: Scrapes visible text from the foreground app window to answer or summarize.
- **Full-Text Search (FTS4) & RAG**: Query saved notes and screen summaries with local vector/text retrieval.
- **Jetpack Glance Widget & QS Tile**: Home screen widget and Quick Settings tile controls.
- **JSON Backup/Restore**: Export/import via Storage Access Framework and MediaStore.
