package com.laoke.weshirt.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InspectionAccessTest {
    private fun access(view: Any = JSONArray(listOf("a", "b")), operate: Any = JSONArray(listOf("a")), mayOperate: Boolean = true) =
        JSONObject().put("permissions", JSONObject().put("inspections", JSONObject()
            .put("view", true).put("operate", mayOperate).put("viewBuildings", view).put("operateBuildings", operate)))

    @Test fun viewingAnotherBuildingNeverGrantsSubmission() {
        val access = access()
        assertTrue(inspectionAllowed(access, "a", operation = true))
        assertTrue(inspectionAllowed(access, "b"))
        assertFalse(inspectionAllowed(access, "b", operation = true))
        assertFalse(inspectionAllowed(access, "c"))
    }

    @Test fun readOnlyAndRevokedPermissionsKeepFormDisabled() {
        assertTrue(inspectionAllowed(access(mayOperate = false), "a"))
        assertFalse(inspectionAllowed(access(mayOperate = false), "a", operation = true))
        val revoked = access().apply { optJSONObject("permissions")!!.optJSONObject("inspections")!!.put("view", false) }
        assertFalse(inspectionAllowed(revoked, "a", operation = true))
        assertFalse(inspectionAllowed(JSONObject().put("identity", JSONObject().put("role", "super")), "a", operation = true))
    }

    @Test fun explicitGlobalScopeIsRequiredAndBothScopesAreEnforced() {
        assertTrue(inspectionAllowed(access(JSONObject.NULL, JSONObject.NULL), "any", operation = true))
        assertFalse(inspectionAllowed(access("a", "a"), "a", operation = true))
        assertFalse(inspectionAllowed(access(JSONArray(listOf("b")), JSONObject.NULL), "a", operation = true))
        val missing = access().apply { optJSONObject("permissions")!!.optJSONObject("inspections")!!.remove("operateBuildings") }
        assertFalse(inspectionAllowed(missing, "a", operation = true))
        assertFalse(inspectionAllowed(access(), "", operation = true))
    }
}
