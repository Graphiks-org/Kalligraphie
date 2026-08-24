package io.ygdrasil.shared.domain.repository

import io.ygdrasil.shared.domain.model.PlatformInfo
import kotlinx.coroutines.flow.Flow

interface PlatformRepository {
    fun getPlatformInfo(): Flow<Result<PlatformInfo>>
}
