# SHADOW Skills and Creator Studio

## Current skill connector

The Android app exposes the existing HTTPS manifest connector as **Skills** in the bottom navigation. A skill pack is a remote HTTPS service described by a version-1 JSON manifest. SHADOW stores the manifest locally and calls only the declared POST action paths on the same HTTPS host.

A skill pack is not an APK, arbitrary downloaded code, shell script, or Android accessibility service. The connector does not execute downloaded code. Tokens are stored encrypted with Android Keystore. Each action is shown to the user and requires an explicit confirmation before its request is sent.

### Manifest shape

```json
{
  "schema_version": 1,
  "id": "example_skill",
  "name": "Example Skill",
  "description": "A small HTTPS action service",
  "base_url": "https://skills.example.org",
  "actions": [
    {
      "id": "run_task",
      "name": "Run task",
      "description": "Processes the supplied JSON input",
      "path": "/v1/run",
      "requires_confirmation": true
    }
  ]
}
```

The current connector supports 1–20 declared actions, JSON request payloads, HTTPS only, same-host action paths, bounded request/response sizes, and short network timeouts. It does not yet implement automatic AI skill selection, action-level permission scopes, or a public skill marketplace.

## Creator Studio target

The Creator Studio should be delivered in testable stages rather than showing non-working controls:

1. **Code Studio** — generate and edit project source, run static checks, preview safe HTML, and export source ZIP. Current project generation is a source preview; it does not compile projects or create APKs.
2. **Video editing** — import media with Android's document picker; trim, crop/scale, join clips, add text/subtitles, adjust speed/volume, preview, and export MP4.
3. **Audio editing** — import audio; trim, join, adjust gain, fade in/out, record or add voice-over, preview, and export.
4. **AI creator workflow** — draft a script, produce scene prompts/assets, create or import voice-over, assemble the timeline, and export. Actual generation requires a configured compatible provider skill or local model; do not promise free unlimited cloud generation.
5. **Skill-aware task routing** — match a user's request to installed, enabled skills; show the chosen skill, requested data, and permissions; ask before external, destructive, publishing, or paid actions.

## Implementation guardrails

- Prefer Android-native media APIs / Media3 for local editing; do not bundle retired FFmpegKit artifacts.
- Keep media processing on-device where practical. Show storage and battery impact before long exports.
- Use the Storage Access Framework / content URIs; do not request broad file access.
- Never run arbitrary code from a skill manifest. Remote skills are HTTPS APIs with explicit action schemas.
- Keep user confirmation for upload, publish, delete, overwrite, purchases, or sending private media off-device.
- Every visible editor control must have a working action before it is presented as available.
- Validate with CI, then install and test on the user's Android device. CI success is not a substitute for device testing.
