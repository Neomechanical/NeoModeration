package com.neomechanical.neomoderation.platform;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.moderation.CaseLog;
import com.neomechanical.neomoderation.moderation.MonitorStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsRegistrarTest {

    private NeoModerationPlugin plugin;
    private MonitorStats monitorStats;
    private CaseLog caseLog;

    @BeforeEach
    void setUp() {
        plugin = mock(NeoModerationPlugin.class);
        monitorStats = mock(MonitorStats.class);
        caseLog = mock(CaseLog.class);

        when(plugin.monitorStats()).thenReturn(monitorStats);
        when(plugin.caseLog()).thenReturn(caseLog);
        when(caseLog.isAvailable()).thenReturn(true);
    }

    @Test
    void usesPersistentCountWhenInMemoryIsZeroAfterRestart() {
        // Right after a nightly server restart, monitorStats is 0 while caseLog holds persistent cases
        when(monitorStats.total()).thenReturn(0L);
        when(caseLog.totalCount()).thenReturn(7L);

        assertEquals(7L, MetricsRegistrar.effectiveDetectionTotal(plugin),
                "Should report persistent case total when in-memory count is reset to 0 after server restart");
    }

    @Test
    void usesInMemoryCountWhenHigherThanPersistentCount() {
        // Monitor-only detections or cases pending disk write can cause in-memory count to exceed caseLog
        when(monitorStats.total()).thenReturn(12L);
        when(caseLog.totalCount()).thenReturn(5L);

        assertEquals(12L, MetricsRegistrar.effectiveDetectionTotal(plugin),
                "Should report in-memory count when it exceeds persistent SQLite count");
    }

    @Test
    void handlesNullPluginGracefully() {
        assertEquals(0L, MetricsRegistrar.effectiveDetectionTotal(null));
    }

    @Test
    void handlesNullMonitorStatsGracefully() {
        when(plugin.monitorStats()).thenReturn(null);
        when(caseLog.totalCount()).thenReturn(10L);

        assertEquals(10L, MetricsRegistrar.effectiveDetectionTotal(plugin));
    }

    @Test
    void handlesNullCaseLogGracefully() {
        when(plugin.caseLog()).thenReturn(null);
        when(monitorStats.total()).thenReturn(8L);

        assertEquals(8L, MetricsRegistrar.effectiveDetectionTotal(plugin));
    }

    @Test
    void handlesBothNullGracefully() {
        when(plugin.monitorStats()).thenReturn(null);
        when(plugin.caseLog()).thenReturn(null);

        assertEquals(0L, MetricsRegistrar.effectiveDetectionTotal(plugin));
    }

    @Test
    void handlesUnavailableCaseLogGracefully() {
        when(caseLog.isAvailable()).thenReturn(false);
        when(caseLog.totalCount()).thenReturn(50L);
        when(monitorStats.total()).thenReturn(4L);

        assertEquals(4L, MetricsRegistrar.effectiveDetectionTotal(plugin),
                "Should ignore unavailable CaseLog and fall back to in-memory stats");
    }
}
