package com.laoke.weshirt.platform

import com.laoke.weshirt.platform.widget.widgetMachineNumber
import com.laoke.weshirt.platform.widget.widgetTaskName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaundryWidgetMachineLabelTest {
    @Test fun machineNumberWinsOverFloorAndBuildingNumbers() {
        assertEquals("2", widgetMachineNumber("Test 1楼2号洗衣机"))
        assertEquals("8", widgetMachineNumber("Test 12栋3楼8号烘干机"))
        assertEquals("01", widgetMachineNumber("Building 5 Floor 2 Washer 01"))
        assertEquals("3", widgetMachineNumber("3 号机"))
        assertNull(widgetMachineNumber("Laundry room"))
    }

    @Test fun taskNamesIncludeTheBuildingOnceAndPreserveTheActualMachineName() {
        assertEquals("示例楼1楼2号洗衣机", widgetTaskName("示例楼", "1楼2号洗衣机"))
        assertEquals("示例楼1楼2号洗衣机", widgetTaskName("示例楼", "示例楼1楼2号洗衣机"))
        assertEquals("Sample Washer 01", widgetTaskName("Sample", "Washer 01"))
        assertEquals("Sample Washer 01", widgetTaskName("Sample", "Sample Washer 01"))
        assertEquals("Washer 01", widgetTaskName(null, "Washer 01"))
        assertEquals("Washer 01", widgetTaskName(" \n ", " Washer\n01 "))
    }
}
