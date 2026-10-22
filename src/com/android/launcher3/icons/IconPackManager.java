package com.android.launcher3.icons;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bluenixx: suporte a icon packs (appfilter.xml no formato ADW/Nova/Apex). */
public final class IconPackManager {
    private static final String TAG = "IconPackManager";
    public static final String PREF_KEY = "pref_icon_pack";
    private static final String[] ACTIONS = {
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.gau.go.launcherex.theme",
    };

    public static final class PackInfo {
        public final String packageName;
        public final CharSequence label;

        PackInfo(String packageName, CharSequence label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private static String sLoadedPack = null;
    private static Resources sPackRes = null;
    private static final Map<String, String> sMap = new ArrayMap<>();

    private IconPackManager() { }

    /** Lista os icon packs instalados, ordenados pelo nome. */
    public static List<PackInfo> getInstalledPacks(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, PackInfo> found = new ArrayMap<>();
        for (String action : ACTIONS) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(action), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!found.containsKey(pkg)) {
                    found.put(pkg, new PackInfo(pkg, ri.loadLabel(pm)));
                }
            }
        }
        List<PackInfo> list = new ArrayList<>(found.values());
        list.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        return list;
    }

    /** Pacote do pack ativo ("" = padrão). Le direto do SharedPreferences do Launcher. */
    public static String getActivePack(Context c) {
        return c.getSharedPreferences(
                com.android.launcher3.LauncherFiles.SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE).getString(PREF_KEY, "");
    }

    /** Acrescenta o pack ao codigo de tema para invalidar o cache de icones ao trocar. */
    public static String appendState(String themeCode, Context c) {
        String pack = getActivePack(c);
        return TextUtils.isEmpty(pack) ? themeCode : themeCode + "|iconpack=" + pack;
    }

    /** Forca reler o appfilter.xml (ex.: pack atualizado). */
    public static synchronized void invalidate() {
        sLoadedPack = null;
    }

    /** Ícone do pack para o componente, ou null se não houver pack ou ícone. */
    public static synchronized Drawable getIcon(Context c, ComponentName cn) {
        String pack = getActivePack(c);
        if (TextUtils.isEmpty(pack) || cn == null) return null;
        ensureLoaded(c, pack);
        if (sPackRes == null) return null;
        String name = sMap.get(cn.flattenToString());
        if (name == null) return null;
        int id = sPackRes.getIdentifier(name, "drawable", pack);
        if (id == 0) id = sPackRes.getIdentifier(name, "mipmap", pack);
        if (id == 0) return null;
        try {
            return sPackRes.getDrawableForDensity(
                    id, c.getResources().getDisplayMetrics().densityDpi, null);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    private static void ensureLoaded(Context c, String pack) {
        if (pack.equals(sLoadedPack)) return;
        sMap.clear();
        sPackRes = null;
        sLoadedPack = pack;
        InputStream in = null;
        XmlPullParser parser = null;
        try {
            Resources res = c.getPackageManager().getResourcesForApplication(pack);
            int xmlId = res.getIdentifier("appfilter", "xml", pack);
            if (xmlId != 0) {
                parser = res.getXml(xmlId);
            } else {
                in = res.getAssets().open("appfilter.xml");
                parser = XmlPullParserFactory.newInstance().newPullParser();
                parser.setInput(in, null);
            }
            parse(parser);
            sPackRes = res;
        } catch (Exception e) {
            Log.e(TAG, "Falha ao ler appfilter de " + pack, e);
        } finally {
            try {
                if (parser instanceof XmlResourceParser) ((XmlResourceParser) parser).close();
                if (in != null) in.close();
            } catch (Exception ignored) { }
        }
    }

    private static void parse(XmlPullParser parser) throws Exception {
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG || !"item".equals(parser.getName())) continue;
            String comp = parser.getAttributeValue(null, "component");
            String drawable = parser.getAttributeValue(null, "drawable");
            if (comp == null || drawable == null) continue;
            if (!comp.startsWith("ComponentInfo{") || !comp.endsWith("}")) continue;
            ComponentName cn = ComponentName.unflattenFromString(
                    comp.substring("ComponentInfo{".length(), comp.length() - 1));
            if (cn != null) sMap.put(cn.flattenToString(), drawable);
        }
    }
}
