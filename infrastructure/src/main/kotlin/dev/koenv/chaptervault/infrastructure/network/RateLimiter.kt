package dev.koenv.chaptervault.infrastructure.network

@Deprecated(
    message = "Use dev.koenv.chaptervault.shared.ratelimit.RateLimiter instead",
    replaceWith = ReplaceWith(
        "RateLimiter(requestsPerSecond)",
        "dev.koenv.chaptervault.shared.ratelimit.RateLimiter",
    ),
)
typealias RateLimiter = dev.koenv.chaptervault.shared.ratelimit.RateLimiter
