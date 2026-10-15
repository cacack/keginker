package io.github.cacack.tagpaper

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform