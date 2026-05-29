package com.algo.trade.strategy.oimomentum.v3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests focus on the JSON parser since the HTTP path is best exercised by integration.
 * The parser is the part most likely to break when NSE shifts their response schema.
 */
class FiiDiiApiClientTest {

    private FiiDiiApiClient client;

    @BeforeEach
    void setUp() {
        client = new FiiDiiApiClient();
    }

    @Test
    void parses_nseStandardShape_withDataArray() {
        String json = "{\"data\":["
                + "{\"category\":\"FII/FPI\",\"buyValue\":\"10234.5\",\"sellValue\":\"9876.3\",\"netValue\":\"358.2\",\"date\":\"29-May-2026\"},"
                + "{\"category\":\"DII\",\"buyValue\":\"5500.0\",\"sellValue\":\"6000.0\",\"netValue\":\"-500.0\",\"date\":\"29-May-2026\"}"
                + "]}";
        Optional<FiiDiiApiClient.FiiDiiData> r = client.parseResponse(json);
        assertTrue(r.isPresent());
        assertEquals(358.2, r.get().fiiNetCrore(), 0.001);
        assertEquals(-500.0, r.get().diiNetCrore(), 0.001);
        assertEquals("29-May-2026", r.get().sourceDate());
    }

    @Test
    void parses_whenNetValueMissing_byComputingBuyMinusSell() {
        String json = "{\"data\":["
                + "{\"category\":\"FII/FPI\",\"buyValue\":\"10000\",\"sellValue\":\"9500\"},"
                + "{\"category\":\"DII\",\"buyValue\":\"3000\",\"sellValue\":\"3100\"}"
                + "]}";
        var r = client.parseResponse(json);
        assertTrue(r.isPresent());
        assertEquals(500.0, r.get().fiiNetCrore(), 0.001);
        assertEquals(-100.0, r.get().diiNetCrore(), 0.001);
    }

    @Test
    void parses_topLevelArrayShape() {
        String json = "["
                + "{\"category\":\"FII\",\"netValue\":\"1200.0\"},"
                + "{\"category\":\"DII\",\"netValue\":\"-800.0\"}"
                + "]";
        var r = client.parseResponse(json);
        assertTrue(r.isPresent());
        assertEquals(1200.0, r.get().fiiNetCrore(), 0.001);
        assertEquals(-800.0, r.get().diiNetCrore(), 0.001);
    }

    @Test
    void parses_resultWrapperShape() {
        String json = "{\"result\":["
                + "{\"category\":\"FPI\",\"netValue\":\"100\"},"
                + "{\"category\":\"DII\",\"netValue\":\"200\"}"
                + "]}";
        var r = client.parseResponse(json);
        assertTrue(r.isPresent());
        assertEquals(100.0, r.get().fiiNetCrore(), 0.001);
        assertEquals(200.0, r.get().diiNetCrore(), 0.001);
    }

    @Test
    void tolerates_commaSeparatedNumbers() {
        String json = "{\"data\":["
                + "{\"category\":\"FII/FPI\",\"netValue\":\"12,345.67\"}"
                + "]}";
        var r = client.parseResponse(json);
        assertTrue(r.isPresent());
        assertEquals(12345.67, r.get().fiiNetCrore(), 0.001);
    }

    @Test
    void tolerates_lowercaseKeys() {
        String json = "{\"data\":["
                + "{\"category\":\"fii/fpi\",\"net_value\":\"50\"}"
                + "]}";
        var r = client.parseResponse(json);
        assertTrue(r.isPresent());
        assertEquals(50.0, r.get().fiiNetCrore(), 0.001);
    }

    @Test
    void returnsEmpty_whenNoFiiNorDiiFound() {
        String json = "{\"data\":[{\"category\":\"BANKS\",\"netValue\":\"100\"}]}";
        var r = client.parseResponse(json);
        assertFalse(r.isPresent());
    }

    @Test
    void returnsEmpty_onMalformedJson() {
        var r = client.parseResponse("{not valid json}");
        assertFalse(r.isPresent());
    }

    @Test
    void returnsEmpty_onNonArrayData() {
        String json = "{\"data\":\"oops\"}";
        var r = client.parseResponse(json);
        assertFalse(r.isPresent());
    }

    @Test
    void isMeaningful_falseWhenBothNetsZero() {
        var d = new FiiDiiApiClient.FiiDiiData(0, 0, "");
        assertFalse(d.isMeaningful());
    }

    @Test
    void isMeaningful_trueWhenEitherNetNonzero() {
        assertTrue(new FiiDiiApiClient.FiiDiiData(100, 0, "").isMeaningful());
        assertTrue(new FiiDiiApiClient.FiiDiiData(0, -50, "").isMeaningful());
    }
}
