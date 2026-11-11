package com.laoke.weshirt.ui.admin

import com.laoke.weshirt.data.AppState
import com.laoke.weshirt.data.Building
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdminScopeTest {
    private fun state(row: JSONObject) = AppState(buildings = listOf(Building("a","A 楼"),Building("b","B 楼")),access=JSONObject().put("permissions",JSONObject().put("adminMessages",row)))
    private fun permission(view:Any=JSONArray(listOf("a","public")),operate:Any=JSONArray(listOf("a"))) = JSONObject().put("view",true).put("operate",true).put("viewBuildings",view).put("operateBuildings",operate)
    @Test fun publicChannelAndBuildingScopesRemainSeparate() {
        val s=state(permission())
        assertEquals(listOf("a"),allowedBuildings(s,"adminMessages").map{it.first})
        assertTrue(allowedChannel(s,"adminMessages","public"))
        assertFalse(allowedChannel(s,"adminMessages","public",true))
        assertFalse(globalOperation(s,"adminMessages"))
        assertFalse(allowedChannel(s,"adminMessages","b"))
    }
    @Test fun viewingDoesNotPermitMutation() {
        val s=state(permission().put("operate",false))
        assertTrue(allowedChannel(s,"adminMessages","a"))
        assertFalse(allowedChannel(s,"adminMessages","a",true))
        assertTrue(allowedBuildings(s,"adminMessages",true).isEmpty())
    }
    @Test fun missingOrMalformedScopeFailsClosed() {
        for(row in listOf(permission().apply{remove("viewBuildings");remove("operateBuildings")},permission("a","a"))) {
            val s=state(row)
            assertFalse(allowedChannel(s,"adminMessages","a"))
            assertFalse(globalOperation(s,"adminMessages"))
            assertTrue(allowedBuildings(s,"adminMessages").isEmpty())
        }
    }
    @Test fun explicitNullAllowsGlobalScopeWhileUnknownResourceDoesNot() {
        val s=state(permission(JSONObject.NULL,JSONObject.NULL))
        assertEquals(2,allowedBuildings(s,"adminMessages").size)
        assertTrue(globalOperation(s,"adminMessages"))
        assertTrue(allowedChannel(s,"adminMessages","public",true))
        assertFalse(allowedChannel(s,"adminSite","a",true))
    }
}
