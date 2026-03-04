package org.freewheel.utils

import org.freewheel.core.utils.StringUtil as KmpStringUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Comparison tests to verify KMP StringUtil produces identical results to legacy StringUtil.
 */
class StringUtilComparisonTest {

    @Test
    fun `inArray matches legacy for value in array`() {
        val array = arrayOf("apple", "banana", "cherry")
        assertThat(KmpStringUtil.inArray("banana", array))
            .isEqualTo(StringUtil.inArray("banana", array))
    }

    @Test
    fun `inArray matches legacy for value not in array`() {
        val array = arrayOf("apple", "banana", "cherry")
        assertThat(KmpStringUtil.inArray("grape", array))
            .isEqualTo(StringUtil.inArray("grape", array))
    }

    @Test
    fun `inArray matches legacy for empty array`() {
        val array = arrayOf<String>()
        assertThat(KmpStringUtil.inArray("anything", array))
            .isEqualTo(StringUtil.inArray("anything", array))
    }

    @Test
    fun `inArray matches legacy for case sensitive check`() {
        val array = arrayOf("Apple", "Banana")
        assertThat(KmpStringUtil.inArray("apple", array))
            .isEqualTo(StringUtil.inArray("apple", array))
        assertThat(KmpStringUtil.inArray("Apple", array))
            .isEqualTo(StringUtil.inArray("Apple", array))
    }
}
