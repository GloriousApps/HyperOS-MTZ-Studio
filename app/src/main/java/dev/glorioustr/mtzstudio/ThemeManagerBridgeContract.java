package dev.glorioustr.mtzstudio;

/** Shared, authenticated request contract for the Theme Manager 10.8 in-process bridge. */
public final class ThemeManagerBridgeContract {
    public static final String ACTION_APPLY_MODERN =
            "dev.glorioustr.mtzstudio.action.APPLY_MODERN_THEME";
    public static final String ACTION_IMPORT_MODERN =
            "dev.glorioustr.mtzstudio.action.IMPORT_MODERN_THEME";
    public static final String ACTION_APPLY_EXISTING =
            "dev.glorioustr.mtzstudio.action.APPLY_EXISTING_THEME";
    public static final String ACTION_DELETE_EXISTING =
            "dev.glorioustr.mtzstudio.action.DELETE_EXISTING_THEME";
    public static final String EXTRA_THEME_PATH =
            "dev.glorioustr.mtzstudio.extra.THEME_PATH";
    public static final String EXTRA_THEME_SHA256 =
            "dev.glorioustr.mtzstudio.extra.THEME_SHA256";
    public static final String EXTRA_THEME_NAME =
            "dev.glorioustr.mtzstudio.extra.THEME_NAME";
    public static final String EXTRA_THEME_LOCAL_ID =
            "dev.glorioustr.mtzstudio.extra.THEME_LOCAL_ID";
    public static final String EXTRA_REPLACED_THEME_LOCAL_ID =
            "dev.glorioustr.mtzstudio.extra.REPLACED_THEME_LOCAL_ID";
    public static final String EXTRA_RESULT =
            "dev.glorioustr.mtzstudio.extra.APPLY_RESULT";
    public static final String EXTRA_ERROR =
            "dev.glorioustr.mtzstudio.extra.APPLY_ERROR";
    public static final String RESULT_OK = "ok";
    public static final String EXTRA_DIAGNOSTIC_TRACE = "dev.glorioustr.mtzstudio.extra.DIAGNOSTIC_TRACE";
    public static final String EXTRA_DIAGNOSTIC_RECEIVER = "dev.glorioustr.mtzstudio.extra.DIAGNOSTIC_RECEIVER";
    public static final String BRIDGE_MARKER = "mtz_import_bridge";

    private ThemeManagerBridgeContract() {
    }
}
