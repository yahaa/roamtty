package com.termux.view;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class RemoteHistoryStateTest {
    @Test public void positionRequestsClampAndOnlyNotifyWhenPositionChanges() {
        RemoteHistoryState state = new RemoteHistoryState();
        List<Integer> requested = new ArrayList<>();

        assertTrue(state.update(12, 4, requested::add));
        assertTrue(state.isActive());
        assertEquals(12, state.rows());
        assertEquals(4, state.position());
        assertFalse(state.requestPosition(4));
        assertTrue(state.requestPosition(20));
        assertTrue(state.requestPosition(-3));
        assertEquals(List.of(12, 0), requested);
    }

    @Test public void updatesApplyRemotePositionAndReplaceListener() {
        RemoteHistoryState state = new RemoteHistoryState();
        List<Integer> first = new ArrayList<>();
        List<Integer> latest = new ArrayList<>();
        state.update(10, 2, first::add);

        assertTrue(state.update(10, 5, latest::add));
        assertEquals(5, state.position());
        assertTrue(state.requestPosition(7));
        assertTrue(first.isEmpty());
        assertEquals(List.of(7), latest);

        assertTrue(state.update(10, 5, null));
        assertFalse(state.isActive());
        assertEquals(0, state.rows());
        assertEquals(0, state.position());
        assertFalse(state.requestPosition(1));
    }
}
