# MADRIX Deck

Android control surface that sends OSC straight to MADRIX 5 over Wi-Fi.
No Companion needed. Works with mobile data on: the app pins itself to Wi-Fi,
even on a network with no internet.

## Using it

- Tap a button to fire it. The current place stays highlighted.
- Press and hold a button to see what it sends, change its label or colour.
- Back gesture, or tap the top bar, opens the menu: Settings, Import Companion file,
  Reset all button edits, Restore built-in layout, Connection info, Exit.

### Speed (bottom row of the PLACE page)

- SPEED − / + change speed by the step in Settings (default 10% of normal speed).
- RESET returns to the reset speed in Settings (default 1.0) and shows the current speed.
- Settings choose Deck A, Deck B or both decks.

### Colours (COL page)

- COLOUR A = MADRIX Global Colour 1, COLOUR B = Global Colour 2.
- SNAP / 1s / 2s set the colour crossfade. The lit one is active.
- The first colour press after opening the app snaps; later presses fade.

### Feedback (optional)

Tick Enable Feedback in MADRIX: Preferences > Remote Control > OSC (destination port 9002).
The app then follows place, speed and colour changes made elsewhere.
"FB ✓" appears in the top bar once feedback arrives.

### Importing a new layout

Export from Companion (Import / Export > Export), copy the .companionconfig to the phone,
then Menu > Import Companion file. Supported: Generic OSC send int / float / string /
multiple arguments, internal Wait, and page changes. Speed buttons, crossfade buttons
and COLOUR headers are added automatically. Importing replaces button edits.

## Build

Every push builds the APK in GitHub Actions (Actions tab > latest "Build APK" run >
Artifacts > MadrixDeck-apk). Each build has a fresh debug signing key, so uninstall
the previous MADRIX Deck before installing a new build.
