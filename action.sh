#!/system/bin/sh

am force-stop com.android.thememanager >/dev/null 2>&1
am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -n com.android.thememanager/.ThemeResourceTabActivity >/dev/null 2>&1
