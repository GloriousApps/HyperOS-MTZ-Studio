# HyperOS MTZ Studio v5.6.5

This maintenance release fixes the Root MTZ Import flow:

- Applying a theme from the MTZ Studio library now reaches the Root bridge's local-theme activity instead of only opening the Xiaomi Themes home screen.
- The home page recognizes the bundled or adopted Root module as current without requiring a fresh boot-runtime marker.
- The signed release artifact is produced with the stable release certificate and includes a SHA-256 checksum.
