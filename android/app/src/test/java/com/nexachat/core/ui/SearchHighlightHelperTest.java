package com.nexachat.core.ui;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SearchHighlightHelperTest {

    @Test
    public void whenQueryEmptyReturnsEmptyList() {
        assertTrue(SearchHighlightHelper.findHighlightRanges("Hello World", "").isEmpty());
        assertTrue(SearchHighlightHelper.findHighlightRanges("Hello World", "   ").isEmpty());
        assertTrue(SearchHighlightHelper.findHighlightRanges("Hello World", null).isEmpty());
        assertTrue(SearchHighlightHelper.findHighlightRanges(null, "query").isEmpty());
    }

    @Test
    public void whenCaseMatches() {
        String text = "Quick brown fox jumps over the lazy dog";
        List<SearchHighlightHelper.HighlightRange> ranges = SearchHighlightHelper.findHighlightRanges(text, "brown");
        assertEquals(1, ranges.size());
        assertEquals(6, ranges.get(0).getStart());
        assertEquals(11, ranges.get(0).getEnd());
        assertEquals("brown", text.substring(ranges.get(0).getStart(), ranges.get(0).getEnd()));
    }

    @Test
    public void whenCaseMismatchMatchesCaseInsensitively() {
        String text = "Quick BROWN fox jumps over";
        List<SearchHighlightHelper.HighlightRange> ranges = SearchHighlightHelper.findHighlightRanges(text, "brown");
        assertEquals(1, ranges.size());
        assertEquals(6, ranges.get(0).getStart());
        assertEquals(11, ranges.get(0).getEnd());
        assertEquals("BROWN", text.substring(ranges.get(0).getStart(), ranges.get(0).getEnd()));
    }

    @Test
    public void whenVietnameseDiacriticsMismatchMatchesDiacriticInsensitively() {
        String text = "Chào An! Hôm nay bạn có đi học không?";
        // Search without accents: "chao"
        List<SearchHighlightHelper.HighlightRange> ranges = SearchHighlightHelper.findHighlightRanges(text, "chao");
        assertEquals(1, ranges.size());
        assertEquals(0, ranges.get(0).getStart());
        assertEquals(4, ranges.get(0).getEnd());
        assertEquals("Chào", text.substring(ranges.get(0).getStart(), ranges.get(0).getEnd()));

        // Search "hom nay"
        ranges = SearchHighlightHelper.findHighlightRanges(text, "hom nay");
        assertEquals(1, ranges.size());
        assertEquals(9, ranges.get(0).getStart());
        assertEquals(16, ranges.get(0).getEnd());
        assertEquals("Hôm nay", text.substring(ranges.get(0).getStart(), ranges.get(0).getEnd()));
    }

    @Test
    public void whenMultipleOccurrencesFindsAll() {
        String text = "test abc test def TEST";
        List<SearchHighlightHelper.HighlightRange> ranges = SearchHighlightHelper.findHighlightRanges(text, "test");
        assertEquals(3, ranges.size());
        assertEquals(new SearchHighlightHelper.HighlightRange(0, 4), ranges.get(0));
        assertEquals(new SearchHighlightHelper.HighlightRange(9, 13), ranges.get(1));
        assertEquals(new SearchHighlightHelper.HighlightRange(18, 22), ranges.get(2));
    }
}
