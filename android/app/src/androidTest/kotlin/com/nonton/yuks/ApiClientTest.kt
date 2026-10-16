package com.nonton.yuks

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.InvocationTargetException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiClientTest {
    @Test
    fun requestedQualityNeverSilentlyAcceptsLowerOrUnknownHeight() {
        val source = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        val parse = ApiClient::class.java.getDeclaredMethod("parse", String::class.java,
            String::class.java, Uri::class.java, Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        for (actual in listOf(360, 0)) {
            val payload = JSONObject().put("platform", "youtube").put("videoId", "jNQXAC9IVRw")
                .put("sourceUrl", source).put("title", "Quality fixture")
                .put("durationMs", 19000).put("height", actual).toString()
            try {
                parse.invoke(ApiClient, payload, "youtube", Uri.parse(source), 720)
                fail("A changed quality must require another user selection")
            } catch (error: InvocationTargetException) {
                val failure = error.cause as BridgeException
                assertEquals("qualityUnavailable", failure.code)
            }
        }
    }
}
