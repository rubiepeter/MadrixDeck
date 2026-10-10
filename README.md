# MADRIX Deck

Android control surface for MADRIX 5 over Wi-Fi, using OSC and/or MADRIX Remote HTTP.
No Companion needed. Works with mobile data on: the app pins itself to Wi-Fi,
even on a network with no internet.

## Using it

- Tap a button to fire it. The current place stays highlighted.
- Press and hold a button to see what it sends and change its label or button colour.
  - Colour buttons: MADRIX output R/G/B sliders, sent live while you adjust
    (Cancel puts the rig back). The button colour has its own sliders, plus
    "Match button to output".
  - SNAP / 1s / 2s: set the crossfade time in seconds.
  - SPEED − / +: set the speed step. RESET: set the reset speed and target deck.
  - Original restores the imported label, colour and RGB.
- Back gesture, or tap the top bar, opens the menu: Settings, Import Companion file,
  Reset all button edits, Restore built-in layout, Connection info, Exit.

### Speed (bottom row of the PLACE page)

- SPEED − / + change speed by the step in Settings (default 10% of normal speed).
- RESET returns to the reset speed in Settings (default 1.0) and shows the current speed.
- Settings choose Deck A, Deck B or both decks.

### Colours (COL page)

- COLOUR A = MADRIX Global Colour 1, COLOUR B = Global Colour 2.
- SNAP / 1s / 2s set the crossfade for colours and places (places use MADRIX /Fade/Time). The lit one is active.
- The first colour press after opening the app snaps; later presses fade.

### Places and HTTP status (MADRIX Remote HTTP)

Turn on Remote HTTP in MADRIX (Preferences > Remote Control > Remote HTTP) and set its
port in Settings (default 80). Settings > Test HTTP shows MADRIX's raw replies.

- Place buttons show MADRIX thumbnails and place names; empty places are dimmed.
- The current place is read back about once a second, whatever changed it.
- Settings > First place picks which 32 places the page shows (banks of 16: 1, 17, 33...).
- Settings > Send presses with: OSC or HTTP. Status can come from OSC feedback, HTTP, or both.
- The top bar shows OSC and HTTP separately.

Speed is in MADRIX units: 1.0 = normal, 0 = paused, negative = reverse, limited to -10...10.

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
Artifacts > MadrixDeck-apk, on the website; the GitHub app doesn't show artifacts).

Builds are signed with app/madrixdeck-debug.keystore, so a new build installs over the
previous one and keeps your button edits. (Builds before v2.1 used a random key:
uninstall those once.)
