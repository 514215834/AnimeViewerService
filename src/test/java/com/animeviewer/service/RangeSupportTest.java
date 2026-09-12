package com.animeviewer.service;

import com.animeviewer.service.stream.RangeSupport;
import com.animeviewer.service.stream.RangeSupport.Full;
import com.animeviewer.service.stream.RangeSupport.Range;
import com.animeviewer.service.stream.RangeSupport.Unsatisfiable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** S4 Range 头解析用例 */
class RangeSupportTest {

    private static final long SIZE = 1000;

    @Test
    void noHeaderIsFull() {
        assertInstanceOf(Full.class, RangeSupport.parse(null, SIZE));
        assertInstanceOf(Full.class, RangeSupport.parse("", SIZE));
        assertInstanceOf(Full.class, RangeSupport.parse("bytes=abc", SIZE));
        assertInstanceOf(Full.class, RangeSupport.parse("items=0-10", SIZE));
    }

    @Test
    void openEnd() {
        Range r = (Range) RangeSupport.parse("bytes=500-", SIZE);
        assertEquals(500, r.start());
        assertEquals(999, r.end());
    }

    @Test
    void closedRange() {
        Range r = (Range) RangeSupport.parse("bytes=0-499", SIZE);
        assertEquals(0, r.start());
        assertEquals(499, r.end());
    }

    @Test
    void endClampedToSize() {
        Range r = (Range) RangeSupport.parse("bytes=100-99999", SIZE);
        assertEquals(100, r.start());
        assertEquals(999, r.end());
    }

    @Test
    void suffixRange() {
        Range r = (Range) RangeSupport.parse("bytes=-200", SIZE);
        assertEquals(800, r.start());
        assertEquals(999, r.end());
        // 后缀超过全长 → 整个文件
        Range r2 = (Range) RangeSupport.parse("bytes=-99999", SIZE);
        assertEquals(0, r2.start());
        assertEquals(999, r2.end());
    }

    @Test
    void unsatisfiable() {
        assertInstanceOf(Unsatisfiable.class, RangeSupport.parse("bytes=1000-", SIZE));
        assertInstanceOf(Unsatisfiable.class, RangeSupport.parse("bytes=2000-3000", SIZE));
    }

    @Test
    void multiRangeTakesFirst() {
        Range r = (Range) RangeSupport.parse("bytes=0-9,500-599", SIZE);
        assertEquals(0, r.start());
        assertEquals(9, r.end());
    }

    @Test
    void invertedRangeFallsBackToFull() {
        assertInstanceOf(Full.class, RangeSupport.parse("bytes=500-100", SIZE));
    }
}
