package tv.zenhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Zen Home : ecran d'accueil minimaliste pour Android TV, facon Apple TV.
 * Photo plein ecran qui change + horloge + dock d'applis personnalisable.
 *
 * Personnalisation directement sur la TV : appui long sur OK (ou touche Menu) sur une appli du dock
 *   -> deplacer, remplacer, retirer, ajouter, disposition 4/6/8 applis, titre.
 *
 * Reglages envoyes par le kit (adb), pris en compte quand zenhome_rev change :
 *   settings put global zenhome_rev         <horodatage>
 *   settings put global zenhome_title_mode  device | none | custom
 *   settings put global zenhome_title_b64   <titre en base64 UTF-8>   (si custom)
 *   settings put global zenhome_layout      4 | 6 | 8
 *   settings put global zenhome_dock        com.netflix.ninja,com.google.android.youtube.tv,...
 *   settings put global zenhome_interval    60   (secondes entre deux photos)
 * Photos perso : fichiers .jpg/.png dans /sdcard/ZenHome/ (remplacent les photos fournies).
 */
public class Home extends Activity {

    /** Applis de streaming connues, par ordre de priorite pour le dock automatique. */
    static final String[] KNOWN_DOCK = {
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.canal.android.canal", "com.wbd.stream",
            "com.apple.atve.androidtv.appletv", "com.spotify.tv.android", "com.plexapp.android",
            "fr.francetv.pluzz", "fr.tf1.mytf1", "fr.m6.m6replay", "tv.arte.plus7", "tv.molotov.app",
            "com.cbs.ott", "com.dazn", "tv.twitch.android.app", "org.xbmc.kodi", "com.google.android.youtube.tvkids",
    };
    /** Applis "entrees TV / chaines" selon les marques. */
    static final String[][] KNOWN_INPUTS = {
            {"com.tcl.tv", "com.tcl.tv.TVActivity"},
            {"com.mediatek.wwtv.tvcenter", null},
            {"org.droidtv.playtv", null},
            {"com.sony.dtv.tvx", null},
            {"com.mitv.tvplayer", null},
            {"com.mitv.livetv", null},
            {"com.hisense.tv", null},
            {"com.google.android.tv", null},
            {"com.android.tv", null},
    };
    static final long FADE_MS = 2500L;
    static final int ACCENT = 0xFF7CC4FF;

    final Handler h = new Handler();
    SharedPreferences prefs;
    long rotationMs = 60_000L;
    FrameLayout root, wallLayer;
    ImageView wallA, wallB;
    boolean aOnTop = true, released = false;
    TextView clock, date, titleView, hintView;
    List<String> walls = new ArrayList<>();
    boolean wallsFromFiles = false;
    int wallIdx = 0;
    LinearLayout dock;
    final List<View> overlays = new ArrayList<>();   // menus / listes ouverts (pile)
    Typeface fReg, fMed, fLight;
    float density;
    boolean fr;

    // verre liquide + meteo
    final Glass.Backdrop backdrop = new Glass.Backdrop();
    LinearLayout weatherCard;
    final Handler ui = new Handler(Looper.getMainLooper());   // resultats reseau (pas annule par h.removeCallbacksAndMessages)
    static final long WEATHER_TTL_MS = 30 * 60 * 1000L;
    boolean weatherBusy = false;
    String city = "";                                          // ville de la meteo ("" = pas de meteo)

    // configuration du dock
    List<String> dockPkgs = new ArrayList<>();
    int layout = 4;               // 4, 6 ou 8 applis
    String titleMode = "device";  // device | none | custom
    String titleText = "";
    int moveIndex = -1;           // >= 0 : appli en cours de deplacement
    List<View> bigTiles = new ArrayList<>();

    int dp(float v) { return Math.round(v * density); }
    String t(String frTxt, String enTxt) { return fr ? frTxt : enTxt; }

    String setting(String key) {
        try { return Settings.Global.getString(getContentResolver(), key); } catch (Throwable e) { return null; }
    }

