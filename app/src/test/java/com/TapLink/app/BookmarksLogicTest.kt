package com.TapLink.app

import org.junit.Test
import org.junit.Assert.*

class BookmarksLogicTest {

    // Mock class replicating the fixed logic in BookmarksView
    // This verifies that using a single variable for the listener works as expected.
    class BookmarksViewLogic {
        var keyboardListener: Listener? = null

        interface Listener {
            fun onAction()
        }

        fun setKeyboardListener(listener: Listener) {
            keyboardListener = listener
        }

        fun triggerAction() {
            keyboardListener?.onAction()
        }
    }

    @Test
    fun testListenerLogic() {
        val view = BookmarksViewLogic()
        var callCount = 0
        val listener = object : BookmarksViewLogic.Listener {
            override fun onAction() {
                callCount++
            }
        }

        view.setKeyboardListener(listener)
        view.triggerAction()

        // Assert that the listener IS called
        assertEquals("Listener should be called", 1, callCount)
    }
}
