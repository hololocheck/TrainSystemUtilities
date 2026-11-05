package com.trainsystemutilities.network;

/**
 * A string that must arrive byte for byte - a name the server compares with the world's (a station, a preset file),
 * a serialised NBT - travels in a manta:data {@code exact} field or param (manta 2.66.0, MANTA_7_CONCEPT U8): its
 * UTF-8 as given, where a {@code string} is NFC-normalised on encode and a name typed in decomposed form (e + U+0301)
 * would reach the server as another name. Until that type such a value travelled as the base64 of its UTF-8 in a
 * {@code string}.
 *
 * <p>The codec refuses an exact value past its maxLen in UTF-8 bytes, and one with no UTF-8 form at all (an unpaired
 * surrogate), and {@code Mirror.send} / {@code Host.set} throw on a refused value. What is here is the sender's side of
 * those two bounds.</p>
 */
public final class ExactText {

    private ExactText() {
    }

    /** The UTF-8 length of {@code s} as an exact string travels, or -1 when it has none (an unpaired surrogate). */
    public static int utf8Length(String s) {
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                return -1;
            }
            bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            i += Character.charCount(cp);
        }
        return bytes;
    }

    /**
     * {@code s} cut at a code point so its UTF-8 (as it is - not normalised) fits {@code maxBytes}. An unpaired
     * surrogate has no UTF-8 form and is left out, so the result is always a value an exact declaration of
     * {@code maxBytes} carries.
     */
    public static String cut(String s, int maxBytes) {
        int length = utf8Length(s);
        if (length >= 0 && length <= maxBytes) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                continue;
            }
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > maxBytes) {
                break;
            }
            out.appendCodePoint(cp);
            bytes += len;
        }
        return out.toString();
    }
}
