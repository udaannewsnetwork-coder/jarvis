# SHADOW — Personal AI Assistant

SHADOW is a cinematic personal AI assistant prototype built with Next.js, React and a server-side AI integration.

## Current prototype
- Futuristic dark assistant interface with a blue animated orb
- Text chat and browser-based voice input
- Audio recording and transcription endpoint
- Hindi and English interface/voice settings
- Spoken replies through browser speech synthesis
- Server-side API key handling

## Local setup
1. Install dependencies:
   `npm install`
2. Copy `.env.example` to `.env.local`.
3. Add your server-side provider key to `.env.local`.
4. Run `npm run dev` and open `http://localhost:3000`.

## Android chat backend
The Android client sends chat requests to the configured HTTPS backend at `/api/chat`. The backend must be deployed and configured with the server-side `OPENAI_API_KEY` environment variable; the key must never be placed in the APK or shared in chat. If the backend URL is missing or the service is not configured, the app displays a connection/error status and keeps the unsent draft available for retry. A successful CI build verifies compilation only, not backend availability or behavior on a physical phone.

## Android direction
The Android APK will be a dedicated mobile client, not merely a website wrapper. It will use the shared backend, keep secrets out of the APK, request permissions only when needed, and include clear microphone/listening controls. See [Android APK roadmap](docs/ANDROID_APK_ROADMAP.md).

## Security
- Never commit real API keys.
- Keep provider secrets in server-side environment variables.
- Device actions will require explicit user approval.
- Do not describe a CI build as a real-device test.

## Background voice commands (experimental)

Start background listening from the Android app and keep the visible SHADOW notification active. Say "Shadow" followed by a command:

- "What time is it" — show the current time.
- "What's the date" — show today's date.
- "Battery status" — show the battery percentage.
- "Set timer for 5 minutes" — post a timer-complete notification.
- "Remind me in 20 minutes to drink water" — schedule a reminder.
- "Volume up" / "Volume down" — adjust media volume.
- "Open YouTube" — show a notification action to open the app.
- "Search [query]" — show a notification action to open Google Search.

Android may delay inexact reminders during Doze/battery-saving modes. App-opening and search actions require tapping OPEN in the notification. Background speech recognition uses the device's installed speech provider; it is not an offline low-power hotword engine. Test on a physical device before relying on it.

## Android Plugin Hub (experimental)

The Android app includes a custom HTTPS plugin manifest importer. A provider publishes a JSON manifest such as:

```json
{
  "schema_version": 1,
  "id": "sample_tools",
  "name": "Sample Tools",
  "description": "Example external integration",
  "base_url": "https://api.example.com",
  "actions": [
    {
      "id": "create_note",
      "name": "Create note",
      "description": "Create a note in the connected service",
      "path": "/v1/notes",
      "requires_confirmation": true
    }
  ]
}
```

Paste the manifest's HTTPS URL in the Android Plugin Hub. An optional bearer token is encrypted with Android Keystore on the device. Each action is shown in the app and requires a user confirmation; the app sends a JSON object to the declared relative path using HTTPS POST. Redirects are blocked, requests time out, response sizes are capped, and removing a plugin clears its saved token.

This is a constrained first version, not a universal ChatGPT plugin marketplace: providers must publish a compatible manifest and HTTPS API. It does not download or execute plugin code, support arbitrary HTTP methods, or automatically grant access to Android private data. Review a provider before connecting it, and only connect services you trust.
