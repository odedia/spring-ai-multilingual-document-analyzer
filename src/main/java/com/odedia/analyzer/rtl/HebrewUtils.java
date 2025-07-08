package com.odedia.analyzer.rtl;

import java.text.Bidi;
import java.util.HashMap;
import java.util.Map;

public class HebrewUtils {

    // Simple map to handle mirrored punctuation when reversing RTL runs
    private static final Map<Character, Character> MIRROR_MAP = new HashMap<>();

    static {
        MIRROR_MAP.put('(', ')');
        MIRROR_MAP.put(')', '(');
        MIRROR_MAP.put('[', ']');
        MIRROR_MAP.put(']', '[');
        MIRROR_MAP.put('{', '}');
        MIRROR_MAP.put('}', '{');
        // Add more mirrored pairs if needed
    }

    /**
     * Converts visual-order Hebrew text (e.g., from PDFBox) into logical-order text
     * suitable for correct rendering and storage.
     *
     * @param visual the visually-ordered string (likely LTR output from PDFBox)
     * @return logical-order string for correct RTL rendering
     */
    public static String toLogical(String visual) {
        Bidi bidi = new Bidi(visual, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        StringBuilder out = new StringBuilder(visual.length());
        int runs = bidi.getRunCount();

        for (int i = 0; i < runs; i++) {
            int start = bidi.getRunStart(i);
            int limit = bidi.getRunLimit(i);
            int level = bidi.getRunLevel(i);

            if ((level & 1) == 1) {
                // RTL run - reverse characters, apply mirroring if needed
                for (int p = limit - 1; p >= start; p--) {
                    char c = visual.charAt(p);
                    out.append(MIRROR_MAP.getOrDefault(c, c));
                }
            } else {
                // LTR run - append as-is
                out.append(visual, start, limit);
            }
        }

        return out.toString();
    }
}
