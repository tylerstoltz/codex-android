# Codex Mobile (Android)

Android app to control Codex `app-server` sessions running on LAN/Tailnet machines.

## Implemented

- Add/remove/select remote Codex servers (`ws://host:port`)
- Connect + initialize JSON-RPC session
- List and resume existing threads
- Start a new thread by `cwd`
- Send turns and stream `item/agentMessage/delta`
- Interrupt running turns
- Auto-handle approval requests (accept)
- Persist server list in local app storage

## Project path

- `codex-android/`

## Build and install app (phone)

### Android Studio

1. Open `codex-android` in Android Studio.
2. Let Gradle sync/install requested SDK components.
3. Connect phone over USB.
4. Run the `app` configuration.

### CLI

```bash
cd codex-android
./gradlew :app:installDebug
adb shell am start -n com.local.codexmobile/.MainActivity
```
or
```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## PC-side setup (Tailnet host)

This is the machine that has your repos and will run `codex app-server`.

1. Ensure Codex CLI is installed:

```bash
codex --version
```

2. Ensure Tailscale is up on the PC:

```bash
tailscale status
tailscale ip -4
```

3. Start app-server bound to the network interface:

```bash
codex app-server --listen ws://0.0.0.0:8390
```

If you want it running in background:

```bash
nohup codex app-server --listen ws://0.0.0.0:8390 >/tmp/codex-app-server.log 2>&1 &
```

4. Optional quick verification on PC:

```bash
ss -lntp | grep 8390
```

## Phone-side connection flow (Codex Mobile)

1. Open `Codex Mobile`.
2. In `Servers`, add:
- `name`: anything (for example `workstation`)
- `host`: Tailnet IP (`100.x.y.z`) or MagicDNS name (`my-pc.tail...`)
- `port`: `8390`
3. Tap the server chip to select it.
4. Tap connect (top-right play icon).
5. In `Sessions`:
- set `cwd` to repo path on PC (example: `/home/user1/Desktop/vscode/codex`)
- tap `New` to start a thread, or select an existing thread to resume
6. Send prompts in the message box.

## Daily usage

1. Start `codex app-server` on PC.
2. Open app on phone and connect.
3. Start/resume thread and work.

## Troubleshooting

- `Connect failed`: confirm PC app-server is running and host/port are correct.
- No response after connect: check Tailnet reachability from phone to PC.
- Session errors for path: verify `cwd` exists on PC.
- Reinstall app:

```bash
cd codex-android
./gradlew :app:installDebug
```
