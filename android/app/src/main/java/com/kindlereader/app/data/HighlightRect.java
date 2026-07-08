package com.kindlereader.app.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalized rectangle (0..1 of page width/height), matching the
 * `Highlight.rects[]` shape in docs/API.md.
 */
public class HighlightRect {

    public double x;
    public double y;
    public double w;
    public double h;

    public HighlightRect() {
    }

    public HighlightRect(double x, double y, double w, double h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("x", x);
        o.put("y", y);
        o.put("w", w);
        o.put("h", h);
        return o;
    }

    public static HighlightRect fromJson(JSONObject o) throws JSONException {
        return new HighlightRect(o.optDouble("x", 0), o.optDouble("y", 0),
                o.optDouble("w", 0), o.optDouble("h", 0));
    }

    public static String listToJsonString(List<HighlightRect> rects) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < rects.size(); i++) {
            try {
                arr.put(rects.get(i).toJson());
            } catch (JSONException ignored) {
            }
        }
        return arr.toString();
    }

    public static JSONArray listToJsonArray(List<HighlightRect> rects) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < rects.size(); i++) {
            try {
                arr.put(rects.get(i).toJson());
            } catch (JSONException ignored) {
            }
        }
        return arr;
    }

    public static List<HighlightRect> listFromJsonString(String json) {
        List<HighlightRect> out = new ArrayList<HighlightRect>();
        if (json == null || json.length() == 0) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                out.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static List<HighlightRect> listFromJsonArray(JSONArray arr) {
        List<HighlightRect> out = new ArrayList<HighlightRect>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            try {
                out.add(fromJson(arr.getJSONObject(i)));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }
}
