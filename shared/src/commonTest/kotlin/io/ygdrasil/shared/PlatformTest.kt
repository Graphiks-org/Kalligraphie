package io.ygdrasil.shared

import io.ygdrasil.shared.data.repository.PlatformRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {

    @Test
    fun testPlatformRepositoryEmitsSuccess() = runTest {
        val repository = PlatformRepositoryImpl()
        val result = repository.getPlatformInfo().first()
        
        assertTrue(result.isSuccess)
        val info = result.getOrNull()
        assertTrue(info != null)
        assertTrue(info.name.value.isNotEmpty())
    }
}
