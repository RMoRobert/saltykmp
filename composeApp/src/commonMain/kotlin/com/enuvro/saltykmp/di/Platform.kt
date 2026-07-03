package com.enuvro.saltykmp.di

import com.enuvro.saltykmp.db.AppDatabase
import io.ktor.client.engine.HttpClientEngine

/** Per-platform providers for the SQLDelight database and the Ktor HTTP engine. */
expect fun createDatabase(): AppDatabase

expect fun createHttpEngine(): HttpClientEngine
