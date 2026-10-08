package com.jarvis.assistant.device

enum class ResultStatus {
    SUCCESS, NEEDS_PERMISSION, NEEDS_USER_ENABLEMENT, CONFIRMATION_REQUIRED,
    NOT_FOUND, AMBIGUOUS, FAILED, UNSUPPORTED
}

data class DeviceActionResult(
    val status: ResultStatus,
    val reason: String,
    val settingsIntentAction: String? = null
) {
    val ok: Boolean get() = status == ResultStatus.SUCCESS
    companion object {
        fun success(msg: String) = DeviceActionResult(ResultStatus.SUCCESS, msg)
        fun failed(msg: String) = DeviceActionResult(ResultStatus.FAILED, msg)
        fun notFound(msg: String) = DeviceActionResult(ResultStatus.NOT_FOUND, msg)
        fun ambiguous(msg: String) = DeviceActionResult(ResultStatus.AMBIGUOUS, msg)
        fun unsupported(msg: String) = DeviceActionResult(ResultStatus.UNSUPPORTED, msg)
        fun needsPermission(msg: String, settings: String? = null) =
            DeviceActionResult(ResultStatus.NEEDS_PERMISSION, msg, settings)
        fun needsEnablement(msg: String, settings: String? = null) =
            DeviceActionResult(ResultStatus.NEEDS_USER_ENABLEMENT, msg, settings)
        fun confirm(msg: String) = DeviceActionResult(ResultStatus.CONFIRMATION_REQUIRED, msg)
    }
}
