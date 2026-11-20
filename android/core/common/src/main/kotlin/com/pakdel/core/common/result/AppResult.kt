package com.pakdel.core.common.result

/** خطاهای دامنه‌ای مشترک هر دو اپلیکیشن */
sealed interface AppError {
    val message: String

    data object Network : AppError {
        override val message = "اتصال به سرور برقرار نیست. عملیات به‌صورت آفلاین ثبت شد."
    }

    data object Unauthorized : AppError {
        override val message = "نشست شما منقضی شده است. دوباره وارد شوید."
    }

    data object Forbidden : AppError {
        override val message = "شما دسترسی لازم برای این عملیات را ندارید."
    }

    data class Validation(override val message: String) : AppError

    /** خطای تجاری شناخته‌شده از سرور (INSUFFICIENT_STOCK، INVALID_DISCOUNT و ...) */
    data class Domain(val code: String, override val message: String) : AppError

    /** تعارض داده محلی با سرور؛ نیازمند تصمیم مدیر */
    data class Conflict(val code: String, override val message: String) : AppError

    data class Unknown(override val message: String, val cause: Throwable? = null) : AppError
}

/** نتیجه‌ی عملیات؛ معادل Either بدون وابستگی خارجی */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): AppError? = (this as? Failure)?.error
    val isSuccess: Boolean get() = this is Success
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

/**
 * اجرای امن یک بلوک؛ استثناهای شبکه/دیتابیس را به AppError تبدیل می‌کند.
 * پیام خطای سرور با پیشوند CODE: به AppError.Domain نگاشت می‌شود.
 */
inline fun <T> runCatchingApp(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (c: kotlinx.coroutines.CancellationException) {
    // لغو Coroutine هرگز نباید به خطای دامنه تبدیل شود
    throw c
} catch (t: Throwable) {
    AppResult.Failure(t.toAppError())
}

fun Throwable.toAppError(): AppError {
    val raw = message.orEmpty()
    val code = raw.substringBefore(':', "").takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
    val text = raw.substringAfter(':', raw).trim().ifBlank { "خطای نامشخص رخ داد." }

    return when {
        raw.contains("timeout", true) || raw.contains("unable to resolve host", true) ||
            raw.contains("failed to connect", true) || raw.contains("connection refused", true) ->
            AppError.Network

        code == "FORBIDDEN" -> AppError.Forbidden
        code == "UNAUTHORIZED" || code == "JWT" || code == "PGRST301" -> AppError.Unauthorized

        // تعارض‌هایی که نیازمند تصمیم انسان هستند
        code != null && code in setOf(
            "INSUFFICIENT_STOCK", "DUPLICATE_OPERATION", "DUPLICATE_BARCODE",
            "DUPLICATE_SKU", "DUPLICATE_PHONE", "ALREADY_CANCELED", "CREDIT_LIMIT",
        ) -> AppError.Conflict(code, text)

        // خطاهای دامنه که فقط باید به کاربر نمایش داده شوند
        code != null && code.all { it.isUpperCase() || it == '_' || it.isDigit() } ->
            AppError.Domain(code, text)

        else -> AppError.Unknown(text, this)
    }
}
