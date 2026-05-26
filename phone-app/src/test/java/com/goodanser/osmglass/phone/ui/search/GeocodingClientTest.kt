package com.goodanser.osmglass.phone.ui.search

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeocodingClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GeocodingClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = GeocodingClient(
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            endpoint = server.url("/").toString().trimEnd('/'),
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `parses Photon FeatureCollection into Places`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"type":"FeatureCollection","features":[
              {"type":"Feature",
               "geometry":{"type":"Point","coordinates":[13.3777034,52.5162699]},
               "properties":{"name":"Brandenburger Tor","street":"Pariser Platz","housenumber":"1","city":"Berlin","country":"Deutschland"}},
              {"type":"Feature",
               "geometry":{"type":"Point","coordinates":[13.3809897,52.5166047]},
               "properties":{"name":"Brandenburger Tor","street":"Unter den Linden","city":"Berlin","country":"Deutschland"}}
            ]}
        """.trimIndent()))
        val result = client.search("brandenburger tor")
        assertThat(result).hasSize(2)
        assertThat(result[0].displayName).startsWith("Brandenburger Tor")
        assertThat(result[0].displayName).contains("Pariser Platz 1")
        assertThat(result[0].displayName).contains("Berlin")
        assertThat(result[0].location.lat).isCloseTo(52.5163, within(0.001))
        assertThat(result[0].location.lon).isCloseTo(13.3777, within(0.001))
    }

    @Test fun `empty query returns empty list without hitting the network`() {
        val result = client.search("")
        assertThat(result).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `passes user-agent header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"type":"FeatureCollection","features":[]}"""))
        client.search("anything")
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("User-Agent")).isEqualTo("osmglass/0.1")
        assertThat(recorded.path).startsWith("/api/")
    }

    @Test fun `parse handles empty body and empty FeatureCollection`() {
        assertThat(client.parse("")).isEmpty()
        assertThat(client.parse("""{"type":"FeatureCollection","features":[]}""")).isEmpty()
    }

    @Test fun `parse skips features with malformed geometry`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[13.0,52.0]},"properties":{"name":"valid","city":"Berlin"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[13.0]},"properties":{"name":"too few coords"}},
              {"type":"Feature","properties":{"name":"no geometry"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[13.0,52.0]},"properties":{}}
            ]}
        """.trimIndent()
        val out = client.parse(json)
        assertThat(out).hasSize(1)
        assertThat(out[0].displayName).contains("valid")
    }
}
