package com.enuvro.saltykmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform