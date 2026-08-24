package io.ygdrasil.shared.data.repository

import io.ygdrasil.shared.Platform
import io.ygdrasil.shared.domain.model.PlatformInfo
import io.ygdrasil.shared.domain.model.PlatformName
import io.ygdrasil.shared.domain.repository.PlatformRepository
import io.ygdrasil.shared.getPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class PlatformRepositoryImpl : PlatformRepository {
    override fun getPlatformInfo(): Flow<Result<PlatformInfo>> = flow {
        val result = try {
            val platform: Platform = getPlatform()
            Result.success(
                PlatformInfo(
                    name = PlatformName(platform.name),
                    osVersion = platform.osVersion,
                    isSupported = true
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
        emit(result)
    }
}