    // =====================================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        fr = "fr".equals(Locale.getDefault().getLanguage());
        prefs = getSharedPreferences("zenhome", MODE_PRIVATE);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        density = Math.max(dm.widthPixels, dm.heightPixels) / 1280f; // maquette sur 1280 unites de large
        backdrop.setScreen(Math.max(dm.widthPixels, dm.heightPixels), Math.min(dm.widthPixels, dm.heightPixels), dp(200), dp(340));
        try {
            fReg = Typeface.createFromAsset(getAssets(), "Poppins-Regular.ttf");
            fMed = Typeface.createFromAsset(getAssets(), "Poppins-Medium.ttf");
            fLight = Typeface.createFromAsset(getAssets(), "Poppins-Light.ttf");
        } catch (Exception e) { fReg = fMed = fLight = Typeface.SANS_SERIF; }
        try {
            String iv = setting("zenhome_interval");
            if (iv != null) rotationMs = Math.max(10, Integer.parseInt(iv.trim())) * 1000L;
        } catch (Exception ignored) { }
        loadConfig();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);
        wallA = newWall(); wallB = newWall();
        wallLayer = new FrameLayout(this);
        wallLayer.addView(wallB); wallLayer.addView(wallA);
        root.addView(wallLayer, new FrameLayout.LayoutParams(-1, -1));

        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(-1, dp(200), Gravity.TOP));
        View bot = new View(this);
        bot.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0x00000000}));
        root.addView(bot, new FrameLayout.LayoutParams(-1, dp(340), Gravity.BOTTOM));

        // Titre
        titleView = text("", 26, fMed, 0xFFFFFFFF);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setMaxWidth(dp(820));
        FrameLayout.LayoutParams lpT = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lpT.setMargins(dp(56), dp(38), 0, 0);
        root.addView(titleView, lpT);
        applyTitle();

        // Horloge + date
        LinearLayout cl = new LinearLayout(this);
        cl.setOrientation(LinearLayout.VERTICAL);
        cl.setGravity(Gravity.END);
        clock = text("", 40, fLight, 0xFFFFFFFF);
        date = text("", 15, fReg, 0xE6FFFFFF);
        cl.addView(clock); cl.addView(date);
        FrameLayout.LayoutParams lpC = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lpC.setMargins(0, dp(26), dp(56), 0);
        root.addView(cl, lpC);

        // Meteo (carte en verre sous l'horloge) : cachee tant qu'aucune ville n'est reglee
        weatherCard = new LinearLayout(this);
        weatherCard.setOrientation(LinearLayout.HORIZONTAL);
        weatherCard.setGravity(Gravity.CENTER_VERTICAL);
        weatherCard.setPadding(dp(20), dp(14), dp(20), dp(14));
        weatherCard.setBackground(new Glass.Panel(weatherCard, backdrop, dp(24), 0x2EFFFFFF, density));
        weatherCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams lpW = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lpW.setMargins(0, dp(104), dp(56), 0);
        root.addView(weatherCard, lpW);

        // Indication pendant le deplacement
        hintView = text("", 13, fMed, 0xFFFFFFFF);
        GradientDrawable hb = new GradientDrawable(); hb.setCornerRadius(dp(16)); hb.setColor(0xB3101216);
        hintView.setBackground(hb);
        hintView.setPadding(dp(16), dp(8), dp(16), dp(8));
        hintView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lpH = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lpH.setMargins(0, 0, 0, dp(layoutHint()));
        root.addView(hintView, lpH);

        // Dock
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setClipChildren(false); dock.setClipToPadding(false);
        dock.setBackground(new Glass.Panel(dock, backdrop, dp(28), 0x2EFFFFFF, density));   // verre liquide
        FrameLayout.LayoutParams lpD = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lpD.setMargins(0, 0, 0, dp(46));
        root.addView(dock, lpD);
        buildDock(0);

        setContentView(root);
        loadWalls();
        showWall(true);
        refreshWeather(false);
    }

    // =====================================================================
    // Configuration : preferences de la TV, remplacees par celles du kit si plus recentes
    void loadConfig() {
        String rev = setting("zenhome_rev");
        if (rev != null && !rev.equals(prefs.getString("rev", null))) {
            SharedPreferences.Editor e = prefs.edit().putString("rev", rev);
            String mode = setting("zenhome_title_mode");
            if (mode != null) e.putString("title_mode", mode.trim());
            String b64 = setting("zenhome_title_b64");
            if (b64 != null && b64.trim().length() > 0) {
                try { e.putString("title", new String(Base64.decode(b64.trim(), Base64.DEFAULT), "UTF-8").trim()); } catch (Exception ignored) { }
            }
            String lay = setting("zenhome_layout");
            if (lay != null) e.putString("layout", lay.trim());
            // meteo : zenhome_city (texte) ou zenhome_city_b64 (UTF-8 en base64, pour les accents depuis un PC Windows)
            String cityB64 = setting("zenhome_city_b64");
            String cityTxt = setting("zenhome_city");
            if (cityB64 != null && cityB64.trim().length() > 0) {
                try { cityTxt = new String(Base64.decode(cityB64.trim(), Base64.DEFAULT), "UTF-8"); } catch (Exception ignored) { }
            }
            if (cityTxt != null && !"null".equals(cityTxt)) e.putString("city", cityTxt.trim());
            String d = setting("zenhome_dock");
            if (d != null) {
                // "auto" : le kit demande le choix automatique des applis
                if ("auto".equalsIgnoreCase(d.trim())) e.putString("dock", "").putBoolean("dock_user_set", false);
                else e.putString("dock", d.trim()).putBoolean("dock_user_set", true);
            }
            e.apply();
        }
        titleMode = prefs.getString("title_mode", "device");
        titleText = prefs.getString("title", "");
        city = prefs.getString("city", "");
        try { layout = Integer.parseInt(prefs.getString("layout", "4")); } catch (Exception ex) { layout = 4; }
        if (layout != 6 && layout != 8) layout = 4;
        dockPkgs.clear();
        String csv = prefs.getString("dock", "");
        if (csv.length() > 0) {
            for (String p : csv.split("[,;\\s]+")) { p = p.trim(); if (p.length() > 0 && usable(p) && !dockPkgs.contains(p)) dockPkgs.add(p); }
        }
        if (dockPkgs.isEmpty() && !prefs.getBoolean("dock_user_set", false)) {
            for (String p : KNOWN_DOCK) { if (dockPkgs.size() >= layout) break; if (usable(p)) dockPkgs.add(p); }
        }
        while (dockPkgs.size() > layout) dockPkgs.remove(dockPkgs.size() - 1);
    }

    void saveConfig() {
        prefs.edit()
                .putString("dock", TextUtils.join(",", dockPkgs))
                .putBoolean("dock_user_set", true)
                .putString("layout", String.valueOf(layout))
                .putString("title_mode", titleMode)
                .putString("title", titleText)
                .putString("city", city)
                .apply();
    }

    String deviceName() {
        String n = setting("device_name");
        if (n == null || n.trim().isEmpty() || "null".equals(n)) n = Build.MODEL;
        return n == null ? "" : n.trim();
    }

    void applyTitle() {
        String s = "custom".equals(titleMode) ? titleText : ("none".equals(titleMode) ? "" : deviceName());
        titleView.setText(s);
        titleView.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    boolean usable(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled && launchIntent(pkg) != null;
        } catch (Exception e) { return false; }
    }

    // =====================================================================
    // Dock : grandes tuiles (1 ou 2 rangees) + petites tuiles (Play Store, Entrees, Toutes, Reglages)
    int bigW() { return layout == 4 ? 176 : 128; }
    int bigH() { return layout == 4 ? 99 : 72; }
    int bigM() { return layout == 4 ? 9 : 7; }
    int smallW() { return layout == 6 ? 64 : 86; }
    int smallH() { return layout == 6 ? 60 : (layout == 8 ? 72 : 80); }
    int layoutHint() { return layout == 8 ? 266 : (layout == 6 ? 174 : 200); }

    void buildDock(int focusIndex) {
        dock.removeAllViews();
        bigTiles.clear();
        int rows = layout == 8 ? 2 : 1;
        int cols = layout == 8 ? 4 : layout;
        dock.setPadding(dp(layout == 4 ? 22 : 18), dp(layout == 8 ? 16 : 18), dp(layout == 4 ? 22 : 18), dp(layout == 8 ? 16 : 18));

        GridLayout big = new GridLayout(this);
        big.setColumnCount(Math.max(1, Math.min(cols, dockPkgs.size() + (dockPkgs.size() < layout ? 1 : 0))));
        big.setClipChildren(false); big.setClipToPadding(false);
        for (int i = 0; i < dockPkgs.size() && i < layout; i++) {
            View v = bigTile(dockPkgs.get(i), i);
            if (v != null) { big.addView(v); bigTiles.add(v); }
        }
        if (dockPkgs.size() < layout) big.addView(addTile());   // case "+" pour ajouter une appli
        dock.addView(big);

        View sep = new View(this); sep.setBackgroundColor(0x4DFFFFFF);
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(dp(1.5f), dp(rows == 2 ? 140 : (layout == 6 ? 52 : 64)));
        lpS.setMargins(dp(layout == 6 ? 10 : 14), 0, dp(layout == 6 ? 10 : 14), 0);
        dock.addView(sep, lpS);

        GridLayout small = new GridLayout(this);
        small.setColumnCount(rows == 2 ? 2 : 4);
        small.setClipChildren(false); small.setClipToPadding(false);
        if (usable("com.android.vending"))
            small.addView(smallTile(pkgIcon("com.android.vending"), "Play Store", new Runnable() { public void run() { launch("com.android.vending"); } }));
        final String[] input = findInput();
        final ComponentName viewInputs = viewInputsTarget();
        if (input != null)
            small.addView(smallTile(pkgIcon(input[0]), t("Entrées TV", "TV inputs"), new Runnable() { public void run() { openInput(input); } }));
        else if (viewInputs != null)   // projecteurs (XGIMI...) : pas d'appli TV connue, mais le système sait ouvrir les entrées
            small.addView(smallTile(pkgIcon(viewInputs.getPackageName()), t("Entrées TV", "TV inputs"), new Runnable() { public void run() { openInputs(); } }));
        small.addView(smallTile(new GridIcon(), t("Toutes", "All apps"), new Runnable() { public void run() { showApps(null); } }));
        Drawable gear = pkgIcon("com.android.tv.settings");
        if (gear == null) gear = pkgIcon("com.android.settings");
        small.addView(smallTile(gear, t("Réglages", "Settings"), new Runnable() { public void run() {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { toast(t("Réglages indisponibles", "Settings unavailable")); } } }));
        dock.addView(small);

        // repositionne l'indication au-dessus du dock
        FrameLayout.LayoutParams lpH = (FrameLayout.LayoutParams) hintView.getLayoutParams();
        if (lpH != null) { lpH.bottomMargin = dp(layoutHint()); hintView.setLayoutParams(lpH); }

        final View target = (focusIndex >= 0 && focusIndex < bigTiles.size()) ? bigTiles.get(focusIndex)
                : (bigTiles.isEmpty() ? (big.getChildCount() > 0 ? big.getChildAt(0) : small.getChildAt(0)) : bigTiles.get(bigTiles.size() - 1));
        if (target != null) dock.post(new Runnable() { public void run() { target.requestFocus(); } });
    }

    View bigTile(final String pkg, final int index) {
        PackageManager pm = getPackageManager();
        Drawable d = null;
        String label = pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(ai).toString();
            d = pm.getApplicationBanner(ai);
            if (d == null) { Intent li = pm.getLeanbackLaunchIntentForPackage(pkg); if (li != null) d = pm.getActivityBanner(li); }
        } catch (Exception e) { return null; }
        FrameLayout f = roundedFrame(14, 0xFF1E1E1E);
        if (d != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(d);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL); l.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this); iv.setImageDrawable(pkgIcon(pkg));
            int is = layout == 4 ? 44 : 32;
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(is), dp(is)); il.gravity = Gravity.CENTER_HORIZONTAL;
            l.addView(iv, il);
            TextView tv = text(label, layout == 4 ? 11 : 9, fMed, 0xFFFFFFFF); tv.setGravity(Gravity.CENTER); tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(dp(6), dp(4), dp(6), 0);
            l.addView(tv, new LinearLayout.LayoutParams(-1, -2));
            f.addView(l, new FrameLayout.LayoutParams(-1, -1));
        }
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(bigW()); lp.height = dp(bigH());
        lp.setMargins(dp(bigM()), dp(layout == 8 ? 6 : 0), dp(bigM()), dp(layout == 8 ? 6 : 0));
        f.setLayoutParams(lp);
        f.setContentDescription(label);
        f.setTag(14);
        wire(f, new Runnable() { public void run() { launch(pkg); } }, layout == 4 ? 1.14f : 1.12f);
        f.setLongClickable(true);
        f.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { showTileMenu(index); return true; } });
        return f;
    }

    View addTile() {
        FrameLayout f = roundedFrame(14, 0x1FFFFFFF);
        GradientDrawable bg = (GradientDrawable) f.getBackground();
        bg.setStroke(Math.max(1, dp(1)), 0x59FFFFFF);
        TextView plus = text("+", layout == 4 ? 34 : 26, fLight, 0xCCFFFFFF);
        plus.setGravity(Gravity.CENTER); plus.setShadowLayer(0, 0, 0, 0);
        f.addView(plus, new FrameLayout.LayoutParams(-1, -1));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(bigW()); lp.height = dp(bigH());
        lp.setMargins(dp(bigM()), dp(layout == 8 ? 6 : 0), dp(bigM()), dp(layout == 8 ? 6 : 0));
        f.setLayoutParams(lp);
        f.setContentDescription(t("Ajouter une appli", "Add an app"));
        f.setTag(14);
        wire(f, new Runnable() { public void run() { pickAppToAdd(); } }, 1.1f);
        f.setLongClickable(true);
        f.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { showTileMenu(-1); return true; } });
        return f;
    }

    View smallTile(Drawable icon, String label, Runnable r) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(layout == 6 ? 14 : 18)); bg.setColor(0x33FFFFFF);
        l.setBackground(bg);
        if (icon == null) icon = new GridIcon();
        ImageView iv = new ImageView(this); iv.setImageDrawable(icon);
        int is = layout == 6 ? 24 : (layout == 8 ? 30 : 34);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(is), dp(is));
        il.gravity = Gravity.CENTER_HORIZONTAL;
        l.addView(iv, il);
        TextView tv = text(label, layout == 6 ? 8 : 10, fMed, 0xFFFFFFFF); tv.setShadowLayer(0, 0, 0, 0);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setIncludeFontPadding(true);
        tv.setPadding(dp(2), dp(layout == 6 ? 2 : 4), dp(2), 0);
        l.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(smallW()); lp.height = dp(smallH());
        int m = layout == 6 ? 5 : 7;
        lp.setMargins(dp(m), dp(layout == 8 ? 6 : 0), dp(m), dp(layout == 8 ? 6 : 0));
        l.setLayoutParams(lp);
        l.setTag(layout == 6 ? 14 : 18);
        l.setContentDescription(label);
        wire(l, r, 1.12f);
        l.setLongClickable(true);
        l.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { showTileMenu(-1); return true; } });
        return l;
    }

    FrameLayout roundedFrame(final int radius, int color) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(radius)); bg.setColor(color);
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(radius)); } });
        return f;
    }

    void wire(final View v, final Runnable r, final float scale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) {
            if (moveIndex >= 0) return;
            if (isInDock(x)) {
                // dock : on attend un peu, une rafale de OK (telecommandes Sony) vaut un appui long
                if (pendingClick != null) h.removeCallbacks(pendingClick);
                pendingClick = r;
                h.postDelayed(r, BURST_GAP_MS);
            } else r.run();
        } });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View x, boolean has) {
                x.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                        .translationY(has ? -dp(6) : 0).translationZ(has ? dp(12) : 0)
                        .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
                int r = x.getTag() instanceof Integer ? (Integer) x.getTag() : 14;
                x.setForeground(has ? ring(r, moveIndex >= 0 ? ACCENT : 0xF2FFFFFF) : null);
            }
        });
    }

    GradientDrawable ring(int r, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(r)); g.setColor(0x00000000); g.setStroke(dp(moveIndex >= 0 ? 3 : 2), color);
        return g;
    }

    Drawable pkgIcon(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); } catch (Exception e) { return null; }
    }

    String appLabel(String pkg) {
        try { PackageManager pm = getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    // =====================================================================
    // Mode edition (appui long sur OK, ou touche Menu)
    void showTileMenu(final int index) {
        if (moveIndex >= 0 || !overlays.isEmpty()) return;
        List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        String header;
        if (index >= 0 && index < dockPkgs.size()) {
            header = appLabel(dockPkgs.get(index));
            if (dockPkgs.size() > 1) { labels.add(t("Déplacer", "Move")); actions.add(new Runnable() { public void run() { startMove(index); } }); }
            labels.add(t("Remplacer par une autre appli", "Replace with another app")); actions.add(new Runnable() { public void run() { pickAppToReplace(index); } });
            labels.add(t("Retirer du dock", "Remove from dock")); actions.add(new Runnable() { public void run() {
                dockPkgs.remove(index); saveConfig(); buildDock(Math.max(0, index - 1)); } });
        } else {
            header = t("Écran d'accueil", "Home screen");
        }
        if (dockPkgs.size() < layout) { labels.add(t("Ajouter une appli", "Add an app")); actions.add(new Runnable() { public void run() { pickAppToAdd(); } }); }
        labels.add(t("Disposition : ", "Layout: ") + layout + t(" applis", " apps")); actions.add(new Runnable() { public void run() { showLayoutMenu(); } });
        labels.add(t("Modifier le titre", "Change the title")); actions.add(new Runnable() { public void run() { editTitle(); } });
        labels.add(t("Météo : ", "Weather: ") + (city.isEmpty() ? t("désactivée", "off") : city));
        actions.add(new Runnable() { public void run() { editCity(); } });
        showMenu(header, labels, actions);
    }

    void editCity() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setText(city);
        et.setHint(t("Ville", "City"));
        et.setSelection(et.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(et);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(t("Ville de la météo", "Weather city"))
                .setMessage(t("Laissez vide pour masquer la météo.", "Leave empty to hide the weather."))
                .setView(box)
                .setPositiveButton(t("Valider", "Save"), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        city = et.getText().toString().trim();
                        saveConfig(); refreshWeather(true); } })
                .setNegativeButton(t("Annuler", "Cancel"), null)
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    public void onDismiss(android.content.DialogInterface d) { focusDock(); } })
                .show();
    }

    // =====================================================================
    // Meteo : ville -> coordonnees (Open-Meteo) -> previsions ; copie gardee pour s'afficher aussi hors ligne
    void refreshWeather(boolean force) {
        if (city.isEmpty()) { weatherCard.setVisibility(View.GONE); return; }
        final String c = city;
        String cached = prefs.getString("wx_json", null);
        boolean sameCity = c.equals(prefs.getString("wx_city", ""));
        long age = System.currentTimeMillis() - prefs.getLong("wx_time", 0);
        if (cached != null && sameCity) renderWeather(cached); else weatherCard.setVisibility(View.GONE);
        if (weatherBusy || (!force && cached != null && sameCity && age >= 0 && age < WEATHER_TTL_MS)) return;
        weatherBusy = true;
        new Thread(new Runnable() { public void run() {
            String json = null;
            boolean notFound = false;
            try {
                String lat, lon;
                if (c.equals(prefs.getString("geo_city", "")) && prefs.getString("geo_lat", null) != null) {
                    lat = prefs.getString("geo_lat", ""); lon = prefs.getString("geo_lon", "");
                } else {
                    String[] g = Weather.geocode(c, fr ? "fr" : "en");
                    if (g == null) throw new IllegalStateException("not found");
                    lat = g[1]; lon = g[2];
                    prefs.edit().putString("geo_city", c).putString("geo_name", g[0]).putString("geo_lat", lat).putString("geo_lon", lon).apply();
                }
                json = Weather.fetchForecast(lat, lon);
                prefs.edit().putString("wx_json", json).putLong("wx_time", System.currentTimeMillis()).putString("wx_city", c).apply();
            } catch (IllegalStateException e) {
                notFound = true;
            } catch (Throwable e) {
                json = null;
            }
            final String fj = json;
            final boolean nf = notFound;
            ui.post(new Runnable() { public void run() {
                weatherBusy = false;
                if (isDestroyed()) return;
                if (!c.equals(city)) return;                 // la ville a change entre-temps
                if (fj != null) renderWeather(fj);
                else if (nf) { weatherCard.setVisibility(View.GONE); toast(t("Ville introuvable : ", "City not found: ") + c); }
            } });
        } }).start();
    }

    void renderWeather(String json) {
        Weather.Forecast f = Weather.parse(json);
        if (f == null) { weatherCard.setVisibility(View.GONE); return; }
        weatherCard.removeAllViews();
        String place = placeName();

        // a gauche : temperature actuelle, texte, mini / maxi du jour
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.HORIZONTAL);
        now.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Weather.Icon(Weather.kind(f.codeNow, f.isDay)));
        now.addView(ic, new LinearLayout.LayoutParams(dp(50), dp(50)));
        TextView temp = text(f.tempNow + "°", 40, fLight, 0xFFFFFFFF);
        temp.setPadding(dp(8), 0, 0, 0);
        now.addView(temp);
        left.addView(now);
        TextView cond = text(Weather.label(f.codeNow, fr), 12, fMed, 0xFFFFFFFF);
        cond.setPadding(0, dp(4), 0, 0);
        cond.setSingleLine(true);
        left.addView(cond);
        TextView sub = text(f.min[0] + "° / " + f.max[0] + "°  ·  " + place, 11, fReg, 0xCCFFFFFF);
        sub.setPadding(0, dp(2), 0, 0);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setMaxWidth(dp(200));
        left.addView(sub);
        weatherCard.addView(left);

        // a droite : les 3 jours suivants
        if (f.days > 1) {
            View sep = new View(this);
            sep.setBackgroundColor(0x4DFFFFFF);
            LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(dp(1.5f), dp(64));
            lpS.setMargins(dp(16), 0, dp(6), 0);
            weatherCard.addView(sep, lpS);
            for (int i = 1; i < f.days; i++) {
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                col.setPadding(dp(8), 0, dp(8), 0);
                TextView dn = text(Weather.dayName(f.date[i], Locale.getDefault()), 11, fMed, 0xE6FFFFFF);
                col.addView(dn);
                ImageView di = new ImageView(this);
                di.setImageDrawable(new Weather.Icon(Weather.kind(f.code[i], true)));
                LinearLayout.LayoutParams lpI = new LinearLayout.LayoutParams(dp(30), dp(30));
                lpI.setMargins(0, dp(3), 0, dp(3));
                col.addView(di, lpI);
                LinearLayout mm = new LinearLayout(this);
                mm.setOrientation(LinearLayout.HORIZONTAL);
                TextView hi = text(f.max[i] + "°", 12, fMed, 0xFFFFFFFF);
                TextView lo = text(" " + f.min[i] + "°", 12, fReg, 0x99FFFFFF);
                mm.addView(hi); mm.addView(lo);
                col.addView(mm);
                weatherCard.addView(col);
            }
        }
        weatherCard.setVisibility(View.VISIBLE);
        weatherCard.invalidate();
    }

    /** Nom de la ville tel que renvoye par la recherche (ex. avec les accents), sinon celui saisi. */
    String placeName() {
        String n = prefs.getString("geo_name", null);
        return (n != null && city.equals(prefs.getString("geo_city", ""))) ? n : city;
    }

    void showLayoutMenu() {
        final int[] opts = {4, 6, 8};
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        String[] desc = {t("4 applis, grandes tuiles", "4 apps, large tiles"), t("6 applis sur une ligne", "6 apps in one row"), t("8 applis sur deux lignes", "8 apps in two rows")};
        for (int i = 0; i < opts.length; i++) {
            final int n = opts[i];
            labels.add((n == layout ? "✓  " : "     ") + desc[i]);
            actions.add(new Runnable() { public void run() {
                layout = n;
                while (dockPkgs.size() > layout) dockPkgs.remove(dockPkgs.size() - 1);
                if (dockPkgs.size() < layout && !prefs.getBoolean("dock_user_set", false)) {
                    for (String p : KNOWN_DOCK) { if (dockPkgs.size() >= layout) break; if (usable(p) && !dockPkgs.contains(p)) dockPkgs.add(p); }
                }
                saveConfig(); buildDock(0); } });
        }
        showMenu(t("Disposition", "Layout"), labels, actions);
    }

    void editTitle() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setText("custom".equals(titleMode) ? titleText : "");
        et.setHint(deviceName());
        et.setSelection(et.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(et);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(t("Titre de l'écran d'accueil", "Home screen title"))
                .setMessage(t("Laissez vide pour afficher le nom de la TV.", "Leave empty to show the TV's name."))
                .setView(box)
                .setPositiveButton(t("Valider", "Save"), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String s = et.getText().toString().trim();
                        if (s.isEmpty()) { titleMode = "device"; titleText = ""; } else { titleMode = "custom"; titleText = s; }
                        saveConfig(); applyTitle(); } })
                .setNeutralButton(t("Aucun titre", "No title"), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { titleMode = "none"; saveConfig(); applyTitle(); } })
                .setNegativeButton(t("Annuler", "Cancel"), null)
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    public void onDismiss(android.content.DialogInterface d) { focusDock(); } })
                .show();
    }

    void startMove(int index) {
        moveIndex = index;
        hintView.setText(t("◀  ▶  pour déplacer   ·   OK pour valider", "◀  ▶  to move   ·   OK to confirm"));
        hintView.setVisibility(View.VISIBLE);
        buildDock(index);
    }

    void endMove() {
        moveIndex = -1;
        hintView.setVisibility(View.GONE);
        saveConfig();
        int keep = 0;
        View f = getCurrentFocus();
        if (f != null && bigTiles.contains(f)) keep = bigTiles.indexOf(f);
        buildDock(keep);
    }

    boolean isInDock(View v) {
        while (v != null) { if (v == dock) return true; v = v.getParent() instanceof View ? (View) v.getParent() : null; }
        return false;
    }

    boolean swallowOkUp = false;

    // Certaines telecommandes (Sony a infrarouge) envoient un appui maintenu comme une rafale
    // d'appuis courts : BURST_PRESSES appuis espaces de moins de BURST_GAP_MS = appui long.
    static final long BURST_GAP_MS = 250;
    static final int BURST_PRESSES = 3;
    Runnable pendingClick;            // clic du dock en attente (annule si une rafale commence)
    View burstView;
    int okBurst = 0;
    long lastOkUp = 0, lastOkEvent = 0;
    boolean burstSwallow = false;     // rafale deja transformee en appui long : on avale la suite

    static boolean isOk(int c) {
        return c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        // appui long sur OK, aussi pour les telecommandes qui envoient la touche en repetition
        if (isOk(kc) && moveIndex < 0) {
            long t = e.getEventTime();
            if (burstSwallow) {
                if (t - lastOkEvent < BURST_GAP_MS) { lastOkEvent = t; return true; }
                burstSwallow = false;
            }
            lastOkEvent = t;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && overlays.isEmpty()) {
                View f = getCurrentFocus();
                if (f != null && isInDock(f)) {
                    okBurst = (f == burstView && t - lastOkUp < BURST_GAP_MS) ? okBurst + 1 : 1;
                    burstView = f;
                    if (okBurst >= 2 && pendingClick != null) { h.removeCallbacks(pendingClick); pendingClick = null; }
                    if (okBurst >= BURST_PRESSES) {
                        okBurst = 0;
                        burstSwallow = true;
                        f.cancelLongPress(); f.setPressed(false);
                        showTileMenu(bigTiles.indexOf(f));
                        return true;
                    }
                }
            }
            if (e.getAction() == KeyEvent.ACTION_UP) lastOkUp = t;
            if (e.getAction() == KeyEvent.ACTION_UP && swallowOkUp) { swallowOkUp = false; return true; }
            if (e.getAction() == KeyEvent.ACTION_DOWN && overlays.isEmpty() && (e.getRepeatCount() >= 1 || e.isLongPress())) {
                View f = getCurrentFocus();
                if (f != null && isInDock(f)) {
                    swallowOkUp = true;
                    f.cancelLongPress(); f.setPressed(false);
                    showTileMenu(bigTiles.indexOf(f));
                    return true;
                }
            }
            if (swallowOkUp && e.getAction() == KeyEvent.ACTION_DOWN) return true;
        }
        if (moveIndex >= 0 && e.getAction() == KeyEvent.ACTION_DOWN) {
            int c = e.getKeyCode();
            int target = moveIndex;
            int cols = layout == 8 ? 4 : layout;
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) target = moveIndex - 1;
            else if (c == KeyEvent.KEYCODE_DPAD_RIGHT) target = moveIndex + 1;
            else if (c == KeyEvent.KEYCODE_DPAD_UP && layout == 8) target = moveIndex - cols;
            else if (c == KeyEvent.KEYCODE_DPAD_DOWN && layout == 8) target = moveIndex + cols;
            else if (c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_BACK
                    || c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_BUTTON_A) {
                endMove(); return true;
            } else return true;
            if (target >= 0 && target < dockPkgs.size() && target != moveIndex) {
                Collections.swap(dockPkgs, moveIndex, target);
                moveIndex = target;
                buildDock(target);
            }
            return true;
        }
        if (moveIndex >= 0) return true; // ignore les relachements pendant le deplacement
        return super.dispatchKeyEvent(e);
    }

    void pickAppToAdd() {
        showApps(new Picker() { public void picked(String pkg) {
            dockPkgs.remove(pkg);
            if (dockPkgs.size() >= layout) return;
            dockPkgs.add(pkg); saveConfig(); buildDock(dockPkgs.indexOf(pkg)); } });
    }

    void pickAppToReplace(final int index) {
        showApps(new Picker() { public void picked(String pkg) {
            if (index >= dockPkgs.size()) return;
            int existing = dockPkgs.indexOf(pkg);
            if (existing >= 0 && existing != index) Collections.swap(dockPkgs, existing, index);
            else dockPkgs.set(index, pkg);
            saveConfig(); buildDock(index); } });
    }

    // ---------- Menus en surimpression ----------
    void showMenu(String header, List<String> labels, final List<Runnable> actions) {
        final FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0x99000000);
        ov.setClickable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(new Glass.Panel(panel, backdrop, dp(22), 0xB0121418, density));   // verre sombre : texte lisible
        panel.setPadding(dp(14), dp(18), dp(14), dp(14));
        panel.setClipChildren(false); panel.setClipToPadding(false);
        TextView hd = text(header, 16, fMed, 0xFFFFFFFF); hd.setShadowLayer(0, 0, 0, 0);
        hd.setPadding(dp(12), 0, dp(12), dp(10)); hd.setSingleLine(true); hd.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(hd);
        View first = null;
        for (int i = 0; i < labels.size(); i++) {
            final Runnable a = actions.get(i);
            TextView row = text(labels.get(i), 13, fReg, 0xFFFFFFFF); row.setShadowLayer(0, 0, 0, 0);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setSingleLine(true);
            final GradientDrawable rb = new GradientDrawable(); rb.setCornerRadius(dp(12)); rb.setColor(0x00FFFFFF);
            row.setBackground(rb);
            row.setFocusable(true); row.setClickable(true);
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() { public void onFocusChange(View v, boolean has) {
                rb.setColor(has ? 0xFFFFFFFF : 0x00FFFFFF); ((TextView) v).setTextColor(has ? 0xFF111111 : 0xFFFFFFFF); } });
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeOverlay(ov, false); a.run(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(2), 0, dp(2));
            panel.addView(row, lp);
            if (first == null) first = row;
        }
        FrameLayout.LayoutParams lpp = new FrameLayout.LayoutParams(dp(360), -2, Gravity.CENTER);
        ov.addView(panel, lpp);
        openOverlay(ov, first);
    }

    interface Picker { void picked(String pkg); }

    /** Liste de toutes les applis. picker == null : ouvre l'appli ; sinon renvoie l'appli choisie. */
    void showApps(final Picker picker) {
        final PackageManager pm = getPackageManager();
        LinkedHashMap<String, ResolveInfo> map = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN); q.addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (!map.containsKey(p) && !p.equals(getPackageName()) && !p.equals("tv.zenhome")) map.put(p, ri);
            }
        }
        List<ResolveInfo> list = new ArrayList<>(map.values());
        Collections.sort(list, new java.util.Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) { return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()); } });
        final FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0xF0101216);
        ov.setClickable(true);
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(5);
        g.setPadding(dp(60), dp(90), dp(60), dp(60));
        g.setClipChildren(false); g.setClipToPadding(false);
        View first = null;
        for (ResolveInfo ri : list) {
            final String pkg = ri.activityInfo.packageName;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
            Drawable ban = ri.activityInfo.loadBanner(pm);
            if (ban == null) ban = ri.loadIcon(pm);
            ImageView iv = new ImageView(this); iv.setImageDrawable(ban); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(iv, new LinearLayout.LayoutParams(dp(150), dp(84)));
            TextView tv = text(ri.loadLabel(pm).toString(), 11, fReg, 0xFFFFFFFF);
            tv.setPadding(0, dp(6), 0, 0); tv.setSingleLine(true); tv.setGravity(Gravity.CENTER); tv.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(tv, new LinearLayout.LayoutParams(dp(150), -2));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.setMargins(dp(10), dp(10), dp(10), dp(10));
            cell.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(14)); bg.setColor(0x14FFFFFF); cell.setBackground(bg);
            cell.setTag(14);
            wire(cell, new Runnable() { public void run() {
                closeOverlay(ov, picker == null);
                if (picker != null) picker.picked(pkg); else launch(pkg); } }, 1.08f);
            if (picker == null) {
                cell.setLongClickable(true);
                cell.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) {
                    List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
                    l.add(t("Ouvrir", "Open")); a.add(new Runnable() { public void run() { closeOverlay(ov, false); launch(pkg); } });
                    if (!dockPkgs.contains(pkg) && dockPkgs.size() < layout) {
                        l.add(t("Ajouter au dock", "Add to dock")); a.add(new Runnable() { public void run() {
                            closeOverlay(ov, false); dockPkgs.add(pkg); saveConfig(); buildDock(dockPkgs.indexOf(pkg)); } });
                    } else if (!dockPkgs.contains(pkg)) {
                        l.add(t("Dock plein : remplacer une appli…", "Dock full: replace an app…")); a.add(new Runnable() { public void run() {
                            closeOverlay(ov, false); chooseSlotFor(pkg); } });
                    }
                    showMenu(appLabel(pkg), l, a); return true; } });
            }
            g.addView(cell, lp);
            if (first == null) first = cell;
        }
        TextView hdr = text(picker == null ? t("Toutes les applications", "All apps") : t("Choisissez une appli", "Choose an app"), 22, fMed, 0xFFFFFFFF);
        FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lh.setMargins(dp(70), dp(30), 0, 0);
        if (picker == null) {
            TextView tip = text(t("Appui long sur OK : ajouter au dock", "Long-press OK: add to dock"), 11, fReg, 0x99FFFFFF);
            FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lt.setMargins(0, dp(40), dp(70), 0);
            ov.addView(tip, lt);
        }
        sv.addView(g);
        ov.addView(sv, 0, new FrameLayout.LayoutParams(-1, -1));
        ov.addView(hdr, lh);
        openOverlay(ov, first);
    }

    void chooseSlotFor(final String pkg) {
        List<String> l = new ArrayList<>(); List<Runnable> a = new ArrayList<>();
        for (int i = 0; i < dockPkgs.size(); i++) {
            final int idx = i;
            l.add(t("Remplacer ", "Replace ") + appLabel(dockPkgs.get(i)));
            a.add(new Runnable() { public void run() { dockPkgs.set(idx, pkg); saveConfig(); buildDock(idx); } });
        }
        showMenu(appLabel(pkg), l, a);
    }

    /** Seul le menu ou la liste du dessus peut recevoir le focus : les flèches ne doivent jamais sauter vers ce qui est derrière. */
    void updateFocusBlocking() {
        int n = overlays.size();
        dock.setDescendantFocusability(n == 0 ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (int i = 0; i < n; i++) {
            View o = overlays.get(i);
            if (o instanceof ViewGroup)
                ((ViewGroup) o).setDescendantFocusability(i == n - 1 ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }

    void openOverlay(View ov, View focus) {
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        overlays.add(ov);
        updateFocusBlocking();
        ov.setAlpha(0f); ov.animate().alpha(1f).setDuration(160).start();
        if (focus != null) focus.requestFocus();
    }

    void closeOverlay(View ov, boolean refocus) {
        root.removeView(ov);
        overlays.remove(ov);
        updateFocusBlocking();
        if (refocus) {
            if (!overlays.isEmpty()) overlays.get(overlays.size() - 1).requestFocus();
            else focusDock();
        }
    }

    void closeTopOverlay() {
        if (overlays.isEmpty()) return;
        View ov = overlays.get(overlays.size() - 1);
        closeOverlay(ov, true);
    }

    void focusDock() {
        if (!bigTiles.isEmpty()) bigTiles.get(0).requestFocus();
        else if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { closeTopOverlay(); return true; } // l'accueil ne se ferme jamais
        // boutons d'applis de la telecommande TCL (si le service KeyService n'est pas actif)
        String keyApp = KeyService.appForKey(code);
        if (keyApp != null && overlays.isEmpty() && moveIndex < 0 && launchIntent(keyApp) != null) {
            if (e.getRepeatCount() == 0) launch(keyApp);
            return true;
        }
        // touche Source / TV : sur certaines TV, seul l'accueil d'origine la gerait
        if ((code == KeyEvent.KEYCODE_TV_INPUT || code == KeyEvent.KEYCODE_TV) && overlays.isEmpty() && moveIndex < 0) {
            openInputs();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (!overlays.isEmpty()) return true;
            View f = getCurrentFocus();
            int idx = f != null ? bigTiles.indexOf(f) : -1;
            showTileMenu(idx);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() { closeTopOverlay(); }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // touche Accueil : ferme tout ce qui est ouvert
        if (moveIndex >= 0) endMove();
        while (!overlays.isEmpty()) closeOverlay(overlays.get(overlays.size() - 1), false);
        focusDock();
    }

    // =====================================================================
    // Lancements
    String[] findInput() {
        for (String[] in : KNOWN_INPUTS) {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(in[0], 0);
                if (!ai.enabled) continue;
                if (in[1] != null || launchIntent(in[0]) != null) return in;
            } catch (Exception ignored) { }
        }
        return null;
    }

    void openInput(String[] in) {
        if (in[1] != null) {
            try {
                Intent i = new Intent(); i.setComponent(new ComponentName(in[0], in[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return;
            } catch (Exception ignored) { }
        }
        launch(in[0]);
    }

    /** Premier ecran du systeme qui gere l'action « voir les entrees » (null s'il n'y en a pas). */
    ComponentName viewInputsTarget() {
        try {
            java.util.List<android.content.pm.ResolveInfo> l = getPackageManager().queryIntentActivities(
                    new Intent("com.android.tv.action.VIEW_INPUTS"), 0);
            if (l != null) for (android.content.pm.ResolveInfo ri : l) {
                if (ri.activityInfo != null && ri.activityInfo.enabled)
                    return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Menu des entrees du systeme s'il existe, sinon l'appli TV de la marque. */
    void openInputs() {
        Intent vi = new Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ComponentName target = viewInputsTarget();
        if (target != null) {
            // on vise l'écran trouvé : sinon, quand plusieurs applis le gèrent (accueil Google + XGIMI), Android affiche un choix
            try { startActivity(new Intent(vi).setComponent(target)); return; } catch (Exception ignored) { }
        }
        if (vi.resolveActivity(getPackageManager()) != null) {
            try { startActivity(vi); return; } catch (Exception ignored) { }
        }
        String[] in = findInput();
        if (in != null) openInput(in);
        else toast(t("Aucun menu des entrées trouvé sur cette TV", "No inputs menu found on this TV"));
    }

    Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    void launch(String pkg) {
        Intent i = launchIntent(pkg);
        if (i == null) { toast(t("Appli introuvable", "App not found")); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (ActivityNotFoundException e) { toast(t("Impossible d'ouvrir", "Cannot open")); }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    ImageView newWall() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    TextView text(String s, float size, Typeface tf, int color) {
        TextView tv = new TextView(this);
        tv.setText(s); tv.setTextColor(color); tv.setTypeface(tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density);
        tv.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    // =====================================================================
    // Fonds d'ecran
    void loadWalls() {
        walls.clear();
        wallsFromFiles = false;
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "ZenHome");
            File[] fs = dir.listFiles();
            if (fs != null) {
                Arrays.sort(fs);
                for (File f : fs) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")))
                        walls.add(f.getAbsolutePath());
                }
            }
        } catch (Throwable ignored) { }
        if (!walls.isEmpty()) { wallsFromFiles = true; }
        else {
            try {
                String[] a = getAssets().list("walls");
                if (a != null) { Arrays.sort(a); for (String n : a) { String l = n.toLowerCase(Locale.ROOT); if (l.endsWith(".jpg") || l.endsWith(".png")) walls.add(n); } }
            } catch (Exception ignored) { }
        }
        wallIdx = walls.isEmpty() ? 0 : (int) ((System.currentTimeMillis() / rotationMs) % walls.size());
    }

    Bitmap decode(String name) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            int maxW = Math.max(getResources().getDisplayMetrics().widthPixels, 1280);
            if (wallsFromFiles) {
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(name, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= maxW) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeFile(name, o);
            }
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            InputStream is = getAssets().open("walls/" + name);
            Bitmap b = BitmapFactory.decodeStream(is, null, o);
            is.close();
            return b;
        } catch (Throwable t) { return null; }
    }

    void showWall(boolean instant) {
        if (walls.isEmpty()) return;
        final ImageView next = aOnTop ? wallB : wallA;
        final ImageView cur = aOnTop ? wallA : wallB;
        Bitmap bm = decode(walls.get(wallIdx));
        if (bm == null) return;
        backdrop.update(bm, instant, FADE_MS);     // copie floutee de cette photo, pour le verre liquide
        Drawable old = next.getDrawable();
        next.setImageBitmap(bm);
        if (old instanceof BitmapDrawable) {
            Bitmap ob = ((BitmapDrawable) old).getBitmap();
            if (ob != null && ob != bm) ob.recycle();
        }
        next.bringToFront(); // uniquement dans le calque des photos
        next.setScaleX(1f); next.setScaleY(1f);
        next.animate().cancel();
        if (instant) { next.setAlpha(1f); cur.setAlpha(0f); }
        else {
            next.setAlpha(0f);
            android.animation.ObjectAnimator.ofFloat(next, "alpha", 0f, 1f).setDuration(FADE_MS).start();
        }
        next.animate().scaleX(1.06f).scaleY(1.06f).setDuration(rotationMs + FADE_MS)
                .setInterpolator(new LinearInterpolator()).start();
        aOnTop = !aOnTop;
    }

    final Runnable rotate = new Runnable() {
        public void run() {
            if (walls.size() > 1) { wallIdx = (wallIdx + 1) % walls.size(); showWall(false); }
            h.postDelayed(this, rotationMs);
        }
    };

    final Runnable tick = new Runnable() {
        public void run() {
            Date n = new Date();
            clock.setText(DateFormat.is24HourFormat(Home.this) ? DateFormat.format("HH:mm", n) : DateFormat.format("h:mm", n));
            Locale loc = Locale.getDefault();
            String d = new SimpleDateFormat(fr ? "EEEE d MMMM" : "EEEE, MMMM d", loc).format(n);
            date.setText(d.substring(0, 1).toUpperCase(loc) + d.substring(1));
            h.postDelayed(this, 15_000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        // la configuration a pu changer via le kit pendant que l'accueil etait en fond
        String rev = setting("zenhome_rev");
        if (rev != null && !rev.equals(prefs.getString("rev", null)) && moveIndex < 0 && overlays.isEmpty()) {
            loadConfig(); applyTitle(); buildDock(0); refreshWeather(true);
        } else {
            refreshWeather(false);     // ne recharge que si la derniere meteo a plus de 30 min
        }
        h.removeCallbacksAndMessages(null);
        h.post(tick);
        h.postDelayed(rotate, rotationMs);
        h.postDelayed(weatherTick, WEATHER_TTL_MS);
    }

    final Runnable weatherTick = new Runnable() {
        public void run() {
            refreshWeather(false);
            h.postDelayed(this, WEATHER_TTL_MS);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null); // rien ne tourne pendant qu'une appli est ouverte
    }

    @Override
    protected void onStop() {
        super.onStop();
        // libere la memoire des photos pendant qu'on regarde une video
        for (ImageView v : new ImageView[]{wallA, wallB}) {
            v.animate().cancel();
            Drawable d = v.getDrawable(); v.setImageDrawable(null);
            if (d instanceof BitmapDrawable) { Bitmap b = ((BitmapDrawable) d).getBitmap(); if (b != null) b.recycle(); }
        }
        released = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (released) { released = false; loadWalls(); showWall(true); }
    }

    /** Icone "toutes les applis" : grille 3x3 centree. */
    static class GridIcon extends Drawable {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        GridIcon() { p.setColor(0xFFFFFFFF); }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float size = Math.min(b.width(), b.height()) * 0.74f;
            float cell = size / 3f, sq = cell * 0.66f, r = sq * 0.3f;
            float x0 = b.exactCenterX() - size / 2f, y0 = b.exactCenterY() - size / 2f;
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                float x = x0 + j * cell + (cell - sq) / 2f, y = y0 + i * cell + (cell - sq) / 2f;
                c.drawRoundRect(x, y, x + sq, y + sq, r, r, p);
            }
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
