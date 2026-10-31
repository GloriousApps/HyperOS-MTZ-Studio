# HyperOS MTZ Studio v5.6.7 test artifact

- Route Root bridge requests through the exported `ThemeResourceTabActivity`.
- Install the bridge hook on the same exported Activity so library theme imports reach the
  authenticated Root flow instead of failing before Xiaomi Themes starts.
