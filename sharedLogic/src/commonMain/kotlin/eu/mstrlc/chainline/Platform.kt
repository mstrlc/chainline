package eu.mstrlc.chainline

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform