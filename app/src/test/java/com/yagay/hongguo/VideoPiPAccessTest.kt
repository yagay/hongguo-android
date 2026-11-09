package com.yagay.hongguo

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPiPAccessTest {
    @Test fun unsupportedHardwareIsNotPresentedAsWorking() {
        assertEquals(VideoPiPAccess.Status.UNSUPPORTED,
            VideoPiPAccess.evaluate(false, AppOpsManager.MODE_ALLOWED))
    }
    @Test fun deniedSystemPictureInPictureBlocksAutoEnter() {
        assertEquals(VideoPiPAccess.Status.BLOCKED,
            VideoPiPAccess.evaluate(true, AppOpsManager.MODE_IGNORED))
        assertEquals(VideoPiPAccess.Status.BLOCKED,
            VideoPiPAccess.evaluate(true, AppOpsManager.MODE_ERRORED))
    }
    @Test fun grantedAndDefaultAppOpsAreAcceptable() {
        assertEquals(VideoPiPAccess.Status.ALLOWED,
            VideoPiPAccess.evaluate(true, AppOpsManager.MODE_ALLOWED))
        assertEquals(VideoPiPAccess.Status.ALLOWED,
            VideoPiPAccess.evaluate(true, AppOpsManager.MODE_DEFAULT))
    }
    @Test fun unknownAppOpRequiresARealWorldPipTest() {
        assertEquals(VideoPiPAccess.Status.UNKNOWN,
            VideoPiPAccess.evaluate(true, null))
    }
}
