package com.nexachat.core.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.crypto.SafetyCode.DeviceIdentity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Safety-number ordering, stability and sensitivity rules. */
public final class SafetyCodeTest {

    private static final DeviceIdentity ALICE_PHONE =
            new DeviceIdentity("u-alice", "d-alice-1", "AAAA");
    private static final DeviceIdentity ALICE_TAB =
            new DeviceIdentity("u-alice", "d-alice-2", "BBBB");
    private static final DeviceIdentity BOB_PHONE =
            new DeviceIdentity("u-bob", "d-bob-1", "CCCC");

    private static List<DeviceIdentity> both() {
        return List.of(ALICE_PHONE, ALICE_TAB, BOB_PHONE);
    }

    @Test
    public void shapeIsTwelveGroupsOfFiveDigits() {
        String code = SafetyCode.compute(both());
        String[] groups = code.split(" ");
        assertEquals(12, groups.length);
        for (String group : groups) {
            assertEquals(5, group.length());
            assertTrue(group.chars().allMatch(Character::isDigit));
        }
    }

    @Test
    public void inputOrderNeverChangesTheCode() {
        String canonical = SafetyCode.compute(both());
        List<DeviceIdentity> shuffled = new ArrayList<>(both());
        Collections.reverse(shuffled);
        assertEquals(canonical, SafetyCode.compute(shuffled));
        // Subset order also does not matter.
        assertEquals(SafetyCode.compute(List.of(ALICE_PHONE, BOB_PHONE)),
                SafetyCode.compute(List.of(BOB_PHONE, ALICE_PHONE)));
    }

    @Test
    public void keyChangeChangesTheCode() {
        String before = SafetyCode.compute(both());
        DeviceIdentity rotated = new DeviceIdentity("u-bob", "d-bob-1", "DDDD");
        assertNotEquals(before,
                SafetyCode.compute(List.of(ALICE_PHONE, ALICE_TAB, rotated)));
        // A different device id (even same key material) changes a sorted line.
        assertNotEquals(before,
                SafetyCode.compute(List.of(ALICE_PHONE, ALICE_TAB,
                        new DeviceIdentity("u-bob", "d-bob-2", "CCCC"))));
    }

    @Test
    public void stabilityVectorIsPinned() {
        // Fixed input must always render the same 12 groups.
        String code = SafetyCode.compute(List.of(
                new DeviceIdentity("u1", "d1", "AQID"),
                new DeviceIdentity("u2", "d2", "BAUG")));
        assertEquals(code, SafetyCode.compute(List.of(
                new DeviceIdentity("u2", "d2", "BAUG"),
                new DeviceIdentity("u1", "d1", "AQID"))));
        assertEquals(EXPECTED_VECTOR, code);
    }

    private static final String EXPECTED_VECTOR =
            "28045 29687 40989 88038 46483 12089 77967 09462 08433 15742 02563 09815";
}
