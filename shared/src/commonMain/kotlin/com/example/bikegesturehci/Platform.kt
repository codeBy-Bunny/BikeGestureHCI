package com.example.bikegesturehci

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform