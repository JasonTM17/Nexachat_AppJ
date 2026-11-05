package com.nexachat.core.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure presentation logic for search query highlight range computation (F4).
 * Performs case-insensitive and diacritic-insensitive matching with 1:1 character
 * index alignment so returned ranges directly index the original string.
 * Written in Java per the project owner language rule.
 */
public final class SearchHighlightHelper {

    private SearchHighlightHelper() {}

    public static final class HighlightRange {
        private final int start;
        private final int end;

        public HighlightRange(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HighlightRange)) return false;
            HighlightRange that = (HighlightRange) o;
            return start == that.start && end == that.end;
        }

        @Override
        public int hashCode() {
            return 31 * start + end;
        }

        @Override
        public String toString() {
            return "[" + start + ", " + end + ")";
        }
    }

    /**
     * Finds all occurrence ranges of [query] in [text], ignoring case and diacritics.
     */
    public static List<HighlightRange> findHighlightRanges(String text, String query) {
        if (text == null || query == null) {
            return Collections.emptyList();
        }
        String trimmedQuery = query.trim();
        if (trimmedQuery.isEmpty()) {
            return Collections.emptyList();
        }
        String foldedText = foldToAscii(text);
        String foldedQuery = foldToAscii(trimmedQuery);
        int queryLen = foldedQuery.length();
        if (queryLen == 0 || queryLen > foldedText.length()) {
            return Collections.emptyList();
        }

        List<HighlightRange> ranges = new ArrayList<>();
        int startIndex = 0;
        while (startIndex <= foldedText.length() - queryLen) {
            int found = foldedText.indexOf(foldedQuery, startIndex);
            if (found < 0) {
                break;
            }
            ranges.add(new HighlightRange(found, found + queryLen));
            startIndex = found + queryLen;
        }
        return Collections.unmodifiableList(ranges);
    }

    /**
     * 1:1 Character-by-character folding to lowercase ASCII preserving string length.
     */
    public static String foldToAscii(String input) {
        if (input == null) return "";
        char[] out = new char[input.length()];
        for (int i = 0; i < input.length(); i++) {
            out[i] = foldChar(input.charAt(i));
        }
        return new String(out);
    }

    public static char foldChar(char c) {
        char ch = Character.toLowerCase(c);
        switch (ch) {
            case 'á': case 'à': case 'ả': case 'ã': case 'ạ':
            case 'ă': case 'ằ': case 'ắ': case 'ẳ': case 'ẵ': case 'ặ':
            case 'â': case 'ầ': case 'ấ': case 'ẩ': case 'ẫ': case 'ậ':
            case 'ä': case 'å': case 'ā': case 'ą':
                return 'a';
            case 'đ':
                return 'd';
            case 'é': case 'è': case 'ẻ': case 'ẽ': case 'ẹ':
            case 'ê': case 'ề': case 'ế': case 'ể': case 'ễ': case 'ệ':
            case 'ë': case 'ē': case 'ę':
                return 'e';
            case 'í': case 'ì': case 'ỉ': case 'ĩ': case 'ị':
            case 'î': case 'ï': case 'ī':
                return 'i';
            case 'ó': case 'ò': case 'ỏ': case 'õ': case 'ọ':
            case 'ô': case 'ồ': case 'ố': case 'ổ': case 'ỗ': case 'ộ':
            case 'ơ': case 'ờ': case 'ớ': case 'ở': case 'ỡ': case 'ợ':
            case 'ö': case 'ø': case 'ō':
                return 'o';
            case 'ú': case 'ù': case 'ủ': case 'ũ': case 'ụ':
            case 'ư': case 'ừ': case 'ứ': case 'ử': case 'ữ': case 'ự':
            case 'ü': case 'ū':
                return 'u';
            case 'ý': case 'ỳ': case 'ỷ': case 'ỹ': case 'ỵ':
            case 'ÿ':
                return 'y';
            default:
                return ch;
        }
    }
}
