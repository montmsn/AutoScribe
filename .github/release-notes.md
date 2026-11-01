AutoScribe finds the Windscribe servers and protocols that actually work on your network. It drives the official **Windscribe CLI**, tests every server × protocol × port you pick, measures ping and packet loss through the tunnel, and ranks the routes that work.

## Download

| Platform | File |
|---|---|
| Windows 10/11 (x64) | `AutoScribe-__TAG__-windows-x64.zip` |
| macOS (Apple Silicon) | `AutoScribe-__TAG__-macos-arm64.zip` |
| macOS (Intel) | `AutoScribe-__TAG__-macos-x64.zip` |
| Linux (x64) | `AutoScribe-__TAG__-linux-x64.zip` |
| Linux (ARM64) | `AutoScribe-__TAG__-linux-arm64.zip` |
| Android | `AutoScribe-__TAG__-android.apk` |

**Desktop:** extract the zip and run `AutoScribe` (`AutoScribe.exe` on Windows) from the extracted folder. Keep `locations.json` next to the executable.

**Android:** install the APK (allow installs from unknown sources if asked).

## Requirements

- The **Windscribe** app, installed and signed in. On desktop it provides `windscribe-cli` (on Linux/macOS it must be on your `PATH`).

## What's new in __TAG__

- **Android app** – run tests on your phone.
- **Test history** – every Android test run is saved as a session.
- **Resume interrupted tests** – pick up an Android run where it stopped.

## Highlights

- Automatic testing of WireGuard, IKEv2, UDP, TCP, Stealth and WStunnel
- Ping and packet loss measured through the tunnel
- **Best routes** leaderboard – double-click a route to connect
- Searchable location picker with one-click server list update
- Protocol priority and per-protocol ports
- Resume, stop on first success, and saveable profiles
- CSV export to `reports/`
- English, Farsi (right-to-left) and Russian

## Notes

- The builds aren't code-signed. Windows SmartScreen may warn ("More info → Run anyway"); on macOS, right-click the app and choose **Open** the first time.
- To run from source, see the [README](https://github.com/montmsn/AutoScribe#installation).
