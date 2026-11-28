package com.pockettrader.app

import com.pockettrader.app.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Offline end-to-end check of the client's socket.io flow against a local mock server that
 * speaks the frames documented by the open-source clients. Never touches Pocket Option.
 */
class ClientFlowTest {
    @Test fun handshakeAuthBalanceOrderAndResult() {
        val received = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        var serverWs: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverWs = webSocket
                webSocket.send("""0{"sid":"s1","upgrades":[],"pingInterval":25000,"pingTimeout":20000}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
                when {
                    text == "40" -> webSocket.send("""40{"sid":"s2"}""")
                    text.startsWith("42[\"auth\"") -> {
                        webSocket.send("""451-["successauth",{"_placeholder":true,"num":0}]""")
                        webSocket.send("""{"id":"abc"}""".encodeUtf8())
                        webSocket.send("2")
                    }
                    text.startsWith("42[\"getBalance\"") -> {
                        webSocket.send("""451-["successupdateBalance",{"_placeholder":true,"num":0}]""")
                        webSocket.send("""{"isDemo":1,"balance":50000.25}""".encodeUtf8())
                        webSocket.send("""42["updateStream",[["EURUSD_otc",1700000000.5,1.0812]]]""")
                    }
                    text.startsWith("42[\"openOrder\"") -> {
                        val o = JSONArray(text.substring(2)).getJSONObject(1)
                        val req = o.getString("requestId")
                        webSocket.send("""451-["successopenOrder",{"_placeholder":true,"num":0}]""")
                        webSocket.send("""{"id":"deal-1","requestId":"$req","asset":"EURUSD_otc","amount":1,"command":0,"isDemo":1,"openPrice":1.08}""".encodeUtf8())
                        webSocket.send("""451-["updateClosedDeals",{"_placeholder":true,"num":0}]""")
                        webSocket.send("""[{"id":"deal-1","requestId":"$req","asset":"EURUSD_otc","amount":1,"profit":0.92,"openPrice":1.08,"closePrice":1.09,"command":0,"isDemo":1}]""".encodeUtf8())
                    }
                }
            }
        }))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = Collections.synchronizedList(ArrayList<PocketEvent>())
        val closed = CountDownLatch(1)
        scope.launch {
            PocketClient(scope).also { client ->
                scope.launch {
                    client.events.collect { e -> events += e; if (e is PocketEvent.Closed) closed.countDown() }
                }
                val session = SsidParser.parse("""42["auth",{"session":"testsession","isDemo":1,"uid":1,"platform":2}]""")
                client.watch("EURUSD_otc", 60)
                client.connect(session, listOf(server.url("/socket.io/?EIO=4&transport=websocket").toString().replace("http", "ws")))
                // wait for connected then send an order
                val deadline = System.currentTimeMillis() + 5000
                while (client.state.value != ConnState.CONNECTED && System.currentTimeMillis() < deadline) Thread.sleep(20)
                assertEquals(ConnState.CONNECTED, client.state.value)
                Thread.sleep(200)
                assertNotNull(client.openOrder("EURUSD_otc", 1.0, Direction.CALL, 60))
            }
        }
        assertTrue("no close event", closed.await(8, TimeUnit.SECONDS))
        val frames = received.toList()
        assertEquals("40", frames[0])
        assertEquals("""42["auth",{"session":"testsession","isDemo":1,"uid":1,"platform":2}]""", frames[1])
        assertTrue(frames.contains("3"))  // pong
        assertTrue(frames.any { it.startsWith("42[\"changeSymbol\"") })
        assertTrue(frames.contains("42[\"subfor\",\"EURUSD_otc\"]"))
        val order = JSONArray(frames.first { it.startsWith("42[\"openOrder\"") }.substring(2)).getJSONObject(1)
        assertEquals(1, order.getInt("isDemo"))
        val bal = events.filterIsInstance<PocketEvent.Balance>().first()
        assertEquals(50000.25, bal.amount, 0.0)
        assertTrue(events.any { it is PocketEvent.Ticks })
        val opened = events.filterIsInstance<PocketEvent.Opened>().single()
        assertEquals("deal-1", opened.deal.id)
        val result = events.filterIsInstance<PocketEvent.Closed>().single().deals.single()
        assertEquals(TradeOutcome.WIN, settle(result).first)
        scope.cancel()
        serverWs?.close(1000, null)
        server.shutdown()
    }
}
