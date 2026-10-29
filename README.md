# Vinamp – a music player for Android

An original music player with a glowing hi-fi style display, a spectrum visualizer,
an equalizer with bass boost, your whole music library with search, playlists,
shuffle/repeat, Android Auto support, and background playback with lock-screen
and headphone controls.

## Get the APK without installing anything (GitHub builds it for you)

1. Create a free account at https://github.com and click **New repository**.
   Name it `deckline`, choose **Private**, and click **Create repository**.
2. On the new repository page click **uploading an existing file**.
   Unzip `Vinamp.zip` on your computer, open the `Vinamp` folder, select
   **everything inside it** (including the `.github` folder) and drag it into the page.
   Click **Commit changes**.
3. Open the **Actions** tab. A build named "Build APK" starts on its own and takes
   about 5 minutes. When it shows a green tick, it's done.
4. On your phone, open your repository on github.com (signed in), tap **Releases**
   → **Vinamp – latest build** → **Vinamp.apk**.
5. Open the downloaded file. Android will ask you to allow installs from your
   browser or Files app the first time: allow it, then tap **Install**.
6. Open Vinamp and tap **Allow** so it can find the music on your phone.

**If the Actions tab says there are no workflows:** your computer hid the `.github`
folder during upload (common on Mac). In the repository click **Add file → Create new
file**, type `.github/workflows/build.yml` as the name, paste in the contents of that
file from the zip, and click **Commit changes**.

## Using Vinamp in Android Auto

Android Auto only shows apps from the Play Store unless you allow others. This is a
one-time setting on your phone:

1. Open Android Auto settings. On most phones: **Settings → Connected devices →
   Connection preferences → Android Auto** (or search Settings for "Android Auto").
2. Scroll to the bottom and tap **Version** about 10 times, then confirm to turn on
   developer settings.
3. Tap the **⋮** menu at the top → **Developer settings** → tick **Unknown sources**.
4. Disconnect and reconnect your phone to the car. Vinamp appears with the other
   media apps. If it doesn't, open **Customize launcher** in Android Auto settings
   and tick Vinamp.

In the car you can browse Playlists, Songs, Albums and Artists, search, and say
"Hey Google, play [song or artist] on Vinamp".

## Updating from an earlier version

Upload the files from the new zip into the same GitHub repository the same way,
and let GitHub replace the existing files. A new APK is built automatically. Install
it over the old app; your playlists and settings are kept.

**To change the app later:** edit any file on GitHub and commit. A new APK is built
automatically, and it installs over the old one, keeping your settings.

## Building with Android Studio instead

Open this folder in Android Studio (Hedgehog or newer), let it sync, then use
**Build → Build APK(s)**, or plug in your phone and press **Run**.

## Notes

- Works on Android 7.0 and newer.
- The signing key (`app/deckline.keystore`, password `deckline`) is included so
  every build can update the previous one. That's fine for a personal app; keep the
  repository private, and create your own key before ever publishing to the Play Store.
- The equalizer uses your phone's built-in audio effects, so the number of bands
  and the presets depend on the phone model.
