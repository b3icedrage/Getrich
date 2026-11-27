package com.pockettrader.app

import com.pockettrader.app.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun parsesEngineAndSocketFrames() {
        val open = SocketIo.parse("""0{"sid":"abc","upgrades":[],"pingInterval":25000,"pingTimeout":20000}""")
        assertEquals(SocketIo.Frame.Open("abc", 25000), open)
        assertEquals(SocketIo.Frame.Ping, SocketIo.parse("2"))
        assertEquals(SocketIo.Frame.Connected("xyz"), SocketIo.parse("""40{"sid":"xyz"}"""))
        assertEquals(SocketIo.Frame.Disconnect, SocketIo.parse("41"))
    }

    @Test fun parsesInlineAndBinaryEvents() {
        val e = SocketIo.parse("""42["successauth",{"id":"x"}]""") as SocketIo.Frame.Event
        assertEquals("successauth", e.name)
        val h = SocketIo.parse("""451-["successupdateBalance",{"_placeholder":true,"num":0}]""")
        assertEquals(SocketIo.Frame.BinaryEventHeader("successupdateBalance", 1), h)
        val payload = SocketIo.decodeBinaryPayload("""{"isDemo":1,"balance":50000.5}""".toByteArray())
        assertEquals(50000.5 to true, PocketParsers.balance(payload))
        val withPrefix = SocketIo.decodeBinaryPayload(byteArrayOf(4) + """[["EURUSD_otc",1700000000.123,1.0812]]""".toByteArray())
        assertEquals(1, PocketParsers.ticks(withPrefix).size)
    }

    @Test fun encodesOrdersAndSubscriptions() {
        val f = PocketFrames.openOrder("EURUSD_otc", 1.5, Direction.CALL, 60, true, "req-1")
        assertTrue(f.startsWith("42[\"openOrder\","))
        val o = JSONArray(f.substring(2)).getJSONObject(1)
        assertEquals("EURUSD_otc", o.getString("asset"))
        assertEquals(1.5, o.getDouble("amount"), 0.0)
        assertEquals("call", o.getString("action"))
        assertEquals(1, o.getInt("isDemo"))
        assertEquals(100, o.getInt("optionType"))
        assertEquals(60, o.getInt("time"))
        assertEquals("req-1", o.getString("requestId"))
        val put = JSONArray(PocketFrames.openOrder("X", 1.0, Direction.PUT, 30, false, "r").substring(2)).getJSONObject(1)
        assertEquals("put", put.getString("action")); assertEquals(0, put.getInt("isDemo"))
        assertEquals("42[\"subfor\",\"EURUSD_otc\"]", PocketFrames.subscribe("EURUSD_otc"))
        assertEquals("42[\"ps\"]", PocketFrames.KEEP_ALIVE)
        val cs = JSONArray(PocketFrames.changeSymbol("EURUSD_otc", 60).substring(2)).getJSONObject(1)
        assertEquals(60, cs.getInt("period"))
    }

    @Test fun parsesDemoSsid() {
        val raw = """42["auth",{"session":"kp8c3a2bd1ets0mqjvrsvjrdgv","isDemo":1,"uid":12345678,"platform":2,"isFastHistory":true,"isOptimized":true}]"""
        val s = SsidParser.parse(raw)
        assertTrue(s.isDemo); assertEquals(12345678L, s.uid); assertEquals(2, s.platform)
        assertEquals(raw, s.authFrame())
        assertEquals(Servers.DEMO.first(), Servers.candidates(s).first())
        // JSON-quoted paste
        assertEquals(s.session, SsidParser.parse(JSONObject.quote(raw)).session)
    }

    @Test fun parsesRealSsidAndUserAgent() {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/146.0.0.0 Safari/537.36"
        val php = "a:4:{s:10:\\\"session_id\\\";s:32:\\\"238ef6dfbcab26ad70e30b5b93e92afc\\\";s:10:\\\"ip_address\\\";s:14:\\\"185.25.195.154\\\";s:10:\\\"user_agent\\\";s:${ua.length}:\\\"$ua\\\";s:13:\\\"last_activity\\\";i:1774696607;}b5fb780af31b82792547625e81206d91"
        val raw = "42[\"auth\",{\"session\":\"$php\",\"isDemo\":0,\"uid\":\"128160124\",\"platform\":2}]"
        val s = SsidParser.parse(raw)
        assertFalse(s.isDemo); assertEquals(128160124L, s.uid)
        assertEquals(ua, s.userAgent)
        assertEquals("185.25.195.154", SsidParser.phpIpAddress(s.session))
        assertTrue(Servers.candidates(s).all { !it.contains("demo") })
    }

    @Test fun rejectsSessionTokenVariant() {
        try {
            SsidParser.parse("""42["auth",{"sessionToken":"abc","uid":"1","lang":"en","currentUrl":"cabinet","isChart":1}]""")
            fail("expected exception")
        } catch (e: SsidParseException) { assertTrue(e.message!!.contains("sessionToken")) }
        assertFalse(SsidParser.looksLikeUsableAuth("""42["auth",{"sessionToken":"abc"}]"""))
    }

    @Test fun parsesAssetsRow() {
        val row = JSONArray("""[5,"EURUSD_otc","EUR/USD OTC","currency",2,92,60,30,3,1,170,0,[],1700000000,true,[{"time":60},{"time":300}],"",false,null]""")
        val a = PocketParsers.assets(JSONArray().put(row)).single()
        assertEquals("EURUSD_otc", a.symbol); assertEquals(92, a.payout); assertTrue(a.isOtc); assertTrue(a.isActive)
    }

    @Test fun parsesHistoryAndLoadHistory() {
        val h = PocketParsers.history(JSONObject("""{"asset":"EURUSD_otc","period":60,"history":[[1700000001.5,1.1],[1700000000,1.0]],"candles":[[1700000040,1.0,1.2,1.3,0.9]]}"""))!!
        assertEquals(2, h.ticks.size); assertEquals(1700000000.0, h.ticks.first().first, 0.0)
        val c = h.candles.single()
        assertEquals(1.0, c.open, 0.0); assertEquals(1.2, c.close, 0.0); assertEquals(1.3, c.high, 0.0); assertEquals(0.9, c.low, 0.0)
        val lh = PocketParsers.loadHistory(JSONObject("""{"asset":"EURUSD_otc","index":1,"period":60,"data":[{"time":1700000060,"open":1,"close":2,"high":3,"low":0.5},{"time":1700000061000,"price":2.1}]}"""))!!
        assertEquals(1, lh.candles.size); assertEquals(1, lh.ticks.size); assertEquals(1700000061.0, lh.ticks[0].first, 0.001)
    }

    @Test fun parsesDealsAndSettles() {
        val win = JSONObject("""{"id":"d1","requestId":"r1","asset":"EURUSD_otc","amount":10,"profit":9.2,"openPrice":1.1,"closePrice":1.2,"command":0,"isDemo":1}""")
        val d = PocketParsers.deals(JSONArray().put(win)).single()
        assertEquals("r1", d.requestId); assertEquals(Direction.CALL, d.direction)
        assertEquals(TradeOutcome.WIN to 9.2, settle(d))
        // gross payout reported -> net
        assertEquals(9.2, settle(d.copy(profit = 19.2)).second, 1e-9)
        // put that loses
        val loss = d.copy(direction = Direction.PUT, profit = -10.0)
        assertEquals(TradeOutcome.LOSS to -10.0, settle(loss))
        // loss reported as 0 profit -> -stake
        assertEquals(-10.0, settle(loss.copy(profit = 0.0)).second, 1e-9)
        // draw
        assertEquals(TradeOutcome.DRAW, settle(d.copy(closePrice = 1.1, profit = 0.0)).first)
        // successcloseOrder shape
        val close = JSONObject().put("profit", 9.2).put("deals", JSONArray().put(win))
        assertEquals(1, PocketParsers.deals(close).size)
    }
}
