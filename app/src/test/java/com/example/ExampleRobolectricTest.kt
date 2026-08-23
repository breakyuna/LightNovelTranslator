package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.i18n.AppLanguage
import com.example.ui.i18n.getAppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Novel Translator", appName)
  }

  @Test
  fun `verify chinese is default and english is switchable`() {
    val zhStrings = getAppStrings(AppLanguage.CHINESE)
    val enStrings = getAppStrings(AppLanguage.ENGLISH)

    assertEquals("小说翻译工作室", zhStrings.appTitle)
    assertEquals("Novel Translator", enStrings.appTitle)
    assertNotNull(zhStrings.openSettings)
    assertNotNull(enStrings.openSettings)
  }
}
