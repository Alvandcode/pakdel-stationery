package com.pakdel.manager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.data.local.entity.OutboxEntity
import com.pakdel.core.data.remote.MemberDto
import com.pakdel.core.data.repository.AuthRepository
import com.pakdel.core.data.repository.StaffRepository
import com.pakdel.core.data.session.Session
import com.pakdel.core.data.sync.SyncEngine
import com.pakdel.core.data.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncEngine: SyncEngine,
    private val staffRepository: StaffRepository,
) : ViewModel() {

    val session: StateFlow<Session?> = authRepository.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val syncStatus: StateFlow<SyncStatus> = syncEngine.observeStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus())

    val conflicts: StateFlow<List<OutboxEntity>> = syncEngine.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busySeq = MutableStateFlow<Long?>(null)
    val busySeq: StateFlow<Long?> = _busySeq.asStateFlow()

    private val _members = MutableStateFlow<List<MemberDto>>(emptyList())
    val members: StateFlow<List<MemberDto>> = _members.asStateFlow()

    private val _membersLoading = MutableStateFlow(false)
    val membersLoading: StateFlow<Boolean> = _membersLoading.asStateFlow()

    private val _membersError = MutableStateFlow<String?>(null)
    val membersError: StateFlow<String?> = _membersError.asStateFlow()

    private val _inviting = MutableStateFlow(false)
    val inviting: StateFlow<Boolean> = _inviting.asStateFlow()

    private val _inviteCode = MutableStateFlow<String?>(null)
    val inviteCode: StateFlow<String?> = _inviteCode.asStateFlow()

    private val _codeLoading = MutableStateFlow(false)
    val codeLoading: StateFlow<Boolean> = _codeLoading.asStateFlow()

    init {
        // فهرست کارکنان فقط برای نقش‌های دارای مجوز staff.manage بارگذاری می‌شود
        viewModelScope.launch {
            session.collect { current ->
                if (current?.canManageStaff == true) {
                    loadMembers()
                    loadInviteCode()
                }
            }
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            val result = syncEngine.syncNow()
            _syncing.value = false
            _message.value = when (result) {
                is AppResult.Success ->
                    if (result.data.skipped) {
                        "برای همگام‌سازی ابتدا وارد شوید."
                    } else {
                        "همگام‌سازی انجام شد: ${result.data.pushed} ارسال، ${result.data.pulled} دریافت" +
                            if (result.data.conflicts > 0) "، ${result.data.conflicts} تعارض" else ""
                    }
                is AppResult.Failure -> result.error.message
            }
        }
    }

    fun retryConflict(seq: Long) {
        if (_busySeq.value != null) return
        _busySeq.value = seq
        viewModelScope.launch {
            when (val retried = syncEngine.retryConflict(seq)) {
                is AppResult.Success -> {
                    val result = syncEngine.syncNow()
                    _message.value = when (result) {
                        is AppResult.Success ->
                            if (result.data.conflicts > 0) {
                                "عملیات همچنان تعارض دارد؛ ابتدا موجودی/اطلاعات را اصلاح کنید."
                            } else {
                                "عملیات با موفقیت ارسال شد."
                            }
                        is AppResult.Failure -> result.error.message
                    }
                }
                is AppResult.Failure -> _message.value = retried.error.message
            }
            _busySeq.value = null
        }
    }

    fun discardConflict(seq: Long) {
        if (_busySeq.value != null) return
        _busySeq.value = seq
        viewModelScope.launch {
            _message.value = when (val result = syncEngine.discardConflict(seq)) {
                is AppResult.Success -> {
                    // همگام‌سازی فوری تا نسخه سرور جایگزین داده رد‌شده شود
                    syncEngine.syncNow()
                    "عملیات رد شد؛ اثرات محلی آن برگشت خورد."
                }
                is AppResult.Failure -> result.error.message
            }
            _busySeq.value = null
        }
    }

    fun loadMembers() {
        if (_membersLoading.value) return
        _membersLoading.value = true
        viewModelScope.launch {
            when (val result = staffRepository.list()) {
                is AppResult.Success -> {
                    _members.value = result.data
                    _membersError.value = null
                }
                is AppResult.Failure -> _membersError.value = result.error.message
            }
            _membersLoading.value = false
        }
    }

    /** کد دعوت فعلی فروشگاه؛ همکاران با آن خودشان عضو می‌شوند. */
    fun loadInviteCode() {
        if (_codeLoading.value) return
        _codeLoading.value = true
        viewModelScope.launch {
            when (val result = staffRepository.inviteCode()) {
                is AppResult.Success -> _inviteCode.value = result.data
                is AppResult.Failure -> _membersError.value = result.error.message
            }
            _codeLoading.value = false
        }
    }

    /** ساخت کد جدید؛ کد قبلی دیگر کار نمی‌کند. */
    fun rotateInviteCode() {
        if (_codeLoading.value) return
        _codeLoading.value = true
        viewModelScope.launch {
            when (val result = staffRepository.rotateInviteCode()) {
                is AppResult.Success -> {
                    _inviteCode.value = result.data
                    _message.value = "کد دعوت جدید ساخته شد؛ کد قبلی باطل شد."
                }
                is AppResult.Failure -> _message.value = result.error.message
            }
            _codeLoading.value = false
        }
    }

    fun changeRole(member: MemberDto, roleCode: String) {
        viewModelScope.launch {
            when (val result = staffRepository.setRole(member.id, roleCode)) {
                is AppResult.Success -> {
                    _message.value = "نقش ${member.fullName} به‌روزرسانی شد."
                    loadMembers()
                }
                is AppResult.Failure -> _message.value = result.error.message
            }
        }
    }

    fun toggleActive(member: MemberDto) {
        viewModelScope.launch {
            when (val result = staffRepository.setActive(member.id, !member.isActive)) {
                is AppResult.Success -> {
                    _message.value = if (member.isActive) {
                        "دسترسی ${member.fullName} غیرفعال شد."
                    } else {
                        "دسترسی ${member.fullName} فعال شد."
                    }
                    loadMembers()
                }
                is AppResult.Failure -> _message.value = result.error.message
            }
        }
    }

    /** افزودن کاربری که قبلاً در Supabase Auth ساخته شده است. */
    fun invite(email: String, roleCode: String, fullName: String, onDone: () -> Unit = {}) {
        if (_inviting.value) return
        if (email.isBlank()) {
            _message.value = "ایمیل کارمند را وارد کنید."
            return
        }
        _inviting.value = true
        viewModelScope.launch {
            _message.value = when (val result = staffRepository.invite(email, roleCode, fullName)) {
                is AppResult.Success -> {
                    onDone()
                    loadMembers()
                    "${result.data.fullName} به فروشگاه اضافه شد."
                }
                is AppResult.Failure -> result.error.message
            }
            _inviting.value = false
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            when (val result = authRepository.signOut()) {
                is AppResult.Success -> onDone()
                is AppResult.Failure -> _message.value = result.error.message
            }
        }
    }

    fun consumeMessage() { _message.value = null }
}
