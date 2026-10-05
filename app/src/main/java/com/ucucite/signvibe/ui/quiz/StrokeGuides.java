package com.ucucite.signvibe.ui.quiz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard stroke order for the tracing quiz: which strokes make each capital
 * letter and digit, in what order, and in which direction.
 *
 * Each glyph is a tiny path script. Coordinates run 0..100 inside the glyph's
 * tight ink box (x to the right, y downward), so they line up with whatever
 * size TracingView draws the guide at. They were fitted to Roboto Bold, the
 * typeface Android uses for Typeface.create(SANS_SERIF, BOLD).
 *
 *   M x y                        move to (starts a stroke)
 *   L x y                        straight line to
 *   A cx cy rx ry start sweep    elliptical arc in degrees: 0 = right,
 *                                90 = down, positive sweep = clockwise
 *   |                            ends this stroke and starts the next one
 *
 * Order follows the common ball-and-stick manuscript style: verticals
 * top-to-bottom first, then lines left-to-right, round letters counter-
 * clockwise from the top.
 *
 * Plain Java (no Android imports) so the parsing can be unit-tested on the JVM.
 */
final class StrokeGuides {

    private static final Map<Character, String> SPECS = new HashMap<>();

    static {
        SPECS.put('A', "M 50 6 L 11 95 | M 50 6 L 89 95 | M 30 71 L 70 71");
        SPECS.put('B', "M 11 5 L 11 95 | M 11 8 L 50 8 A 50 28.5 32 20.5 270 180 A 50 70.5 36 21.5 270 180 L 11 92");
        SPECS.put('C', "A 50 50 37.5 41.5 325 -290");
        SPECS.put('D', "M 13 5 L 13 95 | M 13 8 L 45 8 A 45 50 41 42 270 180 L 13 92");
        SPECS.put('E', "M 15 5 L 15 95 | M 15 8 L 92 8 | M 15 48 L 82 48 | M 15 92 L 92 92");
        SPECS.put('F', "M 16 5 L 16 95 | M 16 8 L 92 8 | M 16 50 L 84 50");
        SPECS.put('G', "A 50 50 37.5 41.5 325 -305 L 87 56 L 58 56");
        SPECS.put('H', "M 12 5 L 12 95 | M 88 5 L 88 95 | M 12 48 L 88 48");
        SPECS.put('I', "M 50 5 L 50 95");
        SPECS.put('J', "M 85 5 L 85 62 A 50 62 35 30 0 160");
        SPECS.put('K', "M 12 5 L 12 95 | M 84 5 L 27 62 | M 47 53 L 85 95");
        SPECS.put('L', "M 13 5 L 13 92 L 94 92");
        SPECS.put('M', "M 9 5 L 9 95 | M 12 6 L 50 90 L 88 6 L 90 95");
        SPECS.put('N', "M 12 5 L 12 95 | M 12 6 L 88 94 L 88 5");
        SPECS.put('O', "A 50 50 37.5 41.5 270 -340");
        SPECS.put('P', "M 13 5 L 13 95 | M 13 8 L 50 8 A 50 32 36 24 270 180 L 13 56");
        SPECS.put('Q', "A 50 43 37 36 270 -340 | M 62 82 L 92 97");
        SPECS.put('R', "M 13 5 L 13 95 | M 13 8 L 50 8 A 50 31 32 23 270 180 L 86 95");
        SPECS.put('S', "A 50 28 33 20 340 -205 L 68 53 A 50 71 36 21 300 220");
        SPECS.put('T', "M 5 8 L 95 8 | M 50 8 L 50 95");
        SPECS.put('U', "M 13 5 L 13 62 A 50 62 37 30 180 -180 L 87 5");
        SPECS.put('V', "M 12 5 L 50 94 L 88 5");
        SPECS.put('W', "M 9 5 L 28 94 L 50 8 L 72 94 L 91 5");
        SPECS.put('X', "M 13 5 L 87 95 | M 87 5 L 13 95");
        SPECS.put('Y', "M 13 5 L 50 55 | M 87 5 L 50 55 L 50 95");
        SPECS.put('Z', "M 5 8 L 88 8 L 12 92 L 95 92");

        SPECS.put('0', "A 50 50 35 42.5 270 -340");
        SPECS.put('1', "M 4 22 L 78 5 L 78 95");
        SPECS.put('2', "A 48 30 36 22 190 200 L 12 90 L 95 92");
        SPECS.put('3', "A 48 29 36 21 200 250 A 50 71 36 21.5 270 255");
        SPECS.put('4', "M 66 5 L 6 70 L 95 70 | M 73 5 L 73 95");
        SPECS.put('5', "M 17 5 L 14 50 A 50 68 35 24 225 300 | M 17 8 L 93 8");
        SPECS.put('6', "M 72 8 L 51 10 A 64 62 50 54 255 -75 A 50 66 36 26 180 -350");
        SPECS.put('7', "M 5 8 L 90 8 L 32 95");
        SPECS.put('8', "A 50 27.5 31 20 270 -180 A 50 71 36 21 270 355 A 50 27.5 31 20 90 -170");
        SPECS.put('9', "A 50 33 35 25 350 -350 L 85 70 A 50 70 35 22 0 115");
    }

    private StrokeGuides() { /* no instances */ }

    /**
     * The strokes for one character, each as a flat {x0, y0, x1, y1, ...} array
     * in 0..100 glyph-box units, in writing order. Empty when we have no guide
     * for the character (e.g. lowercase or punctuation) — the quiz still works,
     * just without arrows.
     */
    static List<float[]> strokesFor(char c) {
        String spec = SPECS.get(c);
        if (spec == null) return Collections.emptyList();
        return parse(spec);
    }

    static List<float[]> parse(String spec) {
        List<float[]> strokes = new ArrayList<>();
        for (String part : spec.split("\\|")) {
            String[] t = part.trim().split("\\s+");
            List<Float> pts = new ArrayList<>();
            int i = 0;
            while (i < t.length) {
                String cmd = t[i];
                if (cmd.equals("M") || cmd.equals("L")) {
                    pts.add(Float.parseFloat(t[i + 1]));
                    pts.add(Float.parseFloat(t[i + 2]));
                    i += 3;
                } else if (cmd.equals("A")) {
                    float cx = Float.parseFloat(t[i + 1]);
                    float cy = Float.parseFloat(t[i + 2]);
                    float rx = Float.parseFloat(t[i + 3]);
                    float ry = Float.parseFloat(t[i + 4]);
                    float start = Float.parseFloat(t[i + 5]);
                    float sweep = Float.parseFloat(t[i + 6]);
                    i += 7;
                    // Sample the arc every ~4 degrees — smooth at any screen size.
                    int n = Math.max(8, (int) (Math.abs(sweep) / 4f));
                    for (int k = 0; k <= n; k++) {
                        double a = Math.toRadians(start + sweep * k / (double) n);
                        pts.add((float) (cx + rx * Math.cos(a)));
                        pts.add((float) (cy + ry * Math.sin(a)));
                    }
                } else {
                    i++;   // unknown token: skip rather than crash the quiz
                }
            }
            if (pts.size() >= 4) {
                float[] arr = new float[pts.size()];
                for (int k = 0; k < arr.length; k++) arr[k] = pts.get(k);
                strokes.add(arr);
            }
        }
        return strokes;
    }
}
