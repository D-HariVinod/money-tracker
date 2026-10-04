package io.github.dharivinod.moneytracker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the shake pop-up and the page share. The pop-up never touches the page's data: it leaves
 * its entries here, and the page collects them the next time it is open and unlocked.
 */
final class Quick {
    private static final String PREFS = "money", KEY_PENDING = "pending", KEY_CATS = "cats";

    private Quick() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void add(Context c, JSONObject entry) {
        JSONArray list;
        try {
            list = new JSONArray(prefs(c).getString(KEY_PENDING, "[]"));
        } catch (JSONException e) {
            list = new JSONArray();
        }
        list.put(entry);
        prefs(c).edit().putString(KEY_PENDING, list.toString()).commit();
    }

    /** Hands over the waiting entries as a JSON array and forgets them. */
    static synchronized String take(Context c) {
        String list = prefs(c).getString(KEY_PENDING, "[]");
        if (!"[]".equals(list)) prefs(c).edit().remove(KEY_PENDING).commit();
        return list;
    }

    /** {"Expense": [[name, emoji], ...], "Income": [...]}, in the order the page wants them shown. */
    static void setCategories(Context c, String json) {
        prefs(c).edit().putString(KEY_CATS, json).apply();
    }

    static String categories(Context c) {
        return prefs(c).getString(KEY_CATS, "");
    }
}
